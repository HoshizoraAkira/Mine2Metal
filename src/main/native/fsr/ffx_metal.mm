#include "ffx_metal.h"

#include <FidelityFX/host/ffx_util.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

// Resources, effect contexts and pipelines live in one backend object; its address sits at the start of the scratch
// memory AMD's interface carries around.

namespace {

struct MetalResource {
	id<MTLResource> object;
	// Per mip level, for binding one level as a storage texture.
	std::vector<id<MTLTexture>> mipViews;
	FfxResourceDescription description;
	uint32_t effectContextId;
	bool registered;
	bool inUse;
};

struct EffectContext {
	bool active;
};

// What a pass binds, in the order its FfxPipelineState lists it: each entry is the Metal argument index.
struct MetalPipeline {
	id<MTLComputePipelineState> state;
	MTLSize threadgroupSize;
	std::vector<NSUInteger> srvTextures, uavTextures, srvBuffers, uavBuffers, constantBuffers;
	// Component type of each shader resource texture, for the stand-in bound when a resource is missing.
	std::vector<MTLDataType> srvTextureTypes;
	// Size the shader declares for each constant buffer: std140 rounds structs up to 16 bytes, the C structs not.
	std::vector<NSUInteger> constantBufferSizes;
	std::vector<id<MTLSamplerState>> samplers;
	std::vector<NSUInteger> samplerIndices;
};

struct MetalBackend {
	id<MTLDevice> device;
	FfxMetalShaderSource shaders;
	std::vector<MetalResource> resources;
	std::vector<EffectContext> contexts;
	std::vector<FfxGpuJobDescription> jobs;
	// 1x1 zero textures bound where an optional input (a distortion field, a HUD-less color) is not given, like the
	// null descriptors of AMD's Vulkan backend. By component type: float, uint, int.
	id<MTLTexture> nullFloat, nullUint, nullInt;
	// Constant data is copied here when staged and stays until the jobs that use it are encoded.
	std::vector<uint32_t> constantRing;
	size_t constantRingOffset = 0;
};

constexpr size_t CONSTANT_RING_WORDS = 64 * 1024;

MetalBackend *backendOf(FfxInterface *backendInterface) {
	return *static_cast<MetalBackend **>(backendInterface->scratchBuffer);
}

std::string narrow(const wchar_t *text) {
	std::string result;
	for (; text && *text; text++) {
		result.push_back(*text < 128 ? static_cast<char>(*text) : '?');
	}
	return result;
}

void widen(const char *text, wchar_t *out, size_t capacity) {
	size_t i = 0;
	for (; text[i] && i + 1 < capacity; i++) {
		out[i] = static_cast<wchar_t>(text[i]);
	}
	out[i] = L'\0';
}

uint32_t fullMipCount(uint32_t width, uint32_t height) {
	return 1 + static_cast<uint32_t>(std::floor(std::log2(static_cast<double>(std::max(1u, std::max(width, height))))));
}

NSString *passName(FfxEffect effect, FfxPass pass) {
	static NSString *const frameInterpolation[] = {
	    @"ffx_frameinterpolation_reconstruct_and_dilate_pass",
	    @"ffx_frameinterpolation_setup_pass",
	    @"ffx_frameinterpolation_reconstruct_previous_depth_pass",
	    @"ffx_frameinterpolation_game_motion_vector_field_pass",
	    @"ffx_frameinterpolation_optical_flow_vector_field_pass",
	    @"ffx_frameinterpolation_disocclusion_mask_pass",
	    @"ffx_frameinterpolation_pass",
	    @"ffx_frameinterpolation_compute_inpainting_pyramid_pass",
	    @"ffx_frameinterpolation_inpainting_pass",
	    @"ffx_frameinterpolation_compute_game_vector_field_inpainting_pyramid_pass",
	    @"ffx_frameinterpolation_debug_view_pass",
	};
	static NSString *const opticalFlow[] = {
	    @"ffx_opticalflow_prepare_luma_pass",
	    @"ffx_opticalflow_compute_luminance_pyramid_pass",
	    @"ffx_opticalflow_generate_scd_histogram_pass",
	    @"ffx_opticalflow_compute_scd_divergence_pass",
	    @"ffx_opticalflow_compute_optical_flow_advanced_pass_v5",
	    @"ffx_opticalflow_filter_optical_flow_pass_v5",
	    @"ffx_opticalflow_scale_optical_flow_advanced_pass_v5",
	};
	// Accumulate with and without sharpening share a shader; it is built without, so sharpening runs as RCAS.
	static NSString *const fsr3Upscaler[] = {
	    @"ffx_fsr3upscaler_prepare_inputs_pass",
	    @"ffx_fsr3upscaler_luma_pyramid_pass",
	    @"ffx_fsr3upscaler_shading_change_pyramid_pass",
	    @"ffx_fsr3upscaler_shading_change_pass",
	    @"ffx_fsr3upscaler_prepare_reactivity_pass",
	    @"ffx_fsr3upscaler_luma_instability_pass",
	    @"ffx_fsr3upscaler_accumulate_pass",
	    @"ffx_fsr3upscaler_accumulate_pass",
	    @"ffx_fsr3upscaler_rcas_pass",
	    @"ffx_fsr3upscaler_debug_view_pass",
	    @"ffx_fsr3upscaler_autogen_reactive_pass",
	};
	static NSString *const fsr1[] = {
	    @"ffx_fsr1_easu_pass",
	    @"ffx_fsr1_easu_pass",
	    @"ffx_fsr1_rcas_pass",
	};
	if (effect == FFX_EFFECT_FSR3UPSCALER && pass < sizeof(fsr3Upscaler) / sizeof(fsr3Upscaler[0])) {
		return fsr3Upscaler[pass];
	}
	if (effect == FFX_EFFECT_FSR1 && pass < sizeof(fsr1) / sizeof(fsr1[0])) {
		return fsr1[pass];
	}
	if (effect == FFX_EFFECT_FRAMEINTERPOLATION && pass < sizeof(frameInterpolation) / sizeof(frameInterpolation[0])) {
		return frameInterpolation[pass];
	}
	if (effect == FFX_EFFECT_OPTICALFLOW && pass < sizeof(opticalFlow) / sizeof(opticalFlow[0])) {
		return opticalFlow[pass];
	}
	return nil;
}

// spirv-cross declares the GLSL workgroup size as a constant rather than a kernel attribute.
MTLSize threadgroupSizeOf(NSString *source) {
	NSRegularExpression *pattern = [NSRegularExpression
	    regularExpressionWithPattern:@"gl_WorkGroupSize[^=]*=\\s*uint3\\((\\d+)u?,\\s*(\\d+)u?,\\s*(\\d+)u?\\)"
	                         options:0
	                           error:nil];
	NSTextCheckingResult *match = [pattern firstMatchInString:source options:0 range:NSMakeRange(0, source.length)];
	if (!match) {
		return MTLSizeMake(64, 1, 1);
	}
	return MTLSizeMake((NSUInteger)[source substringWithRange:[match rangeAtIndex:1]].integerValue,
	                   (NSUInteger)[source substringWithRange:[match rangeAtIndex:2]].integerValue,
	                   (NSUInteger)[source substringWithRange:[match rangeAtIndex:3]].integerValue);
}

id<MTLSamplerState> samplerFor(id<MTLDevice> device, const FfxSamplerDescription &description) {
	MTLSamplerDescriptor *descriptor = [MTLSamplerDescriptor new];
	BOOL linear = description.filter != FFX_FILTER_TYPE_MINMAGMIP_POINT;
	descriptor.minFilter = linear ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
	descriptor.magFilter = descriptor.minFilter;
	descriptor.mipFilter = description.filter == FFX_FILTER_TYPE_MINMAGMIP_LINEAR ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
	MTLSamplerAddressMode modes[] = {MTLSamplerAddressModeRepeat, MTLSamplerAddressModeMirrorRepeat, MTLSamplerAddressModeClampToEdge,
	                                 MTLSamplerAddressModeClampToBorderColor, MTLSamplerAddressModeMirrorClampToEdge};
	descriptor.sAddressMode = modes[description.addressModeU];
	descriptor.tAddressMode = modes[description.addressModeV];
	descriptor.rAddressMode = modes[description.addressModeW];
	return [device newSamplerStateWithDescriptor:descriptor];
}

int32_t addResource(MetalBackend *backend, MetalResource resource) {
	for (size_t i = 0; i < backend->resources.size(); i++) {
		if (!backend->resources[i].inUse) {
			backend->resources[i] = resource;
			return static_cast<int32_t>(i);
		}
	}
	backend->resources.push_back(resource);
	return static_cast<int32_t>(backend->resources.size() - 1);
}

void fillMipViews(MetalResource &resource) {
	resource.mipViews.clear();
	id<MTLTexture> texture = (id<MTLTexture>)resource.object;
	if (![texture conformsToProtocol:@protocol(MTLTexture)]) {
		return;
	}
	for (NSUInteger level = 0; level < texture.mipmapLevelCount; level++) {
		if (texture.mipmapLevelCount == 1) {
			resource.mipViews.push_back(texture);
		} else {
			resource.mipViews.push_back([texture newTextureViewWithPixelFormat:texture.pixelFormat
			                                                       textureType:texture.textureType
			                                                            levels:NSMakeRange(level, 1)
			                                                            slices:NSMakeRange(0, 1)]);
		}
	}
}

// --- FfxInterface ---

FfxVersionNumber getSdkVersion(FfxInterface *) {
	return FFX_SDK_MAKE_VERSION(FFX_SDK_VERSION_MAJOR, FFX_SDK_VERSION_MINOR, FFX_SDK_VERSION_PATCH);
}

FfxErrorCode getEffectGpuMemoryUsage(FfxInterface *, FfxUInt32, FfxEffectMemoryUsage *usage) {
	usage->totalUsageInBytes = 0;
	usage->aliasableUsageInBytes = 0;
	return FFX_OK;
}

FfxErrorCode createBackendContext(FfxInterface *backendInterface, FfxEffect, FfxEffectBindlessConfig *, FfxUInt32 *effectContextId) {
	MetalBackend *backend = backendOf(backendInterface);
	for (size_t i = 0; i < backend->contexts.size(); i++) {
		if (!backend->contexts[i].active) {
			backend->contexts[i].active = true;
			*effectContextId = static_cast<FfxUInt32>(i);
			return FFX_OK;
		}
	}
	backend->contexts.push_back({true});
	*effectContextId = static_cast<FfxUInt32>(backend->contexts.size() - 1);
	return FFX_OK;
}

FfxErrorCode getDeviceCapabilities(FfxInterface *, FfxDeviceCapabilities *capabilities) {
	*capabilities = {};
	capabilities->maximumSupportedShaderModel = FFX_SHADER_MODEL_6_6;
	capabilities->waveLaneCountMin = 32;
	capabilities->waveLaneCountMax = 32;
	// The shaders are translated without half precision for now.
	capabilities->fp16Supported = false;
	return FFX_OK;
}

FfxErrorCode destroyBackendContext(FfxInterface *backendInterface, FfxUInt32 effectContextId) {
	MetalBackend *backend = backendOf(backendInterface);
	for (MetalResource &resource : backend->resources) {
		if (resource.inUse && resource.effectContextId == effectContextId) {
			resource = {};
		}
	}
	if (effectContextId < backend->contexts.size()) {
		backend->contexts[effectContextId].active = false;
	}
	return FFX_OK;
}

FfxErrorCode createResource(FfxInterface *backendInterface, const FfxCreateResourceDescription *description, FfxUInt32 effectContextId,
                            FfxResourceInternal *outResource) {
	MetalBackend *backend = backendOf(backendInterface);
	id<MTLResource> object = ffxCreateResourceMetal(backend->device, description);
	if (!object) {
		return FFX_ERROR_OUT_OF_MEMORY;
	}
	object.label = [NSString stringWithUTF8String:narrow(description->name).c_str()];
	MetalResource resource = {object, {}, description->resourceDescription, effectContextId, false, true};
	if ([object conformsToProtocol:@protocol(MTLTexture)]) {
		resource.description.mipCount = (uint32_t)((id<MTLTexture>)object).mipmapLevelCount;
	}
	fillMipViews(resource);
	outResource->internalIndex = addResource(backend, resource);
	return FFX_OK;
}

FfxErrorCode registerResource(FfxInterface *backendInterface, const FfxResource *inResource, FfxUInt32 effectContextId,
                              FfxResourceInternal *outResource) {
	MetalBackend *backend = backendOf(backendInterface);
	if (!inResource->resource) {
		outResource->internalIndex = -1;
		return FFX_OK;
	}
	MetalResource resource = {(__bridge id<MTLResource>)inResource->resource, {}, inResource->description, effectContextId, true, true};
	fillMipViews(resource);
	outResource->internalIndex = addResource(backend, resource);
	if (getenv("FFX_METAL_TRACE")) {
		NSLog(@"ffx_metal trace: register #%d %@ (context %u)", outResource->internalIndex, resource.object.label, effectContextId);
	}
	return FFX_OK;
}

FfxErrorCode unregisterResources(FfxInterface *backendInterface, FfxCommandList, FfxUInt32 effectContextId) {
	MetalBackend *backend = backendOf(backendInterface);
	for (MetalResource &resource : backend->resources) {
		if (resource.inUse && resource.registered && resource.effectContextId == effectContextId) {
			resource = {};
		}
	}
	return FFX_OK;
}

FfxResource getResource(FfxInterface *backendInterface, FfxResourceInternal resource) {
	MetalBackend *backend = backendOf(backendInterface);
	FfxResource result = {};
	if (resource.internalIndex >= 0 && static_cast<size_t>(resource.internalIndex) < backend->resources.size()) {
		result.resource = (__bridge void *)backend->resources[resource.internalIndex].object;
		result.description = backend->resources[resource.internalIndex].description;
	}
	return result;
}

FfxResourceDescription getResourceDescription(FfxInterface *backendInterface, FfxResourceInternal resource) {
	return getResource(backendInterface, resource).description;
}

FfxErrorCode destroyResource(FfxInterface *backendInterface, FfxResourceInternal resource, FfxUInt32) {
	MetalBackend *backend = backendOf(backendInterface);
	if (resource.internalIndex >= 0 && static_cast<size_t>(resource.internalIndex) < backend->resources.size()) {
		backend->resources[resource.internalIndex] = {};
	}
	return FFX_OK;
}

FfxErrorCode stageConstantBufferData(FfxInterface *backendInterface, void *data, FfxUInt32 size, FfxConstantBuffer *constantBuffer) {
	MetalBackend *backend = backendOf(backendInterface);
	size_t words = (size + 3) / 4;
	if (backend->constantRingOffset + words > backend->constantRing.size()) {
		backend->constantRingOffset = 0;
	}
	uint32_t *destination = backend->constantRing.data() + backend->constantRingOffset;
	memcpy(destination, data, size);
	backend->constantRingOffset += words;
	constantBuffer->data = destination;
	constantBuffer->num32BitEntries = static_cast<uint32_t>(words);
	return FFX_OK;
}

void addBinding(FfxResourceBinding *bindings, uint32_t &count, NSUInteger index, NSString *name) {
	FfxResourceBinding &binding = bindings[count++];
	binding.slotIndex = static_cast<uint32_t>(index);
	binding.arrayIndex = 0;
	binding.resourceIdentifier = 0;
	widen(name.UTF8String, binding.name, FFX_RESOURCE_NAME_SIZE);
}

FfxErrorCode createPipeline(FfxInterface *backendInterface, FfxEffect effect, FfxPass pass, uint32_t permutationOptions,
                            const FfxPipelineDescription *description, FfxUInt32, FfxPipelineState *outPipeline) {
	MetalBackend *backend = backendOf(backendInterface);
	NSString *name = passName(effect, pass);
	NSString *source = name ? backend->shaders(name) : nil;
	if (!source) {
		NSLog(@"ffx_metal: no shader for effect %d pass %d", (int)effect, (int)pass);
		return FFX_ERROR_INVALID_ARGUMENT;
	}
	NSError *error = nil;
	MTLCompileOptions *options = [MTLCompileOptions new];
	options.languageVersion = MTLLanguageVersion3_1;
	id<MTLLibrary> library = [backend->device newLibraryWithSource:source options:options error:&error];
	id<MTLFunction> function = [library newFunctionWithName:name];
	if (!function) {
		NSLog(@"ffx_metal: could not compile %@: %@", name, error);
		return FFX_ERROR_BACKEND_API_ERROR;
	}
	MTLComputePipelineReflection *reflection = nil;
	id<MTLComputePipelineState> state = [backend->device newComputePipelineStateWithFunction:function
	                                                                                  options:MTLPipelineOptionBindingInfo
	                                                                               reflection:&reflection
	                                                                                    error:&error];
	if (!state) {
		NSLog(@"ffx_metal: could not create the pipeline for %@: %@", name, error);
		return FFX_ERROR_BACKEND_API_ERROR;
	}
	if (permutationOptions != 0) {
		NSLog(@"ffx_metal: %@ asked for permutation 0x%x; using the one built", name, permutationOptions);
	}

	auto *pipeline = new MetalPipeline();
	pipeline->state = state;
	pipeline->threadgroupSize = threadgroupSizeOf(source);
	*outPipeline = {};
	NSArray<id<MTLBinding>> *bindings = [reflection.bindings sortedArrayUsingComparator:^NSComparisonResult(id<MTLBinding> a, id<MTLBinding> b) {
		return a.index < b.index ? NSOrderedAscending : a.index > b.index ? NSOrderedDescending : NSOrderedSame;
	}];
	for (id<MTLBinding> binding in bindings) {
		// AMD's binding tables go by name: r_ for shader resource views, rw_ for unordered access views, cb for
		// constants. The name decides, since a pass may only read an rw_ resource and its MSL is then read-only.
		BOOL readOnly = [binding.name hasPrefix:@"r_"] || (![binding.name hasPrefix:@"rw_"] && binding.access == MTLBindingAccessReadOnly);
		switch (binding.type) {
		case MTLBindingTypeTexture:
			if (readOnly) {
				addBinding(outPipeline->srvTextureBindings, outPipeline->srvTextureCount, binding.index, binding.name);
				pipeline->srvTextures.push_back(binding.index);
				pipeline->srvTextureTypes.push_back(((id<MTLTextureBinding>)binding).textureDataType);
			} else {
				addBinding(outPipeline->uavTextureBindings, outPipeline->uavTextureCount, binding.index, binding.name);
				pipeline->uavTextures.push_back(binding.index);
			}
			break;
		case MTLBindingTypeBuffer:
			if ([binding.name hasPrefix:@"cb"]) {
				addBinding(outPipeline->constantBufferBindings, outPipeline->constCount, binding.index, binding.name);
				pipeline->constantBuffers.push_back(binding.index);
				pipeline->constantBufferSizes.push_back(((id<MTLBufferBinding>)binding).bufferDataSize);
			} else if (readOnly) {
				addBinding(outPipeline->srvBufferBindings, outPipeline->srvBufferCount, binding.index, binding.name);
				pipeline->srvBuffers.push_back(binding.index);
			} else {
				addBinding(outPipeline->uavBufferBindings, outPipeline->uavBufferCount, binding.index, binding.name);
				pipeline->uavBuffers.push_back(binding.index);
			}
			break;
		case MTLBindingTypeSampler: {
			size_t i = pipeline->samplers.size();
			FfxSamplerDescription fallback = {FFX_FILTER_TYPE_MINMAGMIP_LINEAR, FFX_ADDRESS_MODE_CLAMP, FFX_ADDRESS_MODE_CLAMP,
			                                  FFX_ADDRESS_MODE_CLAMP, FFX_BIND_COMPUTE_SHADER_STAGE};
			pipeline->samplers.push_back(samplerFor(backend->device, i < description->samplerCount ? description->samplers[i] : fallback));
			pipeline->samplerIndices.push_back(binding.index);
			break;
		}
		default:
			break;
		}
	}
	outPipeline->pipeline = pipeline;
	widen(name.UTF8String, outPipeline->name, FFX_RESOURCE_NAME_SIZE);
	return FFX_OK;
}

FfxErrorCode destroyPipeline(FfxInterface *, FfxPipelineState *pipeline, FfxUInt32) {
	if (pipeline && pipeline->pipeline) {
		delete static_cast<MetalPipeline *>(pipeline->pipeline);
		pipeline->pipeline = nullptr;
	}
	return FFX_OK;
}

FfxErrorCode scheduleGpuJob(FfxInterface *backendInterface, const FfxGpuJobDescription *job) {
	backendOf(backendInterface)->jobs.push_back(*job);
	return FFX_OK;
}

id<MTLResource> objectOf(MetalBackend *backend, FfxResourceInternal resource) {
	if (resource.internalIndex < 0 || static_cast<size_t>(resource.internalIndex) >= backend->resources.size()) {
		return nil;
	}
	return backend->resources[resource.internalIndex].object;
}

void encodeClear(MetalBackend *backend, id<MTLCommandBuffer> commandBuffer, const FfxClearFloatJobDescription &clear) {
	id<MTLResource> object = objectOf(backend, clear.target);
	if (!object) {
		// An optional resource that was not given.
		return;
	}
	if ([object conformsToProtocol:@protocol(MTLBuffer)]) {
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
		[blit fillBuffer:(id<MTLBuffer>)object range:NSMakeRange(0, ((id<MTLBuffer>)object).length) value:(uint8_t)clear.color[0]];
		[blit endEncoding];
		return;
	}
	id<MTLTexture> texture = (id<MTLTexture>)object;
	if ((texture.usage & MTLTextureUsageRenderTarget) == 0) {
		// Not ours to render into (a texture the caller registered): copy zeros over it instead.
		if (clear.color[0] != 0.0f || clear.color[1] != 0.0f || clear.color[2] != 0.0f || clear.color[3] != 0.0f) {
			NSLog(@"ffx_metal: cannot clear %@ to a non-zero color; cleared to zero", texture.label);
		}
		NSUInteger bytesPerRow = texture.width * 16;
		id<MTLBuffer> zeros = [backend->device newBufferWithLength:bytesPerRow * texture.height options:MTLResourceStorageModePrivate];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
		[blit fillBuffer:zeros range:NSMakeRange(0, zeros.length) value:0];
		for (NSUInteger level = 0; level < texture.mipmapLevelCount; level++) {
			NSUInteger levelWidth = std::max<NSUInteger>(1, texture.width >> level);
			NSUInteger levelHeight = std::max<NSUInteger>(1, texture.height >> level);
			[blit copyFromBuffer:zeros
			           sourceOffset:0
			      sourceBytesPerRow:bytesPerRow
			    sourceBytesPerImage:bytesPerRow * levelHeight
			             sourceSize:MTLSizeMake(levelWidth, levelHeight, 1)
			              toTexture:texture
			       destinationSlice:0
			       destinationLevel:level
			      destinationOrigin:MTLOriginMake(0, 0, 0)];
		}
		[blit endEncoding];
		return;
	}
	for (NSUInteger level = 0; level < texture.mipmapLevelCount; level++) {
		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture = texture;
		pass.colorAttachments[0].level = level;
		pass.colorAttachments[0].loadAction = MTLLoadActionClear;
		pass.colorAttachments[0].storeAction = MTLStoreActionStore;
		pass.colorAttachments[0].clearColor = MTLClearColorMake(clear.color[0], clear.color[1], clear.color[2], clear.color[3]);
		[[commandBuffer renderCommandEncoderWithDescriptor:pass] endEncoding];
	}
}

void encodeCopy(MetalBackend *backend, id<MTLCommandBuffer> commandBuffer, const FfxCopyJobDescription &copy) {
	id<MTLResource> source = objectOf(backend, copy.src);
	id<MTLResource> destination = objectOf(backend, copy.dst);
	id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
	if ([source conformsToProtocol:@protocol(MTLBuffer)]) {
		id<MTLBuffer> from = (id<MTLBuffer>)source;
		NSUInteger size = copy.size ? copy.size : from.length - copy.srcOffset;
		[blit copyFromBuffer:from sourceOffset:copy.srcOffset toBuffer:(id<MTLBuffer>)destination destinationOffset:copy.dstOffset size:size];
	} else {
		id<MTLTexture> from = (id<MTLTexture>)source;
		id<MTLTexture> to = (id<MTLTexture>)destination;
		NSUInteger levels = std::min(from.mipmapLevelCount, to.mipmapLevelCount);
		for (NSUInteger level = 0; level < levels; level++) {
			[blit copyFromTexture:from
			              sourceSlice:0
			              sourceLevel:level
			             sourceOrigin:MTLOriginMake(0, 0, 0)
			               sourceSize:MTLSizeMake(std::min(from.width, to.width) >> level ?: 1, std::min(from.height, to.height) >> level ?: 1, 1)
			                toTexture:to
			         destinationSlice:0
			         destinationLevel:level
			        destinationOrigin:MTLOriginMake(0, 0, 0)];
		}
	}
	[blit endEncoding];
}

void encodeCompute(MetalBackend *backend, id<MTLComputeCommandEncoder> encoder, const FfxComputeJobDescription &job) {
	auto *pipeline = static_cast<MetalPipeline *>(job.pipeline.pipeline);
	[encoder setComputePipelineState:pipeline->state];
	for (size_t i = 0; i < pipeline->srvTextures.size(); i++) {
		id<MTLTexture> texture = (id<MTLTexture>)objectOf(backend, job.srvTextures[i].resource);
		if (!texture) {
			MTLDataType type = pipeline->srvTextureTypes[i];
			texture = type == MTLDataTypeUInt ? backend->nullUint : type == MTLDataTypeInt ? backend->nullInt : backend->nullFloat;
		}
		[encoder setTexture:texture atIndex:pipeline->srvTextures[i]];
	}
	for (size_t i = 0; i < pipeline->uavTextures.size(); i++) {
		FfxResourceInternal resource = job.uavTextures[i].resource;
		id<MTLTexture> texture = nil;
		if (resource.internalIndex >= 0 && static_cast<size_t>(resource.internalIndex) < backend->resources.size()) {
			MetalResource &entry = backend->resources[resource.internalIndex];
			uint32_t mip = job.uavTextures[i].mip;
			texture = mip < entry.mipViews.size() ? entry.mipViews[mip] : (id<MTLTexture>)entry.object;
		}
		[encoder setTexture:texture atIndex:pipeline->uavTextures[i]];
	}
	for (size_t i = 0; i < pipeline->srvBuffers.size(); i++) {
		[encoder setBuffer:(id<MTLBuffer>)objectOf(backend, job.srvBuffers[i].resource) offset:job.srvBuffers[i].offset atIndex:pipeline->srvBuffers[i]];
	}
	for (size_t i = 0; i < pipeline->uavBuffers.size(); i++) {
		[encoder setBuffer:(id<MTLBuffer>)objectOf(backend, job.uavBuffers[i].resource) offset:job.uavBuffers[i].offset atIndex:pipeline->uavBuffers[i]];
	}
	for (size_t i = 0; i < pipeline->constantBuffers.size(); i++) {
		NSUInteger length = job.cbs[i].num32BitEntries * 4;
		NSUInteger declared = pipeline->constantBufferSizes[i];
		if (declared > length) {
			uint8_t padded[4096] = {};
			memcpy(padded, job.cbs[i].data, length);
			[encoder setBytes:padded length:std::min<NSUInteger>(declared, sizeof(padded)) atIndex:pipeline->constantBuffers[i]];
		} else {
			[encoder setBytes:job.cbs[i].data length:length atIndex:pipeline->constantBuffers[i]];
		}
	}
	for (size_t i = 0; i < pipeline->samplers.size(); i++) {
		[encoder setSamplerState:pipeline->samplers[i] atIndex:pipeline->samplerIndices[i]];
	}
	[encoder dispatchThreadgroups:MTLSizeMake(job.dimensions[0], job.dimensions[1], job.dimensions[2])
	        threadsPerThreadgroup:pipeline->threadgroupSize];
}

// FFX_METAL_PROFILE=1 (for tools/fsrbench): each job gets a command buffer of its own, committed right away, and its
// GPU time is added up per pass and printed every few hundred jobs. Only correct when the command list holds nothing
// else, as these run ahead of it.
void profileJob(MetalBackend *backend, id<MTLCommandBuffer> commandBuffer, const FfxGpuJobDescription &job) {
	static NSMutableDictionary<NSString *, NSNumber *> *totals;
	static NSMutableDictionary<NSString *, NSNumber *> *counts;
	static int jobs;
	if (!totals) {
		totals = [NSMutableDictionary dictionary];
		counts = [NSMutableDictionary dictionary];
	}
	NSString *label = [NSString stringWithUTF8String:narrow(job.jobType == FFX_GPU_JOB_COMPUTE ? job.computeJobDescriptor.pipeline.name : job.jobLabel).c_str()];
	[commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> finished) {
		@synchronized(totals) {
			totals[label] = @(totals[label].doubleValue + (finished.GPUEndTime - finished.GPUStartTime) * 1000.0);
			counts[label] = @(counts[label].intValue + 1);
			if (++jobs % 600 == 0) {
				NSArray *labels = [totals keysSortedByValueUsingComparator:^NSComparisonResult(NSNumber *a, NSNumber *b) { return [b compare:a]; }];
				for (NSString *name in labels) {
					printf("  %-70s %6.3f ms x %d\n", name.UTF8String, totals[name].doubleValue / counts[name].intValue, counts[name].intValue);
				}
				[totals removeAllObjects];
				[counts removeAllObjects];
			}
		}
	}];
	(void)backend;
}

