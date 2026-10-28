#import <MetalFX/MetalFX.h>

#include "m2m_context.h"

// MetalFX frame interpolation needs macOS 26: m2m_frame_interpolation_supported checks, and nothing else here runs
// unless it said yes.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// MetalFX frame interpolation. Each frame the world is captured before the hand and the GUI are drawn, together with
// motion vectors and depth from the pass the temporal scaler uses. When the frame is presented, MetalFX generates the
// frame halfway between it and the previous one, and both are shown, the generated one first. The finished frame
// (world, hand and GUI) goes in as the composited UI texture: MetalFX separates what was drawn over the world and lays
// it over the generated frame unmoved, so the hotbar and the hand do not warp with the world.

// The world of this frame and of the previous one alternate. Generated frames go one per present slot, next to the
// real frame they are shown before.
static const NSUInteger M2M_INTERPOLATION_SLOTS = 2;

typedef struct {
	float near_plane;
	float far_plane;
	float field_of_view;
	float aspect_ratio;
	float jitter_x;
	float jitter_y;
	float delta_time;
	float motion_scale_x;
	float motion_scale_y;
} M2MInterpolationCamera;

@interface M2MFrameInterpolator : NSObject
@property(nonatomic) int32_t engine;
// With MetalFX: an MTLFXFrameInterpolator, or an MTL4FXFrameInterpolator with Metal 4.
@property(nonatomic, strong) id<MTLFXFrameInterpolatorBase> interpolator;
// With FSR: the frame generator of fsr.m, which keeps the previous frame itself.
@property(nonatomic, strong) id fsr;
@property(nonatomic) NSUInteger inputWidth, inputHeight, outputWidth, outputHeight;
@property(nonatomic) MTLPixelFormat colorFormat;
@property(nonatomic, strong) id<MTLTexture> motion;
@property(nonatomic, strong) id<MTLTexture> depth;
@property(nonatomic, strong) NSMutableArray<id<MTLTexture>> *worlds;
@property(nonatomic, strong) NSMutableArray<id<MTLTexture>> *outputs;
// Index in worlds of the newest capture.
@property(nonatomic) NSUInteger latest;
// A capture waits for its present; the previous world belongs to the frame presented just before it.
@property(nonatomic) BOOL pending;
@property(nonatomic) BOOL previousValid;
@property(nonatomic) BOOL lastPresentCaptured;
@property(nonatomic) M2MInterpolationCamera camera;
// The generated frame of the most recent present, for the game test's readback; nil if it had none.
@property(nonatomic, weak) id<MTLTexture> lastOutput;
@end

@implementation M2MFrameInterpolator
@end

int32_t m2m_fsr_framegen_supported(int64_t ctx) {
	// Texture atomics (MSL 3.1, macOS 14) on Apple GPUs from the M1 on.
	return [m2m_ctx(ctx).device supportsFamily:MTLGPUFamilyApple7] ? 1 : 0;
}

int32_t m2m_frame_interpolation_supported(int64_t ctx) {
	M2MContext *context = m2m_ctx(ctx);
	if (@available(macOS 26.0, *)) {
		if (context.metal4) {
			return [MTLFXFrameInterpolatorDescriptor supportsMetal4FX:context.device] ? 1 : 0;
		}
		return [MTLFXFrameInterpolatorDescriptor supportsDevice:context.device] ? 1 : 0;
	}
	return 0;
}

static void m2m_frame_interpolator_retire(M2MContext *ctx, M2MFrameInterpolator *old) {
	if (!old) {
		return;
	}
	m2m_retire(ctx, old.motion);
	m2m_retire(ctx, old.depth);
	for (id<MTLTexture> texture in old.worlds) {
		m2m_retire(ctx, texture);
	}
	for (id<MTLTexture> texture in old.outputs) {
		m2m_retire(ctx, texture);
	}
	m2m_retire(ctx, old.interpolator);
	if (old.fsr) {
		m2m_fsr_framegen_release(ctx, old.fsr);
	}
}

