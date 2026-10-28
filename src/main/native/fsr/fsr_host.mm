#include "fsr_host.h"

#include "fsr_shaders.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

@implementation M2MFfxHost

- (void)didCreateResource:(id<MTLResource>)resource {
	m2m_track(self.ctx, (id<MTLAllocation>)resource);
}

- (void)releaseResource:(id<MTLResource>)resource {
	m2m_retire(self.ctx, resource);
}

- (void)setComputePipeline:(id<MTLComputePipelineState>)pipeline {
	m2m_compute_begin(self.ctx, pipeline);
}

- (void)setTexture:(id<MTLTexture>)texture atIndex:(NSUInteger)index {
	m2m_bind_texture(self.ctx, index, texture, NO, NO);
}

- (void)setBuffer:(id<MTLBuffer>)buffer offset:(NSUInteger)offset atIndex:(NSUInteger)index {
	m2m_bind_buffer(self.ctx, index, buffer, offset, NO, NO);
}

- (void)setBytes:(const void *)bytes length:(NSUInteger)length atIndex:(NSUInteger)index {
	m2m_bind_bytes(self.ctx, index, bytes, length, NO, NO);
}

- (void)setSamplerState:(id<MTLSamplerState>)sampler atIndex:(NSUInteger)index {
	m2m_bind_sampler(self.ctx, index, sampler, NO, NO);
}

- (void)dispatchThreadgroups:(MTLSize)threadgroups threadsPerThreadgroup:(MTLSize)threadsPerThreadgroup {
	m2m_compute_dispatch(self.ctx, threadgroups, threadsPerThreadgroup);
}

- (void)clearTexture:(id<MTLTexture>)texture color:(MTLClearColor)color {
	for (NSUInteger level = 0; level < texture.mipmapLevelCount; level++) {
		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture = texture;
		pass.colorAttachments[0].level = level;
		pass.colorAttachments[0].loadAction = MTLLoadActionClear;
		pass.colorAttachments[0].storeAction = MTLStoreActionStore;
		pass.colorAttachments[0].clearColor = color;
		m2m_open_render_encoder(self.ctx, pass, @"FidelityFX clear");
		m2m_end_encoding(self.ctx);
	}
}

- (void)fillBuffer:(id<MTLBuffer>)buffer value:(uint8_t)value {
	[m2m_copier(self.ctx) fillBuffer:buffer range:NSMakeRange(0, buffer.length) value:value];
}

- (void)copyBuffer:(id<MTLBuffer>)source offset:(NSUInteger)sourceOffset toBuffer:(id<MTLBuffer>)destination
            offset:(NSUInteger)destinationOffset size:(NSUInteger)size {
	[m2m_copier(self.ctx) copyFromBuffer:source sourceOffset:sourceOffset toBuffer:destination destinationOffset:destinationOffset size:size];
}

- (void)copyTexture:(id<MTLTexture>)source toTexture:(id<MTLTexture>)destination {
	[m2m_copier(self.ctx) copyFromTexture:source toTexture:destination];
}

- (void)endEncoding {
	m2m_end_encoding(self.ctx);
}

@end

NSDictionary<NSString *, NSString *> *m2m_fsr_shader_sources(void) {
	static NSDictionary<NSString *, NSString *> *sources;
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		NSMutableDictionary *table = [NSMutableDictionary dictionaryWithCapacity:m2m_fsr_shader_count];
		for (size_t i = 0; i < m2m_fsr_shader_count; i++) {
			table[@(m2m_fsr_shaders[i].name)] = @(m2m_fsr_shaders[i].source);
		}
		sources = table;
	});
	return sources;
}

FfxSurfaceFormat m2m_ffx_format(MTLPixelFormat format) {
	switch (format) {
	case MTLPixelFormatRGBA16Float: return FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT;
	case MTLPixelFormatBGRA8Unorm: return FFX_SURFACE_FORMAT_B8G8R8A8_UNORM;
	case MTLPixelFormatRG16Float: return FFX_SURFACE_FORMAT_R16G16_FLOAT;
	case MTLPixelFormatR8Unorm: return FFX_SURFACE_FORMAT_R8_UNORM;
	case MTLPixelFormatDepth32Float:
	case MTLPixelFormatR32Float: return FFX_SURFACE_FORMAT_R32_FLOAT;
	default: return FFX_SURFACE_FORMAT_R8G8B8A8_UNORM;
	}
}

FfxResource m2m_ffx_resource(id<MTLTexture> texture, uint32_t usage, const wchar_t *name) {
	FfxResourceDescription description = {};
	description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
	description.format = m2m_ffx_format(texture.pixelFormat);
	description.width = (uint32_t)texture.width;
	description.height = (uint32_t)texture.height;
	description.depth = 1;
	description.mipCount = 1;
	description.usage = (FfxResourceUsage)usage;
	return ffxGetResourceMetal(texture, description, name);
}