FfxErrorCode executeGpuJobs(FfxInterface *backendInterface, FfxCommandList commandList, FfxUInt32) {
	MetalBackend *backend = backendOf(backendInterface);
	id<MTLCommandBuffer> commandBuffer = (__bridge id<MTLCommandBuffer>)commandList;
	if (getenv("FFX_METAL_PROFILE")) {
		for (const FfxGpuJobDescription &job : backend->jobs) {
			if (job.jobType == FFX_GPU_JOB_BARRIER || job.jobType == FFX_GPU_JOB_DISCARD) {
				continue;
			}
			id<MTLCommandBuffer> own = [commandBuffer.commandQueue commandBuffer];
			if (job.jobType == FFX_GPU_JOB_COMPUTE) {
				id<MTLComputeCommandEncoder> compute = [own computeCommandEncoder];
				encodeCompute(backend, compute, job.computeJobDescriptor);
				[compute endEncoding];
			} else if (job.jobType == FFX_GPU_JOB_CLEAR_FLOAT) {
				encodeClear(backend, own, job.clearJobDescriptor);
			} else if (job.jobType == FFX_GPU_JOB_COPY) {
				encodeCopy(backend, own, job.copyJobDescriptor);
			}
			profileJob(backend, own, job);
			[own commit];
		}
		backend->jobs.clear();
		return FFX_OK;
	}
	id<MTLComputeCommandEncoder> compute = nil;
	for (const FfxGpuJobDescription &job : backend->jobs) {
		if (job.jobType == FFX_GPU_JOB_COMPUTE) {
			if (!compute) {
				compute = [commandBuffer computeCommandEncoder];
				compute.label = @"FidelityFX";
			}
			encodeCompute(backend, compute, job.computeJobDescriptor);
			continue;
		}
		if (job.jobType == FFX_GPU_JOB_BARRIER || job.jobType == FFX_GPU_JOB_DISCARD) {
			// Metal tracks hazards between the passes of a command buffer.
			continue;
		}
		[compute endEncoding];
		compute = nil;
		if (job.jobType == FFX_GPU_JOB_CLEAR_FLOAT) {
			if (getenv("FFX_METAL_TRACE")) {
				id<MTLResource> target = objectOf(backend, job.clearJobDescriptor.target);
				NSLog(@"ffx_metal trace: clear %s -> #%d %@", narrow(job.jobLabel).c_str(), job.clearJobDescriptor.target.internalIndex, target.label);
			}
			encodeClear(backend, commandBuffer, job.clearJobDescriptor);
		} else if (job.jobType == FFX_GPU_JOB_COPY) {
			encodeCopy(backend, commandBuffer, job.copyJobDescriptor);
		}
	}
	[compute endEncoding];
	backend->jobs.clear();
	return FFX_OK;
}