// format is the color's, or RGBA16Float with EDR: then every color input and the output are extended linear, the
// worlds expanded like the composed frame (hdr.m).
static M2MFrameInterpolator *m2m_fsr_interpolator(M2MContext *ctx, id<MTLTexture> color, id<MTLTexture> sourceDepth,
                                                  MTLPixelFormat format) {
	M2MFrameInterpolator *created = [M2MFrameInterpolator new];
	created.engine = M2M_FRAMEGEN_FSR;
	created.fsr = m2m_fsr_framegen_create(ctx, color.width, color.height, sourceDepth.width, sourceDepth.height, format, ctx.hdr);
	if (!created.fsr) {
		return nil;
	}
	MTLTextureUsage read = MTLTextureUsageShaderRead;
	created.motion = m2m_new_private_texture(ctx, MTLPixelFormatRG16Float, sourceDepth.width, sourceDepth.height, MTLTextureUsageRenderTarget | read);
	created.depth = m2m_new_private_texture(ctx, MTLPixelFormatDepth32Float, sourceDepth.width, sourceDepth.height, MTLTextureUsageRenderTarget | read);
	created.worlds = [NSMutableArray array];
	created.outputs = [NSMutableArray array];
	for (NSUInteger i = 0; i < M2M_INTERPOLATION_SLOTS; i++) {
		[created.worlds addObject:m2m_new_private_texture(ctx, format, color.width, color.height, read | MTLTextureUsageRenderTarget)];
	}
	for (NSUInteger i = 0; i < M2M_PRESENT_SLOTS; i++) {
		[created.outputs addObject:m2m_new_private_texture(ctx, format, color.width, color.height, read | MTLTextureUsageShaderWrite)];
	}
	return created;
}

static M2MFrameInterpolator *m2m_frame_interpolator(M2MContext *ctx, id<MTLTexture> color, id<MTLTexture> sourceDepth,
                                                    MTLPixelFormat format, int32_t engine) {
	M2MFrameInterpolator *current = ctx.frameInterpolator;
	if (current && current.engine == engine && current.inputWidth == sourceDepth.width && current.inputHeight == sourceDepth.height &&
	    current.outputWidth == color.width && current.outputHeight == color.height && current.colorFormat == format) {
		return current;
	}
	if (engine == M2M_FRAMEGEN_FSR) {
		M2MFrameInterpolator *created = m2m_fsr_interpolator(ctx, color, sourceDepth, format);
		if (!created) {
			return nil;
		}
		created.inputWidth = sourceDepth.width;
		created.inputHeight = sourceDepth.height;
		created.outputWidth = color.width;
		created.outputHeight = color.height;
		created.colorFormat = format;
		m2m_frame_interpolator_retire(ctx, current);
		ctx.frameInterpolator = created;
		return created;
	}

	MTLFXFrameInterpolatorDescriptor *descriptor = [MTLFXFrameInterpolatorDescriptor new];
	descriptor.colorTextureFormat = format;
	descriptor.outputTextureFormat = format;
	descriptor.uiTextureFormat = format;
	descriptor.depthTextureFormat = MTLPixelFormatDepth32Float;
	descriptor.motionTextureFormat = MTLPixelFormatRG16Float;
	descriptor.inputWidth = sourceDepth.width;
	descriptor.inputHeight = sourceDepth.height;
	descriptor.outputWidth = color.width;
	descriptor.outputHeight = color.height;

	M2MFrameInterpolator *created = [M2MFrameInterpolator new];
	created.engine = M2M_FRAMEGEN_METALFX;
	if (ctx.metal4) {
		created.interpolator = [descriptor newFrameInterpolatorWithDevice:ctx.device compiler:ctx.compiler];
	} else {
		created.interpolator = [descriptor newFrameInterpolatorWithDevice:ctx.device];
	}
	if (!created.interpolator) {
		return nil;
	}
	created.inputWidth = sourceDepth.width;
	created.inputHeight = sourceDepth.height;
	created.outputWidth = color.width;
	created.outputHeight = color.height;
	created.colorFormat = format;
	id<MTLFXFrameInterpolatorBase> interpolator = created.interpolator;
	created.motion = m2m_new_private_texture(ctx, MTLPixelFormatRG16Float, sourceDepth.width, sourceDepth.height,
	                                         MTLTextureUsageRenderTarget | interpolator.motionTextureUsage);
	created.depth = m2m_new_private_texture(ctx, MTLPixelFormatDepth32Float, sourceDepth.width, sourceDepth.height,
	                                        MTLTextureUsageRenderTarget | interpolator.depthTextureUsage);
	created.worlds = [NSMutableArray array];
	created.outputs = [NSMutableArray array];
	for (NSUInteger i = 0; i < M2M_INTERPOLATION_SLOTS; i++) {
		// Written by a copy, or rendered by the EDR expansion.
		[created.worlds addObject:m2m_new_private_texture(ctx, format, color.width, color.height,
		                                                  interpolator.colorTextureUsage | MTLTextureUsageRenderTarget)];
	}
	for (NSUInteger i = 0; i < M2M_PRESENT_SLOTS; i++) {
		// Read by the present pass that draws it onto a drawable.
		[created.outputs addObject:m2m_new_private_texture(ctx, format, color.width, color.height,
		                                                   interpolator.outputTextureUsage | MTLTextureUsageShaderRead)];
	}
	m2m_frame_interpolator_retire(ctx, current);
	ctx.frameInterpolator = created;
	return created;
}

