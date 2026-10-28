#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalDisplayLink.h>

#include "m2m_context.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// Every frame goes the same way. The render thread copies it into one of these slots (usually one being written,
// one holding the latest finished frame and one being shown) and publishes it once the GPU has finished it; a
// present thread, driven by a CAMetalDisplayLink, shows the newest published frame on each refresh.
//
// What differs is how far the render thread may run ahead. With vsync off it never waits: a frame whose slot is not
// free yet is skipped, so the game renders uncapped while the screen still gets a new frame every refresh, without
// tearing. With vsync on, and with frame interpolation, it waits until the present thread has taken the previous
// frame, so every frame is shown and the game runs at the refresh rate (half of it with interpolation, each frame
// then taking two refreshes).
static const int32_t M2M_LATEST_SLOTS = (int32_t)M2M_PRESENT_SLOTS;
// The render thread waits at most this long for the present thread, so a stalled display (a hidden window) cannot
// hold the game.
static const NSTimeInterval M2M_TAKE_TIMEOUT = 0.25;
static const int32_t M2M_NO_SLOT = -1;

static void m2m_record_presented(M2MContext *ctx, id<CAMetalDrawable> drawable) {
	__weak M2MContext *weakCtx = ctx;
	[drawable addPresentedHandler:^(id<MTLDrawable> shown) {
		M2MContext *strongCtx = weakCtx;
		// presentedTime is 0 for a drawable the compositor never showed; recorded too, as dropped frames.
		CFTimeInterval presented = shown.presentedTime;
		@synchronized(strongCtx.presentedTimes) {
			if (presented > 0) {
				strongCtx.shownFrames++;
			}
			if (strongCtx.presentedTimes.count < 4096) {
				[strongCtx.presentedTimes addObject:@(presented)];
			}
		}
	}];
}

// A texture the size of source to stage the frame in: a copy of it, or with EDR its extended linear composition.
static id<MTLTexture> m2m_matching_texture(M2MContext *ctx, id slot, id<MTLTexture> source, MTLTextureUsage usage) {
	id<MTLTexture> current = slot == [NSNull null] ? nil : slot;
	MTLPixelFormat format = ctx.hdr ? MTLPixelFormatRGBA16Float : source.pixelFormat;
	usage |= MTLTextureUsageShaderRead | (ctx.hdr ? MTLTextureUsageRenderTarget : 0);
	if (current && current.width == source.width && current.height == source.height && current.pixelFormat == format &&
	    (current.usage & usage) == usage) {
		return current;
	}
	m2m_retire(ctx, current);
	return m2m_new_private_texture(ctx, format, source.width, source.height, usage);
}

static void m2m_stage_frame(M2MContext *ctx, id<MTLTexture> source, id<MTLTexture> target) {
	if (target.pixelFormat == MTLPixelFormatRGBA16Float && source.pixelFormat != MTLPixelFormatRGBA16Float) {
		m2m_hdr_compose(ctx, source, target);
		return;
	}
	[m2m_copier(ctx) copyFromTexture:source toTexture:target];
	m2m_end_encoding(ctx);
}

