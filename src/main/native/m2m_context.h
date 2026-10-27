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
// Attachment size of the open render pass, for clamping scissor rects.
@property(nonatomic) NSUInteger passWidth;
@property(nonatomic) NSUInteger passHeight;

// Presentation pacing for vsync-off: see m2m_present.
@property(nonatomic) BOOL layerVsync;
@property(nonatomic) CFTimeInterval refreshInterval;
@property(nonatomic) CFTimeInterval lastPresentTime;

// Committed command buffers not yet known to be finished, oldest first.
@property(nonatomic, strong) NSMutableArray<id<MTLCommandBuffer>> *inFlight;
@property(nonatomic) int64_t firstInFlightIndex;
@property(nonatomic) int64_t submitted;
// Written from Metal's completion thread.
@property(atomic) int64_t completed;
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
id<MTLBlitCommandEncoder> m2m_blit_encoder(M2MContext *ctx);
// Copies message into a caller-provided buffer as NUL-terminated UTF-8, truncating to capacity.
void m2m_copy_string(char *out, int64_t capacity, NSString *message);

#endif
