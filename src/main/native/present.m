#import <AppKit/AppKit.h>

#include "m2m_context.h"

// Presents slightly early so a frame finished just before the refresh still makes it.
static const double M2M_PACING_MARGIN = 0.9;

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
		// every drawable queued at the compositor and block nextDrawable, so only one frame per refresh goes out.
		CFTimeInterval now = CACurrentMediaTime();
		if (!ctx.layerVsync && now - ctx.lastPresentTime < ctx.refreshInterval * M2M_PACING_MARGIN) {
			return 2;
		}

		CAMetalLayer *layer = m2m_borrow(layer_handle);
		id<CAMetalDrawable> drawable = [layer nextDrawable];
		if (!drawable) {
			return 0;
		}
		ctx.lastPresentTime = now;

		m2m_end_encoding(ctx);
		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture = drawable.texture;
		pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		pass.colorAttachments[0].storeAction = MTLStoreActionStore;

		id<MTLCommandBuffer> commandBuffer = m2m_command_buffer(ctx);
		id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:pass];
		encoder.label = @"mine2metal present";
		[encoder setRenderPipelineState:ctx.presentPipeline];
		[encoder setFragmentTexture:m2m_borrow(texture) atIndex:0];
		[encoder setFragmentSamplerState:ctx.presentSampler atIndex:0];
		[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[encoder endEncoding];

		[commandBuffer presentDrawable:drawable];
		return 1;
	}
}
