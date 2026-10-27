#include "m2m_context.h"

int64_t m2m_library_create(int64_t ctx, const char *source, char *error, int64_t error_capacity) {
	@autoreleasepool {
		NSError *compileError = nil;
		MTLCompileOptions *options = [MTLCompileOptions new];
		options.languageVersion = MTLLanguageVersion3_0;
		id<MTLLibrary> library = [m2m_ctx(ctx).device newLibraryWithSource:@(source) options:options error:&compileError];
		if (!library) {
			m2m_copy_string(error, error_capacity, compileError.localizedDescription);
			return 0;
		}
		return m2m_retain(library);
	}
}

int64_t m2m_pipeline_create(int64_t ctx, int64_t vertex_library, const char *vertex_entry, int64_t fragment_library,
                            const char *fragment_entry, const int32_t *layout, int32_t depth_format,
                            int32_t stencil_format, const char *label, char *error, int64_t error_capacity) {
	@autoreleasepool {
		MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
		descriptor.label = @(label);
		descriptor.vertexFunction = [(id<MTLLibrary>)m2m_borrow(vertex_library) newFunctionWithName:@(vertex_entry)];
		descriptor.fragmentFunction = [(id<MTLLibrary>)m2m_borrow(fragment_library) newFunctionWithName:@(fragment_entry)];
		if (!descriptor.vertexFunction || !descriptor.fragmentFunction) {
			m2m_copy_string(error, error_capacity, @"Entry point not found in the translated library");
			return 0;
		}

		MTLVertexDescriptor *vertex = [MTLVertexDescriptor vertexDescriptor];
		const int32_t *cursor = layout;
		int32_t buffer_count = *cursor++;
		for (int32_t i = 0; i < buffer_count; i++, cursor += 4) {
			MTLVertexBufferLayoutDescriptor *buffer = vertex.layouts[(NSUInteger)cursor[0]];
			buffer.stride = (NSUInteger)cursor[1];
			buffer.stepFunction = (MTLVertexStepFunction)cursor[2];
			buffer.stepRate = (NSUInteger)cursor[3];
		}
		int32_t attribute_count = *cursor++;
		for (int32_t i = 0; i < attribute_count; i++, cursor += 4) {
			MTLVertexAttributeDescriptor *attribute = vertex.attributes[(NSUInteger)cursor[0]];
			attribute.bufferIndex = (NSUInteger)cursor[1];
			attribute.offset = (NSUInteger)cursor[2];
			attribute.format = (MTLVertexFormat)cursor[3];
		}
		if (attribute_count > 0) {
			descriptor.vertexDescriptor = vertex;
		}

		int32_t color_count = *cursor++;
		for (int32_t i = 0; i < color_count; i++, cursor += 9) {
			MTLRenderPipelineColorAttachmentDescriptor *color = descriptor.colorAttachments[(NSUInteger)i];
			color.pixelFormat = (MTLPixelFormat)cursor[0];
			color.writeMask = (MTLColorWriteMask)cursor[1];
			color.blendingEnabled = cursor[2] != 0;
			color.sourceRGBBlendFactor = (MTLBlendFactor)cursor[3];
			color.destinationRGBBlendFactor = (MTLBlendFactor)cursor[4];
			color.rgbBlendOperation = (MTLBlendOperation)cursor[5];
			color.sourceAlphaBlendFactor = (MTLBlendFactor)cursor[6];
			color.destinationAlphaBlendFactor = (MTLBlendFactor)cursor[7];
			color.alphaBlendOperation = (MTLBlendOperation)cursor[8];
		}

		descriptor.depthAttachmentPixelFormat = (MTLPixelFormat)depth_format;
		descriptor.stencilAttachmentPixelFormat = (MTLPixelFormat)stencil_format;

		NSError *stateError = nil;
		id<MTLRenderPipelineState> state = [m2m_ctx(ctx).device newRenderPipelineStateWithDescriptor:descriptor
		                                                                                        error:&stateError];
		if (!state) {
			m2m_copy_string(error, error_capacity, stateError.localizedDescription);
			return 0;
		}
		return m2m_retain(state);
	}
}

int64_t m2m_depth_state_create(int64_t ctx, int32_t compare, int32_t write) {
	@autoreleasepool {
		MTLDepthStencilDescriptor *descriptor = [MTLDepthStencilDescriptor new];
		descriptor.depthCompareFunction = (MTLCompareFunction)compare;
		descriptor.depthWriteEnabled = write != 0;
		return m2m_retain([m2m_ctx(ctx).device newDepthStencilStateWithDescriptor:descriptor]);
	}
}

int64_t m2m_buffer_texture_create(int64_t buffer_handle, int64_t offset, int64_t length, int32_t pixel_format,
                                  int32_t bytes_per_pixel) {
	@autoreleasepool {
		id<MTLBuffer> buffer = m2m_borrow(buffer_handle);
		NSUInteger width = (NSUInteger)(length / bytes_per_pixel);
		MTLTextureDescriptor *descriptor =
			[MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:(MTLPixelFormat)pixel_format
			                                                       width:width
			                                             resourceOptions:buffer.resourceOptions
			                                                       usage:MTLTextureUsageShaderRead];
		id<MTLTexture> texture = [buffer newTextureWithDescriptor:descriptor
		                                                   offset:(NSUInteger)offset
		                                              bytesPerRow:(NSUInteger)length];
		return texture ? m2m_retain(texture) : 0;
	}
}
