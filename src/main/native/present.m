#import <AppKit/AppKit.h>

#include "m2m_context.h"

// Slots the newest finished frame is copied into when vsync is off: usually one being written, one holding the
// latest finished frame and one being shown.
static const int32_t M2M_LATEST_SLOTS = 3;
static const int32_t M2M_NO_SLOT = -1;

static void m2m_record_presented(M2MContext *ctx, id<CAMetalDrawable> drawable) {
	__weak M2MContext *weakCtx = ctx;
	[drawable addPresentedHandler:^(id<MTLDrawable> shown) {
		M2MContext *strongCtx = weakCtx;
		// presentedTime is 0 for a drawable the compositor never showed; recorded too, as dropped frames.
		CFTimeInterval presented = shown.presentedTime;
		@synchronized(strongCtx.presentedTimes) {
			if (strongCtx.presentedTimes.count < 4096) {
				[strongCtx.presentedTimes addObject:@(presented)];
			}
		}
	}];
}

static id<MTLTexture> m2m_matching_texture(M2MContext *ctx, id slot, id<MTLTexture> source) {
	id<MTLTexture> current = slot == [NSNull null] ? nil : slot;
	if (current && current.width == source.width && current.height == source.height &&
	    current.pixelFormat == source.pixelFormat) {
		return current;
	}
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:source.pixelFormat
	                                                                                    width:source.width
	                                                                                   height:source.height
	                                                                                mipmapped:NO];
	descriptor.storageMode = MTLStorageModePrivate;
	descriptor.usage = MTLTextureUsageShaderRead;
	return [ctx.device newTextureWithDescriptor:descriptor];
}

static void m2m_draw_to_drawable(M2MContext *ctx, id<MTLCommandBuffer> commandBuffer, id<MTLTexture> source,
                                 id<CAMetalDrawable> drawable) {
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = drawable.texture;
	pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:pass];
	encoder.label = @"mine2metal present";
	[encoder setRenderPipelineState:ctx.presentPipeline];
	[encoder setFragmentTexture:source atIndex:0];
	[encoder setFragmentSamplerState:ctx.presentSampler atIndex:0];
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[encoder endEncoding];
}

// With vsync off the game renders as fast as it can while this thread shows, once per refresh, the newest frame
// that has finished. nextDrawable on a display-synced layer blocks until the compositor frees a drawable, which
// paces the loop to the display without ever holding up the render thread.
static void m2m_present_loop(M2MContext *ctx, CAMetalLayer *layer, dispatch_semaphore_t stopped) {
	uint64_t shownFrame = 0;
	while (YES) {
		@autoreleasepool {
			[ctx.latestLock lock];
			while (ctx.presentThreadRunning && ctx.latestFrame <= shownFrame) {
				[ctx.latestLock waitUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
			}
			BOOL running = ctx.presentThreadRunning;
			[ctx.latestLock unlock];
			if (!running) {
				break;
			}

			id<CAMetalDrawable> drawable = [layer nextDrawable];
			if (!drawable) {
				continue;
			}

			// Taken after the wait for a drawable, so the frame shown is the newest one at that moment.
			[ctx.latestLock lock];
			int32_t slot = ctx.latestSlot;
			uint64_t frame = ctx.latestFrame;
			id<MTLTexture> source = ctx.latestSlots[(NSUInteger)slot];
			ctx.presentingSlots |= 1u << slot;
			[ctx.latestLock unlock];

			id<MTLCommandBuffer> commandBuffer = [ctx.presentQueue commandBuffer];
			m2m_draw_to_drawable(ctx, commandBuffer, source, drawable);
			__weak M2MContext *weakCtx = ctx;
			[commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> finished) {
				(void)finished;
				M2MContext *strongCtx = weakCtx;
				[strongCtx.latestLock lock];
				strongCtx.presentingSlots &= ~(1u << slot);
				[strongCtx.latestLock broadcast];
				[strongCtx.latestLock unlock];
			}];
			m2m_record_presented(ctx, drawable);
			[commandBuffer presentDrawable:drawable];
			[commandBuffer commit];
			shownFrame = frame;
		}
	}
	dispatch_semaphore_signal(stopped);
}

static void m2m_stop_present_thread(M2MContext *ctx) {
	[ctx.latestLock lock];
	BOOL wasRunning = ctx.presentThreadRunning;
	ctx.presentThreadRunning = NO;
	[ctx.latestLock broadcast];
	[ctx.latestLock unlock];
	if (wasRunning) {
		// nextDrawable gives up after a second, so the loop is out well within this.
		dispatch_semaphore_wait(ctx.presentThreadStopped, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC));
	}
}

static void m2m_start_present_thread(M2MContext *ctx, CAMetalLayer *layer) {
	ctx.presentThreadRunning = YES;
	ctx.presentThreadStopped = dispatch_semaphore_create(0);
	dispatch_semaphore_t stopped = ctx.presentThreadStopped;
	NSThread *thread = [[NSThread alloc] initWithBlock:^{
		m2m_present_loop(ctx, layer, stopped);
	}];
	thread.name = @"mine2metal present";
	thread.qualityOfService = NSQualityOfServiceUserInteractive;
	[thread start];
}