// Draws source onto drawable and presents it, on the present queue. The draw is on a queue of its own because a
// command buffer that renders to a drawable can wait on the GPU until the compositor releases it; on the render
// queue that wait would hold up every later frame.
static void m2m_present_on_queue(M2MContext *ctx, id<MTLTexture> source, id<CAMetalDrawable> drawable, M2MCompletion completion) {
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = drawable.texture;
	pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	m2m_record_presented(ctx, drawable);
	id<MTLRenderPipelineState> pipeline =
	    drawable.texture.pixelFormat == MTLPixelFormatRGBA16Float ? ctx.presentPipelineEDR : ctx.presentPipeline;

	if (ctx.metal4) {
		id<MTL4CommandBuffer> commandBuffer = m2m_present_command_buffer4(ctx);
		MTL4RenderPassDescriptor *pass4 = [MTL4RenderPassDescriptor new];
		pass4.colorAttachments[0] = pass.colorAttachments[0];
		id<MTL4RenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:pass4];
		encoder.label = @"mine2metal present";
		[encoder barrierAfterQueueStages:MTLStageAll beforeStages:MTLStageFragment visibilityOptions:MTL4VisibilityOptionDevice];
		[encoder setRenderPipelineState:pipeline];
		[ctx.presentArgumentTable setTexture:source.gpuResourceID atIndex:0];
		[ctx.presentArgumentTable setSamplerState:ctx.presentSampler.gpuResourceID atIndex:0];
		[encoder setArgumentTable:ctx.presentArgumentTable atStages:MTLRenderStageFragment];
		[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[encoder endEncoding];
		[ctx.presentQueue4 waitForDrawable:drawable];
		m2m_commit_present4(ctx, commandBuffer, completion);
		[ctx.presentQueue4 signalDrawable:drawable];
		[drawable present];
		return;
	}

	id<MTLCommandBuffer> commandBuffer = [ctx.presentQueue commandBuffer];
	id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:pass];
	encoder.label = @"mine2metal present";
	[encoder setRenderPipelineState:pipeline];
	[encoder setFragmentTexture:source atIndex:0];
	[encoder setFragmentSamplerState:ctx.presentSampler atIndex:0];
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[encoder endEncoding];
	if (completion) {
		[commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> finished) {
			completion(finished.GPUStartTime, finished.GPUEndTime, finished.error);
		}];
	}
	[commandBuffer presentDrawable:drawable];
	[commandBuffer commit];
}

// Longest a generated frame is held before its real one, in refreshes.
static const NSInteger M2M_MAX_HOLD_REFRESHES = 4;
// A gap between finished frames longer than this is a hitch, not a frame rate to pace generated frames for.
static const CFTimeInterval M2M_PACING_HITCH_SECONDS = 0.25;
// macOS stops the display link of a window that is fully covered; after this long without a callback, frames are
// dropped as they finish so the render thread does not wait for a display that is not showing them.
static const CFTimeInterval M2M_DISPLAY_IDLE_SECONDS = 0.1;

// Called by a CAMetalDisplayLink once per refresh, with a drawable, on the present thread: shows the newest finished
// frame. A frame with a generated one shows that first and holds it for about half the time between finished frames
// (at least one refresh), then the real one, so both get even time on screen also when the game renders well below
// half the refresh rate. The hold comes from when frames finish on the GPU, which waiting for the display does not
// stretch.
@interface M2MPresenter : NSObject <CAMetalDisplayLinkDelegate>
@property(nonatomic, weak) M2MContext *ctx;
@property(nonatomic) uint64_t shownFrame;
// The real frame waiting behind its generated one, its slot, and the refreshes it still waits.
@property(nonatomic, strong) id<MTLTexture> waitingSource;
@property(nonatomic) int32_t waitingSlot;
@property(nonatomic) NSInteger holdRefreshes;
// Smoothed: the refresh interval, and the interval between finished frames in refreshes.
@property(nonatomic) CFTimeInterval refreshInterval;
@property(nonatomic) double frameRefreshes;
@property(nonatomic) CFTimeInterval lastTarget;
@property(nonatomic) CFTimeInterval lastReadyTime;
@property(nonatomic) CFTimeInterval lastCallback;
@end

@implementation M2MPresenter

- (void)releaseSlot:(int32_t)slot {
	M2MContext *ctx = self.ctx;
	[ctx.latestLock lock];
	ctx.presentingSlots &= ~(1u << slot);
	[ctx.latestLock broadcast];
	[ctx.latestLock unlock];
}

- (void)show:(id<MTLTexture>)source slot:(int32_t)slot drawable:(id<CAMetalDrawable>)drawable {
	__weak M2MPresenter *weakSelf = self;
	m2m_present_on_queue(self.ctx, source, drawable, ^(CFTimeInterval gpuStart, CFTimeInterval gpuEnd, NSError *failure) {
		(void)gpuStart;
		(void)gpuEnd;
		(void)failure;
		[weakSelf releaseSlot:slot];
	});
}

