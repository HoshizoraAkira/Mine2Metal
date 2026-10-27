#ifndef MINE2METAL_H
#define MINE2METAL_H

#include <stddef.h>
#include <stdint.h>

// C ABI called from Java through FFM (dev.mine2metal.metal.MetalNative).
// Objective-C objects cross as retained pointers in an int64_t; 0 means none.
// Every function that takes a ctx must be called from the render thread.

#define M2M_EXPORT __attribute__((visibility("default")))

// Device probe

// Returns a retained id<MTLDevice> for the system default GPU, or 0. With validation set, Metal's API
// validation layer is switched on first; it only takes effect if no Metal device existed in the process yet.
M2M_EXPORT int64_t m2m_device_create(int32_t validation);

// Writes the device name as NUL-terminated UTF-8 into out (truncated to capacity).
// Returns the full name length in bytes, excluding the terminator.
M2M_EXPORT int64_t m2m_device_name(int64_t device, char *out, int64_t capacity);

// 1 when Metal's validation layer wraps the device, else 0.
M2M_EXPORT int32_t m2m_device_validation_active(int64_t device);

// 1 when the device supports MTLGPUFamilyMetal3, else 0.
M2M_EXPORT int32_t m2m_device_supports_metal3(int64_t device);
// 1 on macOS 26 or newer with a GPU in the Metal 4 family (Apple M1 or later).
M2M_EXPORT int32_t m2m_device_supports_metal4(int64_t device);

// Releases any object handed out by this library. Passing 0 is a no-op.
M2M_EXPORT void m2m_release(int64_t object);
// As m2m_release, for buffers, textures and anything else the GPU may use: with Metal 4 it also leaves the
// context's residency set.
M2M_EXPORT void m2m_resource_release(int64_t ctx, int64_t object);

// Context: device, command queue and the command buffer being recorded

// Returns a retained context for device, or 0 with a message written to error. With debug set, command
// buffers record per-encoder execution status so GPU errors name the failing encoder.
// metal4 selects the Metal 4 command model (MTL4CommandQueue, residency sets, argument tables); it needs macOS 26
// and m2m_device_supports_metal4.
M2M_EXPORT int64_t m2m_context_create(int64_t device, int32_t debug, int32_t metal4, char *error, int64_t error_capacity);
// Number of command buffers that have failed on the GPU so far.
M2M_EXPORT int64_t m2m_context_error_count(int64_t ctx);
// Writes the messages of failed command buffers since the last call, one per line, then forgets them.
// Returns 1 if any of them reported a lost or removed device.
M2M_EXPORT int32_t m2m_context_take_errors(int64_t ctx, char *out, int64_t capacity);
M2M_EXPORT int64_t m2m_context_max_buffer_length(int64_t ctx);

// Ends any open encoder and commits the current command buffer. Returns its submit index
// (1, 2, ...); an empty frame still gets an index so fences stay simple. A non-null label
// accumulates the buffer's GPU time under that name (see m2m_context_take_label_times).
M2M_EXPORT int64_t m2m_context_commit(int64_t ctx, const char *label);
// Highest submit index the GPU has finished.
M2M_EXPORT int64_t m2m_context_completed(int64_t ctx);
// Render and blit encoders created so far, and passes that continued an existing render encoder.
M2M_EXPORT void m2m_context_encoder_counts(int64_t ctx, int64_t *render, int64_t *blit, int64_t *merged);
// Writes "label=seconds" lines (largest first) for labelled commits since the last call, then resets.
M2M_EXPORT int64_t m2m_context_take_label_times(int64_t ctx, char *out, int64_t capacity);
// Total GPU execution time of all finished command buffers, from Metal's GPU timestamps.
M2M_EXPORT double m2m_context_gpu_seconds(int64_t ctx);
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

// Render passes: describe the attachments, then open. Closing a pass keeps its encoder open
// until the next blit, present, commit or incompatible pass, so consecutive passes on the same
// attachments share one encoder and skip a store and reload of every attachment.

