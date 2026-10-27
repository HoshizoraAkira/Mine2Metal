#include "m2m_context.h"

// Clears part of a texture: Metal load actions only clear whole attachments, so a triangle covering the attachment is
// drawn with the region as its scissor rect. The vertex stage places it at the clear depth. Depth and color use
// different slots, since Metal 4 shares bindings between the stages.
static NSString *const M2M_CLEAR_SHADER = @"#include <metal_stdlib>\n"
                                           "using namespace metal;\n"
                                           "vertex float4 m2m_clear_vertex(uint vid [[vertex_id]], constant float &depth [[buffer(1)]]) {\n"
                                           "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
                                           "    return float4(p * 2.0 - 1.0, depth, 1.0);\n"
                                           "}\n"
                                           "fragment float4 m2m_clear_fragment(constant float4 &color [[buffer(0)]]) {\n"
                                           "    return color;\n"
                                           "}\n";

static id<MTLRenderPipelineState> m2m_clear_pipeline(M2MContext *ctx, MTLPixelFormat color, MTLPixelFormat depth, BOOL stencil) {
	NSNumber *key = @((uint64_t)color | ((uint64_t)depth << 20) | ((uint64_t)stencil << 40));
	id<MTLRenderPipelineState> cached = ctx.clearPipelines[key];
	if (cached) {
		return cached;
	}
	if (!ctx.clearLibrary) {
		NSError *error = nil;
		ctx.clearLibrary = [ctx.device newLibraryWithSource:M2M_CLEAR_SHADER options:nil error:&error];
		if (!ctx.clearLibrary) {
			NSLog(@"mine2metal: could not compile the clear shader: %@", error);
			return nil;
		}
	}
	MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
	descriptor.label = @"mine2metal partial clear";
	descriptor.vertexFunction = [ctx.clearLibrary newFunctionWithName:@"m2m_clear_vertex"];
	if (color != MTLPixelFormatInvalid) {
		descriptor.fragmentFunction = [ctx.clearLibrary newFunctionWithName:@"m2m_clear_fragment"];
		descriptor.colorAttachments[0].pixelFormat = color;
	}
	descriptor.depthAttachmentPixelFormat = depth;
	if (stencil) {
		descriptor.stencilAttachmentPixelFormat = depth;
	}
	NSError *error = nil;
	id<MTLRenderPipelineState> pipeline = [ctx.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
	if (!pipeline) {
		NSLog(@"mine2metal: could not create a clear pipeline: %@", error);
		return nil;
	}
	ctx.clearPipelines[key] = pipeline;
	return pipeline;
}

int32_t m2m_clear_region(int64_t handle, int64_t color_texture, float r, float g, float b, float a, int64_t depth_texture,
                         int32_t has_stencil, double depth, int32_t x, int32_t y, int32_t width, int32_t height, int32_t mip_level) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> color = color_texture ? m2m_borrow(color_texture) : nil;
		id<MTLTexture> depthTarget = depth_texture ? m2m_borrow(depth_texture) : nil;
		id<MTLTexture> sizing = color ?: depthTarget;
		if (!sizing) {
			return 1;
		}
		NSUInteger levelWidth = MAX((NSUInteger)1, sizing.width >> mip_level);
		NSUInteger levelHeight = MAX((NSUInteger)1, sizing.height >> mip_level);
		// Clipped like a GL scissor; rows keep GL's bottom-up order, so y needs no flip.
		NSInteger left = MAX(0, x);
		NSInteger top = MAX(0, y);
		NSInteger right = MIN((NSInteger)levelWidth, (NSInteger)x + width);
		NSInteger bottom = MIN((NSInteger)levelHeight, (NSInteger)y + height);
		if (right <= left || bottom <= top) {
			return 1;
		}

		id<MTLRenderPipelineState> pipeline = m2m_clear_pipeline(ctx, color ? color.pixelFormat : MTLPixelFormatInvalid,
		                                                         depthTarget ? depthTarget.pixelFormat : MTLPixelFormatInvalid, has_stencil != 0);
		if (!pipeline) {
			return 0;
		}

		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		if (color) {
			pass.colorAttachments[0].texture = color;
			pass.colorAttachments[0].level = (NSUInteger)mip_level;
			pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
			pass.colorAttachments[0].storeAction = MTLStoreActionStore;
		}
		if (depthTarget) {
			pass.depthAttachment.texture = depthTarget;
			pass.depthAttachment.level = (NSUInteger)mip_level;
			pass.depthAttachment.loadAction = MTLLoadActionLoad;
			pass.depthAttachment.storeAction = MTLStoreActionStore;
			if (has_stencil) {
				// Like glClear with only the depth bit: stencil is kept.
				pass.stencilAttachment.texture = depthTarget;
				pass.stencilAttachment.level = (NSUInteger)mip_level;
				pass.stencilAttachment.loadAction = MTLLoadActionLoad;
				pass.stencilAttachment.storeAction = MTLStoreActionStore;
			}
			if (!ctx.clearDepthState) {
				MTLDepthStencilDescriptor *state = [MTLDepthStencilDescriptor new];
				state.depthCompareFunction = MTLCompareFunctionAlways;
				state.depthWriteEnabled = YES;
				ctx.clearDepthState = [ctx.device newDepthStencilStateWithDescriptor:state];
			}
		}

		id<M2MRenderCommands> encoder = m2m_open_render_encoder(ctx, pass, @"mine2metal partial clear");
		[encoder setRenderPipelineState:pipeline];
		if (depthTarget) {
			[encoder setDepthStencilState:ctx.clearDepthState];
		}
		MTLScissorRect rect = {(NSUInteger)left, (NSUInteger)top, (NSUInteger)(right - left), (NSUInteger)(bottom - top)};
		[encoder setScissorRect:rect];
		float clearDepth = (float)depth;
		m2m_bind_bytes(ctx, 1, &clearDepth, sizeof(clearDepth), YES, NO);
		if (color) {
			float clearColor[4] = {r, g, b, a};
			m2m_bind_bytes(ctx, 0, clearColor, sizeof(clearColor), NO, YES);
		}
		[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		m2m_end_encoding(ctx);
		return 1;
	}
}
