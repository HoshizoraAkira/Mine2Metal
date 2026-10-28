#import <AppKit/AppKit.h>
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

// The world of this frame and of the previous one alternate; generated frames alternate with the present staging
// textures they are shown before.
static const NSUInteger M2M_INTERPOLATION_SLOTS = 2;
// A real frame interval longer than this is a hitch, not a frame rate to pace generated frames for.
static const double M2M_PACING_RESET_SECONDS = 0.25;

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
// An MTLFXFrameInterpolator, or an MTL4FXFrameInterpolator with Metal 4.
@property(nonatomic, strong) id<MTLFXFrameInterpolatorBase> interpolator;
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
// Pacing: when the last frame with a generated one was presented, and the smoothed interval between those.
@property(nonatomic) CFTimeInterval lastPresent;
@property(nonatomic) double frameInterval;
// The generated frame of the most recent present, for the game test's readback; nil if it had none.
@property(nonatomic, weak) id<MTLTexture> lastOutput;
@end

@implementation M2MFrameInterpolator
@end

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
}

// format is the color's, or RGBA16Float with EDR: then every color input and the output are extended linear, the
// worlds expanded like the composed frame (hdr.m).
static M2MFrameInterpolator *m2m_frame_interpolator(M2MContext *ctx, id<MTLTexture> color, id<MTLTexture> sourceDepth,
                                                    MTLPixelFormat format) {
	M2MFrameInterpolator *current = ctx.frameInterpolator;
	if (current && current.inputWidth == sourceDepth.width && current.inputHeight == sourceDepth.height &&
	    current.outputWidth == color.width && current.outputHeight == color.height && current.colorFormat == format) {
		return current;
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
		// Read by the present pass that draws it onto a drawable.
		[created.outputs addObject:m2m_new_private_texture(ctx, format, color.width, color.height,
		                                                   interpolator.outputTextureUsage | MTLTextureUsageShaderRead)];
	}
	m2m_frame_interpolator_retire(ctx, current);
	ctx.frameInterpolator = created;
	return created;
}

int32_t m2m_frame_interpolation_capture(int64_t handle, int64_t color_handle, int64_t depth_handle, const float *matrices,
                                        const float *camera, int32_t reset) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = m2m_borrow(color_handle);
		id<MTLTexture> sourceDepth = m2m_borrow(depth_handle);
		M2MFrameInterpolator *previous = ctx.frameInterpolator;
		MTLPixelFormat format = ctx.hdr ? MTLPixelFormatRGBA16Float : color.pixelFormat;
		M2MFrameInterpolator *interpolator = m2m_frame_interpolator(ctx, color, sourceDepth, format);
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
	return interpolator ? interpolator.interpolator.uiTextureUsage : 0;
}

id<MTLTexture> m2m_frame_interpolation_encode(M2MContext *ctx, id<MTLTexture> ui, NSUInteger slot) {
	M2MFrameInterpolator *interpolator = ctx.frameInterpolator;
	if (!interpolator || !interpolator.pending || !interpolator.previousValid || ui.width != interpolator.outputWidth ||
	    ui.height != interpolator.outputHeight || ui.pixelFormat != interpolator.colorFormat) {
		return nil;
	}
	M2MInterpolationCamera camera = interpolator.camera;
	id<MTLTexture> output = interpolator.outputs[slot % M2M_INTERPOLATION_SLOTS];
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
	m2m_end_encoding(ctx);
	if (ctx.metal4) {
		[(id<MTL4FXFrameInterpolator>)effect encodeToCommandBuffer:m2m_command_buffer4(ctx)];
	} else {
		[(id<MTLFXFrameInterpolator>)effect encodeToCommandBuffer:m2m_command_buffer(ctx)];
	}
	return output;
}

double m2m_frame_interpolation_pacing(M2MContext *ctx) {
	M2MFrameInterpolator *interpolator = ctx.frameInterpolator;
	CFTimeInterval now = CACurrentMediaTime();
	double interval = now - interpolator.lastPresent;
	interpolator.lastPresent = now;
	if (interval > M2M_PACING_RESET_SECONDS || interpolator.frameInterval <= 0.0) {
		interpolator.frameInterval = MIN(interval, M2M_PACING_RESET_SECONDS);
	} else {
		interpolator.frameInterval = interpolator.frameInterval * 0.8 + interval * 0.2;
	}
	// Each of the two images is held for whole refreshes, as many as fit in half a real frame, rounded down: holding
	// them longer than the game takes for a frame would slow the game down to match, and the interval with it.
	double refresh = ctx.refreshInterval > 0.0 ? ctx.refreshInterval : 1.0 / 60.0;
	double refreshes = floor(interpolator.frameInterval * 0.95 / (2.0 * refresh));
	// Half a refresh short of the target, so timing noise does not push an image to the refresh after.
	return refreshes >= 2.0 ? (refreshes - 0.5) * refresh : 0.0;
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
