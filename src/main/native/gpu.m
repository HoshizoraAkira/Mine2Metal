#import <objc/runtime.h>

#include "m2m_context.h"

// See m2m_context.h: Metal 4 code here runs only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// MINE2METAL_TRACE=1 logs every Metal 4 commit, queue wait and signal, and completion, for debugging GPU stalls.
BOOL m2m_tracing(void) {
	static BOOL tracing;
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		tracing = getenv("MINE2METAL_TRACE") != NULL;
	});
	return tracing;
}

// Constants copied for one draw land in these, 256-byte aligned like Metal's constant buffer offsets.
static const NSUInteger M2M_UPLOAD_CHUNK = 1 << 20;
static const NSUInteger M2M_UPLOAD_ALIGNMENT = 256;

@interface M2MUploadChunk : NSObject
@property(nonatomic, strong) id<MTLBuffer> buffer;
@property(nonatomic) NSUInteger used;
// Submit index of the last command buffer that reads it.
@property(nonatomic) int64_t lastUse;
@end

@implementation M2MUploadChunk
@end

// A queue-level wait or signal applied around the next Metal 4 commit.
@interface M2MEventValue : NSObject
@property(nonatomic, strong) id<MTLEvent> event;
@property(nonatomic) uint64_t value;
@end

@implementation M2MEventValue
@end

@interface M2MRetired : NSObject
@property(nonatomic, strong) id object;
@property(nonatomic) int64_t until;
@end

@implementation M2MRetired
@end

// Submits a retired object waits for: the one being recorded, plus the frames the present queue may lag behind.
static const int64_t M2M_RETIRE_DELAY = 3;

void m2m_record_error(M2MContext *ctx, NSError *error) {
	NSMutableString *message = [NSMutableString stringWithFormat:@"Command buffer failed: %@", error.localizedDescription];
	for (id<MTLCommandBufferEncoderInfo> info in error.userInfo[MTLCommandBufferEncoderInfoErrorKey]) {
		if (info.errorState == MTLCommandEncoderErrorStateFaulted) {
			[message appendFormat:@" (faulted in encoder \"%@\")", info.label];
		}
	}
	@synchronized(ctx.errors) {
		if (ctx.errors.count < 32) {
			[ctx.errors addObject:message];
		}
	}
	BOOL lost = [error.domain isEqualToString:MTLCommandBufferErrorDomain]
	                ? error.code == MTLCommandBufferErrorDeviceRemoved || error.code == MTLCommandBufferErrorAccessRevoked
	                : ctx.metal4 && [error.domain isEqualToString:MTL4CommandQueueErrorDomain] &&
	                      error.code == MTL4CommandQueueErrorAccessRevoked;
	if (lost) {
		ctx.deviceLost = YES;
	}
	ctx.errorCount += 1;
}

// Residency

// Resources are created and released on worker threads too, and a residency set takes one writer at a time.
void m2m_track(M2MContext *ctx, id<MTLAllocation> allocation) {
	if (ctx.metal4 && allocation) {
		@synchronized(ctx.residency) {
			[ctx.residency addAllocation:allocation];
			ctx.residencyDirty = YES;
		}
	}
}

void m2m_untrack(M2MContext *ctx, id<MTLAllocation> allocation) {
	if (ctx.metal4 && allocation) {
		@synchronized(ctx.residency) {
			[ctx.residency removeAllocation:allocation];
			ctx.residencyDirty = YES;
		}
	}
}

id<MTLTexture> m2m_new_private_texture(M2MContext *ctx, MTLPixelFormat format, NSUInteger width, NSUInteger height,
                                       MTLTextureUsage usage) {
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format
	                                                                                    width:width
	                                                                                   height:height
	                                                                                mipmapped:NO];
	descriptor.storageMode = MTLStorageModePrivate;
	descriptor.usage = usage;
	id<MTLTexture> texture = [ctx.device newTextureWithDescriptor:descriptor];
	m2m_track(ctx, texture);
	return texture;
}

void m2m_resource_release(int64_t handle, int64_t object) {
	if (object == 0) {
		return;
	}
	M2MContext *ctx = m2m_ctx(handle);
	id resource = m2m_borrow(object);
	if (ctx.metal4 && [resource conformsToProtocol:@protocol(MTLAllocation)]) {
		m2m_untrack(ctx, resource);
	}
	CFRelease((CFTypeRef)(intptr_t)object);
}