void m2m_layer_configure(int64_t ctx, int64_t layer_handle, int32_t width, int32_t height, int32_t vsync) {
	@autoreleasepool {
		M2MContext *context = m2m_ctx(ctx);
		CAMetalLayer *layer = m2m_borrow(layer_handle);
		m2m_stop_present_thread(context);

		layer.device = context.device;
		layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
		layer.framebufferOnly = YES;
		layer.opaque = YES;
		layer.maximumDrawableCount = 3;
		// Always display-synced: without vsync the game still renders uncapped, but frames reach the screen at
		// refresh boundaries through the present thread instead of tearing.
		layer.displaySyncEnabled = YES;
		layer.drawableSize = CGSizeMake(width, height);
		context.layerVsync = vsync != 0;

		if (!context.layerVsync) {
			m2m_start_present_thread(context, layer);
		}
	}
}

void m2m_layer_set_hud(int64_t layer_handle, int32_t enabled) {
	@autoreleasepool {
		CAMetalLayer *layer = m2m_borrow(layer_handle);
		// The same overlay MTL_HUD_ENABLED=1 shows: frame rate, frame time graph, GPU time and memory.
		layer.developerHUDProperties = enabled ? @{@"mode" : @"default", @"logging" : @"default"} : nil;
	}
}

void m2m_layer_detach(int64_t ctx) {
	m2m_stop_present_thread(m2m_ctx(ctx));
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

// Vsync off: copy the frame into a free slot and publish it once the GPU has finished it.
static int32_t m2m_publish_latest(M2MContext *ctx, id<MTLTexture> source) {
	[ctx.latestLock lock];
	int32_t slot = M2M_NO_SLOT;
	// While the previous copy is in flight this frame is skipped; a newer one follows right after.
	for (int32_t i = 0; ctx.writingSlot == M2M_NO_SLOT && i < M2M_LATEST_SLOTS; i++) {
		if (i != ctx.latestSlot && (ctx.presentingSlots & (1u << i)) == 0) {
			slot = i;
			break;
		}
	}
	if (slot == M2M_NO_SLOT) {
		[ctx.latestLock unlock];
		return 2;
	}
	id<MTLTexture> target = m2m_matching_texture(ctx, ctx.latestSlots[(NSUInteger)slot], source);
	ctx.latestSlots[(NSUInteger)slot] = target;
	ctx.writingSlot = slot;
	uint64_t frame = ++ctx.frameCounter;
	[ctx.latestLock unlock];

	m2m_end_encoding(ctx);
	id<MTLCommandBuffer> commandBuffer = m2m_command_buffer(ctx);
	[m2m_blit_encoder(ctx) copyFromTexture:source toTexture:target];
	m2m_end_encoding(ctx);
	__weak M2MContext *weakCtx = ctx;
	[commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> finished) {
		(void)finished;
		M2MContext *strongCtx = weakCtx;
		[strongCtx.latestLock lock];
		strongCtx.writingSlot = M2M_NO_SLOT;
		if (frame > strongCtx.latestFrame) {
			strongCtx.latestSlot = slot;
			strongCtx.latestFrame = frame;
		}
		[strongCtx.latestLock broadcast];
		[strongCtx.latestLock unlock];
	}];
	return 1;
}

// Vsync on: present every frame from the render thread; nextDrawable blocking is what caps the frame rate.
static int32_t m2m_present_synced(M2MContext *ctx, CAMetalLayer *layer, id<MTLTexture> source) {
	id<CAMetalDrawable> drawable = [layer nextDrawable];
	if (!drawable) {
		return 0;
	}
	m2m_record_presented(ctx, drawable);

	// The frame is copied out on the render queue and drawn to the drawable on a separate present queue. A
	// command buffer that renders to a drawable waits on the GPU until the compositor releases it, often until
	// the next refresh; on the render queue that wait would hold up every later frame while the GPU idles.
	int64_t present = ctx.presentCount + 1;
	NSUInteger slot = (NSUInteger)(present % 2);
	id<MTLTexture> staging = m2m_matching_texture(ctx, ctx.presentStaging[slot], source);
	ctx.presentStaging[slot] = staging;

	m2m_end_encoding(ctx);
	id<MTLCommandBuffer> renderCommands = m2m_command_buffer(ctx);
	if (present > 2) {
		// This slot was last read by present - 2.
		[renderCommands encodeWaitForEvent:ctx.presentDone value:(uint64_t)(present - 2)];
	}
	[m2m_blit_encoder(ctx) copyFromTexture:source toTexture:staging];
	m2m_end_encoding(ctx);
	[renderCommands encodeSignalEvent:ctx.frameReady value:(uint64_t)present];

	id<MTLCommandBuffer> presentCommands = [ctx.presentQueue commandBuffer];
	[presentCommands encodeWaitForEvent:ctx.frameReady value:(uint64_t)present];
	m2m_draw_to_drawable(ctx, presentCommands, staging, drawable);
	[presentCommands encodeSignalEvent:ctx.presentDone value:(uint64_t)present];
	[presentCommands presentDrawable:drawable];
	// Safe to commit before the render queue's buffer: the GPU holds it at the frameReady wait.
	[presentCommands commit];

	ctx.presentCount = present;
	return 1;
}

int32_t m2m_present(int64_t handle, int64_t layer_handle, int64_t texture) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> source = m2m_borrow(texture);
		if (!ctx.layerVsync) {
			return m2m_publish_latest(ctx, source);
		}
		return m2m_present_synced(ctx, m2m_borrow(layer_handle), source);
	}
}
