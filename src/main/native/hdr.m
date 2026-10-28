#import <AppKit/AppKit.h>

#include "m2m_context.h"

// EDR output. Minecraft draws SDR, so instead of HDR lighting the world's brightest parts (sky, sun, lava, glowstone,
// lit snow) are stretched above SDR white, up to the peak the player chose and the display can show right now.
// Everything else keeps its SDR value, 1.0 being SDR white in the layer's extended linear sRGB space.
//
// The hand and the GUI must stay SDR: a white hotbar or chat line at full EDR brightness glares. The world is
// captured before they are drawn, and the finished frame is compared with it pixel by pixel; only where the two still
// match, nothing was drawn over the world, and its highlights are expanded there. Overlays that darken the whole
// screen (a pause menu, the vignette at the edges) keep what they cover in SDR too.
static NSString *const M2M_HDR_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct HdrParams { float peak; float knee; float hasWorld; float pad; };\n"
     "vertex float4 m2m_hdr_vertex(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    return float4(p * 2.0 - 1.0, 0.0, 1.0);\n"
     "}\n"
     "static float3 m2m_decode(float3 e) {\n"
     "    return select(pow((e + 0.055) / 1.055, 2.4), e / 12.92, e <= 0.04045);\n"
     "}\n"
     // Colors whose strongest channel is past the knee are scaled up, smoothly from 1 at the knee to peak at full.
     "static float3 m2m_expand(float3 encoded, constant HdrParams &params) {\n"
     "    float strongest = max(encoded.r, max(encoded.g, encoded.b));\n"
     "    float t = saturate((strongest - params.knee) / (1.0 - params.knee));\n"
     "    return m2m_decode(encoded) * (1.0 + (params.peak - 1.0) * t * t);\n"
     "}\n"
     "fragment float4 m2m_hdr_compose(float4 position [[position]], texture2d<float, access::read> frame [[texture(0)]],\n"
     "                                texture2d<float, access::read> world [[texture(1)]], constant HdrParams &params [[buffer(0)]]) {\n"
     "    uint2 pixel = uint2(position.xy);\n"
     "    float3 encoded = frame.read(pixel).rgb;\n"
     "    float3 linear = m2m_decode(encoded);\n"
     "    if (params.hasWorld == 0.0) {\n"
     "        return float4(linear, 1.0);\n"
     "    }\n"
     "    float3 bare = world.read(pixel).rgb;\n"
     "    float3 difference = abs(encoded - bare);\n"
     "    float covered = smoothstep(1.5 / 255.0, 6.0 / 255.0, max(difference.r, max(difference.g, difference.b)));\n"
     "    return float4(linear + (m2m_expand(bare, params) - m2m_decode(bare)) * (1.0 - covered), 1.0);\n"
     "}\n"
     "fragment float4 m2m_hdr_expand(float4 position [[position]], texture2d<float, access::read> world [[texture(1)]],\n"
     "                               constant HdrParams &params [[buffer(0)]]) {\n"
     "    return float4(m2m_expand(world.read(uint2(position.xy)).rgb, params), 1.0);\n"
     "}\n";

// Encoded values below this are left alone.
static const float M2M_HDR_KNEE = 0.75f;
// The display's headroom changes with the brightness setting; it is read again after this many presents.
static const int64_t M2M_HEADROOM_PRESENTS = 30;

typedef struct {
	float peak;
	float knee;
	float has_world;
	float pad;
} M2MHdrParams;