void m2m_retire(M2MContext *ctx, id object) {
	if (!object) {
		return;
	}
	if (!ctx.metal4) {
		// Metal 3 command buffers keep what they use alive.
		return;
	}
	M2MRetired *retired = [M2MRetired new];
	retired.object = object;
	retired.until = ctx.submitted + M2M_RETIRE_DELAY;
	[ctx.retired addObject:retired];
}

static void m2m_release_retired(M2MContext *ctx) {
	int64_t completed = (int64_t)ctx.completion.signaledValue;
	while (ctx.retired.count > 0) {
		M2MRetired *oldest = ctx.retired.firstObject;
		if (oldest.until > completed) {
			break;
		}
		if ([oldest.object conformsToProtocol:@protocol(MTLAllocation)]) {
			m2m_untrack(ctx, oldest.object);
		}
		[ctx.retired removeObjectAtIndex:0];
	}
}

// Command buffers

static id<MTL4CommandAllocator> m2m_take_allocator(M2MContext *ctx, NSMutableArray<id<MTL4CommandAllocator>> *pool) {
	id<MTL4CommandAllocator> allocator = nil;
	@synchronized(pool) {
		allocator = pool.lastObject;
		if (allocator) {
			[pool removeLastObject];
		}
	}
	if (allocator) {
		// Only allocators whose command buffers have completed are in the pool.
		[allocator reset];
		return allocator;
	}
	return [ctx.device newCommandAllocator];
}

id<MTLCommandBuffer> m2m_command_buffer(M2MContext *ctx) {
	if (!ctx.commandBuffer) {
		if (ctx.debug) {
			MTLCommandBufferDescriptor *descriptor = [MTLCommandBufferDescriptor new];
			descriptor.errorOptions = MTLCommandBufferErrorOptionEncoderExecutionStatus;
			ctx.commandBuffer = [ctx.queue commandBufferWithDescriptor:descriptor];
		} else {
			ctx.commandBuffer = [ctx.queue commandBuffer];
		}
	}
	return ctx.commandBuffer;
}

id<MTL4CommandBuffer> m2m_command_buffer4(M2MContext *ctx) {
	if (!ctx.commandBuffer4) {
		ctx.allocator4 = m2m_take_allocator(ctx, ctx.freeAllocators);
		id<MTL4CommandBuffer> commandBuffer = [ctx.device newCommandBuffer];
		[commandBuffer beginCommandBufferWithAllocator:ctx.allocator4];
		ctx.commandBuffer4 = commandBuffer;
	}
	return ctx.commandBuffer4;
}

// Metal 4 runs encoders concurrently unless told otherwise; these keep each one after everything before it, as
// Metal 3's hazard tracking does for the resources this backend shares between passes.
static void m2m_barrier_at_start(id<MTL4CommandEncoder> encoder, MTLStages stages) {
	[encoder barrierAfterQueueStages:MTLStageAll beforeStages:stages visibilityOptions:MTL4VisibilityOptionDevice];
}

static void m2m_barrier_at_end(id<MTL4CommandEncoder> encoder, MTLStages stages) {
	[encoder barrierAfterStages:stages beforeQueueStages:MTLStageAll visibilityOptions:MTL4VisibilityOptionDevice];
}

static const MTLStages M2M_RENDER_STAGES = MTLStageVertex | MTLStageFragment;
static const MTLStages M2M_COPY_STAGES = MTLStageBlit | MTLStageDispatch;

void m2m_end_encoding(M2MContext *ctx) {
	if (ctx.metal4) {
		if (ctx.computeEncoder4) {
			m2m_barrier_at_end(ctx.computeEncoder4, MTLStageDispatch);
			[ctx.computeEncoder4 endEncoding];
			ctx.computeEncoder4 = nil;
		}
		if (ctx.transferEncoder4) {
			m2m_barrier_at_end(ctx.transferEncoder4, M2M_COPY_STAGES);
			[ctx.transferEncoder4 endEncoding];
			ctx.transferEncoder4 = nil;
		}
		if (ctx.renderEncoder4) {
			m2m_barrier_at_end(ctx.renderEncoder4, M2M_RENDER_STAGES);
			[ctx.renderEncoder4 endEncoding];
			ctx.renderEncoder4 = nil;
			ctx.renderEncoderReusable = NO;
			ctx.renderEncoderAttachments = nil;
		}
		return;
	}
	if (ctx.blitEncoder) {
		[ctx.blitEncoder endEncoding];
		ctx.blitEncoder = nil;
	}
	if (ctx.computeEncoder) {
		[ctx.computeEncoder endEncoding];
		ctx.computeEncoder = nil;
	}
	if (ctx.renderEncoder) {
		[ctx.renderEncoder endEncoding];
		ctx.renderEncoder = nil;
		ctx.renderEncoderReusable = NO;
		ctx.renderEncoderAttachments = nil;
	}
}

