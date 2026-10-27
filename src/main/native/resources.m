#include "m2m_context.h"

int64_t m2m_buffer_create(int64_t ctx, int64_t size) {
	@autoreleasepool {
		// Rounded up to 16 bytes: a std140 block ending in a vec3 is 12 bytes in GL, but its MSL struct is 16, and
		// Metal must not read past the buffer's end. This also keeps zero-length buffers, which Metal rejects, valid.
		NSUInteger length = ((NSUInteger)(size > 0 ? size : 1) + 15) & ~(NSUInteger)15;
		id<MTLBuffer> buffer = [m2m_ctx(ctx).device newBufferWithLength:length
		                                                        options:MTLResourceStorageModeShared];
		m2m_track(m2m_ctx(ctx), buffer);
		return buffer ? m2m_retain(buffer) : 0;
	}
}

int64_t m2m_buffer_contents(int64_t buffer) {
	return (int64_t)(intptr_t)[(id<MTLBuffer>)m2m_borrow(buffer) contents];
}

int64_t m2m_texture_create(int64_t ctx, int32_t pixel_format, int32_t width, int32_t height, int32_t layers,
                           int32_t mip_levels, int32_t flags) {
	@autoreleasepool {
		MTLTextureDescriptor *descriptor = [MTLTextureDescriptor new];
		descriptor.pixelFormat = (MTLPixelFormat)pixel_format;
		descriptor.width = (NSUInteger)width;
		descriptor.height = (NSUInteger)height;
		descriptor.mipmapLevelCount = (NSUInteger)mip_levels;
		descriptor.storageMode = MTLStorageModePrivate;
		// Every texture may be sampled, if only by the present pass.
		descriptor.usage = MTLTextureUsageShaderRead;
		if (flags & M2M_TEXTURE_RENDER_TARGET) {
			descriptor.usage |= MTLTextureUsageRenderTarget;
		}

		if ((flags & M2M_TEXTURE_CUBE) && layers == 6) {
			descriptor.textureType = MTLTextureTypeCube;
		} else if (layers > 1) {
			descriptor.textureType = MTLTextureType2DArray;
			descriptor.arrayLength = (NSUInteger)layers;
		} else {
			descriptor.textureType = MTLTextureType2D;
		}

		id<MTLTexture> texture = [m2m_ctx(ctx).device newTextureWithDescriptor:descriptor];
		// Views of it (m2m_texture_view_create) share its memory, so only the texture itself is made resident.
		m2m_track(m2m_ctx(ctx), texture);
		return texture ? m2m_retain(texture) : 0;
	}
}

int64_t m2m_texture_view_create(int64_t texture_handle, int32_t base_mip, int32_t mip_levels) {
	@autoreleasepool {
		id<MTLTexture> texture = m2m_borrow(texture_handle);
		NSUInteger slices = texture.textureType == MTLTextureTypeCube ? 6 : texture.arrayLength;
		id<MTLTexture> view = [texture newTextureViewWithPixelFormat:texture.pixelFormat
		                                                 textureType:texture.textureType
		                                                      levels:NSMakeRange((NSUInteger)base_mip, (NSUInteger)mip_levels)
		                                                      slices:NSMakeRange(0, slices)];
		return view ? m2m_retain(view) : 0;
	}
}

int64_t m2m_sampler_create(int64_t ctx, int32_t address_u, int32_t address_v, int32_t min_filter, int32_t mag_filter,
                           int32_t mip_filter, int32_t max_anisotropy, float max_lod) {
	@autoreleasepool {
		MTLSamplerDescriptor *descriptor = [MTLSamplerDescriptor new];
		// Needed to bind it through a Metal 4 argument table.
		descriptor.supportArgumentBuffers = YES;
		descriptor.sAddressMode = address_u == 0 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
		descriptor.tAddressMode = address_v == 0 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
		descriptor.minFilter = min_filter == 0 ? MTLSamplerMinMagFilterNearest : MTLSamplerMinMagFilterLinear;
		descriptor.magFilter = mag_filter == 0 ? MTLSamplerMinMagFilterNearest : MTLSamplerMinMagFilterLinear;
		descriptor.mipFilter = mip_filter == 0   ? MTLSamplerMipFilterNotMipmapped
		                       : mip_filter == 1 ? MTLSamplerMipFilterNearest
		                                         : MTLSamplerMipFilterLinear;
		descriptor.maxAnisotropy = (NSUInteger)(max_anisotropy < 1 ? 1 : max_anisotropy);
		descriptor.lodMaxClamp = max_lod;
		id<MTLSamplerState> sampler = [m2m_ctx(ctx).device newSamplerStateWithDescriptor:descriptor];
		return sampler ? m2m_retain(sampler) : 0;
	}
}