// While the display link is silent: frames count as taken (and are not shown) as soon as they finish.
- (void)dropWhileIdle {
	M2MContext *ctx = self.ctx;
	if (CACurrentMediaTime() - self.lastCallback < M2M_DISPLAY_IDLE_SECONDS) {
		return;
	}
	[ctx.latestLock lock];
	ctx.presentIdle = YES;
	if (ctx.latestFrame > self.shownFrame) {
		self.shownFrame = ctx.latestFrame;
		ctx.takenFrame = ctx.latestFrame;
	}
	if (self.waitingSource) {
		ctx.presentingSlots &= ~(1u << self.waitingSlot);
		self.waitingSource = nil;
	}
	[ctx.latestLock broadcast];
	[ctx.latestLock unlock];
}

- (void)measureRefresh:(CFTimeInterval)target {
	CFTimeInterval interval = target - self.lastTarget;
	self.lastTarget = target;
	// Consecutive callbacks are a refresh apart; anything else (a pause, a hidden window) says nothing.
	if (interval < 1.0 / 500.0 || interval > 1.0 / 20.0) {
		return;
	}
	self.refreshInterval = self.refreshInterval > 0.0 ? MIN(interval, self.refreshInterval * 0.95 + interval * 0.05) : interval;
}

// Refreshes to hold a generated frame: half the time between finished frames, rounded.
- (NSInteger)holdFor:(CFTimeInterval)readyTime {
	CFTimeInterval gap = readyTime - self.lastReadyTime;
	self.lastReadyTime = readyTime;
	double refresh = self.refreshInterval > 0.0 ? self.refreshInterval : 1.0 / 60.0;
	if (gap <= 0.0 || gap > M2M_PACING_HITCH_SECONDS) {
		self.frameRefreshes = 2.0;
	} else {
		self.frameRefreshes = self.frameRefreshes * 0.8 + (gap / refresh) * 0.2;
	}
	return MAX(1, MIN(M2M_MAX_HOLD_REFRESHES, (NSInteger)lround(self.frameRefreshes / 2.0)));
}

- (void)metalDisplayLink:(CAMetalDisplayLink *)link needsUpdate:(CAMetalDisplayLinkUpdate *)update {
	(void)link;
	M2MContext *ctx = self.ctx;
	if (!ctx) {
		return;
	}
	self.lastCallback = CACurrentMediaTime();
	if (ctx.presentIdle) {
		[ctx.latestLock lock];
		ctx.presentIdle = NO;
		[ctx.latestLock unlock];
	}
	[self measureRefresh:update.targetPresentationTimestamp];
	if (m2m_tracing()) {
		static int calls;
		static CFTimeInterval since;
		calls++;
		CFTimeInterval now = CACurrentMediaTime();
		if (now - since > 1.0) {
			NSLog(@"mine2metal trace: display link %d calls/s, latest frame %llu, shown %llu", calls, ctx.latestFrame, self.shownFrame);
			calls = 0;
			since = now;
		}
	}
	if (self.waitingSource) {
		if (--self.holdRefreshes > 0) {
			// The generated frame stays on screen.
			return;
		}
		[self show:self.waitingSource slot:self.waitingSlot drawable:update.drawable];
		self.waitingSource = nil;
		return;
	}

	[ctx.latestLock lock];
	if (ctx.latestFrame <= self.shownFrame) {
		[ctx.latestLock unlock];
		return;
	}
	int32_t slot = ctx.latestSlot;
	uint64_t frame = ctx.latestFrame;
	CFTimeInterval readyTime = ctx.latestReadyTime;
	id<MTLTexture> source = ctx.latestSlots[(NSUInteger)slot];
	id<MTLTexture> generated = (ctx.generatedSlots & (1u << slot)) ? ctx.latestGenerated[(NSUInteger)slot] : nil;
	ctx.presentingSlots |= 1u << slot;
	ctx.takenFrame = frame;
	[ctx.latestLock broadcast];
	[ctx.latestLock unlock];
	self.shownFrame = frame;

	if (!generated) {
		self.lastReadyTime = readyTime;
		[self show:source slot:slot drawable:update.drawable];
		return;
	}
	self.holdRefreshes = [self holdFor:readyTime];
	m2m_present_on_queue(ctx, generated, update.drawable, nil);
	self.waitingSource = source;
	self.waitingSlot = slot;
}

