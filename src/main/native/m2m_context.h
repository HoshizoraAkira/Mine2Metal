#ifndef M2M_CONTEXT_H
#define M2M_CONTEXT_H

#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>

#include "mine2metal.h"

// Metal 4 objects are only created after an @available(macOS 26.0, *) check (see m2m_context_create) and only used
// while ctx.metal4 is set, so their uses need no availability guard of their own.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

typedef void (^M2MCompletion)(CFTimeInterval gpuStart, CFTimeInterval gpuEnd, NSError *error);

// Slots the present thread shows frames from (present.m); frame interpolation keeps a generated frame per slot.
static const NSUInteger M2M_PRESENT_SLOTS = 3;

// Copy commands with the same selectors on a Metal 3 blit encoder and a Metal 4 compute encoder.
@protocol M2MCopier <NSObject>
- (void)copyFromBuffer:(id<MTLBuffer>)sourceBuffer
         sourceOffset:(NSUInteger)sourceOffset
             toBuffer:(id<MTLBuffer>)destinationBuffer
    destinationOffset:(NSUInteger)destinationOffset
                 size:(NSUInteger)size;
- (void)copyFromBuffer:(id<MTLBuffer>)sourceBuffer
           sourceOffset:(NSUInteger)sourceOffset
      sourceBytesPerRow:(NSUInteger)sourceBytesPerRow
    sourceBytesPerImage:(NSUInteger)sourceBytesPerImage
             sourceSize:(MTLSize)sourceSize
              toTexture:(id<MTLTexture>)destinationTexture
       destinationSlice:(NSUInteger)destinationSlice
       destinationLevel:(NSUInteger)destinationLevel
      destinationOrigin:(MTLOrigin)destinationOrigin;
- (void)copyFromTexture:(id<MTLTexture>)sourceTexture
                 sourceSlice:(NSUInteger)sourceSlice
                 sourceLevel:(NSUInteger)sourceLevel
                sourceOrigin:(MTLOrigin)sourceOrigin
                  sourceSize:(MTLSize)sourceSize
                    toBuffer:(id<MTLBuffer>)destinationBuffer
           destinationOffset:(NSUInteger)destinationOffset
      destinationBytesPerRow:(NSUInteger)destinationBytesPerRow
    destinationBytesPerImage:(NSUInteger)destinationBytesPerImage;
- (void)copyFromTexture:(id<MTLTexture>)sourceTexture
           sourceSlice:(NSUInteger)sourceSlice
           sourceLevel:(NSUInteger)sourceLevel
          sourceOrigin:(MTLOrigin)sourceOrigin
            sourceSize:(MTLSize)sourceSize
             toTexture:(id<MTLTexture>)destinationTexture
      destinationSlice:(NSUInteger)destinationSlice
      destinationLevel:(NSUInteger)destinationLevel
     destinationOrigin:(MTLOrigin)destinationOrigin;
- (void)copyFromTexture:(id<MTLTexture>)sourceTexture toTexture:(id<MTLTexture>)destinationTexture;
@end

// Render commands with the same selectors on Metal 3 and Metal 4 render encoders. Bindings and indexed or indirect
// draws differ and go through the m2m_bind_* functions and render.m.
@protocol M2MRenderCommands <NSObject>
- (void)setRenderPipelineState:(id<MTLRenderPipelineState>)pipelineState;
- (void)setDepthStencilState:(id<MTLDepthStencilState>)depthStencilState;
- (void)setCullMode:(MTLCullMode)cullMode;
- (void)setTriangleFillMode:(MTLTriangleFillMode)fillMode;
- (void)setDepthBias:(float)depthBias slopeScale:(float)slopeScale clamp:(float)clamp;
- (void)setScissorRect:(MTLScissorRect)rect;
- (void)setFrontFacingWinding:(MTLWinding)frontFacingWinding;
- (void)drawPrimitives:(MTLPrimitiveType)primitiveType vertexStart:(NSUInteger)vertexStart vertexCount:(NSUInteger)vertexCount;
- (void)drawPrimitives:(MTLPrimitiveType)primitiveType
           vertexStart:(NSUInteger)vertexStart
           vertexCount:(NSUInteger)vertexCount
         instanceCount:(NSUInteger)instanceCount
          baseInstance:(NSUInteger)baseInstance;
@end

@interface M2MContext : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLRenderPipelineState> presentPipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> presentPipelineEDR;
@property(nonatomic, strong) id<MTLSamplerState> presentSampler;

