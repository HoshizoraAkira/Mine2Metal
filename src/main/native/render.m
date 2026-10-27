#include "m2m_context.h"

static inline MTLIndexType m2m_index_type(int32_t index_size) {
	return index_size == 2 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
}

void m2m_render_pipeline(int64_t ctx, int64_t pipeline, int64_t depth_state, int32_t cull, int32_t wireframe,
                         float depth_bias, float depth_bias_slope) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	[encoder setRenderPipelineState:m2m_borrow(pipeline)];
	[encoder setDepthStencilState:m2m_borrow(depth_state)];
	[encoder setCullMode:cull ? MTLCullModeBack : MTLCullModeNone];
	[encoder setTriangleFillMode:wireframe ? MTLTriangleFillModeLines : MTLTriangleFillModeFill];
	[encoder setDepthBias:depth_bias slopeScale:depth_bias_slope clamp:0.0f];
}

void m2m_render_buffer(int64_t ctx, int32_t index, int64_t buffer, int64_t offset) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	[encoder setVertexBuffer:m2m_borrow(buffer) offset:(NSUInteger)offset atIndex:(NSUInteger)index];
	[encoder setFragmentBuffer:m2m_borrow(buffer) offset:(NSUInteger)offset atIndex:(NSUInteger)index];
}

void m2m_render_texture(int64_t ctx, int32_t index, int64_t texture, int64_t sampler) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	[encoder setVertexTexture:m2m_borrow(texture) atIndex:(NSUInteger)index];
	[encoder setFragmentTexture:m2m_borrow(texture) atIndex:(NSUInteger)index];
	if (sampler != 0) {
		[encoder setVertexSamplerState:m2m_borrow(sampler) atIndex:(NSUInteger)index];
		[encoder setFragmentSamplerState:m2m_borrow(sampler) atIndex:(NSUInteger)index];
	}
}

void m2m_render_bytes(int64_t ctx, int32_t index, int64_t data_address, int64_t length) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	const void *data = (const void *)(intptr_t)data_address;
	[encoder setVertexBytes:data length:(NSUInteger)length atIndex:(NSUInteger)index];
	[encoder setFragmentBytes:data length:(NSUInteger)length atIndex:(NSUInteger)index];
}

void m2m_render_vertex_buffer(int64_t ctx, int32_t index, int64_t buffer, int64_t offset) {
	[m2m_ctx(ctx).renderEncoder setVertexBuffer:m2m_borrow(buffer) offset:(NSUInteger)offset atIndex:(NSUInteger)index];
}

void m2m_render_scissor(int64_t handle, int32_t x, int32_t y, int32_t width, int32_t height) {
	M2MContext *ctx = m2m_ctx(handle);
	// Metal rejects rects that leave the attachment, which GL simply clips.
	NSInteger left = MAX(0, x);
	NSInteger top = MAX(0, y);
	NSInteger right = MIN((NSInteger)ctx.passWidth, (NSInteger)x + width);
	NSInteger bottom = MIN((NSInteger)ctx.passHeight, (NSInteger)y + height);
	MTLScissorRect rect = {(NSUInteger)left, (NSUInteger)top, (NSUInteger)MAX(0, right - left), (NSUInteger)MAX(0, bottom - top)};
	[ctx.renderEncoder setScissorRect:rect];
}

void m2m_render_draw(int64_t ctx, int32_t primitive, int32_t first_vertex, int32_t vertex_count, int32_t instance_count,
                     int32_t first_instance) {
	[m2m_ctx(ctx).renderEncoder drawPrimitives:(MTLPrimitiveType)primitive
	                               vertexStart:(NSUInteger)first_vertex
	                               vertexCount:(NSUInteger)vertex_count
	                             instanceCount:(NSUInteger)instance_count
	                              baseInstance:(NSUInteger)first_instance];
}

void m2m_render_draw_indexed(int64_t ctx, int32_t primitive, int32_t index_size, int64_t index_buffer,
                             int64_t index_offset, int32_t index_count, int32_t instance_count, int32_t base_vertex,
                             int32_t first_instance) {
	[m2m_ctx(ctx).renderEncoder drawIndexedPrimitives:(MTLPrimitiveType)primitive
	                                       indexCount:(NSUInteger)index_count
	                                        indexType:m2m_index_type(index_size)
	                                      indexBuffer:m2m_borrow(index_buffer)
	                                indexBufferOffset:(NSUInteger)index_offset
	                                    instanceCount:(NSUInteger)instance_count
	                                       baseVertex:base_vertex
	                                     baseInstance:(NSUInteger)first_instance];
}

void m2m_render_multi_draw_indexed(int64_t ctx, int32_t primitive, int32_t index_size, int64_t index_buffer,
                                   int64_t index_offsets_address, int64_t index_counts_address,
                                   int64_t base_vertices_address, int32_t draw_count) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	id<MTLBuffer> indices = m2m_borrow(index_buffer);
	MTLIndexType type = m2m_index_type(index_size);
	const int64_t *offsets = (const int64_t *)(intptr_t)index_offsets_address;
	const int32_t *counts = (const int32_t *)(intptr_t)index_counts_address;
	const int32_t *base_vertices = (const int32_t *)(intptr_t)base_vertices_address;
	for (int32_t i = 0; i < draw_count; i++) {
		[encoder drawIndexedPrimitives:(MTLPrimitiveType)primitive
		                    indexCount:(NSUInteger)counts[i]
		                     indexType:type
		                   indexBuffer:indices
		             indexBufferOffset:(NSUInteger)offsets[i]
		                 instanceCount:1
		                    baseVertex:base_vertices[i]
		                  baseInstance:0];
	}
}

void m2m_render_multi_draw(int64_t ctx, int32_t primitive, int64_t first_vertices_address, int64_t vertex_counts_address,
                           int32_t draw_count) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	const int32_t *firsts = (const int32_t *)(intptr_t)first_vertices_address;
	const int32_t *counts = (const int32_t *)(intptr_t)vertex_counts_address;
	for (int32_t i = 0; i < draw_count; i++) {
		[encoder drawPrimitives:(MTLPrimitiveType)primitive vertexStart:(NSUInteger)firsts[i] vertexCount:(NSUInteger)counts[i]];
	}
}

void m2m_render_draw_indexed_indirect(int64_t ctx, int32_t primitive, int32_t index_size, int64_t index_buffer,
                                      int64_t indirect_buffer, int64_t offset, int32_t draw_count, int32_t stride) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	id<MTLBuffer> indices = m2m_borrow(index_buffer);
	id<MTLBuffer> arguments = m2m_borrow(indirect_buffer);
	MTLIndexType type = m2m_index_type(index_size);
	for (int32_t i = 0; i < draw_count; i++) {
		[encoder drawIndexedPrimitives:(MTLPrimitiveType)primitive
		                     indexType:type
		                   indexBuffer:indices
		             indexBufferOffset:0
		                indirectBuffer:arguments
		          indirectBufferOffset:(NSUInteger)(offset + (int64_t)i * stride)];
	}
}

void m2m_render_draw_indirect(int64_t ctx, int32_t primitive, int64_t indirect_buffer, int64_t offset,
                              int32_t draw_count, int32_t stride) {
	id<MTLRenderCommandEncoder> encoder = m2m_ctx(ctx).renderEncoder;
	id<MTLBuffer> arguments = m2m_borrow(indirect_buffer);
	for (int32_t i = 0; i < draw_count; i++) {
		[encoder drawPrimitives:(MTLPrimitiveType)primitive
		         indirectBuffer:arguments
		   indirectBufferOffset:(NSUInteger)(offset + (int64_t)i * stride)];
	}
}
