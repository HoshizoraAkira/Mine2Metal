#import <Foundation/Foundation.h>
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

int64_t m2m_device_create(void) {
	@autoreleasepool {
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

int32_t m2m_device_supports_metal3(int64_t device) {
	return [(id<MTLDevice>)m2m_borrow(device) supportsFamily:MTLGPUFamilyMetal3] ? 1 : 0;
}

void m2m_release(int64_t object) {
	if (object != 0) {
		CFRelease((CFTypeRef)(intptr_t)object);
	}
}

int64_t m2m_context_create(int64_t device_handle, char *error, int64_t error_capacity) {
	@autoreleasepool {
		M2MContext *ctx = [M2MContext new];
		ctx.device = m2m_borrow(device_handle);
		ctx.queue = [ctx.device newCommandQueue];
		ctx.inFlight = [NSMutableArray array];
		ctx.firstInFlightIndex = 1;
		if (!ctx.queue) {
			m2m_copy_string(error, error_capacity, @"Could not create a command queue");
			return 0;
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
		if (!ctx.presentPipeline) {
			m2m_copy_string(error, error_capacity, compileError.localizedDescription);
			return 0;
		}

		MTLSamplerDescriptor *sampler = [MTLSamplerDescriptor new];
		sampler.minFilter = MTLSamplerMinMagFilterLinear;
		sampler.magFilter = MTLSamplerMinMagFilterLinear;
		ctx.presentSampler = [ctx.device newSamplerStateWithDescriptor:sampler];

		return m2m_retain(ctx);
	}
}

int64_t m2m_context_max_buffer_length(int64_t ctx) {
	return (int64_t)m2m_ctx(ctx).device.maxBufferLength;
}

id<MTLCommandBuffer> m2m_command_buffer(M2MContext *ctx) {
	if (!ctx.commandBuffer) {
		ctx.commandBuffer = [ctx.queue commandBuffer];
	}
	return ctx.commandBuffer;
}

void m2m_end_encoding(M2MContext *ctx) {
	if (ctx.blitEncoder) {
		[ctx.blitEncoder endEncoding];
		ctx.blitEncoder = nil;
	}
	if (ctx.renderEncoder) {
		[ctx.renderEncoder endEncoding];
		ctx.renderEncoder = nil;
	}
}

id<MTLBlitCommandEncoder> m2m_blit_encoder(M2MContext *ctx) {
	if (!ctx.blitEncoder) {
		m2m_end_encoding(ctx);
		ctx.blitEncoder = [m2m_command_buffer(ctx) blitCommandEncoder];
	}
	return ctx.blitEncoder;
}

int64_t m2m_context_commit(int64_t handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		m2m_end_encoding(ctx);
		id<MTLCommandBuffer> commandBuffer = m2m_command_buffer(ctx);
		int64_t index = ++ctx.submitted;
		__weak M2MContext *weakCtx = ctx;
		// Completion order on one queue is submission order, so the latest index covers all before it.
		[commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> finished) {
			(void)finished;
			weakCtx.completed = index;
		}];
		[commandBuffer commit];
		[ctx.inFlight addObject:commandBuffer];
		ctx.commandBuffer = nil;

		while (ctx.inFlight.count > 0 && ctx.inFlight.firstObject.status >= MTLCommandBufferStatusCompleted) {
			[ctx.inFlight removeObjectAtIndex:0];
			ctx.firstInFlightIndex++;
		}
		return index;
	}
}

int64_t m2m_context_completed(int64_t ctx) {
	return m2m_ctx(ctx).completed;
}

void m2m_context_wait(int64_t handle, int64_t index) {
	M2MContext *ctx = m2m_ctx(handle);
	while (ctx.inFlight.count > 0 && ctx.firstInFlightIndex <= index) {
		[ctx.inFlight.firstObject waitUntilCompleted];
		[ctx.inFlight removeObjectAtIndex:0];
		ctx.firstInFlightIndex++;
	}
}