void m2m_compute_begin(M2MContext *ctx, id<MTLComputePipelineState> pipeline) {
	if (ctx.metal4) {
		if (!ctx.computeEncoder4) {
			m2m_end_encoding(ctx);
			ctx.computeEncoder4 = [m2m_command_buffer4(ctx) computeCommandEncoder];
			m2m_barrier_at_start(ctx.computeEncoder4, MTLStageDispatch);
			[ctx.computeEncoder4 setArgumentTable:ctx.argumentTable];
		}
		[ctx.computeEncoder4 setComputePipelineState:pipeline];
		return;
	}
	if (!ctx.computeEncoder) {
		m2m_end_encoding(ctx);
		ctx.computeEncoder = [m2m_command_buffer(ctx) computeCommandEncoder];
	}
	[ctx.computeEncoder setComputePipelineState:pipeline];
}

void m2m_compute_dispatch(M2MContext *ctx, MTLSize threadgroups, MTLSize threadsPerThreadgroup) {
	if (ctx.metal4) {
		[ctx.computeEncoder4 dispatchThreadgroups:threadgroups threadsPerThreadgroup:threadsPerThreadgroup];
		// Each dispatch may read what the one before wrote; Metal 3 orders a serial encoder's dispatches itself.
		[ctx.computeEncoder4 barrierAfterEncoderStages:MTLStageDispatch beforeEncoderStages:MTLStageDispatch
		                             visibilityOptions:MTL4VisibilityOptionDevice];
		return;
	}
	[ctx.computeEncoder dispatchThreadgroups:threadgroups threadsPerThreadgroup:threadsPerThreadgroup];
}

id<M2MCopier> m2m_copier(M2MContext *ctx) {
	if (ctx.metal4) {
		if (!ctx.transferEncoder4) {
			m2m_end_encoding(ctx);
			ctx.transferEncoder4 = [m2m_command_buffer4(ctx) computeCommandEncoder];
			m2m_barrier_at_start(ctx.transferEncoder4, M2M_COPY_STAGES);
			ctx.transferEncoderUsed = NO;
			ctx.blitEncoderCount++;
		} else if (ctx.transferEncoderUsed) {
			// Copies in one Metal 4 encoder may overlap; a later one can read what an earlier one wrote.
			[ctx.transferEncoder4 barrierAfterEncoderStages:MTLStageBlit
			                        beforeEncoderStages:MTLStageBlit
			                          visibilityOptions:MTL4VisibilityOptionDevice];
		}
		ctx.transferEncoderUsed = YES;
		return (id<M2MCopier>)ctx.transferEncoder4;
	}
	if (!ctx.blitEncoder) {
		m2m_end_encoding(ctx);
		ctx.blitEncoder = [m2m_command_buffer(ctx) blitCommandEncoder];
		ctx.blitEncoderCount++;
	}
	return (id<M2MCopier>)ctx.blitEncoder;
}

static MTL4RenderPassDescriptor *m2m_pass4(MTLRenderPassDescriptor *pass) {
	MTL4RenderPassDescriptor *converted = [MTL4RenderPassDescriptor new];
	for (NSUInteger i = 0; i < 8; i++) {
		if (pass.colorAttachments[i].texture) {
			converted.colorAttachments[i] = pass.colorAttachments[i];
		}
	}
	if (pass.depthAttachment.texture) {
		converted.depthAttachment = pass.depthAttachment;
	}
	if (pass.stencilAttachment.texture) {
		converted.stencilAttachment = pass.stencilAttachment;
	}
	return converted;
}

id<M2MRenderCommands> m2m_open_render_encoder(M2MContext *ctx, MTLRenderPassDescriptor *pass, NSString *label) {
	m2m_end_encoding(ctx);
	ctx.renderEncoderCount++;
	if (ctx.metal4) {
		id<MTL4RenderCommandEncoder> encoder = [m2m_command_buffer4(ctx) renderCommandEncoderWithDescriptor:m2m_pass4(pass)];
		m2m_barrier_at_start(encoder, M2M_RENDER_STAGES);
		[encoder setArgumentTable:ctx.argumentTable atStages:MTLRenderStageVertex | MTLRenderStageFragment];
		if (label) {
			encoder.label = label;
		}
		ctx.renderEncoder4 = encoder;
		return (id<M2MRenderCommands>)encoder;
	}
	id<MTLRenderCommandEncoder> encoder = [m2m_command_buffer(ctx) renderCommandEncoderWithDescriptor:pass];
	if (label) {
		encoder.label = label;
	}
	ctx.renderEncoder = encoder;
	return (id<M2MRenderCommands>)encoder;
}