int32_t m2m_frame_interpolation_capture(int64_t handle, int32_t engine, int64_t color_handle, int64_t depth_handle,
                                        const float *matrices, const float *camera, int32_t reset) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> sourceDepth = m2m_borrow(depth_handle);
		M2MFrameInterpolator *previous = ctx.frameInterpolator;
		MTLPixelFormat format = ctx.hdr ? MTLPixelFormatRGBA16Float : color.pixelFormat;
		M2MFrameInterpolator *interpolator = m2m_frame_interpolator(ctx, color, sourceDepth, format, engine);
		if (!interpolator) {
			return 0;
		}
		NSUInteger slot = (interpolator.latest + 1) % M2M_INTERPOLATION_SLOTS;
		if (!m2m_encode_motion(ctx, sourceDepth, interpolator.motion, interpolator.depth, matrices)) {
			return 0;
		}
		if (format == color.pixelFormat) {
			[m2m_copier(ctx) copyFromTexture:color toTexture:interpolator.worlds[slot]];
			m2m_end_encoding(ctx);
		} else if (!m2m_hdr_expand(ctx, color, interpolator.worlds[slot])) {
			return 0;
		}

		M2MInterpolationCamera values;
		memcpy(&values, camera, sizeof(values));
		interpolator.camera = values;
		interpolator.previousValid = interpolator == previous && interpolator.lastPresentCaptured && !interpolator.pending &&
		                             reset == 0;
		interpolator.latest = slot;
		interpolator.pending = YES;
		return 1;
	}
}

BOOL m2m_frame_interpolation_pending(M2MContext *ctx) {
	M2MFrameInterpolator *interpolator = ctx.frameInterpolator;
	return interpolator != nil && interpolator.pending;
}

MTLTextureUsage m2m_frame_interpolation_ui_usage(M2MContext *ctx) {
	M2MFrameInterpolator *interpolator = ctx.frameInterpolator;
	if (interpolator.engine == M2M_FRAMEGEN_FSR) {
		return MTLTextureUsageShaderRead;
	}
	return interpolator ? interpolator.interpolator.uiTextureUsage : 0;
}

// Frame generation runs in a command buffer of its own, so its GPU time can be measured: the work before it is
// committed first (as the present stage, for the stage timings), then the generation itself.
static void m2m_frame_generation_begin(M2MContext *ctx) {
	__weak M2MContext *weakCtx = ctx;
	BOOL stages = ctx.stageTiming;
	m2m_commit_open(ctx, @"present", ^(CFTimeInterval gpuStart, CFTimeInterval gpuEnd, NSError *error) {
		(void)error;
		M2MContext *strongCtx = weakCtx;
		strongCtx.gpuSeconds += gpuEnd - gpuStart;
		if (stages) {
			m2m_add_label_seconds(strongCtx, @"stage present", gpuEnd - gpuStart);
		}
	});
}

static void m2m_frame_generation_end(M2MContext *ctx) {
	__weak M2MContext *weakCtx = ctx;
	BOOL stages = ctx.stageTiming;
	m2m_commit_open(ctx, @"frame generation", ^(CFTimeInterval gpuStart, CFTimeInterval gpuEnd, NSError *error) {
		(void)error;
		M2MContext *strongCtx = weakCtx;
		double seconds = gpuEnd - gpuStart;
		strongCtx.gpuSeconds += seconds;
		// Smoothed over about ten frames; read by the frame generation guard.
		double previous = strongCtx.frameGenerationSeconds;
		strongCtx.frameGenerationSeconds = previous > 0.0 ? previous * 0.9 + seconds * 0.1 : seconds;
		if (stages) {
			m2m_add_label_seconds(strongCtx, @"stage frame generation", seconds);
		}
	});
}

double m2m_frame_generation_gpu_millis(int64_t ctx) {
	return m2m_ctx(ctx).frameGenerationSeconds * 1000.0;
}

