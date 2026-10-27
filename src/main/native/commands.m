#include "m2m_context.h"

void m2m_blit_buffer(int64_t ctx, int64_t src, int64_t src_offset, int64_t dst, int64_t dst_offset, int64_t size) {
	@autoreleasepool {
		[m2m_blit_encoder(m2m_ctx(ctx)) copyFromBuffer:m2m_borrow(src)
		                                  sourceOffset:(NSUInteger)src_offset
		                                      toBuffer:m2m_borrow(dst)
		                             destinationOffset:(NSUInteger)dst_offset
		                                          size:(NSUInteger)size];
	}
}

void m2m_blit_buffer_to_texture(int64_t ctx, int64_t buffer, int64_t offset, int64_t bytes_per_row,
                                int64_t bytes_per_image, int64_t texture, int32_t layer, int32_t mip, int32_t x,
                                int32_t y, int32_t width, int32_t height) {
	@autoreleasepool {
		[m2m_blit_encoder(m2m_ctx(ctx)) copyFromBuffer:m2m_borrow(buffer)
		                                  sourceOffset:(NSUInteger)offset
		                             sourceBytesPerRow:(NSUInteger)bytes_per_row
		                           sourceBytesPerImage:(NSUInteger)bytes_per_image
		                                    sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
		                                     toTexture:m2m_borrow(texture)
		                              destinationSlice:(NSUInteger)layer
		                              destinationLevel:(NSUInteger)mip
		                             destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)];
	}
}

void m2m_blit_texture_to_buffer(int64_t ctx, int64_t texture, int32_t mip, int32_t x, int32_t y, int32_t width,
                                int32_t height, int64_t buffer, int64_t offset, int64_t bytes_per_row) {
	@autoreleasepool {
		[m2m_blit_encoder(m2m_ctx(ctx)) copyFromTexture:m2m_borrow(texture)
		                                    sourceSlice:0
		                                    sourceLevel:(NSUInteger)mip
		                                   sourceOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)
		                                     sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
		                                       toBuffer:m2m_borrow(buffer)
		                              destinationOffset:(NSUInteger)offset
		                         destinationBytesPerRow:(NSUInteger)bytes_per_row
		                       destinationBytesPerImage:(NSUInteger)(bytes_per_row * height)];
	}
}

void m2m_blit_texture_to_texture(int64_t ctx, int64_t src, int64_t dst, int32_t mip, int32_t dst_x, int32_t dst_y,
                                 int32_t src_x, int32_t src_y, int32_t width, int32_t height) {
	@autoreleasepool {
		[m2m_blit_encoder(m2m_ctx(ctx)) copyFromTexture:m2m_borrow(src)
		                                    sourceSlice:0
		                                    sourceLevel:(NSUInteger)mip
		                                   sourceOrigin:MTLOriginMake((NSUInteger)src_x, (NSUInteger)src_y, 0)
		                                     sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
		                                      toTexture:m2m_borrow(dst)
		                               destinationSlice:0
		                               destinationLevel:(NSUInteger)mip
		                              destinationOrigin:MTLOriginMake((NSUInteger)dst_x, (NSUInteger)dst_y, 0)];
	}
}

static MTLRenderPassDescriptor *m2m_pending_pass(M2MContext *ctx) {
	if (!ctx.pendingPass) {
		ctx.pendingPass = [MTLRenderPassDescriptor renderPassDescriptor];
	}
	return ctx.pendingPass;
}

void m2m_pass_color(int64_t ctx, int32_t index, int64_t texture, int32_t clear, float r, float g, float b, float a) {
	@autoreleasepool {
		MTLRenderPassColorAttachmentDescriptor *attachment = m2m_pending_pass(m2m_ctx(ctx)).colorAttachments[index];
		attachment.texture = m2m_borrow(texture);
		attachment.loadAction = clear ? MTLLoadActionClear : MTLLoadActionLoad;
		attachment.clearColor = MTLClearColorMake(r, g, b, a);
		attachment.storeAction = MTLStoreActionStore;
	}
}

void m2m_pass_depth(int64_t ctx, int64_t texture, int32_t has_stencil, int32_t clear, double depth) {
	@autoreleasepool {
		MTLRenderPassDescriptor *pass = m2m_pending_pass(m2m_ctx(ctx));
		pass.depthAttachment.texture = m2m_borrow(texture);
		pass.depthAttachment.loadAction = clear ? MTLLoadActionClear : MTLLoadActionLoad;
		pass.depthAttachment.clearDepth = depth;
		pass.depthAttachment.storeAction = MTLStoreActionStore;
		if (has_stencil) {
			pass.stencilAttachment.texture = pass.depthAttachment.texture;
			pass.stencilAttachment.loadAction = clear ? MTLLoadActionClear : MTLLoadActionLoad;
			pass.stencilAttachment.clearStencil = 0;
			pass.stencilAttachment.storeAction = MTLStoreActionStore;
		}
	}
}

void m2m_pass_open(int64_t handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		MTLRenderPassDescriptor *pass = m2m_pending_pass(ctx);
		id<MTLTexture> sizing = pass.colorAttachments[0].texture ?: pass.depthAttachment.texture;
		ctx.passWidth = sizing.width;
		ctx.passHeight = sizing.height;

		m2m_end_encoding(ctx);
		ctx.renderEncoder = [m2m_command_buffer(ctx) renderCommandEncoderWithDescriptor:pass];
		ctx.pendingPass = nil;
		// renderpearl's front faces are counter-clockwise in GL window space; flipping vertex Y mirrors that.
		[ctx.renderEncoder setFrontFacingWinding:MTLWindingClockwise];
	}
}

void m2m_pass_close(int64_t handle) {
	@autoreleasepool {
		m2m_end_encoding(m2m_ctx(handle));
	}
}