id<M2MRenderCommands> m2m_render_encoder(M2MContext *ctx) {
	return ctx.metal4 ? (id<M2MRenderCommands>)ctx.renderEncoder4 : (id<M2MRenderCommands>)ctx.renderEncoder;
}

// Bindings

static M2MUploadChunk *m2m_upload_chunk(M2MContext *ctx, NSUInteger length) {
	int64_t completed = (int64_t)ctx.completion.signaledValue;
	int64_t recording = ctx.submitted + 1;
	M2MUploadChunk *current = ctx.uploadChunks.lastObject;
	if (current && current.used + length <= current.buffer.length) {
		return current;
	}
	// The oldest chunk goes to the back once the GPU is past it; otherwise a new one is added.
	M2MUploadChunk *oldest = ctx.uploadChunks.firstObject;
	M2MUploadChunk *chunk;
	if (oldest && oldest != current && oldest.lastUse <= completed && length <= oldest.buffer.length) {
		chunk = oldest;
		[ctx.uploadChunks removeObjectAtIndex:0];
	} else {
		chunk = [M2MUploadChunk new];
		chunk.buffer = [ctx.device newBufferWithLength:MAX(M2M_UPLOAD_CHUNK, length) options:MTLResourceStorageModeShared];
		chunk.buffer.label = @"mine2metal uploads";
		m2m_track(ctx, chunk.buffer);
	}
	chunk.used = 0;
	chunk.lastUse = recording;
	[ctx.uploadChunks addObject:chunk];
	return chunk;
}

void m2m_bind_buffer(M2MContext *ctx, NSUInteger index, id<MTLBuffer> buffer, NSUInteger offset, BOOL vertex, BOOL fragment) {
	if (ctx.metal4) {
		[ctx.argumentTable setAddress:buffer ? buffer.gpuAddress + offset : 0 atIndex:index];
		return;
	}
	if (ctx.computeEncoder) {
		[ctx.computeEncoder setBuffer:buffer offset:offset atIndex:index];
		return;
	}
	if (vertex) {
		[ctx.renderEncoder setVertexBuffer:buffer offset:offset atIndex:index];
	}
	if (fragment) {
		[ctx.renderEncoder setFragmentBuffer:buffer offset:offset atIndex:index];
	}
}

void m2m_bind_bytes(M2MContext *ctx, NSUInteger index, const void *data, NSUInteger length, BOOL vertex, BOOL fragment) {
	if (ctx.metal4) {
		M2MUploadChunk *chunk = m2m_upload_chunk(ctx, length);
		NSUInteger offset = chunk.used;
		memcpy((char *)chunk.buffer.contents + offset, data, length);
		chunk.used = (offset + length + M2M_UPLOAD_ALIGNMENT - 1) & ~(M2M_UPLOAD_ALIGNMENT - 1);
		chunk.lastUse = ctx.submitted + 1;
		[ctx.argumentTable setAddress:chunk.buffer.gpuAddress + offset atIndex:index];
		return;
	}
	if (ctx.computeEncoder) {
		[ctx.computeEncoder setBytes:data length:length atIndex:index];
		return;
	}
	if (vertex) {
		[ctx.renderEncoder setVertexBytes:data length:length atIndex:index];
	}
	if (fragment) {
		[ctx.renderEncoder setFragmentBytes:data length:length atIndex:index];
	}
}

void m2m_bind_texture(M2MContext *ctx, NSUInteger index, id<MTLTexture> texture, BOOL vertex, BOOL fragment) {
	if (ctx.metal4) {
		[ctx.argumentTable setTexture:texture.gpuResourceID atIndex:index];
		return;
	}
	if (ctx.computeEncoder) {
		[ctx.computeEncoder setTexture:texture atIndex:index];
		return;
	}
	if (vertex) {
		[ctx.renderEncoder setVertexTexture:texture atIndex:index];
	}
	if (fragment) {
		[ctx.renderEncoder setFragmentTexture:texture atIndex:index];
	}
}

