#import <Foundation/Foundation.h>
#include <stdlib.h>
#include <string.h>

#include "m2m_context.h"

static NSString *const M2M_PRESENT_SHADER =
	@"#include <metal_stdlib>\n"
	 "using namespace metal;\n"
	 "struct PresentVertex { float4 position [[position]]; float2 uv; };\n"
	 "vertex PresentVertex m2m_present_vertex(uint id [[vertex_id]]) {\n"
	 "    float2 uv = float2((id << 1) & 2, id & 2);\n"
	 "    PresentVertex out;\n"
	 "    out.position = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);\n"
	 // Render targets keep GL's bottom-up row order (see FLIP_VERTEX_Y), so the top of the screen is the last row.
	 "    out.uv = float2(uv.x, 1.0 - uv.y);\n"
	 "    return out;\n"
	 "}\n"
	 "fragment float4 m2m_present_fragment(PresentVertex in [[stage_in]],\n"
	 "                                     texture2d<float> source [[texture(0)]],\n"
	 "                                     sampler linear [[sampler(0)]]) {\n"
	 "    return float4(source.sample(linear, in.uv).rgb, 1.0);\n"
	 "}\n";

@implementation M2MContext
@end

void m2m_copy_string(char *out, int64_t capacity, NSString *message) {
	if (capacity <= 0) {
		return;
	}
	const char *text = message.UTF8String ?: "unknown error";
	size_t length = strlen(text);
	size_t copied = length < (size_t)capacity - 1 ? length : (size_t)capacity - 1;
	memcpy(out, text, copied);
	out[copied] = '\0';
}

int64_t m2m_device_create(int32_t validation) {
	@autoreleasepool {
		if (validation) {
			setenv("MTL_DEBUG_LAYER", "1", 1);
		}
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		return device ? m2m_retain(device) : 0;
	}
}

int64_t m2m_device_name(int64_t device, char *out, int64_t capacity) {
	@autoreleasepool {
		NSString *name = [(id<MTLDevice>)m2m_borrow(device) name];
		m2m_copy_string(out, capacity, name);
		return (int64_t)strlen(name.UTF8String);
	}
}

int32_t m2m_device_validation_active(int64_t device) {
	return [NSStringFromClass([m2m_borrow(device) class]) containsString:@"Debug"] ? 1 : 0;
}

int32_t m2m_device_supports_metal3(int64_t device) {
	return [(id<MTLDevice>)m2m_borrow(device) supportsFamily:MTLGPUFamilyMetal3] ? 1 : 0;
}

int32_t m2m_device_supports_metal4(int64_t device) {
	if (@available(macOS 26.0, *)) {
		return [(id<MTLDevice>)m2m_borrow(device) supportsFamily:MTLGPUFamilyMetal4] ? 1 : 0;
	}
	return 0;
}

NSString *m2m_metal4_init(M2MContext *ctx) API_AVAILABLE(macos(26.0));

void m2m_release(int64_t object) {
	if (object != 0) {
		CFRelease((CFTypeRef)(intptr_t)object);
	}
}

int64_t m2m_context_create(int64_t device_handle, int32_t debug, int32_t metal4, char *error, int64_t error_capacity) {
	@autoreleasepool {
		M2MContext *ctx = [M2MContext new];
		ctx.device = m2m_borrow(device_handle);
		ctx.debug = debug != 0;
		ctx.errors = [NSMutableArray array];
		ctx.queue = [ctx.device newCommandQueue];
		ctx.presentQueue = [ctx.device newCommandQueue];
		ctx.frameReady = [ctx.device newEvent];
		ctx.frameReady.label = @"frameReady";
		ctx.presentDone = [ctx.device newEvent];
		ctx.presentDone.label = @"presentDone";
		ctx.presentStaging = [NSMutableArray arrayWithObjects:[NSNull null], [NSNull null], nil];
		ctx.latestLock = [NSCondition new];
		ctx.latestSlots = [NSMutableArray arrayWithObjects:[NSNull null], [NSNull null], [NSNull null], nil];
		ctx.latestGenerated = [NSMutableArray arrayWithObjects:[NSNull null], [NSNull null], [NSNull null], nil];
		ctx.latestSlot = -1;
		ctx.writingSlot = -1;
		ctx.inFlight = [NSMutableArray array];
		ctx.clearPipelines = [NSMutableDictionary dictionary];
		ctx.gpuSecondsByLabel = [NSMutableDictionary dictionary];
		ctx.presentedTimes = [NSMutableArray array];
		ctx.firstInFlightIndex = 1;
		if (!ctx.queue || !ctx.presentQueue) {
			m2m_copy_string(error, error_capacity, @"Could not create a command queue");
			return 0;
		}
		if (metal4) {
			if (@available(macOS 26.0, *)) {
				NSString *failure = m2m_metal4_init(ctx);
				if (failure) {
					m2m_copy_string(error, error_capacity, failure);
					return 0;
				}
			} else {
				m2m_copy_string(error, error_capacity, @"Metal 4 needs macOS 26 or newer");
				return 0;
			}
		}

		NSError *compileError = nil;
		id<MTLLibrary> library = [ctx.device newLibraryWithSource:M2M_PRESENT_SHADER options:nil error:&compileError];
		if (!library) {
			m2m_copy_string(error, error_capacity, compileError.localizedDescription);
			return 0;
		}

		MTLRenderPipelineDescriptor *pipeline = [MTLRenderPipelineDescriptor new];
		pipeline.label = @"mine2metal present";
		pipeline.vertexFunction = [library newFunctionWithName:@"m2m_present_vertex"];
		pipeline.fragmentFunction = [library newFunctionWithName:@"m2m_present_fragment"];
		pipeline.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
		ctx.presentPipeline = [ctx.device newRenderPipelineStateWithDescriptor:pipeline error:&compileError];
		// For EDR drawables; the frame is already in extended linear values then (hdr.m).
		pipeline.label = @"mine2metal present EDR";
		pipeline.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
		ctx.presentPipelineEDR = [ctx.device newRenderPipelineStateWithDescriptor:pipeline error:&compileError];
		if (!ctx.presentPipeline || !ctx.presentPipelineEDR) {
			m2m_copy_string(error, error_capacity, compileError.localizedDescription);
			return 0;
		}

		MTLSamplerDescriptor *sampler = [MTLSamplerDescriptor new];
		sampler.supportArgumentBuffers = YES;
		sampler.minFilter = MTLSamplerMinMagFilterLinear;
		sampler.magFilter = MTLSamplerMinMagFilterLinear;
		ctx.presentSampler = [ctx.device newSamplerStateWithDescriptor:sampler];

		return m2m_retain(ctx);
	}
}

