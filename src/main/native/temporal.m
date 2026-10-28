#import <MetalFX/MetalFX.h>

#include "m2m_context.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// MetalFX temporal upscaling. Minecraft draws no motion vectors, so a pass derives them from depth and the camera:
// each pixel is unprojected with this frame's matrices and projected with the previous frame's. That is exact for
// the world, which does not move; moving entities get the camera's motion only. The same pass copies depth into a
// plain Depth32Float texture, since the game's depth texture has a stencil aspect.
//
// For the temporal scaler it also writes a reactive mask (macOS 14.4+), which tells MetalFX where the history cannot be
// trusted. A pixel's surface is followed back to the previous frame with the camera's motion and compared with what
// the previous depth shows there: if nothing there lies close to it, the surface moved by itself (an entity) or just
// came into view, and the pixel leans on the current frame. The closest of the 3x3 neighbours counts, so the subpixel
// jitter of edges does not look like motion, and "close" is measured in pixels at that depth: what ghosts is motion of
// a few pixels a frame, however far away.
static NSString *const M2M_MOTION_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct Params {\n"
     "    float4x4 inverseCurrent;\n"
     "    float4x4 previous;\n"
     // w is 1 when the previous frame is history, 0 after a reset.
     "    float4 cameraDelta;\n"
     "    float4x4 inversePrevious;\n"
     "};\n"
     "struct MotionOut { float2 motion [[color(0)]]; float depth [[depth(any)]]; };\n"
     "vertex float4 m2m_motion_vertex(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    return float4(p * 2.0 - 1.0, 0.0, 1.0);\n"
     "}\n"
     "fragment MotionOut m2m_motion_fragment(float4 position [[position]], depth2d<float, access::read> depth [[texture(0)]],\n"
     "                                       constant Params &params [[buffer(0)]]) {\n"
     "    uint2 pixel = uint2(position.xy);\n"
     "    float2 size = float2(depth.get_width(), depth.get_height());\n"
     "    float z = depth.read(pixel);\n"
     // Rows keep GL's bottom-up order, so row y is NDC y without a flip.
     "    float2 ndc = position.xy / size * 2.0 - 1.0;\n"
     "    float4 world = params.inverseCurrent * float4(ndc, z, 1.0);\n"
     "    world /= world.w;\n"
     "    float4 previous = params.previous * float4(world.xyz + params.cameraDelta.xyz, 1.0);\n"
     "    MotionOut out;\n"
     "    out.depth = z;\n"
     "    out.motion = float2(0.0);\n"
     "    if (previous.w > 0.0) {\n"
     "        float2 previousPixel = (previous.xy / previous.w * 0.5 + 0.5) * size;\n"
     "        out.motion = previousPixel - position.xy;\n"
     "    }\n"
     "    return out;\n"
     "}\n"
     "struct ReactiveOut { float2 motion [[color(0)]]; float reactive [[color(1)]]; float depth [[depth(any)]]; };\n"
     "fragment ReactiveOut m2m_motion_reactive_fragment(float4 position [[position]], depth2d<float, access::read> depth [[texture(0)]],\n"
     "                                                  depth2d<float, access::read> previousDepth [[texture(1)]],\n"
     "                                                  constant Params &params [[buffer(0)]]) {\n"
     "    uint2 pixel = uint2(position.xy);\n"
     "    float2 size = float2(depth.get_width(), depth.get_height());\n"
     "    float z = depth.read(pixel);\n"
     "    float2 ndc = position.xy / size * 2.0 - 1.0;\n"
     "    float4 world = params.inverseCurrent * float4(ndc, z, 1.0);\n"
     "    world /= world.w;\n"
     // Where this surface was relative to the previous camera.
     "    float3 here = world.xyz + params.cameraDelta.xyz;\n"
     "    float4 previous = params.previous * float4(here, 1.0);\n"
     "    ReactiveOut out;\n"
     "    out.depth = z;\n"
     "    out.motion = float2(0.0);\n"
     "    out.reactive = 0.0;\n"
     "    if (previous.w <= 0.0) {\n"
     "        return out;\n"
     "    }\n"
     "    float2 previousPixel = (previous.xy / previous.w * 0.5 + 0.5) * size;\n"
     "    out.motion = previousPixel - position.xy;\n"
     // The sky has nothing to follow, and after a reset there is no previous frame.
     "    if (params.cameraDelta.w == 0.0 || z >= 1.0) {\n"
     "        return out;\n"
     "    }\n"
     "    int2 centre = int2(floor(previousPixel));\n"
     // Off screen in the previous frame: just came into view.
     "    if (any(centre < 0) || any(centre >= int2(size))) {\n"
     "        out.reactive = 1.0;\n"
     "        return out;\n"
     "    }\n"
     "    float3 there[9];\n"
     "    float nearest = 1.0e30;\n"
     "    for (int i = 0; i < 9; i++) {\n"
     "        int2 neighbour = clamp(centre + int2(i % 3 - 1, i / 3 - 1), int2(0), int2(size) - 1);\n"
     "        float2 neighbourNdc = (float2(neighbour) + 0.5) / size * 2.0 - 1.0;\n"
     "        float4 found = params.inversePrevious * float4(neighbourNdc, previousDepth.read(uint2(neighbour)), 1.0);\n"
     "        there[i] = found.xyz / found.w;\n"
     "        nearest = min(nearest, distance(there[i], here));\n"
     "    }\n"
     // What a pixel spans at this depth, and how far apart the previous samples lie on their surface (more than a
     // pixel where it is seen at a grazing angle). The nearer side along each axis, so an edge does not count.
     "    float4 above = params.inverseCurrent * float4(ndc + float2(0.0, 2.0 / size.y), z, 1.0);\n"
     "    float pixelSize = distance(above.xyz / above.w, world.xyz);\n"
     "    float spacingX = min(distance(there[3], there[4]), distance(there[5], there[4]));\n"
     "    float spacingY = min(distance(there[1], there[4]), distance(there[7], there[4]));\n"
     "    float tolerance = max(1.5 * pixelSize, 1.25 * max(spacingX, spacingY));\n"
     "    out.reactive = smoothstep(tolerance, 2.0 * tolerance, nearest);\n"
     "    return out;\n"
     "}\n";

typedef struct {
	float inverse_current[16];
	float previous[16];
	float camera_delta[4];
	float inverse_previous[16];
} M2MMotionParams;

@interface M2MTemporalUpscaler : NSObject
// An MTLFXTemporalScaler, or an MTL4FXTemporalScaler with Metal 4.
@property(nonatomic, strong) id<MTLFXTemporalScalerBase> scaler;
@property(nonatomic) NSUInteger inputWidth, inputHeight, outputWidth, outputHeight;
@property(nonatomic) MTLPixelFormat colorFormat, outputFormat;
@property(nonatomic, strong) id<MTLTexture> motion;
// This frame's depth copy and the previous frame's, alternating; the previous one feeds the reactive mask.
@property(nonatomic, strong) NSArray<id<MTLTexture>> *depths;
@property(nonatomic) NSUInteger depthIndex;
// The reactive mask, when MetalFX takes one (macOS 14.4+) and it is on.
@property(nonatomic, strong) id<MTLTexture> reactive;
// Until its first frame, when the previous depth holds nothing yet.
@property(nonatomic) BOOL fresh;
// As for the spatial scaler: written by MetalFX when the game's target lacks the usages it needs, then copied over.
@property(nonatomic, strong) id<MTLTexture> staging;
@end

@implementation M2MTemporalUpscaler
@end

int32_t m2m_metalfx_temporal_supported(int64_t ctx) {
	M2MContext *context = m2m_ctx(ctx);
	if (context.metal4) {
		return [MTLFXTemporalScalerDescriptor supportsMetal4FX:context.device] ? 1 : 0;
	}
	return [MTLFXTemporalScalerDescriptor supportsDevice:context.device] ? 1 : 0;
}

void m2m_metalfx_temporal_scale_range(int64_t ctx, float *min_scale, float *max_scale) {
	id<MTLDevice> device = m2m_ctx(ctx).device;
	*min_scale = [MTLFXTemporalScalerDescriptor supportedInputContentMinScaleForDevice:device];
	*max_scale = [MTLFXTemporalScalerDescriptor supportedInputContentMaxScaleForDevice:device];
}

static BOOL m2m_reactive_mask_supported(void) {
	if (@available(macOS 14.4, *)) {
		return YES;
	}
	return NO;
}

static M2MTemporalUpscaler *m2m_temporal_upscaler(M2MContext *ctx, id<MTLTexture> color, id<MTLTexture> output, BOOL reactive) {
	M2MTemporalUpscaler *current = ctx.temporalUpscaler;
	reactive = reactive && m2m_reactive_mask_supported();
	if (current && current.inputWidth == color.width && current.inputHeight == color.height &&
	    current.outputWidth == output.width && current.outputHeight == output.height &&
	    current.colorFormat == color.pixelFormat && current.outputFormat == output.pixelFormat && (current.reactive != nil) == reactive) {
		return current;
	}

	MTLFXTemporalScalerDescriptor *descriptor = [MTLFXTemporalScalerDescriptor new];
	descriptor.inputWidth = color.width;
	descriptor.inputHeight = color.height;
	descriptor.outputWidth = output.width;
	descriptor.outputHeight = output.height;
	descriptor.colorTextureFormat = color.pixelFormat;
	descriptor.depthTextureFormat = MTLPixelFormatDepth32Float;
	descriptor.motionTextureFormat = MTLPixelFormatRG16Float;
	descriptor.outputTextureFormat = output.pixelFormat;
	if (reactive) {
		if (@available(macOS 14.4, *)) {
			descriptor.reactiveMaskTextureEnabled = YES;
			descriptor.reactiveMaskTextureFormat = MTLPixelFormatR8Unorm;
		}
	}

	M2MTemporalUpscaler *created = [M2MTemporalUpscaler new];
	created.fresh = YES;
	if (ctx.metal4) {
		created.scaler = [descriptor newTemporalScalerWithDevice:ctx.device compiler:ctx.compiler];
	} else {
		created.scaler = [descriptor newTemporalScalerWithDevice:ctx.device];
	}
	if (!created.scaler) {
		return nil;
	}
	created.inputWidth = color.width;
	created.inputHeight = color.height;
	created.outputWidth = output.width;
	created.outputHeight = output.height;
	created.colorFormat = color.pixelFormat;
	created.outputFormat = output.pixelFormat;
	created.motion = m2m_new_private_texture(ctx, MTLPixelFormatRG16Float, color.width, color.height,
	                                     MTLTextureUsageRenderTarget | created.scaler.motionTextureUsage);
	MTLTextureUsage depthUsage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead | created.scaler.depthTextureUsage;
	created.depths = @[
		m2m_new_private_texture(ctx, MTLPixelFormatDepth32Float, color.width, color.height, depthUsage),
		m2m_new_private_texture(ctx, MTLPixelFormatDepth32Float, color.width, color.height, depthUsage),
	];
	if (reactive) {
		if (@available(macOS 14.4, *)) {
			created.reactive = m2m_new_private_texture(ctx, MTLPixelFormatR8Unorm, color.width, color.height,
			                                           MTLTextureUsageRenderTarget | created.scaler.reactiveTextureUsage);
		}
	}
	MTLTextureUsage needed = created.scaler.outputTextureUsage;
	if ((output.usage & needed) != needed) {
		created.staging = m2m_new_private_texture(ctx, output.pixelFormat, output.width, output.height, needed);
	}
	m2m_retire(ctx, current.motion);
	for (id<MTLTexture> texture in current.depths) {
		m2m_retire(ctx, texture);
	}
	m2m_retire(ctx, current.reactive);
	m2m_retire(ctx, current.staging);
	m2m_retire(ctx, current.scaler);
	ctx.temporalUpscaler = created;
	return created;
}

// The motion vector pipeline, or with reactive its variant that also writes the reactive mask.
static id<MTLRenderPipelineState> m2m_motion_pipeline(M2MContext *ctx, BOOL reactive) {
	id<MTLRenderPipelineState> existing = reactive ? ctx.motionReactivePipeline : ctx.motionPipeline;
	if (existing) {
		return existing;
	}
	NSError *error = nil;
	id<MTLLibrary> library = [ctx.device newLibraryWithSource:M2M_MOTION_SHADER options:nil error:&error];
	if (!library) {
		NSLog(@"mine2metal: could not compile the motion vector shader: %@", error);
		return nil;
	}
	MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
	descriptor.label = reactive ? @"mine2metal motion vectors and reactive mask" : @"mine2metal motion vectors";
	descriptor.vertexFunction = [library newFunctionWithName:@"m2m_motion_vertex"];
	descriptor.fragmentFunction = [library newFunctionWithName:reactive ? @"m2m_motion_reactive_fragment" : @"m2m_motion_fragment"];
	descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatRG16Float;
	if (reactive) {
		descriptor.colorAttachments[1].pixelFormat = MTLPixelFormatR8Unorm;
	}
	descriptor.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
	id<MTLRenderPipelineState> pipeline = [ctx.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
	if (!pipeline) {
		NSLog(@"mine2metal: could not create the motion vector pipeline: %@", error);
		return nil;
	}
	if (reactive) {
		ctx.motionReactivePipeline = pipeline;
	} else {
		ctx.motionPipeline = pipeline;
	}
	if (!ctx.motionDepthState) {
		MTLDepthStencilDescriptor *state = [MTLDepthStencilDescriptor new];
		state.depthCompareFunction = MTLCompareFunctionAlways;
		state.depthWriteEnabled = YES;
		ctx.motionDepthState = [ctx.device newDepthStencilStateWithDescriptor:state];
	}
	return pipeline;
}

BOOL m2m_encode_motion(M2MContext *ctx, id<MTLTexture> sourceDepth, id<MTLTexture> motion, id<MTLTexture> depth,
                       const float *matrices, id<MTLTexture> previousDepth, id<MTLTexture> reactive) {
	BOOL withReactive = reactive != nil && previousDepth != nil;
	id<MTLRenderPipelineState> motionPipeline = m2m_motion_pipeline(ctx, withReactive);
	if (!motionPipeline) {
		return NO;
	}
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = motion;
	pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	if (withReactive) {
		pass.colorAttachments[1].texture = reactive;
		pass.colorAttachments[1].loadAction = MTLLoadActionDontCare;
		pass.colorAttachments[1].storeAction = MTLStoreActionStore;
	}
	pass.depthAttachment.texture = depth;
	pass.depthAttachment.loadAction = MTLLoadActionDontCare;
	pass.depthAttachment.storeAction = MTLStoreActionStore;
	id<M2MRenderCommands> encoder = m2m_open_render_encoder(ctx, pass, @"mine2metal motion vectors");
	[encoder setRenderPipelineState:motionPipeline];
	[encoder setDepthStencilState:ctx.motionDepthState];
	m2m_bind_texture(ctx, 0, sourceDepth, NO, YES);
	if (withReactive) {
		m2m_bind_texture(ctx, 1, previousDepth, NO, YES);
	}
	M2MMotionParams params;
	memcpy(&params, matrices, sizeof(params));
	m2m_bind_bytes(ctx, 0, &params, sizeof(params), NO, YES);
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	m2m_end_encoding(ctx);
	return YES;
}

int32_t m2m_metalfx_temporal_upscale(int64_t handle, int64_t color_handle, int64_t depth_handle, int64_t output_handle,
                                     const float *matrices, float jitter_x, float jitter_y, int32_t reset, int32_t reactive) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> sourceDepth = m2m_borrow(depth_handle);
		id<MTLTexture> output = m2m_borrow(output_handle);
		BOOL rebuilt = ctx.temporalUpscaler == nil;
		M2MTemporalUpscaler *upscaler = m2m_temporal_upscaler(ctx, color, output, reactive != 0);
		if (!upscaler) {
			return 0;
		}
		upscaler.depthIndex = (upscaler.depthIndex + 1) % 2;
		id<MTLTexture> depth = upscaler.depths[upscaler.depthIndex];
		id<MTLTexture> previousDepth = upscaler.depths[(upscaler.depthIndex + 1) % 2];
		float motionMatrices[52];
		memcpy(motionMatrices, matrices, sizeof(motionMatrices));
		if (upscaler.fresh || reset) {
			motionMatrices[35] = 0.0f;
		}
		upscaler.fresh = NO;
		if (!m2m_encode_motion(ctx, sourceDepth, upscaler.motion, depth, motionMatrices, previousDepth, upscaler.reactive)) {
			return 0;
		}
		rebuilt = rebuilt || upscaler != ctx.lastTemporalUpscaler;
		ctx.lastTemporalUpscaler = upscaler;

		id<MTLFXTemporalScalerBase> scaler = upscaler.scaler;
		scaler.colorTexture = color;
		scaler.depthTexture = depth;
		if (upscaler.reactive) {
			if (@available(macOS 14.4, *)) {
				scaler.reactiveMaskTexture = upscaler.reactive;
			}
		}
		scaler.motionTexture = upscaler.motion;
		scaler.outputTexture = upscaler.staging ?: output;
		scaler.inputContentWidth = color.width;
		scaler.inputContentHeight = color.height;
		scaler.jitterOffsetX = jitter_x;
		scaler.jitterOffsetY = jitter_y;
		scaler.motionVectorScaleX = 1.0f;
		scaler.motionVectorScaleY = 1.0f;
		scaler.depthReversed = NO;
		scaler.reset = reset != 0 || rebuilt;
		if (ctx.metal4) {
			[(id<MTL4FXTemporalScaler>)scaler encodeToCommandBuffer:m2m_command_buffer4(ctx)];
		} else {
			[(id<MTLFXTemporalScaler>)scaler encodeToCommandBuffer:m2m_command_buffer(ctx)];
		}

		if (upscaler.staging) {
			[m2m_copier(ctx) copyFromTexture:upscaler.staging toTexture:output];
		}
		return 1;
	}
}