void m2m_bind_sampler(M2MContext *ctx, NSUInteger index, id<MTLSamplerState> sampler, BOOL vertex, BOOL fragment) {
	if (ctx.metal4) {
		[ctx.argumentTable setSamplerState:sampler.gpuResourceID atIndex:index];
		return;
	}
	if (ctx.computeEncoder) {
		[ctx.computeEncoder setSamplerState:sampler atIndex:index];
		return;
	}
	if (vertex) {
		[ctx.renderEncoder setVertexSamplerState:sampler atIndex:index];
	}
	if (fragment) {
		[ctx.renderEncoder setFragmentSamplerState:sampler atIndex:index];
	}
}

// Completion, events and commits

void m2m_on_completed(M2MContext *ctx, M2MCompletion completion) {
	if (ctx.metal4) {
		[ctx.pendingCompletions addObject:completion];
		return;
	}
	[m2m_command_buffer(ctx) addCompletedHandler:^(id<MTLCommandBuffer> finished) {
		completion(finished.GPUStartTime, finished.GPUEndTime, finished.status == MTLCommandBufferStatusError ? finished.error : nil);
	}];
}

void m2m_wait_for_event(M2MContext *ctx, id<MTLEvent> event, uint64_t value) {
	if (ctx.metal4) {
		M2MEventValue *wait = [M2MEventValue new];
		wait.event = event;
		wait.value = value;
		[ctx.waitsBeforeCommit addObject:wait];
		return;
	}
	m2m_end_encoding(ctx);
	[m2m_command_buffer(ctx) encodeWaitForEvent:event value:value];
}

void m2m_signal_event(M2MContext *ctx, id<MTLEvent> event, uint64_t value) {
	if (ctx.metal4) {
		M2MEventValue *signal = [M2MEventValue new];
		signal.event = event;
		signal.value = value;
		[ctx.signalsAfterCommit addObject:signal];
		return;
	}
	m2m_end_encoding(ctx);
	[m2m_command_buffer(ctx) encodeSignalEvent:event value:value];
}

static void m2m_commit4(M2MContext *ctx, id<MTL4CommandQueue> queue, id<MTL4CommandBuffer> commandBuffer,
                        id<MTL4CommandAllocator> allocator, NSMutableArray *pool, NSArray<M2MCompletion> *completions) {
	[commandBuffer endCommandBuffer];
	@synchronized(ctx.residency) {
		if (ctx.residencyDirty) {
			[ctx.residency commit];
			ctx.residencyDirty = NO;
		}
	}
	MTL4CommitOptions *options = [MTL4CommitOptions new];
	NSString *name = commandBuffer.label ?: @"(unlabeled)";
	if (m2m_tracing()) {
		NSLog(@"mine2metal trace: commit %@ on %@", name, queue == ctx.queue4 ? @"render queue" : @"present queue");
	}
	[options addFeedbackHandler:^(id<MTL4CommitFeedback> feedback) {
		if (m2m_tracing()) {
			NSLog(@"mine2metal trace: completed %@ (error %@)", name, feedback.error);
		}
		for (M2MCompletion completion in completions) {
			completion(feedback.GPUStartTime, feedback.GPUEndTime, feedback.error);
		}
		@synchronized(pool) {
			[pool addObject:allocator];
		}
	}];
	const id<MTL4CommandBuffer> buffers[] = {commandBuffer};
	[queue commit:buffers count:1 options:options];
}

void m2m_commit_open(M2MContext *ctx, NSString *label, M2MCompletion completion) {
	m2m_end_encoding(ctx);
	if (ctx.metal4) {
		id<MTL4CommandBuffer> commandBuffer = m2m_command_buffer4(ctx);
		commandBuffer.label = label;
		NSMutableArray<M2MCompletion> *completions = ctx.pendingCompletions;
		if (completion) {
			[completions addObject:completion];
		}
		for (M2MEventValue *wait in ctx.waitsBeforeCommit) {
			if (m2m_tracing()) {
				NSLog(@"mine2metal trace: render queue waits for %@ = %llu", wait.event.label, wait.value);
			}
			[ctx.queue4 waitForEvent:wait.event value:wait.value];
		}
		m2m_commit4(ctx, ctx.queue4, commandBuffer, ctx.allocator4, ctx.freeAllocators, completions);
		for (M2MEventValue *signal in ctx.signalsAfterCommit) {
			if (m2m_tracing()) {
				NSLog(@"mine2metal trace: render queue signals %@ = %llu", signal.event.label, signal.value);
			}
			[ctx.queue4 signalEvent:signal.event value:signal.value];
		}
		ctx.commandBuffer4 = nil;
		ctx.allocator4 = nil;
		m2m_release_retired(ctx);
		ctx.pendingCompletions = [NSMutableArray array];
		[ctx.waitsBeforeCommit removeAllObjects];
		[ctx.signalsAfterCommit removeAllObjects];
		return;
	}
	id<MTLCommandBuffer> commandBuffer = m2m_command_buffer(ctx);
	commandBuffer.label = label;
	if (completion) {
		m2m_on_completed(ctx, completion);
	}
	[commandBuffer commit];
	ctx.commandBuffer = nil;
}

