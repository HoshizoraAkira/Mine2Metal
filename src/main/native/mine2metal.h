#ifndef MINE2METAL_H
#define MINE2METAL_H

#include <stddef.h>
#include <stdint.h>

// C ABI called from Java through FFM (dev.mine2metal.metal.MetalNative).
// Objective-C objects cross as retained pointers in an int64_t; 0 means none.
// Every function that takes a ctx must be called from the render thread.

#define M2M_EXPORT __attribute__((visibility("default")))

// Device probe

// Returns a retained id<MTLDevice> for the system default GPU, or 0.
M2M_EXPORT int64_t m2m_device_create(void);

// Writes the device name as NUL-terminated UTF-8 into out (truncated to capacity).
// Returns the full name length in bytes, excluding the terminator.
M2M_EXPORT int64_t m2m_device_name(int64_t device, char *out, int64_t capacity);

// 1 when the device supports MTLGPUFamilyMetal3, else 0.
M2M_EXPORT int32_t m2m_device_supports_metal3(int64_t device);

// Releases any object handed out by this library. Passing 0 is a no-op.
M2M_EXPORT void m2m_release(int64_t object);

// Context: device, command queue and the command buffer being recorded

// Returns a retained context for device, or 0 with a message written to error.
M2M_EXPORT int64_t m2m_context_create(int64_t device, char *error, int64_t error_capacity);
M2M_EXPORT int64_t m2m_context_max_buffer_length(int64_t ctx);

// Ends any open encoder and commits the current command buffer. Returns its submit index
// (1, 2, ...); an empty frame still gets an index so fences stay simple.
M2M_EXPORT int64_t m2m_context_commit(int64_t ctx);
// Highest submit index the GPU has finished.
M2M_EXPORT int64_t m2m_context_completed(int64_t ctx);
// Blocks until submit index has finished (no-op for indices not committed yet).
M2M_EXPORT void m2m_context_wait(int64_t ctx, int64_t index);

// Resources

// Shared-storage buffer: on Apple silicon the CPU mapping is the GPU memory.
M2M_EXPORT int64_t m2m_buffer_create(int64_t ctx, int64_t size);
M2M_EXPORT int64_t m2m_buffer_contents(int64_t buffer);

#define M2M_TEXTURE_RENDER_TARGET 1
#define M2M_TEXTURE_CUBE 2

M2M_EXPORT int64_t m2m_texture_create(int64_t ctx, int32_t pixel_format, int32_t width, int32_t height,
                                      int32_t layers, int32_t mip_levels, int32_t flags);
M2M_EXPORT int64_t m2m_texture_view_create(int64_t texture, int32_t base_mip, int32_t mip_levels);

// address: 0 repeat, 1 clamp to edge; filter: 0 nearest, 1 linear; mip: 0 none, 1 nearest, 2 linear
M2M_EXPORT int64_t m2m_sampler_create(int64_t ctx, int32_t address_u, int32_t address_v, int32_t min_filter,
                                      int32_t mag_filter, int32_t mip_filter, int32_t max_anisotropy, float max_lod);

// Blit commands (recorded into the current command buffer, in order with render passes)

M2M_EXPORT void m2m_blit_buffer(int64_t ctx, int64_t src, int64_t src_offset, int64_t dst, int64_t dst_offset,
                                int64_t size);
M2M_EXPORT void m2m_blit_buffer_to_texture(int64_t ctx, int64_t buffer, int64_t offset, int64_t bytes_per_row,
                                           int64_t bytes_per_image, int64_t texture, int32_t layer, int32_t mip,
                                           int32_t x, int32_t y, int32_t width, int32_t height);
M2M_EXPORT void m2m_blit_texture_to_buffer(int64_t ctx, int64_t texture, int32_t mip, int32_t x, int32_t y,
                                           int32_t width, int32_t height, int64_t buffer, int64_t offset,
                                           int64_t bytes_per_row);
M2M_EXPORT void m2m_blit_texture_to_texture(int64_t ctx, int64_t src, int64_t dst, int32_t mip, int32_t dst_x,
                                            int32_t dst_y, int32_t src_x, int32_t src_y, int32_t width,
                                            int32_t height);

// Render passes: describe the attachments, then open. A pass stays open until the next
// blit, pass, present or commit.

M2M_EXPORT void m2m_pass_color(int64_t ctx, int32_t index, int64_t texture, int32_t clear, float r, float g, float b,
                               float a);
M2M_EXPORT void m2m_pass_depth(int64_t ctx, int64_t texture, int32_t has_stencil, int32_t clear, double depth);
M2M_EXPORT void m2m_pass_open(int64_t ctx);
M2M_EXPORT void m2m_pass_close(int64_t ctx);

