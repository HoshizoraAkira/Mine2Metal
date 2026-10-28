#import <MetalFX/MetalFX.h>

#include "m2m_context.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// MetalFX temporal upscaling. Minecraft draws no motion vectors, so a pass derives them from depth and the camera:
// each pixel is unprojected with this frame's matrices and projected with the previous frame's. That is exact for
// the world, which does not move; moving entities get the camera's motion only. The same pass copies depth into a
// plain Depth32Float texture, since the game's depth texture has a stencil aspect.
static NSString *const M2M_MOTION_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct Params {\n"
     "    float4x4 inverseCurrent;\n"
     "    float4x4 previous;\n"
     "    float4 cameraDelta;\n"
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
     "}\n";

typedef struct {
	float inverse_current[16];
	float previous[16];
	float camera_delta[4];
} M2MMotionParams;

@interface M2MTemporalUpscaler : NSObject
// An MTLFXTemporalScaler, or an MTL4FXTemporalScaler with Metal 4.
@property(nonatomic, strong) id<MTLFXTemporalScalerBase> scaler;
@property(nonatomic) NSUInteger inputWidth, inputHeight, outputWidth, outputHeight;
@property(nonatomic) MTLPixelFormat colorFormat, outputFormat;
@property(nonatomic, strong) id<MTLTexture> motion;
@property(nonatomic, strong) id<MTLTexture> depth;
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

static M2MTemporalUpscaler *m2m_temporal_upscaler(M2MContext *ctx, id<MTLTexture> color, id<MTLTexture> output) {
	M2MTemporalUpscaler *current = ctx.temporalUpscaler;
	if (current && current.inputWidth == color.width && current.inputHeight == color.height &&
	    current.outputWidth == output.width && current.outputHeight == output.height &&
	    current.colorFormat == color.pixelFormat && current.outputFormat == output.pixelFormat) {
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

	M2MTemporalUpscaler *created = [M2MTemporalUpscaler new];
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
	created.depth = m2m_new_private_texture(ctx, MTLPixelFormatDepth32Float, color.width, color.height,
	                                    MTLTextureUsageRenderTarget | created.scaler.depthTextureUsage);
	MTLTextureUsage needed = created.scaler.outputTextureUsage;
	if ((output.usage & needed) != needed) {
		created.staging = m2m_new_private_texture(ctx, output.pixelFormat, output.width, output.height, needed);
	}
	m2m_retire(ctx, current.motion);
	m2m_retire(ctx, current.depth);
	m2m_retire(ctx, current.staging);
	m2m_retire(ctx, current.scaler);
	ctx.temporalUpscaler = created;
	return created;
}

static id<MTLRenderPipelineState> m2m_motion_pipeline(M2MContext *ctx) {
	if (ctx.motionPipeline) {
		return ctx.motionPipeline;
	}
	NSError *error = nil;
	id<MTLLibrary> library = [ctx.device newLibraryWithSource:M2M_MOTION_SHADER options:nil error:&error];
	if (!library) {
		NSLog(@"mine2metal: could not compile the motion vector shader: %@", error);
		return nil;
	}
	MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
	descriptor.label = @"mine2metal motion vectors";
	descriptor.vertexFunction = [library newFunctionWithName:@"m2m_motion_vertex"];
	descriptor.fragmentFunction = [library newFunctionWithName:@"m2m_motion_fragment"];
	descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatRG16Float;
	descriptor.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
	ctx.motionPipeline = [ctx.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
	if (!ctx.motionPipeline) {
		NSLog(@"mine2metal: could not create the motion vector pipeline: %@", error);
		return nil;
	}
	MTLDepthStencilDescriptor *state = [MTLDepthStencilDescriptor new];
	state.depthCompareFunction = MTLCompareFunctionAlways;
	state.depthWriteEnabled = YES;
	ctx.motionDepthState = [ctx.device newDepthStencilStateWithDescriptor:state];
	return ctx.motionPipeline;
}

BOOL m2m_encode_motion(M2MContext *ctx, id<MTLTexture> sourceDepth, id<MTLTexture> motion, id<MTLTexture> depth,
                       const float *matrices) {
	id<MTLRenderPipelineState> motionPipeline = m2m_motion_pipeline(ctx);
	if (!motionPipeline) {
		return NO;
	}
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = motion;
	pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	pass.depthAttachment.texture = depth;
	pass.depthAttachment.loadAction = MTLLoadActionDontCare;
	pass.depthAttachment.storeAction = MTLStoreActionStore;
	id<M2MRenderCommands> encoder = m2m_open_render_encoder(ctx, pass, @"mine2metal motion vectors");
	[encoder setRenderPipelineState:motionPipeline];
	[encoder setDepthStencilState:ctx.motionDepthState];
	m2m_bind_texture(ctx, 0, sourceDepth, NO, YES);
	M2MMotionParams params;
	memcpy(&params, matrices, sizeof(params));
	m2m_bind_bytes(ctx, 0, &params, sizeof(params), NO, YES);
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	m2m_end_encoding(ctx);
	return YES;
}

int32_t m2m_metalfx_temporal_upscale(int64_t handle, int64_t color_handle, int64_t depth_handle, int64_t output_handle,
                                     const float *matrices, float jitter_x, float jitter_y, int32_t reset) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> sourceDepth = m2m_borrow(depth_handle);
		id<MTLTexture> output = m2m_borrow(output_handle);
		BOOL rebuilt = ctx.temporalUpscaler == nil;
		M2MTemporalUpscaler *upscaler = m2m_temporal_upscaler(ctx, color, output);
		if (!upscaler || !m2m_encode_motion(ctx, sourceDepth, upscaler.motion, upscaler.depth, matrices)) {
			return 0;
		}
		rebuilt = rebuilt || upscaler != ctx.lastTemporalUpscaler;
		ctx.lastTemporalUpscaler = upscaler;

		id<MTLFXTemporalScalerBase> scaler = upscaler.scaler;
		scaler.colorTexture = color;
		scaler.depthTexture = upscaler.depth;
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