id<MTLTexture> m2m_frame_interpolation_encode(M2MContext *ctx, id<MTLTexture> ui, NSUInteger slot) {
	M2MFrameInterpolator *interpolator = ctx.frameInterpolator;
	if (!interpolator || !interpolator.pending || ui.width != interpolator.outputWidth || ui.height != interpolator.outputHeight ||
	    ui.pixelFormat != interpolator.colorFormat) {
		return nil;
	}
	M2MInterpolationCamera camera = interpolator.camera;
	id<MTLTexture> output = interpolator.outputs[slot % M2M_PRESENT_SLOTS];
	if (interpolator.engine == M2M_FRAMEGEN_FSR) {
		// FSR keeps the previous frame itself, so it sees every captured frame; after a reset its output is not
		// shown, as there is nothing to interpolate from.
		M2MFsrFrame frame = {0};
		frame.hudless = interpolator.worlds[interpolator.latest];
		frame.backbuffer = ui;
		frame.output = output;
		frame.depth = interpolator.depth;
		frame.motion = interpolator.motion;
		frame.near_plane = camera.near_plane;
		frame.far_plane = camera.far_plane;
		frame.field_of_view = camera.field_of_view * (float)M_PI / 180.0f;
		frame.jitter_x = camera.jitter_x;
		frame.jitter_y = camera.jitter_y;
		frame.frame_time_ms = camera.delta_time * 1000.0f;
		frame.motion_scale_x = camera.motion_scale_x;
		frame.motion_scale_y = camera.motion_scale_y;
		frame.max_luminance = ctx.hdr ? MAX(1.0f, ctx.hdrPeak) : 1.0f;
		frame.reset = !interpolator.previousValid;
		m2m_frame_generation_begin(ctx);
		BOOL encoded = m2m_fsr_framegen_encode(ctx, interpolator.fsr, &frame);
		m2m_frame_generation_end(ctx);
		return encoded && interpolator.previousValid ? output : nil;
	}
	if (!interpolator.previousValid) {
		return nil;
	}
	id<MTLFXFrameInterpolatorBase> effect = interpolator.interpolator;
	effect.colorTexture = interpolator.worlds[interpolator.latest];
	effect.prevColorTexture = interpolator.worlds[(interpolator.latest + M2M_INTERPOLATION_SLOTS - 1) % M2M_INTERPOLATION_SLOTS];
	effect.depthTexture = interpolator.depth;
	effect.motionTexture = interpolator.motion;
	effect.uiTexture = ui;
	effect.uiTextureComposited = YES;
	effect.outputTexture = output;
	effect.motionVectorScaleX = camera.motion_scale_x;
	effect.motionVectorScaleY = camera.motion_scale_y;
	effect.deltaTime = camera.delta_time;
	effect.nearPlane = camera.near_plane;
	effect.farPlane = camera.far_plane;
	effect.fieldOfView = camera.field_of_view;
	effect.aspectRatio = camera.aspect_ratio;
	effect.jitterOffsetX = camera.jitter_x;
	effect.jitterOffsetY = camera.jitter_y;
	effect.depthReversed = NO;
	effect.shouldResetHistory = NO;
	m2m_frame_generation_begin(ctx);
	if (ctx.metal4) {
		[(id<MTL4FXFrameInterpolator>)effect encodeToCommandBuffer:m2m_command_buffer4(ctx)];
	} else {
		[(id<MTLFXFrameInterpolator>)effect encodeToCommandBuffer:m2m_command_buffer(ctx)];
	}
	m2m_frame_generation_end(ctx);
	return output;
}

void m2m_frame_interpolation_presented(M2MContext *ctx, id<MTLTexture> generated) {
	M2MFrameInterpolator *interpolator = ctx.frameInterpolator;
	if (!interpolator) {
		return;
	}
	interpolator.lastPresentCaptured = interpolator.pending;
	interpolator.lastOutput = generated;
	interpolator.pending = NO;
}

int64_t m2m_frame_interpolation_last_output(int64_t ctx) {
	M2MFrameInterpolator *interpolator = m2m_ctx(ctx).frameInterpolator;
	id<MTLTexture> output = interpolator.lastOutput;
	return output ? (int64_t)(intptr_t)(__bridge void *)output : 0;
}

void m2m_frame_interpolation_release(int64_t handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		m2m_frame_interpolator_retire(ctx, ctx.frameInterpolator);
		ctx.frameInterpolator = nil;
	}
}