id<MTLTexture> nullTexture(id<MTLDevice> device, MTLPixelFormat format) {
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:1 height:1 mipmapped:NO];
	descriptor.usage = MTLTextureUsageShaderRead;
	descriptor.storageMode = MTLStorageModeShared;
	id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
	const uint64_t zero[2] = {0, 0};
	[texture replaceRegion:MTLRegionMake2D(0, 0, 1, 1) mipmapLevel:0 withBytes:zero bytesPerRow:16];
	return texture;
}

} // namespace

size_t ffxGetScratchMemorySizeMetal(void) {
	return sizeof(MetalBackend *);
}

FfxErrorCode ffxGetInterfaceMetal(FfxInterface *backendInterface, id<MTLDevice> device, FfxMetalShaderSource shaders, void *scratch,
                                  size_t scratchSize) {
	if (!backendInterface || !device || !scratch || scratchSize < ffxGetScratchMemorySizeMetal()) {
		return FFX_ERROR_INVALID_ARGUMENT;
	}
	auto *backend = new MetalBackend();
	backend->device = device;
	backend->shaders = shaders;
	backend->constantRing.resize(CONSTANT_RING_WORDS);
	backend->nullFloat = nullTexture(device, MTLPixelFormatRGBA16Float);
	backend->nullUint = nullTexture(device, MTLPixelFormatR32Uint);
	backend->nullInt = nullTexture(device, MTLPixelFormatR32Sint);
	*static_cast<MetalBackend **>(scratch) = backend;

	*backendInterface = {};
	backendInterface->fpGetSDKVersion = getSdkVersion;
	backendInterface->fpGetEffectGpuMemoryUsage = getEffectGpuMemoryUsage;
	backendInterface->fpCreateBackendContext = createBackendContext;
	backendInterface->fpGetDeviceCapabilities = getDeviceCapabilities;
	backendInterface->fpDestroyBackendContext = destroyBackendContext;
	backendInterface->fpCreateResource = createResource;
	backendInterface->fpRegisterResource = registerResource;
	backendInterface->fpGetResource = getResource;
	backendInterface->fpUnregisterResources = unregisterResources;
	backendInterface->fpGetResourceDescription = getResourceDescription;
	backendInterface->fpDestroyResource = destroyResource;
	backendInterface->fpStageConstantBufferDataFunc = stageConstantBufferData;
	backendInterface->fpCreatePipeline = createPipeline;
	backendInterface->fpDestroyPipeline = destroyPipeline;
	backendInterface->fpScheduleGpuJob = scheduleGpuJob;
	backendInterface->fpExecuteGpuJobs = executeGpuJobs;
	backendInterface->scratchBuffer = scratch;
	backendInterface->scratchBufferSize = scratchSize;
	backendInterface->device = (__bridge void *)device;
	return FFX_OK;
}