id<MTL4CommandBuffer> m2m_present_command_buffer4(M2MContext *ctx) {
	id<MTL4CommandBuffer> commandBuffer = [ctx.device newCommandBuffer];
	id<MTL4CommandAllocator> allocator = m2m_take_allocator(ctx, ctx.freePresentAllocators);
	[commandBuffer beginCommandBufferWithAllocator:allocator];
	objc_setAssociatedObject(commandBuffer, @selector(m2m_present_command_buffer4), allocator, OBJC_ASSOCIATION_RETAIN);
	return commandBuffer;
}

void m2m_commit_present4(M2MContext *ctx, id<MTL4CommandBuffer> commandBuffer, M2MCompletion completion) {
	id<MTL4CommandAllocator> allocator = objc_getAssociatedObject(commandBuffer, @selector(m2m_present_command_buffer4));
	m2m_commit4(ctx, ctx.presentQueue4, commandBuffer, allocator, ctx.freePresentAllocators, completion ? @[completion] : @[]);
}

// Set up in m2m_context_create when Metal 4 is chosen; returns an error message, or nil.
NSString *m2m_metal4_init(M2MContext *ctx) {
	NSError *error = nil;
	ctx.queue4 = [ctx.device newMTL4CommandQueue];
	ctx.presentQueue4 = [ctx.device newMTL4CommandQueue];
	MTLResidencySetDescriptor *residency = [MTLResidencySetDescriptor new];
	residency.label = @"mine2metal resources";
	residency.initialCapacity = 4096;
	ctx.residency = [ctx.device newResidencySetWithDescriptor:residency error:&error];
	if (!ctx.queue4 || !ctx.presentQueue4 || !ctx.residency) {
		return error.localizedDescription ?: @"Could not create a Metal 4 command queue";
	}
	[ctx.queue4 addResidencySet:ctx.residency];
	[ctx.presentQueue4 addResidencySet:ctx.residency];
	[ctx.residency requestResidency];

	MTL4ArgumentTableDescriptor *table = [MTL4ArgumentTableDescriptor new];
	// Uniforms and textures use slots 0..25, push constants 26 and vertex buffers 27..30 (see MetalRenderPipeline).
	table.maxBufferBindCount = 31;
	table.maxTextureBindCount = 31;
	table.maxSamplerStateBindCount = 16;
	table.label = @"mine2metal bindings";
	ctx.argumentTable = [ctx.device newArgumentTableWithDescriptor:table error:&error];
	table.label = @"mine2metal present bindings";
	table.maxBufferBindCount = 1;
	table.maxTextureBindCount = 1;
	table.maxSamplerStateBindCount = 1;
	ctx.presentArgumentTable = [ctx.device newArgumentTableWithDescriptor:table error:&error];
	if (!ctx.argumentTable || !ctx.presentArgumentTable) {
		return error.localizedDescription ?: @"Could not create a Metal 4 argument table";
	}
	ctx.compiler = [ctx.device newCompilerWithDescriptor:[MTL4CompilerDescriptor new] error:&error];
	if (!ctx.compiler) {
		return error.localizedDescription ?: @"Could not create a Metal 4 compiler";
	}
	ctx.completion = [ctx.device newSharedEvent];
	ctx.freeAllocators = [NSMutableArray array];
	ctx.freePresentAllocators = [NSMutableArray array];
	ctx.pendingCompletions = [NSMutableArray array];
	ctx.waitsBeforeCommit = [NSMutableArray array];
	ctx.signalsAfterCommit = [NSMutableArray array];
	ctx.uploadChunks = [NSMutableArray array];
	ctx.retired = [NSMutableArray array];
	ctx.metal4 = YES;
	return nil;
}
