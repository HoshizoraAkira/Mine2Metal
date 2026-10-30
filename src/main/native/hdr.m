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
//
// How far a pixel is expanded follows the brightness of the block of pixels around it, not its own: the curve is
// steep near white, so per pixel it would make the small changes of a bright surface from frame to frame (texture
// shimmer, temporal upscaling's jitter, generated frames) nearly three times as large as in SDR, which reads as
// flicker. One gain for a whole block scales them like everything else.
static NSString *const M2M_HDR_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct HdrParams { float peak; float knee; float hasWorld; float hasLevels; };\n"
     "constant uint M2M_LEVEL_BLOCK = 4;\n"
     "vertex float4 m2m_hdr_vertex(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    return float4(p * 2.0 - 1.0, 0.0, 1.0);\n"
     "}\n"
     "static float3 m2m_decode(float3 e) {\n"
     "    return select(pow((e + 0.055) / 1.055, 2.4), e / 12.92, e <= 0.04045);\n"
     "}\n"
     "static float m2m_strongest(float3 e) {\n"
     "    return max(e.r, max(e.g, e.b));\n"
     "}\n"
     // Brightness past the knee is scaled up, smoothly from 1 at the knee to peak at full.
     "static float m2m_gain(float strongest, constant HdrParams &params) {\n"
     "    float t = saturate((strongest - params.knee) / (1.0 - params.knee));\n"
     "    return 1.0 + (params.peak - 1.0) * t * t;\n"
     "}\n"
     // The brightness that sets a pixel's gain: its block's, filtered between blocks, or its own without levels.
     "static float m2m_level(float4 position, float3 bare, texture2d<float> levels, sampler linear, constant HdrParams &params) {\n"
     "    if (params.hasLevels == 0.0) {\n"
     "        return m2m_strongest(bare);\n"
     "    }\n"
     "    float2 size = float2(levels.get_width(), levels.get_height()) * float(M2M_LEVEL_BLOCK);\n"
     "    return levels.sample(linear, position.xy / size).r;\n"
     "}\n"
     "fragment float4 m2m_hdr_compose(float4 position [[position]], texture2d<float, access::read> frame [[texture(0)]],\n"
     "                                texture2d<float, access::read> world [[texture(1)]], texture2d<float> levels [[texture(2)]],\n"
     "                                sampler linear [[sampler(0)]], constant HdrParams &params [[buffer(0)]]) {\n"
     "    uint2 pixel = uint2(position.xy);\n"
     "    float3 encoded = frame.read(pixel).rgb;\n"
     "    float3 linear_color = m2m_decode(encoded);\n"
     "    if (params.hasWorld == 0.0) {\n"
     "        return float4(linear_color, 1.0);\n"
     "    }\n"
     "    float3 bare = world.read(pixel).rgb;\n"
     "    float3 difference = abs(encoded - bare);\n"
     "    float covered = smoothstep(1.5 / 255.0, 6.0 / 255.0, max(difference.r, max(difference.g, difference.b)));\n"
     "    if (covered < 1.0) {\n"
     "        float level = m2m_level(position, bare, levels, linear, params);\n"
     "        if (level > params.knee) {\n"
     "            float gain = m2m_gain(level, params);\n"
     "            float3 bare_linear = covered > 0.0 ? m2m_decode(bare) : linear_color;\n"
     "            linear_color += bare_linear * ((gain - 1.0) * (1.0 - covered));\n"
     "        }\n"
     "    }\n"
     "    return float4(linear_color, 1.0);\n"
     "}\n"
     "fragment float4 m2m_hdr_expand(float4 position [[position]], texture2d<float, access::read> world [[texture(1)]],\n"
     "                               texture2d<float> levels [[texture(2)]], sampler linear [[sampler(0)]],\n"
     "                               constant HdrParams &params [[buffer(0)]]) {\n"
     "    float3 bare = world.read(uint2(position.xy)).rgb;\n"
     "    float level = m2m_level(position, bare, levels, linear, params);\n"
     "    float gain = level > params.knee ? m2m_gain(level, params) : 1.0;\n"
     "    return float4(m2m_decode(bare) * gain, 1.0);\n"
     "}\n"
     // The mean brightness of each block of the world.
     "fragment float m2m_hdr_levels(float4 position [[position]], texture2d<float, access::read> world [[texture(1)]]) {\n"
     "    uint2 origin = uint2(position.xy) * M2M_LEVEL_BLOCK;\n"
     "    uint2 last = uint2(world.get_width(), world.get_height()) - 1;\n"
     "    float total = 0.0;\n"
     "    for (uint y = 0; y < M2M_LEVEL_BLOCK; y++) {\n"
     "        for (uint x = 0; x < M2M_LEVEL_BLOCK; x++) {\n"
     "            total += m2m_strongest(world.read(min(origin + uint2(x, y), last)).rgb);\n"
     "        }\n"
     "    }\n"
     "    return total / float(M2M_LEVEL_BLOCK * M2M_LEVEL_BLOCK);\n"
     "}\n";