// Pipelines

// Compiles MSL source. Returns a retained id<MTLLibrary>, or 0 with a message in error.
M2M_EXPORT int64_t m2m_library_create(int64_t ctx, const char *source, char *error, int64_t error_capacity);

// layout is a flat int array:
//   buffer_count, then per buffer: index, stride, step_function, step_rate
//   attribute_count, then per attribute: location, buffer_index, offset, vertex_format
//   color_count, then per target: pixel_format, write_mask, blend, rgb_src, rgb_dst, rgb_op, alpha_src, alpha_dst, alpha_op
// depth_format / stencil_format are MTLPixelFormat values or 0 for none.
M2M_EXPORT int64_t m2m_pipeline_create(int64_t ctx, int64_t vertex_library, const char *vertex_entry,
                                       int64_t fragment_library, const char *fragment_entry, const int32_t *layout,
                                       int32_t depth_format, int32_t stencil_format, const char *label, char *error,
                                       int64_t error_capacity);
M2M_EXPORT int64_t m2m_depth_state_create(int64_t ctx, int32_t compare, int32_t write);

// Draw state for the open render pass. Resources are bound to both the vertex and fragment stage.

M2M_EXPORT void m2m_render_pipeline(int64_t ctx, int64_t pipeline, int64_t depth_state, int32_t cull, int32_t wireframe,
                                    float depth_bias, float depth_bias_slope);
M2M_EXPORT void m2m_render_buffer(int64_t ctx, int32_t index, int64_t buffer, int64_t offset);
M2M_EXPORT void m2m_render_texture(int64_t ctx, int32_t index, int64_t texture, int64_t sampler);
M2M_EXPORT void m2m_render_bytes(int64_t ctx, int32_t index, int64_t data_address, int64_t length);
M2M_EXPORT void m2m_render_vertex_buffer(int64_t ctx, int32_t index, int64_t buffer, int64_t offset);
// In framebuffer rows from the start of memory, which with flipped vertex Y is GL's bottom-left origin.
M2M_EXPORT void m2m_render_scissor(int64_t ctx, int32_t x, int32_t y, int32_t width, int32_t height);

M2M_EXPORT void m2m_render_draw(int64_t ctx, int32_t primitive, int32_t first_vertex, int32_t vertex_count,
                                int32_t instance_count, int32_t first_instance);
// index_size is 2 or 4 bytes; index_offset is in bytes.
M2M_EXPORT void m2m_render_draw_indexed(int64_t ctx, int32_t primitive, int32_t index_size, int64_t index_buffer,
                                        int64_t index_offset, int32_t index_count, int32_t instance_count,
                                        int32_t base_vertex, int32_t first_instance);
// Batched draws: one call per batch instead of per draw, which matters for Sodium's thousands of chunk draws.
// index_offsets_address points to draw_count int64 byte offsets; counts and base vertices to int32 arrays.
M2M_EXPORT void m2m_render_multi_draw_indexed(int64_t ctx, int32_t primitive, int32_t index_size, int64_t index_buffer,
                                              int64_t index_offsets_address, int64_t index_counts_address,
                                              int64_t base_vertices_address, int32_t draw_count);
M2M_EXPORT void m2m_render_multi_draw(int64_t ctx, int32_t primitive, int64_t first_vertices_address,
                                      int64_t vertex_counts_address, int32_t draw_count);
// draw_count indirect argument records, stride bytes apart, starting at offset.
M2M_EXPORT void m2m_render_draw_indexed_indirect(int64_t ctx, int32_t primitive, int32_t index_size,
                                                 int64_t index_buffer, int64_t indirect_buffer, int64_t offset,
                                                 int32_t draw_count, int32_t stride);
M2M_EXPORT void m2m_render_draw_indirect(int64_t ctx, int32_t primitive, int64_t indirect_buffer, int64_t offset,
                                         int32_t draw_count, int32_t stride);

// A texture view of buffer memory, for texel-buffer uniforms.
M2M_EXPORT int64_t m2m_buffer_texture_create(int64_t buffer, int64_t offset, int64_t length, int32_t pixel_format,
                                             int32_t bytes_per_pixel);

// Presentation

M2M_EXPORT void m2m_layer_configure(int64_t ctx, int64_t layer, int32_t width, int32_t height, int32_t vsync);
// Draws texture onto the layer's next drawable and schedules its present on the current
// command buffer. Returns 1 when presented, 0 when no drawable is available (for example a
// hidden window), and 2 when skipped because vsync is off and this refresh already has a frame.
M2M_EXPORT int32_t m2m_present(int64_t ctx, int64_t layer, int64_t texture);

#endif