static BOOL m2m_hdr_pipelines(M2MContext *ctx) {
	if (ctx.hdrComposePipeline && ctx.hdrExpandPipeline) {
		return YES;
	}
	NSError *error = nil;
	id<MTLLibrary> library = [ctx.device newLibraryWithSource:M2M_HDR_SHADER options:nil error:&error];
	if (!library) {
		NSLog(@"mine2metal: could not compile the HDR shader: %@", error);
		return NO;
	}
	MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
	descriptor.vertexFunction = [library newFunctionWithName:@"m2m_hdr_vertex"];
	descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
	descriptor.label = @"mine2metal HDR compose";
	descriptor.fragmentFunction = [library newFunctionWithName:@"m2m_hdr_compose"];
	ctx.hdrComposePipeline = [ctx.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
	descriptor.label = @"mine2metal HDR expand";
	descriptor.fragmentFunction = [library newFunctionWithName:@"m2m_hdr_expand"];
	ctx.hdrExpandPipeline = [ctx.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
	if (!ctx.hdrComposePipeline || !ctx.hdrExpandPipeline) {
		NSLog(@"mine2metal: could not create the HDR pipelines: %@", error);
		return NO;
	}
	return YES;
}

// The peak to expand to: what the player chose, within what the display can show now.
static float m2m_hdr_effective_peak(M2MContext *ctx) {
	if (ctx.hdrIgnoreDisplay) {
		return ctx.hdrPeak;
	}
	if (ctx.hdrHeadroomAge <= 0) {
		NSScreen *screen = NSScreen.mainScreen;
		ctx.hdrHeadroom = screen ? (float)screen.maximumExtendedDynamicRangeColorComponentValue : 1.0f;
		ctx.hdrHeadroomAge = M2M_HEADROOM_PRESENTS;
	}
	ctx.hdrHeadroomAge--;
	return MAX(1.0f, MIN(ctx.hdrPeak, ctx.hdrHeadroom));
}

static BOOL m2m_hdr_draw(M2MContext *ctx, id<MTLRenderPipelineState> pipeline, id<MTLTexture> frame, id<MTLTexture> world,
                         id<MTLTexture> target, NSString *label) {
	M2MHdrParams params = {m2m_hdr_effective_peak(ctx), M2M_HDR_KNEE, world ? 1.0f : 0.0f, 0.0f};
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = target;
	pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<M2MRenderCommands> encoder = m2m_open_render_encoder(ctx, pass, label);
	[encoder setRenderPipelineState:pipeline];
	// Every texture the shader names is bound, the frame standing in for a missing world.
	m2m_bind_texture(ctx, 0, frame ?: world, NO, YES);
	m2m_bind_texture(ctx, 1, world ?: frame, NO, YES);
	m2m_bind_bytes(ctx, 0, &params, sizeof(params), NO, YES);
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	m2m_end_encoding(ctx);
	return YES;
}

BOOL m2m_hdr_compose(M2MContext *ctx, id<MTLTexture> source, id<MTLTexture> target) {
	if (!m2m_hdr_pipelines(ctx)) {
		return NO;
	}
	id<MTLTexture> world = ctx.hdrWorld;
	BOOL matches = ctx.hdrWorldCaptured && world && world.width == source.width && world.height == source.height;
	ctx.hdrWorldCaptured = NO;
	ctx.hdrLastFrame = target;
	return m2m_hdr_draw(ctx, ctx.hdrComposePipeline, source, matches ? world : nil, target, @"mine2metal HDR compose");
}

BOOL m2m_hdr_expand(M2MContext *ctx, id<MTLTexture> world, id<MTLTexture> target) {
	if (!m2m_hdr_pipelines(ctx)) {
		return NO;
	}
	return m2m_hdr_draw(ctx, ctx.hdrExpandPipeline, nil, world, target, @"mine2metal HDR expand");
}

int32_t m2m_hdr_supported(void) {
	@autoreleasepool {
		NSScreen *screen = NSScreen.mainScreen;
		return screen && screen.maximumPotentialExtendedDynamicRangeColorComponentValue > 1.0 ? 1 : 0;
	}
}

float m2m_hdr_headroom(void) {
	@autoreleasepool {
		NSScreen *screen = NSScreen.mainScreen;
		return screen ? (float)screen.maximumExtendedDynamicRangeColorComponentValue : 1.0f;
	}
}

void m2m_hdr_set_peak(int64_t handle, float peak, int32_t ignore_display) {
	M2MContext *ctx = m2m_ctx(handle);
	ctx.hdrPeak = MAX(1.0f, peak);
	ctx.hdrIgnoreDisplay = ignore_display != 0;
	ctx.hdrHeadroomAge = 0;
}

void m2m_hdr_capture(int64_t handle, int64_t color_handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		if (!ctx.hdr) {
			return;
		}
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> world = ctx.hdrWorld;
		if (!world || world.width != color.width || world.height != color.height || world.pixelFormat != color.pixelFormat) {
			m2m_retire(ctx, world);
			world = m2m_new_private_texture(ctx, color.pixelFormat, color.width, color.height, MTLTextureUsageShaderRead);
			ctx.hdrWorld = world;
		}
		[m2m_copier(ctx) copyFromTexture:color toTexture:world];
		m2m_end_encoding(ctx);
		ctx.hdrWorldCaptured = YES;
	}
}

int64_t m2m_hdr_last_frame(int64_t ctx) {
	id<MTLTexture> frame = m2m_ctx(ctx).hdrLastFrame;
	return frame ? (int64_t)(intptr_t)(__bridge void *)frame : 0;
}
