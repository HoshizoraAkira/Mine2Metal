#import <AppKit/AppKit.h>

#include "m2m_context.h"

// Presents slightly early so a frame finished just before the refresh still makes it.
static const double M2M_PACING_MARGIN = 0.9;
// After this many refreshes without the presented handler, stop waiting for it.
static const double M2M_STALE_PRESENT_REFRESHES = 4.0;
// With vsync off, presents allowed on their way to the screen at once. One is not enough: the frame after a
// shown one cannot be rendered, copied and composited before the next refresh, so only every other refresh
// would get a new frame. Two keep every refresh fed while leaving the third drawable free, so nextDrawable
// never blocks.
static const int32_t M2M_MAX_PRESENTS_IN_FLIGHT = 2;

void m2m_layer_configure(int64_t ctx, int64_t layer_handle, int32_t width, int32_t height, int32_t vsync) {
	@autoreleasepool {
		CAMetalLayer *layer = m2m_borrow(layer_handle);
		layer.device = m2m_ctx(ctx).device;
		layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
		layer.framebufferOnly = YES;
		layer.opaque = YES;
		layer.maximumDrawableCount = 3;
		layer.displaySyncEnabled = vsync != 0;
		layer.drawableSize = CGSizeMake(width, height);

		M2MContext *context = m2m_ctx(ctx);
		NSView *view = [layer.delegate isKindOfClass:[NSView class]] ? (NSView *)layer.delegate : nil;
		NSScreen *screen = view.window.screen ?: NSScreen.mainScreen;
		NSInteger refreshRate = screen.maximumFramesPerSecond > 0 ? screen.maximumFramesPerSecond : 60;
		context.layerVsync = vsync != 0;
		context.refreshInterval = 1.0 / (double)refreshRate;
	}
}

int64_t m2m_take_presented_times(int64_t handle, double *out, int64_t capacity) {
	M2MContext *ctx = m2m_ctx(handle);
	int64_t count = 0;
	@synchronized(ctx.presentedTimes) {
		for (NSNumber *time in ctx.presentedTimes) {
			if (count >= capacity) {
				break;
			}
			out[count++] = time.doubleValue;
		}
		[ctx.presentedTimes removeAllObjects];
	}
	return count;
}

int32_t m2m_present(int64_t handle, int64_t layer_handle, int64_t texture) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		// Without vsync the game renders faster than the display shows frames. Presenting each one would leave
		// every drawable queued at the compositor and block nextDrawable, so frames go out about once per refresh
		// and only while fewer than M2M_MAX_PRESENTS_IN_FLIGHT are still on their way to the screen.
		CFTimeInterval now = CACurrentMediaTime();
		if (!ctx.layerVsync) {
			int32_t inFlight;
			@synchronized(ctx) {
				if (now - ctx.lastPresentTime > ctx.refreshInterval * M2M_STALE_PRESENT_REFRESHES) {
					// The presented handler can be skipped (window hidden, display asleep); do not wait for it forever.
					ctx.presentsInFlight = 0;
				}
				inFlight = ctx.presentsInFlight;
			}
			if (inFlight >= M2M_MAX_PRESENTS_IN_FLIGHT || now - ctx.lastPresentTime < ctx.refreshInterval * M2M_PACING_MARGIN) {
				return 2;
			}
		}

		CAMetalLayer *layer = m2m_borrow(layer_handle);
		id<CAMetalDrawable> drawable = [layer nextDrawable];
		if (!drawable) {
			return 0;
		}
		ctx.lastPresentTime = now;
		@synchronized(ctx) {
			ctx.presentsInFlight += 1;
		}
		__weak M2MContext *weakCtx = ctx;
		[drawable addPresentedHandler:^(id<MTLDrawable> shown) {
			M2MContext *strongCtx = weakCtx;
			@synchronized(strongCtx) {
				strongCtx.presentsInFlight = MAX(0, strongCtx.presentsInFlight - 1);
			}
			// presentedTime is 0 for a drawable the compositor never showed; recorded too, as dropped frames.
			CFTimeInterval presented = shown.presentedTime;
			@synchronized(strongCtx.presentedTimes) {
				if (strongCtx.presentedTimes.count < 4096) {
					[strongCtx.presentedTimes addObject:@(presented)];
				}
			}
		}];

		// The frame is copied out on the render queue and drawn to the drawable on a separate present queue. A
		// command buffer that renders to a drawable waits on the GPU until the compositor releases it, often until
		// the next refresh; on the render queue that wait would hold up every later frame while the GPU idles.
		id<MTLTexture> source = m2m_borrow(texture);
		int64_t present = ctx.presentCount + 1;
		NSUInteger slot = (NSUInteger)(present % 2);
		id<MTLTexture> staging = ctx.presentStaging[slot];
		if (staging == (id)[NSNull null] || staging.width != source.width || staging.height != source.height ||
		    staging.pixelFormat != source.pixelFormat) {
			MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:source.pixelFormat
			                                                                                    width:source.width
			                                                                                   height:source.height
			                                                                                mipmapped:NO];
			descriptor.storageMode = MTLStorageModePrivate;
			descriptor.usage = MTLTextureUsageShaderRead;
			staging = [ctx.device newTextureWithDescriptor:descriptor];
			ctx.presentStaging[slot] = staging;
		}

		m2m_end_encoding(ctx);
		id<MTLCommandBuffer> renderCommands = m2m_command_buffer(ctx);
		if (present > 2) {
			// This slot was last read by present - 2.
			[renderCommands encodeWaitForEvent:ctx.presentDone value:(uint64_t)(present - 2)];
		}
		[m2m_blit_encoder(ctx) copyFromTexture:source toTexture:staging];
		m2m_end_encoding(ctx);
		[renderCommands encodeSignalEvent:ctx.frameReady value:(uint64_t)present];

		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture = drawable.texture;
		pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		pass.colorAttachments[0].storeAction = MTLStoreActionStore;

		id<MTLCommandBuffer> presentCommands = [ctx.presentQueue commandBuffer];
		[presentCommands encodeWaitForEvent:ctx.frameReady value:(uint64_t)present];
		id<MTLRenderCommandEncoder> encoder = [presentCommands renderCommandEncoderWithDescriptor:pass];
		encoder.label = @"mine2metal present";
		[encoder setRenderPipelineState:ctx.presentPipeline];
		[encoder setFragmentTexture:staging atIndex:0];
		[encoder setFragmentSamplerState:ctx.presentSampler atIndex:0];
		[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[encoder endEncoding];
		[presentCommands encodeSignalEvent:ctx.presentDone value:(uint64_t)present];
		[presentCommands presentDrawable:drawable];
		// Safe to commit before the render queue's buffer: the GPU holds it at the frameReady wait.
		[presentCommands commit];

		ctx.presentCount = present;
		return 1;
	}
}