M2M_EXPORT void m2m_pass_color(int64_t ctx, int32_t index, int64_t texture, int32_t clear, float r, float g, float b,
                               float a);
M2M_EXPORT void m2m_pass_depth(int64_t ctx, int64_t texture, int32_t has_stencil, int32_t clear, double depth);
// label may be null; it names the encoder in GPU captures and traces.
M2M_EXPORT void m2m_pass_open(int64_t ctx, const char *label);
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

// MetalFX

M2M_EXPORT int32_t m2m_metalfx_spatial_supported(int64_t ctx);
// Upscales input into output (both RGBA, sizes taken from the textures) on the current command buffer.
// Returns 0 if MetalFX could not create a scaler for these sizes and formats.
M2M_EXPORT int32_t m2m_metalfx_spatial_upscale(int64_t ctx, int64_t input, int64_t output);

// Clears a region of one mip level of a color and/or depth texture (either handle may be 0), keeping the rest and the
// stencil aspect. Returns 0 if the clear pipeline could not be created.
M2M_EXPORT int32_t m2m_clear_region(int64_t ctx, int64_t color, float r, float g, float b, float a, int64_t depth,
                                    int32_t has_stencil, double depth_value, int32_t x, int32_t y, int32_t width,
                                    int32_t height, int32_t mip_level);

M2M_EXPORT int32_t m2m_metalfx_temporal_supported(int64_t ctx);
// The range of output/input size ratios the temporal scaler supports on this device.
M2M_EXPORT void m2m_metalfx_temporal_scale_range(int64_t ctx, float *min_scale, float *max_scale);
// Upscales color (rendered with a jitter of jitter_x, jitter_y pixels) into output with MetalFX temporal scaling.
// matrices holds 36 floats, column-major: this frame's inverse unjittered view-projection, the previous frame's
// view-projection, and the camera's movement since then (xyz, then padding). Motion vectors are derived from these
// and depth. reset drops the history (first frame, cuts, teleports). Returns 0 if MetalFX refused the textures.
M2M_EXPORT int32_t m2m_metalfx_temporal_upscale(int64_t ctx, int64_t color, int64_t depth, int64_t output,
                                                const float *matrices, float jitter_x, float jitter_y, int32_t reset);

// Timestamps

// A pool of GPU timestamps, released with m2m_release.
M2M_EXPORT int64_t m2m_timestamp_pool_create(int64_t size);
// Copies count values from first on, in nanoseconds of host time; -1 where one is not available yet.
M2M_EXPORT void m2m_timestamp_pool_read(int64_t pool, int64_t first, int64_t count, int64_t *out);
// Records when the GPU finishes everything encoded so far; ends the current command buffer.
M2M_EXPORT void m2m_timestamp_write(int64_t ctx, int64_t pool, int64_t index);
// Now, on the clock timestamps use.
M2M_EXPORT int64_t m2m_host_time_nanos(void);

// Presentation

// Copies up to capacity on-screen times (seconds) of presented frames since the last call, 0 for frames the
// compositor dropped; returns how many.
M2M_EXPORT int64_t m2m_take_presented_times(int64_t ctx, double *out, int64_t capacity);

// With vsync off this starts a present thread that shows the newest finished frame once per refresh; with vsync
// on frames are presented from the render thread and nextDrawable caps the frame rate.
M2M_EXPORT void m2m_layer_configure(int64_t ctx, int64_t layer, int32_t width, int32_t height, int32_t vsync);
// Shows or hides the Metal Performance HUD on the layer.
M2M_EXPORT void m2m_layer_set_hud(int64_t layer, int32_t enabled);
// Stops the present thread before the layer goes away.
M2M_EXPORT void m2m_layer_detach(int64_t ctx);
// Hands the finished frame to the display: with vsync on it is presented now (1, or 0 without a drawable,
// for example in a hidden window); with vsync off it is published for the present thread (1), or skipped
// (2) while the previous frame's copy is still in flight.
M2M_EXPORT int32_t m2m_present(int64_t ctx, int64_t layer, int64_t texture);

#endif
