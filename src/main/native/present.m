#import <AppKit/AppKit.h>

#include "m2m_context.h"

// Presents slightly early so a frame finished just before the refresh still makes it.
static const double M2M_PACING_MARGIN = 0.9;
// After this many refreshes without the presented handler, stop waiting for it.
static const double M2M_STALE_PRESENT_REFRESHES = 4.0;

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

int32_t m2m_present(int64_t handle, int64_t layer_handle, int64_t texture) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		// Without vsync the game renders faster than the display shows frames. Presenting each one would leave
		// every drawable queued at the compositor and block nextDrawable, so a frame goes out only once the previous
		// one has reached the screen (or a refresh has passed, in case the presented handler never comes).
		CFTimeInterval now = CACurrentMediaTime();
		if (!ctx.layerVsync) {
			BOOL previousOnScreen = !ctx.awaitingDisplay || now - ctx.lastPresentTime > ctx.refreshInterval * M2M_STALE_PRESENT_REFRESHES;
			if (!previousOnScreen || now - ctx.lastPresentTime < ctx.refreshInterval * M2M_PACING_MARGIN) {
				return 2;
			}
		}

		CAMetalLayer *layer = m2m_borrow(layer_handle);
		id<CAMetalDrawable> drawable = [layer nextDrawable];
		if (!drawable) {
			return 0;
		}
		ctx.lastPresentTime = now;
		ctx.awaitingDisplay = YES;
		__weak M2MContext *weakCtx = ctx;
		[drawable addPresentedHandler:^(id<MTLDrawable> shown) {
			(void)shown;
			weakCtx.awaitingDisplay = NO;
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
