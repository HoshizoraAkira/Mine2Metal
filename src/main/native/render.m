#include "m2m_context.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

static inline MTLIndexType m2m_index_type(int32_t index_size) {
	return index_size == 2 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
}

void m2m_render_pipeline(int64_t ctx, int64_t pipeline, int64_t depth_state, int32_t cull, int32_t wireframe,
                         float depth_bias, float depth_bias_slope) {
	id<M2MRenderCommands> encoder = m2m_render_encoder(m2m_ctx(ctx));
	[encoder setRenderPipelineState:m2m_borrow(pipeline)];
	[encoder setDepthStencilState:m2m_borrow(depth_state)];
	[encoder setCullMode:cull ? MTLCullModeBack : MTLCullModeNone];
	[encoder setTriangleFillMode:wireframe ? MTLTriangleFillModeLines : MTLTriangleFillModeFill];
	[encoder setDepthBias:depth_bias slopeScale:depth_bias_slope clamp:0.0f];
}

void m2m_render_buffer(int64_t ctx, int32_t index, int64_t buffer, int64_t offset, int32_t vertex, int32_t fragment) {
	m2m_bind_buffer(m2m_ctx(ctx), (NSUInteger)index, m2m_borrow(buffer), (NSUInteger)offset, vertex != 0, fragment != 0);
}

void m2m_render_texture(int64_t handle, int32_t index, int64_t texture, int64_t sampler, int32_t vertex,
                        int32_t fragment) {
	M2MContext *ctx = m2m_ctx(handle);
	BOOL bind_vertex = vertex != 0;
	BOOL bind_fragment = fragment != 0;
	m2m_bind_texture(ctx, (NSUInteger)index, m2m_borrow(texture), bind_vertex, bind_fragment);
	if (sampler != 0) {
		m2m_bind_sampler(ctx, (NSUInteger)index, m2m_borrow(sampler), bind_vertex, bind_fragment);
	}
}

void m2m_render_bytes(int64_t ctx, int32_t index, int64_t data_address, int64_t length) {
	m2m_bind_bytes(m2m_ctx(ctx), (NSUInteger)index, (const void *)(intptr_t)data_address, (NSUInteger)length, YES, YES);
}

void m2m_render_vertex_buffer(int64_t ctx, int32_t index, int64_t buffer, int64_t offset) {
	m2m_bind_buffer(m2m_ctx(ctx), (NSUInteger)index, buffer ? m2m_borrow(buffer) : nil, (NSUInteger)offset, YES, NO);
}

void m2m_render_scissor(int64_t handle, int32_t x, int32_t y, int32_t width, int32_t height) {
	M2MContext *ctx = m2m_ctx(handle);
	// Metal rejects rects that leave the attachment, which GL simply clips.
	NSInteger left = MAX(0, x);
	NSInteger top = MAX(0, y);
	NSInteger right = MIN((NSInteger)ctx.passWidth, (NSInteger)x + width);
	NSInteger bottom = MIN((NSInteger)ctx.passHeight, (NSInteger)y + height);
	MTLScissorRect rect = {(NSUInteger)left, (NSUInteger)top, (NSUInteger)MAX(0, right - left), (NSUInteger)MAX(0, bottom - top)};
	[m2m_render_encoder(ctx) setScissorRect:rect];
}

void m2m_render_draw(int64_t ctx, int32_t primitive, int32_t first_vertex, int32_t vertex_count, int32_t instance_count,
                     int32_t first_instance) {
	// GL ignores an empty draw (the weather renderer issues one before any rain has fallen); Metal rejects it.
	if (vertex_count <= 0 || instance_count <= 0) {
		return;
	}
	[m2m_render_encoder(m2m_ctx(ctx)) drawPrimitives:(MTLPrimitiveType)primitive
	                                     vertexStart:(NSUInteger)first_vertex
	                                     vertexCount:(NSUInteger)vertex_count
	                                   instanceCount:(NSUInteger)instance_count
	                                    baseInstance:(NSUInteger)first_instance];
}

// One indexed draw; Metal 4 takes the index buffer as a GPU address and length.
static void m2m_draw_indexed(M2MContext *ctx, MTLPrimitiveType primitive, MTLIndexType type, id<MTLBuffer> indices,
                             NSUInteger offset, NSUInteger count, NSUInteger instances, NSInteger base_vertex,
                             NSUInteger first_instance) {
	if (count == 0 || instances == 0) {
		return;
	}
	if (ctx.metal4) {
		[ctx.renderEncoder4 drawIndexedPrimitives:primitive
		                               indexCount:count
		                                indexType:type
		                              indexBuffer:indices.gpuAddress + offset
		                        indexBufferLength:indices.length - offset
		                            instanceCount:instances
		                               baseVertex:base_vertex
		                             baseInstance:first_instance];
		return;
	}
	[ctx.renderEncoder drawIndexedPrimitives:primitive
	                              indexCount:count
	                               indexType:type
	                             indexBuffer:indices
	                       indexBufferOffset:offset
	                           instanceCount:instances
	                              baseVertex:base_vertex
	                            baseInstance:first_instance];
}