// Metal 4 (gpu.m). Command buffers come from allocators that return to a pool when the GPU is done with them;
// every buffer and texture lives in one residency set; bindings go through argument tables, small constants
// through an upload ring; and since Metal 4 tracks no hazards, every encoder starts and ends with a full barrier.
@property(nonatomic) BOOL metal4;
@property(nonatomic, strong) id<MTL4CommandQueue> queue4;
@property(nonatomic, strong) id<MTL4CommandQueue> presentQueue4;
@property(nonatomic, strong) id<MTL4CommandBuffer> commandBuffer4;
@property(nonatomic, strong) id<MTL4CommandAllocator> allocator4;
@property(nonatomic, strong) NSMutableArray<id<MTL4CommandAllocator>> *freeAllocators;
@property(nonatomic, strong) NSMutableArray<id<MTL4CommandAllocator>> *freePresentAllocators;
@property(nonatomic, strong) id<MTL4RenderCommandEncoder> renderEncoder4;
@property(nonatomic, strong) id<MTL4ComputeCommandEncoder> transferEncoder4;
@property(nonatomic) BOOL transferEncoderUsed;
@property(nonatomic, strong) id<MTL4ArgumentTable> argumentTable;
@property(nonatomic, strong) id<MTL4ArgumentTable> presentArgumentTable;
@property(nonatomic, strong) id<MTLResidencySet> residency;
@property(nonatomic) BOOL residencyDirty;
@property(nonatomic, weak) CAMetalLayer *residentLayer;
// Signaled with each submit index once the GPU has finished it.
@property(nonatomic, strong) id<MTLSharedEvent> completion;
// For MetalFX's Metal 4 scalers.
@property(nonatomic, strong) id<MTL4Compiler> compiler;
// Run when the open command buffer completes, and queue-level waits and signals around its commit.
@property(nonatomic, strong) NSMutableArray<M2MCompletion> *pendingCompletions;
@property(nonatomic, strong) NSMutableArray *waitsBeforeCommit;
@property(nonatomic, strong) NSMutableArray *signalsAfterCommit;
// Shared buffers small constants are copied into (Metal 4 has no setBytes), reused once the GPU is past them.
@property(nonatomic, strong) NSMutableArray *uploadChunks;
// Objects replaced while the GPU may still use them, with the submit index after which they can go. Metal 4
// command buffers do not keep the resources they use alive.
@property(nonatomic, strong) NSMutableArray *retired;

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

// Presentation (present.m): a present thread shows the newest finished frame from these slots once per refresh (a
// generated frame and then the real one with interpolation), drawing it on a separate present queue. All of the
// following are guarded by latestLock.
@property(nonatomic) BOOL layerVsync;
@property(nonatomic, strong) id<MTLCommandQueue> presentQueue;
@property(nonatomic, strong) NSCondition *latestLock;
@property(nonatomic, strong) NSMutableArray *latestSlots;
@property(nonatomic, strong) NSMutableArray *latestGenerated;
// Bit i is set while slot i has a generated frame to show before its real one.
@property(nonatomic) uint32_t generatedSlots;
// The newest frame the present thread has taken; with vsync on or interpolation the render thread waits for it to
// catch up.
@property(nonatomic) uint64_t takenFrame;
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
// MetalFX temporal scaler state (temporal.m); the last one used, to reset history when it is rebuilt.
@property(nonatomic, strong) id temporalUpscaler;
@property(nonatomic, weak) id lastTemporalUpscaler;
@property(nonatomic, strong) id<MTLRenderPipelineState> motionPipeline;
@property(nonatomic, strong) id<MTLDepthStencilState> motionDepthState;
// EDR output (hdr.m): the layer takes extended linear RGBA16Float, and the world's highlights go above SDR white.
// hdrWorld is this frame's world before the hand and the GUI, compared with the finished frame to tell them apart.
@property(nonatomic) BOOL hdr;
@property(nonatomic) float hdrPeak;
@property(nonatomic) BOOL hdrIgnoreDisplay;
@property(nonatomic) float hdrHeadroom;
@property(nonatomic) int64_t hdrHeadroomAge;
@property(nonatomic, strong) id<MTLTexture> hdrWorld;
@property(nonatomic) BOOL hdrWorldCaptured;
@property(nonatomic, weak) id<MTLTexture> hdrLastFrame;
@property(nonatomic, strong) id<MTLRenderPipelineState> hdrComposePipeline;
@property(nonatomic, strong) id<MTLRenderPipelineState> hdrExpandPipeline;
// MetalFX frame interpolation state (interpolation.m).
@property(nonatomic, strong) id frameInterpolator;
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

// Shared by every encoding entry point (gpu.m).

