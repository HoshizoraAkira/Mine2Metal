extern "C" {
#include "m2m_context.h"
}

#include <FidelityFX/host/ffx_frameinterpolation.h>
#include <FidelityFX/host/ffx_opticalflow.h>

#include "ffx_metal.h"
#include "fsr_shaders.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// FSR 3.1 frame generation (AMD FidelityFX SDK v1.1.4, MIT; docs/fsr-frame-gen.md): the other engine frame
// interpolation (interpolation.m) can use. AMD's host code records its passes through ffx_metal.mm, which hands them to
// M2MFfxHost below, so they land in the frame's command buffer like the backend's own work, on Metal 3 or Metal 4.

// FRAMEINTERPOLATION_SHADER_PERMUTATION_LOW_RES_MOTION_VECTORS in AMD's private header: motion vectors at render size.
static const uint32_t M2M_FSR_LOW_RES_MOTION_VECTORS = 1u << 0;

@interface M2MFfxHost : NSObject <FfxMetalHost>
@property(nonatomic, weak) M2MContext *ctx;
@end

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

static NSDictionary<NSString *, NSString *> *m2m_fsr_shader_sources() {
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

static FfxSurfaceFormat m2m_ffx_format(MTLPixelFormat format) {
	switch (format) {
	case MTLPixelFormatRGBA16Float: return FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT;
	case MTLPixelFormatBGRA8Unorm: return FFX_SURFACE_FORMAT_B8G8R8A8_UNORM;
	case MTLPixelFormatRG16Float: return FFX_SURFACE_FORMAT_R16G16_FLOAT;
	case MTLPixelFormatDepth32Float:
	case MTLPixelFormatR32Float: return FFX_SURFACE_FORMAT_R32_FLOAT;
	default: return FFX_SURFACE_FORMAT_R8G8B8A8_UNORM;
	}
}

static FfxResource m2m_ffx_resource(id<MTLTexture> texture, uint32_t usage, const wchar_t *name) {
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

@interface M2MFsrFrameGenerator : NSObject
@property(nonatomic, strong) M2MFfxHost *host;
@property(nonatomic, strong) NSMutableArray *shared;
@property(nonatomic) NSUInteger displayWidth, displayHeight, renderWidth, renderHeight;
@property(nonatomic) BOOL hdr;
@property(nonatomic) uint64_t frameId;
@end

@implementation M2MFsrFrameGenerator {
@public
	FfxInterface _backend;
	uint8_t _scratch[64];
	FfxOpticalflowContext _opticalFlow;
	FfxFrameInterpolationContext _interpolation;
	BOOL _opticalFlowCreated;
	BOOL _interpolationCreated;
	FfxResource _opticalFlowVector;
	FfxResource _opticalFlowSceneChange;
	FfxResource _dilatedDepth;
	FfxResource _dilatedMotionVectors;
	FfxResource _previousDepth;
}

- (void)dealloc {
	if (_interpolationCreated) {
		ffxFrameInterpolationContextDestroy(&_interpolation);
	}
	if (_opticalFlowCreated) {
		ffxOpticalflowContextDestroy(&_opticalFlow);
	}
	ffxDestroyInterfaceMetal(&_backend);
}

@end

// A shared resource AMD's code asks the application to create, tracked for residency and kept by the generator.
static FfxResource m2m_fsr_shared(M2MContext *ctx, M2MFsrFrameGenerator *generator, const FfxCreateResourceDescription *description) {
	id<MTLResource> resource = ffxCreateResourceMetal(ctx.device, description);
	m2m_track(ctx, (id<MTLAllocation>)resource);
	[generator.shared addObject:resource];
	return ffxGetResourceMetal(resource, description->resourceDescription, description->name);
}

id m2m_fsr_framegen_create(M2MContext *ctx, NSUInteger displayWidth, NSUInteger displayHeight, NSUInteger renderWidth,
                           NSUInteger renderHeight, MTLPixelFormat format, BOOL hdr) {
	M2MFsrFrameGenerator *generator = [M2MFsrFrameGenerator new];
	generator.host = [M2MFfxHost new];
	generator.host.ctx = ctx;
	generator.shared = [NSMutableArray array];
	generator.displayWidth = displayWidth;
	generator.displayHeight = displayHeight;
	generator.renderWidth = renderWidth;
	generator.renderHeight = renderHeight;
	generator.hdr = hdr;
	NSDictionary<NSString *, NSString *> *sources = m2m_fsr_shader_sources();
	FfxErrorCode error = ffxGetInterfaceMetal(&generator->_backend, ctx.device, generator.host,
	                                          ^NSString *(NSString *pass, uint32_t permutation) {
		                                          BOOL lowRes = [pass hasPrefix:@"ffx_frameinterpolation_"] &&
		                                                        (permutation & M2M_FSR_LOW_RES_MOTION_VECTORS) != 0;
		                                          return sources[lowRes ? [pass stringByAppendingString:@"@lowres"] : pass];
	                                          },
	                                          generator->_scratch, sizeof(generator->_scratch));
	if (error != FFX_OK) {
		NSLog(@"mine2metal: could not set up the FidelityFX backend (0x%x)", (unsigned)error);
		return nil;
	}

	FfxOpticalflowContextDescription opticalFlow = {};
	opticalFlow.backendInterface = generator->_backend;
	opticalFlow.resolution = (FfxDimensions2D){(uint32_t)displayWidth, (uint32_t)displayHeight};
	error = ffxOpticalflowContextCreate(&generator->_opticalFlow, &opticalFlow);
	if (error != FFX_OK) {
		NSLog(@"mine2metal: could not create the FSR optical flow context (0x%x)", (unsigned)error);
		return nil;
	}
	generator->_opticalFlowCreated = YES;

	FfxFrameInterpolationContextDescription interpolation = {};
	interpolation.backendInterface = generator->_backend;
	interpolation.flags = (renderWidth == displayWidth && renderHeight == displayHeight ? FFX_FRAMEINTERPOLATION_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS : 0) |
	                      (hdr ? FFX_FRAMEINTERPOLATION_ENABLE_HDR_COLOR_INPUT : 0);
	interpolation.maxRenderSize = (FfxDimensions2D){(uint32_t)renderWidth, (uint32_t)renderHeight};
	interpolation.displaySize = (FfxDimensions2D){(uint32_t)displayWidth, (uint32_t)displayHeight};
	interpolation.backBufferFormat = m2m_ffx_format(format);
	interpolation.previousInterpolationSourceFormat = m2m_ffx_format(format);
	error = ffxFrameInterpolationContextCreate(&generator->_interpolation, &interpolation);
	if (error != FFX_OK) {
		NSLog(@"mine2metal: could not create the FSR frame interpolation context (0x%x)", (unsigned)error);
		return nil;
	}
	generator->_interpolationCreated = YES;

	FfxOpticalflowSharedResourceDescriptions opticalFlowShared;
	FfxFrameInterpolationSharedResourceDescriptions interpolationShared;
	if (ffxOpticalflowGetSharedResourceDescriptions(&generator->_opticalFlow, &opticalFlowShared) != FFX_OK ||
	    ffxFrameInterpolationGetSharedResourceDescriptions(&generator->_interpolation, &interpolationShared) != FFX_OK) {
		return nil;
	}
	generator->_opticalFlowVector = m2m_fsr_shared(ctx, generator, &opticalFlowShared.opticalFlowVector);
	generator->_opticalFlowSceneChange = m2m_fsr_shared(ctx, generator, &opticalFlowShared.opticalFlowSCD);
	generator->_dilatedDepth = m2m_fsr_shared(ctx, generator, &interpolationShared.dilatedDepth);
	generator->_dilatedMotionVectors = m2m_fsr_shared(ctx, generator, &interpolationShared.dilatedMotionVectors);
	generator->_previousDepth = m2m_fsr_shared(ctx, generator, &interpolationShared.reconstructedPrevNearestDepth);
	return generator;
}

void m2m_fsr_framegen_release(M2MContext *ctx, id generator) {
	M2MFsrFrameGenerator *fsr = generator;
	for (id resource in fsr.shared) {
		m2m_retire(ctx, resource);
	}
	// Its pipelines and resources may still be in flight: let it go with them.
	m2m_retire(ctx, generator);
}

BOOL m2m_fsr_framegen_encode(M2MContext *ctx, id generator, const M2MFsrFrame *frame) {
	M2MFsrFrameGenerator *fsr = generator;
	FfxCommandList commandList = ffxGetCommandListMetal(fsr.host);
	FfxDimensions2D renderSize = {(uint32_t)fsr.renderWidth, (uint32_t)fsr.renderHeight};
	FfxDimensions2D displaySize = {(uint32_t)fsr.displayWidth, (uint32_t)fsr.displayHeight};
	FfxResource hudless = m2m_ffx_resource(frame->hudless, FFX_RESOURCE_USAGE_READ_ONLY, L"HUD-less color");
	BOOL reset = frame->reset || fsr.frameId == 0;
	uint64_t frameId = ++fsr.frameId;
	FfxBackbufferTransferFunction transfer = fsr.hdr ? FFX_BACKBUFFER_TRANSFER_FUNCTION_SCRGB : FFX_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
	m2m_end_encoding(ctx);

	FfxFrameInterpolationPrepareDescription prepare = {};
	prepare.commandList = commandList;
	prepare.renderSize = renderSize;
	prepare.jitterOffset = (FfxFloatCoords2D){frame->jitter_x, frame->jitter_y};
	// In pixels: AMD's code divides by the motion vector size itself.
	prepare.motionVectorScale = (FfxFloatCoords2D){frame->motion_scale_x, frame->motion_scale_y};
	prepare.frameTimeDelta = frame->frame_time_ms;
	prepare.cameraNear = frame->near_plane;
	prepare.cameraFar = frame->far_plane;
	prepare.viewSpaceToMetersFactor = 1.0f;
	prepare.cameraFovAngleVertical = frame->field_of_view;
	prepare.depth = m2m_ffx_resource(frame->depth, FFX_RESOURCE_USAGE_READ_ONLY, L"depth");
	prepare.motionVectors = m2m_ffx_resource(frame->motion, FFX_RESOURCE_USAGE_READ_ONLY, L"motion vectors");
	prepare.frameID = frameId;
	prepare.dilatedDepth = fsr->_dilatedDepth;
	prepare.dilatedMotionVectors = fsr->_dilatedMotionVectors;
	prepare.reconstructedPrevDepth = fsr->_previousDepth;
	prepare.cameraUp[1] = 1.0f;
	prepare.cameraRight[0] = 1.0f;
	prepare.cameraForward[2] = 1.0f;
	FfxErrorCode error = ffxFrameInterpolationPrepare(&fsr->_interpolation, &prepare);

	FfxOpticalflowDispatchDescription flow = {};
	flow.commandList = commandList;
	flow.color = hudless;
	flow.opticalFlowVector = fsr->_opticalFlowVector;
	flow.opticalFlowSCD = fsr->_opticalFlowSceneChange;
	flow.reset = reset;
	flow.backbufferTransferFunction = transfer;
	flow.minMaxLuminance = (FfxFloatCoords2D){0.0f, frame->max_luminance};
	if (error == FFX_OK) {
		error = ffxOpticalflowContextDispatch(&fsr->_opticalFlow, &flow);
	}

	FfxFrameInterpolationDispatchDescription dispatch = {};
	dispatch.commandList = commandList;
	dispatch.displaySize = displaySize;
	dispatch.renderSize = renderSize;
	dispatch.currentBackBuffer = m2m_ffx_resource(frame->backbuffer, FFX_RESOURCE_USAGE_READ_ONLY, L"back buffer");
	dispatch.currentBackBuffer_HUDLess = hudless;
	dispatch.output = m2m_ffx_resource(frame->output, FFX_RESOURCE_USAGE_UAV, L"generated frame");
	dispatch.interpolationRect = (FfxRect2D){0, 0, (int32_t)fsr.displayWidth, (int32_t)fsr.displayHeight};
	dispatch.opticalFlowVector = fsr->_opticalFlowVector;
	dispatch.opticalFlowSceneChangeDetection = fsr->_opticalFlowSceneChange;
	dispatch.opticalFlowBufferSize = (FfxDimensions2D){fsr->_opticalFlowVector.description.width, fsr->_opticalFlowVector.description.height};
	dispatch.opticalFlowScale = (FfxFloatCoords2D){1.0f / fsr.displayWidth, 1.0f / fsr.displayHeight};
	dispatch.opticalFlowBlockSize = 8;
	dispatch.cameraNear = frame->near_plane;
	dispatch.cameraFar = frame->far_plane;
	dispatch.cameraFovAngleVertical = frame->field_of_view;
	dispatch.viewSpaceToMetersFactor = 1.0f;
	dispatch.frameTimeDelta = frame->frame_time_ms;
	dispatch.reset = reset;
	dispatch.backBufferTransferFunction = transfer;
	dispatch.minMaxLuminance[0] = 0.0f;
	dispatch.minMaxLuminance[1] = frame->max_luminance;
	dispatch.frameID = frameId;
	dispatch.dilatedDepth = fsr->_dilatedDepth;
	dispatch.dilatedMotionVectors = fsr->_dilatedMotionVectors;
	dispatch.reconstructedPrevDepth = fsr->_previousDepth;
	if (error == FFX_OK) {
		error = ffxFrameInterpolationDispatch(&fsr->_interpolation, &dispatch);
	}
	if (error != FFX_OK) {
		static BOOL warned;
		if (!warned) {
			warned = YES;
			NSLog(@"mine2metal: FSR frame generation failed (0x%x)", (unsigned)error);
		}
		return NO;
	}
	return YES;
}