void m2m_render_draw_indexed(int64_t ctx, int32_t primitive, int32_t index_size, int64_t index_buffer,
                             int64_t index_offset, int32_t index_count, int32_t instance_count, int32_t base_vertex,
                             int32_t first_instance) {
	m2m_draw_indexed(m2m_ctx(ctx), (MTLPrimitiveType)primitive, m2m_index_type(index_size), m2m_borrow(index_buffer),
	                 (NSUInteger)index_offset, (NSUInteger)index_count, (NSUInteger)instance_count, base_vertex,
	                 (NSUInteger)first_instance);
}

void m2m_render_multi_draw_indexed(int64_t handle, int32_t primitive, int32_t index_size, int64_t index_buffer,
                                   int64_t index_offsets_address, int64_t index_counts_address,
                                   int64_t base_vertices_address, int32_t draw_count) {
	M2MContext *ctx = m2m_ctx(handle);
	id<MTLBuffer> indices = m2m_borrow(index_buffer);
	MTLIndexType type = m2m_index_type(index_size);
	const int64_t *offsets = (const int64_t *)(intptr_t)index_offsets_address;
	const int32_t *counts = (const int32_t *)(intptr_t)index_counts_address;
	const int32_t *base_vertices = (const int32_t *)(intptr_t)base_vertices_address;
	for (int32_t i = 0; i < draw_count; i++) {
		m2m_draw_indexed(ctx, (MTLPrimitiveType)primitive, type, indices, (NSUInteger)offsets[i], (NSUInteger)counts[i], 1,
		                 base_vertices[i], 0);
	}
}

void m2m_render_multi_draw(int64_t ctx, int32_t primitive, int64_t first_vertices_address, int64_t vertex_counts_address,
                           int32_t draw_count) {
	id<M2MRenderCommands> encoder = m2m_render_encoder(m2m_ctx(ctx));
	const int32_t *firsts = (const int32_t *)(intptr_t)first_vertices_address;
	const int32_t *counts = (const int32_t *)(intptr_t)vertex_counts_address;
	for (int32_t i = 0; i < draw_count; i++) {
		if (counts[i] > 0) {
			[encoder drawPrimitives:(MTLPrimitiveType)primitive vertexStart:(NSUInteger)firsts[i] vertexCount:(NSUInteger)counts[i]];
		}
	}
}

void m2m_render_draw_indexed_indirect(int64_t handle, int32_t primitive, int32_t index_size, int64_t index_buffer,
                                      int64_t indirect_buffer, int64_t offset, int32_t draw_count, int32_t stride) {
	M2MContext *ctx = m2m_ctx(handle);
	id<MTLBuffer> indices = m2m_borrow(index_buffer);
	id<MTLBuffer> arguments = m2m_borrow(indirect_buffer);
	MTLIndexType type = m2m_index_type(index_size);
	for (int32_t i = 0; i < draw_count; i++) {
		NSUInteger argumentOffset = (NSUInteger)(offset + (int64_t)i * stride);
		if (ctx.metal4) {
			[ctx.renderEncoder4 drawIndexedPrimitives:(MTLPrimitiveType)primitive
			                                indexType:type
			                              indexBuffer:indices.gpuAddress
			                        indexBufferLength:indices.length
			                           indirectBuffer:arguments.gpuAddress + argumentOffset];
		} else {
			[ctx.renderEncoder drawIndexedPrimitives:(MTLPrimitiveType)primitive
			                               indexType:type
			                             indexBuffer:indices
			                       indexBufferOffset:0
			                          indirectBuffer:arguments
			                    indirectBufferOffset:argumentOffset];
		}
	}
}

void m2m_render_draw_indirect(int64_t handle, int32_t primitive, int64_t indirect_buffer, int64_t offset,
                              int32_t draw_count, int32_t stride) {
	M2MContext *ctx = m2m_ctx(handle);
	id<MTLBuffer> arguments = m2m_borrow(indirect_buffer);
	for (int32_t i = 0; i < draw_count; i++) {
		NSUInteger argumentOffset = (NSUInteger)(offset + (int64_t)i * stride);
		if (ctx.metal4) {
			[ctx.renderEncoder4 drawPrimitives:(MTLPrimitiveType)primitive indirectBuffer:arguments.gpuAddress + argumentOffset];
		} else {
			[ctx.renderEncoder drawPrimitives:(MTLPrimitiveType)primitive indirectBuffer:arguments indirectBufferOffset:argumentOffset];
		}
	}
}