// Encoded values below this are left alone.
static const float M2M_HDR_KNEE = 0.75f;
// Pixels per side of a block whose mean brightness sets its gain; as M2M_LEVEL_BLOCK in the shader.
static const NSUInteger M2M_HDR_LEVEL_BLOCK = 4;
// The display's headroom changes with its brightness (and with automatic brightness, all the time); it is read again
// this often, and the peak eases towards it over about this long so highlights do not jump.
static const CFTimeInterval M2M_HEADROOM_INTERVAL = 0.1;
static const CFTimeInterval M2M_PEAK_EASING = 0.5;

typedef struct {
	float peak;
	float knee;
	float has_world;
	float has_levels;
} M2MHdrParams;

static BOOL m2m_hdr_pipelines(M2MContext *ctx) {
	if (ctx.hdrComposePipeline && ctx.hdrExpandPipeline && ctx.hdrLevelsPipeline) {
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
	descriptor.label = @"mine2metal HDR levels";
	descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatR16Float;
	descriptor.fragmentFunction = [library newFunctionWithName:@"m2m_hdr_levels"];
	ctx.hdrLevelsPipeline = [ctx.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
	if (!ctx.hdrComposePipeline || !ctx.hdrExpandPipeline || !ctx.hdrLevelsPipeline) {
		NSLog(@"mine2metal: could not create the HDR pipelines: %@", error);
		return NO;
	}
	return YES;
}

// Once a frame: the peak to expand to, what the player chose within what the display can show now. A change of
// headroom is followed gradually.
static void m2m_hdr_update_peak(M2MContext *ctx) {
	CFTimeInterval now = CACurrentMediaTime();
	if (ctx.hdrIgnoreDisplay) {
		ctx.hdrFramePeak = ctx.hdrPeak;
		ctx.hdrFramePeakTime = now;
		return;
	}
	if (ctx.hdrHeadroomTime == 0.0 || now - ctx.hdrHeadroomTime >= M2M_HEADROOM_INTERVAL) {
		NSScreen *screen = NSScreen.mainScreen;
		ctx.hdrHeadroom = screen ? (float)screen.maximumExtendedDynamicRangeColorComponentValue : 1.0f;
		ctx.hdrHeadroomTime = now;
	}
	float target = MAX(1.0f, MIN(ctx.hdrPeak, ctx.hdrHeadroom));
	CFTimeInterval elapsed = now - ctx.hdrFramePeakTime;
	if (ctx.hdrFramePeakTime == 0.0 || elapsed > 1.0) {
		ctx.hdrFramePeak = target;
	} else {
		ctx.hdrFramePeak += (target - ctx.hdrFramePeak) * (float)(1.0 - exp(-elapsed / M2M_PEAK_EASING));
	}
	ctx.hdrFramePeakTime = now;
}

static id<MTLTexture> m2m_hdr_levels_for(M2MContext *ctx, id<MTLTexture> world) {
	id<MTLTexture> levels = ctx.hdrLevels;
	NSUInteger width = (world.width + M2M_HDR_LEVEL_BLOCK - 1) / M2M_HDR_LEVEL_BLOCK;
	NSUInteger height = (world.height + M2M_HDR_LEVEL_BLOCK - 1) / M2M_HDR_LEVEL_BLOCK;
	return levels && levels.width == width && levels.height == height ? levels : nil;
}

static BOOL m2m_hdr_draw(M2MContext *ctx, id<MTLRenderPipelineState> pipeline, id<MTLTexture> frame, id<MTLTexture> world,
                         id<MTLTexture> levels, id<MTLTexture> target, NSString *label) {
	M2MHdrParams params = {ctx.hdrFramePeak, M2M_HDR_KNEE, world ? 1.0f : 0.0f, levels ? 1.0f : 0.0f};
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = target;
	pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<M2MRenderCommands> encoder = m2m_open_render_encoder(ctx, pass, label);
	[encoder setRenderPipelineState:pipeline];
	// Every texture the shader names is bound, the frame standing in for a missing world or levels.
	id<MTLTexture> any = frame ?: world;
	m2m_bind_texture(ctx, 0, any, NO, YES);
	m2m_bind_texture(ctx, 1, world ?: any, NO, YES);
	m2m_bind_texture(ctx, 2, levels ?: any, NO, YES);
	m2m_bind_sampler(ctx, 0, ctx.presentSampler, NO, YES);
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
	if (!ctx.hdrWorldCaptured) {
		// No world this frame (a menu over no level): the capture did not set the peak.
		m2m_hdr_update_peak(ctx);
	}
	ctx.hdrWorldCaptured = NO;
	ctx.hdrLastFrame = target;
	return m2m_hdr_draw(ctx, ctx.hdrComposePipeline, source, matches ? world : nil, matches ? m2m_hdr_levels_for(ctx, world) : nil, target,
	                    @"mine2metal HDR compose");
}

BOOL m2m_hdr_expand(M2MContext *ctx, id<MTLTexture> world, id<MTLTexture> target) {
	if (!m2m_hdr_pipelines(ctx)) {
		return NO;
	}
	// The levels of this frame's capture, which is of the same world.
	id<MTLTexture> levels = ctx.hdrWorldCaptured ? m2m_hdr_levels_for(ctx, world) : nil;
	return m2m_hdr_draw(ctx, ctx.hdrExpandPipeline, nil, world, levels, target, @"mine2metal HDR expand");
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
	ctx.hdrHeadroomTime = 0.0;
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
		m2m_hdr_update_peak(ctx);
		if (m2m_hdr_pipelines(ctx)) {
			id<MTLTexture> levels = m2m_hdr_levels_for(ctx, world);
			if (!levels) {
				m2m_retire(ctx, ctx.hdrLevels);
				levels = m2m_new_private_texture(ctx, MTLPixelFormatR16Float, (world.width + M2M_HDR_LEVEL_BLOCK - 1) / M2M_HDR_LEVEL_BLOCK,
				                                 (world.height + M2M_HDR_LEVEL_BLOCK - 1) / M2M_HDR_LEVEL_BLOCK,
				                                 MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
				ctx.hdrLevels = levels;
			}
			m2m_hdr_draw(ctx, ctx.hdrLevelsPipeline, nil, world, nil, levels, @"mine2metal HDR levels");
		}
		ctx.hdrWorldCaptured = YES;
	}
}

int64_t m2m_hdr_last_frame(int64_t ctx) {
	id<MTLTexture> frame = m2m_ctx(ctx).hdrLastFrame;
	return frame ? (int64_t)(intptr_t)(__bridge void *)frame : 0;
}
