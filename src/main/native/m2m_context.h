#ifndef M2M_CONTEXT_H
#define M2M_CONTEXT_H

#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>

#include "mine2metal.h"

@interface M2MContext : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLRenderPipelineState> presentPipeline;
@property(nonatomic, strong) id<MTLSamplerState> presentSampler;

// Recording state; at most one encoder is open at a time.
@property(nonatomic, strong) id<MTLCommandBuffer> commandBuffer;
@property(nonatomic, strong) id<MTLBlitCommandEncoder> blitEncoder;
@property(nonatomic, strong) id<MTLRenderCommandEncoder> renderEncoder;
@property(nonatomic, strong) MTLRenderPassDescriptor *pendingPass;
// A pass that renderpearl closed but whose encoder is kept open, so a following pass on the same
// attachments that clears nothing can continue in it (see m2m_pass_open).
@property(nonatomic) BOOL renderEncoderReusable;
@property(nonatomic, strong) NSArray *renderEncoderAttachments;
@property(nonatomic) int64_t mergedPassCount;
// Attachment size of the open render pass, for clamping scissor rects.
@property(nonatomic) NSUInteger passWidth;
@property(nonatomic) NSUInteger passHeight;

// Presentation (present.m). With vsync on, frames go through two staging textures to a separate present queue.
@property(nonatomic) BOOL layerVsync;
@property(nonatomic, strong) id<MTLCommandQueue> presentQueue;
@property(nonatomic, strong) id<MTLEvent> frameReady;
@property(nonatomic, strong) id<MTLEvent> presentDone;
@property(nonatomic, strong) NSMutableArray *presentStaging;
@property(nonatomic) int64_t presentCount;
// With vsync off, a present thread shows the newest finished frame from these slots once per refresh. All of
// the following are guarded by latestLock.
@property(nonatomic, strong) NSCondition *latestLock;
@property(nonatomic, strong) NSMutableArray *latestSlots;
@property(nonatomic) int32_t latestSlot;
// Bit i is set while a present command buffer still reads slot i.
@property(nonatomic) uint32_t presentingSlots;
@property(nonatomic) int32_t writingSlot;
@property(nonatomic) uint64_t latestFrame;
@property(nonatomic) uint64_t frameCounter;
@property(nonatomic) BOOL presentThreadRunning;
@property(nonatomic, strong) dispatch_semaphore_t presentThreadStopped;
// When presented drawables actually reached the screen (CACurrentMediaTime seconds, 0 if dropped), for the stats.
@property(nonatomic, strong) NSMutableArray<NSNumber *> *presentedTimes;
// Partial clears (clear.m): pipelines by attachment formats, compiled on first use.
@property(nonatomic, strong) id<MTLLibrary> clearLibrary;
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *clearPipelines;
@property(nonatomic, strong) id<MTLDepthStencilState> clearDepthState;
// MetalFX spatial scaler state (metalfx.m), kept untyped here so other files need not import MetalFX.
@property(nonatomic, strong) id spatialUpscaler;

// Committed command buffers not yet known to be finished, oldest first.
@property(nonatomic, strong) NSMutableArray<id<MTLCommandBuffer>> *inFlight;
@property(nonatomic) int64_t firstInFlightIndex;
@property(nonatomic) int64_t submitted;
// Written from Metal's completion thread.
@property(atomic) int64_t completed;
@property(atomic) double gpuSeconds;
@property(nonatomic) BOOL debug;
// Failed command buffers, filled from the completion thread under @synchronized.
@property(nonatomic, strong) NSMutableArray<NSString *> *errors;
@property(atomic) int64_t errorCount;
@property(atomic) BOOL deviceLost;
// Encoders created so far, for the frame stats.
@property(nonatomic) int64_t renderEncoderCount;
@property(nonatomic) int64_t blitEncoderCount;
// GPU seconds per command buffer label, filled from the completion thread under @synchronized.
@property(nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *gpuSecondsByLabel;
@end

static inline int64_t m2m_retain(id object) {
	return (int64_t)(intptr_t)CFBridgingRetain(object);
}

static inline id m2m_borrow(int64_t handle) {
	return (__bridge id)(void *)(intptr_t)handle;
}

static inline M2MContext *m2m_ctx(int64_t handle) {
	return (M2MContext *)m2m_borrow(handle);
}

// Shared by every encoding entry point.
id<MTLCommandBuffer> m2m_command_buffer(M2MContext *ctx);
void m2m_end_encoding(M2MContext *ctx);
// Logs a failed command buffer's error and notes device loss.
void m2m_record_error(M2MContext *ctx, id<MTLCommandBuffer> failed);
id<MTLBlitCommandEncoder> m2m_blit_encoder(M2MContext *ctx);
// Copies message into a caller-provided buffer as NUL-terminated UTF-8, truncating to capacity.
void m2m_copy_string(char *out, int64_t capacity, NSString *message);

#endif