// The open command buffer, begun on first use: Metal 3 and Metal 4 respectively.
id<MTLCommandBuffer> m2m_command_buffer(M2MContext *ctx);
id<MTL4CommandBuffer> m2m_command_buffer4(M2MContext *ctx);
void m2m_end_encoding(M2MContext *ctx);
// An encoder for copies, continuing the open one if it is one.
id<M2MCopier> m2m_copier(M2MContext *ctx);
// Ends what is open and starts a render encoder for pass, which becomes ctx's open render encoder.
id<M2MRenderCommands> m2m_open_render_encoder(M2MContext *ctx, MTLRenderPassDescriptor *pass, NSString *label);
id<M2MRenderCommands> m2m_render_encoder(M2MContext *ctx);
// Bindings on the open render encoder. Metal 4 shares one argument table between the vertex and fragment stages,
// so a slot holds one resource for both.
void m2m_bind_buffer(M2MContext *ctx, NSUInteger index, id<MTLBuffer> buffer, NSUInteger offset, BOOL vertex,
                     BOOL fragment);
void m2m_bind_bytes(M2MContext *ctx, NSUInteger index, const void *data, NSUInteger length, BOOL vertex, BOOL fragment);
void m2m_bind_texture(M2MContext *ctx, NSUInteger index, id<MTLTexture> texture, BOOL vertex, BOOL fragment);
void m2m_bind_sampler(M2MContext *ctx, NSUInteger index, id<MTLSamplerState> sampler, BOOL vertex, BOOL fragment);
// Runs completion once the GPU has finished everything encoded so far.
void m2m_on_completed(M2MContext *ctx, M2MCompletion completion);
// GPU work committed after this point waits for event to reach value; event reaches value once the work encoded so
// far is done. Metal 3 encodes these where they are called; Metal 4 applies them to the whole open command buffer.
void m2m_wait_for_event(M2MContext *ctx, id<MTLEvent> event, uint64_t value);
void m2m_signal_event(M2MContext *ctx, id<MTLEvent> event, uint64_t value);
// Commits the open command buffer, or an empty one; completion runs when it finishes.
void m2m_commit_open(M2MContext *ctx, NSString *label, M2MCompletion completion);
// True when MINE2METAL_TRACE is set: Metal 4 commits, queue waits and signals, and completions are logged.
BOOL m2m_tracing(void);
// Logs a failed command buffer's error and notes device loss.
void m2m_record_error(M2MContext *ctx, NSError *error);
// Metal 4 resources must be in the residency set while the GPU uses them.
void m2m_track(M2MContext *ctx, id<MTLAllocation> allocation);
void m2m_untrack(M2MContext *ctx, id<MTLAllocation> allocation);
// Keeps object (and its residency) until the GPU has finished the work encoded so far, and a little after, since
// the present queue may read it too. object may be nil.
void m2m_retire(M2MContext *ctx, id object);
// A private texture for the backend's own use, tracked for residency.
id<MTLTexture> m2m_new_private_texture(M2MContext *ctx, MTLPixelFormat format, NSUInteger width, NSUInteger height,
                                       MTLTextureUsage usage);
// Present-queue command buffers for Metal 4 (present.m), from their own allocator pool.
id<MTL4CommandBuffer> m2m_present_command_buffer4(M2MContext *ctx);
void m2m_commit_present4(M2MContext *ctx, id<MTL4CommandBuffer> commandBuffer, M2MCompletion completion);
// Motion vectors (RG16Float, in pixels towards where each pixel was in the previous frame) and a Depth32Float copy
// of sourceDepth, from the matrices described at m2m_metalfx_temporal_upscale (temporal.m). NO if the shader failed.
BOOL m2m_encode_motion(M2MContext *ctx, id<MTLTexture> sourceDepth, id<MTLTexture> motion, id<MTLTexture> depth,
                       const float *matrices);
// Frame interpolation (interpolation.m), called by m2m_present. pending: this frame's world was captured, so the
// frame goes to the present thread. encode: the generated frame shown before this one (nil if there is none) for
// present slot, encoded once ui (the finished frame) has been staged in a texture with ui_usage. presented ends the
// frame either way.
BOOL m2m_frame_interpolation_pending(M2MContext *ctx);
MTLTextureUsage m2m_frame_interpolation_ui_usage(M2MContext *ctx);
id<MTLTexture> m2m_frame_interpolation_encode(M2MContext *ctx, id<MTLTexture> ui, NSUInteger slot);
void m2m_frame_interpolation_presented(M2MContext *ctx, id<MTLTexture> generated);
// EDR output (hdr.m). compose: the finished frame source into target (RGBA16Float, extended linear), with this
// frame's world highlights expanded where nothing was drawn over them. expand: a world texture alone, for frame
// interpolation's history. Both NO if the shaders failed.
BOOL m2m_hdr_compose(M2MContext *ctx, id<MTLTexture> source, id<MTLTexture> target);
BOOL m2m_hdr_expand(M2MContext *ctx, id<MTLTexture> world, id<MTLTexture> target);
// Copies message into a caller-provided buffer as NUL-terminated UTF-8, truncating to capacity.
void m2m_copy_string(char *out, int64_t capacity, NSString *message);

#pragma clang diagnostic pop

#endif