int64_t m2m_context_max_buffer_length(int64_t ctx) {
	return (int64_t)m2m_ctx(ctx).device.maxBufferLength;
}

int64_t m2m_context_commit(int64_t handle, const char *label) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		NSString *timingLabel = label ? @(label) : nil;
		int64_t index = ++ctx.submitted;
		__weak M2MContext *weakCtx = ctx;
		id<MTLCommandBuffer> commandBuffer = ctx.metal4 ? nil : m2m_command_buffer(ctx);
		// Completion order on one queue is submission order, so the latest index covers all before it.
		m2m_commit_open(ctx, timingLabel, ^(CFTimeInterval gpuStart, CFTimeInterval gpuEnd, NSError *failure) {
			M2MContext *strongCtx = weakCtx;
			if (failure) {
				m2m_record_error(strongCtx, failure);
			}
			double seconds = gpuEnd - gpuStart;
			strongCtx.gpuSeconds += seconds;
			if (timingLabel) {
				@synchronized(strongCtx.gpuSecondsByLabel) {
					NSNumber *total = strongCtx.gpuSecondsByLabel[timingLabel];
					strongCtx.gpuSecondsByLabel[timingLabel] = @(total.doubleValue + seconds);
				}
			}
			strongCtx.completed = index;
		});
		if (ctx.metal4) {
			if (@available(macOS 26.0, *)) {
				if (m2m_tracing()) {
					NSLog(@"mine2metal trace: submit %lld", index);
				}
				[ctx.queue4 signalEvent:ctx.completion value:(uint64_t)index];
			}
			return index;
		}
		[ctx.inFlight addObject:commandBuffer];

		while (ctx.inFlight.count > 0 && ctx.inFlight.firstObject.status >= MTLCommandBufferStatusCompleted) {
			[ctx.inFlight removeObjectAtIndex:0];
			ctx.firstInFlightIndex++;
		}
		return index;
	}
}

int64_t m2m_context_completed(int64_t handle) {
	M2MContext *ctx = m2m_ctx(handle);
	if (ctx.metal4) {
		return MAX(ctx.completed, (int64_t)ctx.completion.signaledValue);
	}
	return ctx.completed;
}

void m2m_context_encoder_counts(int64_t handle, int64_t *render, int64_t *blit, int64_t *merged) {
	M2MContext *ctx = m2m_ctx(handle);
	*render = ctx.renderEncoderCount;
	*blit = ctx.blitEncoderCount;
	*merged = ctx.mergedPassCount;
}

int64_t m2m_context_take_label_times(int64_t handle, char *out, int64_t capacity) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		NSMutableString *text = [NSMutableString string];
		@synchronized(ctx.gpuSecondsByLabel) {
			NSArray<NSString *> *labels = [ctx.gpuSecondsByLabel keysSortedByValueUsingComparator:^(NSNumber *a, NSNumber *b) {
				return [b compare:a];
			}];
			for (NSString *name in labels) {
				[text appendFormat:@"%@=%.6f\n", name, ctx.gpuSecondsByLabel[name].doubleValue];
			}
			[ctx.gpuSecondsByLabel removeAllObjects];
		}
		m2m_copy_string(out, capacity, text);
		return (int64_t)text.length;
	}
}

int64_t m2m_context_error_count(int64_t ctx) {
	return m2m_ctx(ctx).errorCount;
}

int32_t m2m_context_take_errors(int64_t handle, char *out, int64_t capacity) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		NSString *text;
		@synchronized(ctx.errors) {
			text = [ctx.errors componentsJoinedByString:@"\n"];
			[ctx.errors removeAllObjects];
		}
		m2m_copy_string(out, capacity, text);
		return ctx.deviceLost ? 1 : 0;
	}
}

double m2m_context_gpu_seconds(int64_t ctx) {
	return m2m_ctx(ctx).gpuSeconds;
}

void m2m_context_wait(int64_t handle, int64_t index) {
	M2MContext *ctx = m2m_ctx(handle);
	if (ctx.metal4) {
		// As with Metal 3, only committed work is waited for: index may be below 1 or not submitted yet.
		index = MIN(index, ctx.submitted);
		if (index <= 0) {
			return;
		}
		// A lost device never signals; give up then, as Metal 3's waitUntilCompleted returns with an error.
		while (![ctx.completion waitUntilSignaledValue:(uint64_t)index timeoutMS:1000] && !ctx.deviceLost) {
		}
		return;
	}
	while (ctx.inFlight.count > 0 && ctx.firstInFlightIndex <= index) {
		[ctx.inFlight.firstObject waitUntilCompleted];
		[ctx.inFlight removeObjectAtIndex:0];
		ctx.firstInFlightIndex++;
	}
}