FfxResource ffxGetResourceMetal(id<MTLResource> resource, FfxResourceDescription description, const wchar_t *name) {
	FfxResource result = {};
	result.resource = (__bridge void *)resource;
	result.description = description;
	result.state = FFX_RESOURCE_STATE_COMPUTE_READ;
	if (name) {
		wcsncpy(result.name, name, FFX_RESOURCE_NAME_SIZE - 1);
	}
	return result;
}

FfxCommandList ffxGetCommandListMetal(id<MTLCommandBuffer> commandBuffer) {
	return (__bridge void *)commandBuffer;
}

MTLPixelFormat ffxGetMetalPixelFormat(FfxSurfaceFormat format) {
	switch (format) {
	case FFX_SURFACE_FORMAT_R32G32B32A32_TYPELESS:
	case FFX_SURFACE_FORMAT_R32G32B32A32_FLOAT: return MTLPixelFormatRGBA32Float;
	case FFX_SURFACE_FORMAT_R32G32B32A32_UINT: return MTLPixelFormatRGBA32Uint;
	case FFX_SURFACE_FORMAT_R16G16B16A16_TYPELESS:
	case FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT: return MTLPixelFormatRGBA16Float;
	case FFX_SURFACE_FORMAT_R32G32_TYPELESS:
	case FFX_SURFACE_FORMAT_R32G32_FLOAT: return MTLPixelFormatRG32Float;
	case FFX_SURFACE_FORMAT_R8_UINT: return MTLPixelFormatR8Uint;
	case FFX_SURFACE_FORMAT_R32_UINT: return MTLPixelFormatR32Uint;
	case FFX_SURFACE_FORMAT_R8G8B8A8_TYPELESS:
	case FFX_SURFACE_FORMAT_R8G8B8A8_UNORM: return MTLPixelFormatRGBA8Unorm;
	case FFX_SURFACE_FORMAT_R8G8B8A8_SNORM: return MTLPixelFormatRGBA8Snorm;
	case FFX_SURFACE_FORMAT_R8G8B8A8_SRGB: return MTLPixelFormatRGBA8Unorm_sRGB;
	case FFX_SURFACE_FORMAT_B8G8R8A8_TYPELESS:
	case FFX_SURFACE_FORMAT_B8G8R8A8_UNORM: return MTLPixelFormatBGRA8Unorm;
	case FFX_SURFACE_FORMAT_B8G8R8A8_SRGB: return MTLPixelFormatBGRA8Unorm_sRGB;
	case FFX_SURFACE_FORMAT_R11G11B10_FLOAT: return MTLPixelFormatRG11B10Float;
	case FFX_SURFACE_FORMAT_R10G10B10A2_TYPELESS:
	case FFX_SURFACE_FORMAT_R10G10B10A2_UNORM: return MTLPixelFormatRGB10A2Unorm;
	case FFX_SURFACE_FORMAT_R16G16_TYPELESS:
	case FFX_SURFACE_FORMAT_R16G16_FLOAT: return MTLPixelFormatRG16Float;
	case FFX_SURFACE_FORMAT_R16G16_UINT: return MTLPixelFormatRG16Uint;
	case FFX_SURFACE_FORMAT_R16G16_SINT: return MTLPixelFormatRG16Sint;
	case FFX_SURFACE_FORMAT_R16_TYPELESS:
	case FFX_SURFACE_FORMAT_R16_FLOAT: return MTLPixelFormatR16Float;
	case FFX_SURFACE_FORMAT_R16_UINT: return MTLPixelFormatR16Uint;
	case FFX_SURFACE_FORMAT_R16_UNORM: return MTLPixelFormatR16Unorm;
	case FFX_SURFACE_FORMAT_R16_SNORM: return MTLPixelFormatR16Snorm;
	case FFX_SURFACE_FORMAT_R8_TYPELESS:
	case FFX_SURFACE_FORMAT_R8_UNORM: return MTLPixelFormatR8Unorm;
	case FFX_SURFACE_FORMAT_R8G8_TYPELESS:
	case FFX_SURFACE_FORMAT_R8G8_UNORM: return MTLPixelFormatRG8Unorm;
	case FFX_SURFACE_FORMAT_R8G8_UINT: return MTLPixelFormatRG8Uint;
	case FFX_SURFACE_FORMAT_R32_TYPELESS:
	case FFX_SURFACE_FORMAT_R32_FLOAT: return MTLPixelFormatR32Float;
	case FFX_SURFACE_FORMAT_R9G9B9E5_SHAREDEXP: return MTLPixelFormatRGB9E5Float;
	default: return MTLPixelFormatInvalid;
	}
}