@end

static void m2m_present_loop(M2MContext *ctx, CAMetalLayer *layer, dispatch_semaphore_t stopped) {
	@autoreleasepool {
		if (m2m_tracing()) {
			NSLog(@"mine2metal trace: present thread starts on %@ (window %@)", layer, layer.delegate);
		}
		M2MPresenter *presenter = [M2MPresenter new];
		presenter.ctx = ctx;
		CAMetalDisplayLink *link = [[CAMetalDisplayLink alloc] initWithMetalLayer:layer];
		link.delegate = presenter;
		// The present pass is short, so the drawable can be handed out a single frame ahead.
		link.preferredFrameLatency = 1.0f;
		[link addToRunLoop:[NSRunLoop currentRunLoop] forMode:NSDefaultRunLoopMode];
		while (YES) {
			[ctx.latestLock lock];
			BOOL running = ctx.presentThreadRunning;
			[ctx.latestLock unlock];
			if (!running) {
				break;
			}
			@autoreleasepool {
				[[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
				[presenter dropWhileIdle];
			}
		}
		[link invalidate];
		if (presenter.waitingSource) {
			// A real frame still behind its generated one is not shown.
			[presenter releaseSlot:presenter.waitingSlot];
		}
	}
	dispatch_semaphore_signal(stopped);
}

// With EDR the drawables are extended linear sRGB half floats, 1.0 being SDR white; without, plain 8-bit sRGB.
static void m2m_layer_apply_hdr(M2MContext *ctx, CAMetalLayer *layer) {
	if (ctx.hdr) {
		CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearSRGB);
		layer.pixelFormat = MTLPixelFormatRGBA16Float;
		layer.colorspace = space;
		CGColorSpaceRelease(space);
		layer.wantsExtendedDynamicRangeContent = YES;
	} else {
		layer.wantsExtendedDynamicRangeContent = NO;
		layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
		layer.colorspace = NULL;
	}
}

static void m2m_stop_present_thread(M2MContext *ctx) {
	[ctx.latestLock lock];
	BOOL wasRunning = ctx.presentThreadRunning;
	ctx.presentThreadRunning = NO;
	[ctx.latestLock broadcast];
	[ctx.latestLock unlock];
	if (wasRunning) {
		// The thread's run loop wakes at least every 50 ms, so it is out well within this.
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
		m2m_layer_apply_hdr(context, layer);
		layer.framebufferOnly = YES;
		layer.opaque = YES;
		layer.maximumDrawableCount = 3;
		// Always display-synced: without vsync the game still renders uncapped, but frames reach the screen at
		// refresh boundaries instead of tearing.
		layer.displaySyncEnabled = YES;
		layer.drawableSize = CGSizeMake(width, height);
		context.layerVsync = vsync != 0;
		if (context.metal4 && context.residentLayer != layer) {
			// The layer's drawables are resident through its own set.
			[context.presentQueue4 addResidencySet:layer.residencySet];
			context.residentLayer = layer;
		}

		m2m_start_present_thread(context, layer);
	}
}

void m2m_layer_set_hdr(int64_t ctx, int64_t layer_handle, int32_t enabled) {
	@autoreleasepool {
		M2MContext *context = m2m_ctx(ctx);
		CAMetalLayer *layer = m2m_borrow(layer_handle);
		if (context.hdr == (enabled != 0) && layer.wantsExtendedDynamicRangeContent == (enabled != 0)) {
			return;
		}
		// The present thread may be drawing with the old format.
		m2m_stop_present_thread(context);
		context.hdr = enabled != 0;
		m2m_layer_apply_hdr(context, layer);
		m2m_start_present_thread(context, layer);
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

int64_t m2m_shown_frame_count(int64_t handle) {
	M2MContext *ctx = m2m_ctx(handle);
	@synchronized(ctx.presentedTimes) {
		return (int64_t)ctx.shownFrames;
	}
}

// Copies the frame into a free slot, with its generated frame, and publishes it once the GPU has finished it.
static int32_t m2m_publish_latest(M2MContext *ctx, id<MTLTexture> source) {
	BOOL interpolating = m2m_frame_interpolation_pending(ctx);
	[ctx.latestLock lock];
	if (ctx.layerVsync || interpolating) {
		// One frame waits for the present thread at most. The previous copy must be done too, or this frame would be
		// skipped: with vsync on every frame is shown, and with interpolation a skipped frame breaks its history (a
		// generated frame replaced before it is shown would be GPU time wasted as well).
		NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:M2M_TAKE_TIMEOUT];
		// While the display is not showing the window, only the copy is waited for.
		while (((!ctx.presentIdle && ctx.frameCounter > ctx.takenFrame) || ctx.writingSlot != M2M_NO_SLOT) && ctx.presentThreadRunning &&
		       [ctx.latestLock waitUntilDate:deadline]) {
		}
	}
	int32_t slot = M2M_NO_SLOT;
	// With vsync off, while the previous copy is in flight this frame is skipped; a newer one follows right after.
	for (int32_t i = 0; ctx.writingSlot == M2M_NO_SLOT && i < M2M_LATEST_SLOTS; i++) {
		if (i != ctx.latestSlot && (ctx.presentingSlots & (1u << i)) == 0) {
			slot = i;
			break;
		}
	}
	if (slot == M2M_NO_SLOT) {
		if (m2m_tracing()) {
			static int skips;
			if (++skips % 500 == 1) {
				NSLog(@"mine2metal trace: frame skipped (%d): writing %d, latest %d, presenting 0x%x, counter %llu, taken %llu, idle %d",
				      skips, ctx.writingSlot, ctx.latestSlot, ctx.presentingSlots, ctx.frameCounter, ctx.takenFrame, ctx.presentIdle);
			}
		}
		[ctx.latestLock unlock];
		m2m_frame_interpolation_presented(ctx, nil);
		return 2;
	}
	// The staged frame is also the UI texture of a generated frame.
	MTLTextureUsage usage = interpolating ? m2m_frame_interpolation_ui_usage(ctx) : 0;
	id<MTLTexture> target = m2m_matching_texture(ctx, ctx.latestSlots[(NSUInteger)slot], source, usage);
	ctx.latestSlots[(NSUInteger)slot] = target;
	ctx.writingSlot = slot;
	uint64_t frame = ++ctx.frameCounter;
	[ctx.latestLock unlock];

	m2m_stage_frame(ctx, source, target);
	id<MTLTexture> generated = interpolating ? m2m_frame_interpolation_encode(ctx, target, (NSUInteger)slot) : nil;
	m2m_frame_interpolation_presented(ctx, generated);
	[ctx.latestLock lock];
	ctx.latestGenerated[(NSUInteger)slot] = generated ?: (id)[NSNull null];
	if (generated) {
		ctx.generatedSlots |= 1u << slot;
	} else {
		ctx.generatedSlots &= ~(1u << slot);
	}
	[ctx.latestLock unlock];
	__weak M2MContext *weakCtx = ctx;
	m2m_on_completed(ctx, ^(CFTimeInterval gpuStart, CFTimeInterval gpuEnd, NSError *failure) {
		(void)gpuStart;
		(void)gpuEnd;
		(void)failure;
		M2MContext *strongCtx = weakCtx;
		[strongCtx.latestLock lock];
		strongCtx.writingSlot = M2M_NO_SLOT;
		if (frame > strongCtx.latestFrame) {
			strongCtx.latestSlot = slot;
			strongCtx.latestFrame = frame;
			strongCtx.latestReadyTime = CACurrentMediaTime();
		}
		[strongCtx.latestLock broadcast];
		[strongCtx.latestLock unlock];
	});
	return 1;
}

// Hands the finished frame to the present thread: 1 when published, 2 when skipped (vsync off, the previous copy still
// in flight, or the present thread not taking frames).
int32_t m2m_present(int64_t handle, int64_t layer_handle, int64_t texture) {
	(void)layer_handle;
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		return m2m_publish_latest(ctx, m2m_borrow(texture));
	}
}