id<MTLResource> ffxCreateResourceMetal(id<MTLDevice> device, const FfxCreateResourceDescription *description) {
	const FfxResourceDescription &resource = description->resourceDescription;
	const FfxResourceInitData &initData = description->initData;
	if (resource.type == FFX_RESOURCE_TYPE_BUFFER) {
		NSUInteger length = std::max<NSUInteger>(resource.size, 4);
		id<MTLBuffer> buffer = [device newBufferWithLength:length options:MTLResourceStorageModeShared];
		if (initData.type == FFX_RESOURCE_INIT_DATA_TYPE_BUFFER && initData.buffer) {
			memcpy(buffer.contents, initData.buffer, std::min<size_t>(initData.size, length));
		} else if (initData.type == FFX_RESOURCE_INIT_DATA_TYPE_VALUE) {
			memset(buffer.contents, initData.value, length);
		} else {
			memset(buffer.contents, 0, length);
		}
		return buffer;
	}
	MTLPixelFormat format = ffxGetMetalPixelFormat(resource.format);
	if (format == MTLPixelFormatInvalid) {
		NSLog(@"ffx_metal: unsupported format %d", (int)resource.format);
		return nil;
	}
	uint32_t width = std::max(1u, resource.width);
	uint32_t height = resource.type == FFX_RESOURCE_TYPE_TEXTURE1D ? 1 : std::max(1u, resource.height);
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor new];
	descriptor.textureType = resource.type == FFX_RESOURCE_TYPE_TEXTURE1D ? MTLTextureType1D : MTLTextureType2D;
	descriptor.pixelFormat = format;
	descriptor.width = width;
	descriptor.height = height;
	descriptor.mipmapLevelCount = descriptor.textureType == MTLTextureType1D ? 1
	                              : resource.mipCount ? std::min(resource.mipCount, fullMipCount(width, height))
	                                                  : fullMipCount(width, height);
	descriptor.storageMode = MTLStorageModePrivate;
	// Render target, so clears can use a load action.
	descriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget;
	if (format == MTLPixelFormatR32Uint) {
		descriptor.usage |= MTLTextureUsageShaderAtomic;
	}
	if (descriptor.textureType == MTLTextureType1D) {
		descriptor.usage &= ~MTLTextureUsageRenderTarget;
	}
	id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
	if (texture && initData.type == FFX_RESOURCE_INIT_DATA_TYPE_BUFFER && initData.buffer) {
		// Small lookup tables: upload through a shared staging copy.
		id<MTLTexture> staging = nil;
		descriptor.storageMode = MTLStorageModeShared;
		descriptor.usage = MTLTextureUsageShaderRead;
		descriptor.mipmapLevelCount = 1;
		staging = [device newTextureWithDescriptor:descriptor];
		NSUInteger bytesPerRow = initData.size / height;
		[staging replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:initData.buffer bytesPerRow:bytesPerRow];
		id<MTLCommandQueue> queue = [device newCommandQueue];
		id<MTLCommandBuffer> commandBuffer = [queue commandBuffer];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
		[blit copyFromTexture:staging sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(width, height, 1)
		            toTexture:texture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
		[blit endEncoding];
		[commandBuffer commit];
		[commandBuffer waitUntilCompleted];
	}
	return texture;
}
