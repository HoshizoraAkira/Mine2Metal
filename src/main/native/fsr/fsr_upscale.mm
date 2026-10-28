#include <FidelityFX/host/ffx_fsr1.h>
#include <FidelityFX/host/ffx_fsr3upscaler.h>

#include "fsr_host.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// AMD's upscalers (FidelityFX SDK v1.1.4, MIT; docs/fsr-frame-gen.md) as choices next to MetalFX's: FSR 3.1 in place of
// the temporal scaler, fed the same depth, motion vectors and reactive mask (temporal.m), and FSR 1 (EASU, then RCAS
// sharpening) in place of the spatial one. Both cost about twice what MetalFX does on Apple GPUs; they are there for
// comparison and for players who prefer their look.

@interface M2MFsrUpscaler : NSObject
@property(nonatomic, strong) M2MFfxHost *host;
@property(nonatomic) NSUInteger renderWidth, renderHeight, displayWidth, displayHeight;
@property(nonatomic) MTLPixelFormat format;
// FSR 1 writes its output from compute; when the game's target cannot be written that way, it writes here and the
// result is copied into the target.
@property(nonatomic, strong) id<MTLTexture> staging;
@end

@implementation M2MFsrUpscaler {
@public
	FfxInterface _backend;
	uint8_t _scratch[64];
	FfxFsr3UpscalerContext _fsr3;
	FfxFsr1Context _fsr1;
	BOOL _fsr3Created;
	BOOL _fsr1Created;
}

- (void)dealloc {
	if (_fsr3Created) {
		ffxFsr3UpscalerContextDestroy(&_fsr3);
	}
	if (_fsr1Created) {
		ffxFsr1ContextDestroy(&_fsr1);
	}
	ffxDestroyInterfaceMetal(&_backend);
}

@end

static M2MFsrUpscaler *m2m_fsr_upscaler_new(M2MContext *ctx, NSUInteger renderWidth, NSUInteger renderHeight, NSUInteger displayWidth,
                                            NSUInteger displayHeight) {
	M2MFsrUpscaler *upscaler = [M2MFsrUpscaler new];
	upscaler.host = [M2MFfxHost new];
	upscaler.host.ctx = ctx;
	upscaler.renderWidth = renderWidth;
	upscaler.renderHeight = renderHeight;
	upscaler.displayWidth = displayWidth;
	upscaler.displayHeight = displayHeight;
	NSDictionary<NSString *, NSString *> *sources = m2m_fsr_shader_sources();
	FfxErrorCode error = ffxGetInterfaceMetal(&upscaler->_backend, ctx.device, upscaler.host, ^NSString *(NSString *pass, uint32_t) {
		return sources[pass];
	}, upscaler->_scratch, sizeof(upscaler->_scratch));
	if (error != FFX_OK) {
		NSLog(@"mine2metal: could not set up the FidelityFX backend (0x%x)", (unsigned)error);
		return nil;
	}
	return upscaler;
}

id m2m_fsr3_upscaler_create(M2MContext *ctx, NSUInteger renderWidth, NSUInteger renderHeight, NSUInteger displayWidth,
                            NSUInteger displayHeight) {
	M2MFsrUpscaler *upscaler = m2m_fsr_upscaler_new(ctx, renderWidth, renderHeight, displayWidth, displayHeight);
	if (!upscaler) {
		return nil;
	}
	FfxFsr3UpscalerContextDescription description = {};
	// Motion vectors at render size, depth from 0 (near) to 1 (far), colors in SDR: the world is expanded to EDR only
	// after the hand and the GUI are drawn (hdr.m).
	description.flags = 0;
	description.maxRenderSize = (FfxDimensions2D){(uint32_t)renderWidth, (uint32_t)renderHeight};
	description.maxUpscaleSize = (FfxDimensions2D){(uint32_t)displayWidth, (uint32_t)displayHeight};
	description.backendInterface = upscaler->_backend;
	FfxErrorCode error = ffxFsr3UpscalerContextCreate(&upscaler->_fsr3, &description);
	if (error != FFX_OK) {
		NSLog(@"mine2metal: could not create the FSR 3.1 upscaler (0x%x)", (unsigned)error);
		return nil;
	}
	upscaler->_fsr3Created = YES;
	return upscaler;
}

BOOL m2m_fsr3_upscaler_encode(M2MContext *ctx, id handle, const M2MFsrUpscale *frame) {
	M2MFsrUpscaler *upscaler = handle;
	m2m_end_encoding(ctx);
	FfxFsr3UpscalerDispatchDescription dispatch = {};
	dispatch.commandList = ffxGetCommandListMetal(upscaler.host);
	dispatch.color = m2m_ffx_resource(frame->color, FFX_RESOURCE_USAGE_READ_ONLY, L"color");
	dispatch.depth = m2m_ffx_resource(frame->depth, FFX_RESOURCE_USAGE_READ_ONLY, L"depth");
	dispatch.motionVectors = m2m_ffx_resource(frame->motion, FFX_RESOURCE_USAGE_READ_ONLY, L"motion vectors");
	if (frame->reactive) {
		dispatch.reactive = m2m_ffx_resource(frame->reactive, FFX_RESOURCE_USAGE_READ_ONLY, L"reactive mask");
	}
	dispatch.output = m2m_ffx_resource(frame->output, FFX_RESOURCE_USAGE_UAV, L"upscaled");
	dispatch.jitterOffset = (FfxFloatCoords2D){frame->jitter_x, frame->jitter_y};
	// In pixels: AMD's code divides by the render size itself.
	dispatch.motionVectorScale = (FfxFloatCoords2D){1.0f, 1.0f};
	dispatch.renderSize = (FfxDimensions2D){(uint32_t)frame->color.width, (uint32_t)frame->color.height};
	dispatch.upscaleSize = (FfxDimensions2D){(uint32_t)frame->output.width, (uint32_t)frame->output.height};
	dispatch.enableSharpening = false;
	dispatch.frameTimeDelta = frame->frame_time_ms;
	dispatch.preExposure = 1.0f;
	dispatch.reset = frame->reset;
	dispatch.cameraNear = frame->near_plane;
	dispatch.cameraFar = frame->far_plane;
	dispatch.cameraFovAngleVertical = frame->field_of_view;
	dispatch.viewSpaceToMetersFactor = 1.0f;
	FfxErrorCode error = ffxFsr3UpscalerContextDispatch(&upscaler->_fsr3, &dispatch);
	if (error != FFX_OK) {
		static BOOL warned;
		if (!warned) {
			warned = YES;
			NSLog(@"mine2metal: FSR 3.1 upscaling failed (0x%x)", (unsigned)error);
		}
		return NO;
	}
	return YES;
}

void m2m_fsr_upscaler_release(M2MContext *ctx, id upscaler) {
	m2m_retire(ctx, ((M2MFsrUpscaler *)upscaler).staging);
	// Its pipelines and resources may still be in flight: let it go with them.
	m2m_retire(ctx, upscaler);
}

static M2MFsrUpscaler *m2m_fsr1_upscaler(M2MContext *ctx, id<MTLTexture> input, id<MTLTexture> output) {
	M2MFsrUpscaler *current = ctx.fsrSpatialUpscaler;
	if (current && current.renderWidth == input.width && current.renderHeight == input.height && current.displayWidth == output.width &&
	    current.displayHeight == output.height && current.format == output.pixelFormat) {
		return current;
	}
	M2MFsrUpscaler *created = m2m_fsr_upscaler_new(ctx, input.width, input.height, output.width, output.height);
	if (!created) {
		return nil;
	}
	created.format = output.pixelFormat;
	FfxFsr1ContextDescription description = {};
	description.flags = FFX_FSR1_ENABLE_RCAS;
	description.outputFormat = m2m_ffx_format(output.pixelFormat);
	description.maxRenderSize = (FfxDimensions2D){(uint32_t)input.width, (uint32_t)input.height};
	description.displaySize = (FfxDimensions2D){(uint32_t)output.width, (uint32_t)output.height};
	description.backendInterface = created->_backend;
	FfxErrorCode error = ffxFsr1ContextCreate(&created->_fsr1, &description);
	if (error != FFX_OK) {
		NSLog(@"mine2metal: could not create the FSR 1 upscaler (0x%x)", (unsigned)error);
		return nil;
	}
	created->_fsr1Created = YES;
	MTLTextureUsage needed = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	if ((output.usage & needed) != needed) {
		created.staging = m2m_new_private_texture(ctx, output.pixelFormat, output.width, output.height, needed);
	}
	if (current) {
		m2m_fsr_upscaler_release(ctx, current);
	}
	ctx.fsrSpatialUpscaler = created;
	return created;
}

int32_t m2m_fsr1_upscale(int64_t handle, int64_t input_handle, int64_t output_handle, float sharpness) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> input = m2m_borrow(input_handle);
		id<MTLTexture> output = m2m_borrow(output_handle);
		M2MFsrUpscaler *upscaler = m2m_fsr1_upscaler(ctx, input, output);
		if (!upscaler) {
			return 0;
		}
		m2m_end_encoding(ctx);
		id<MTLTexture> target = upscaler.staging ?: output;
		FfxFsr1DispatchDescription dispatch = {};
		dispatch.commandList = ffxGetCommandListMetal(upscaler.host);
		dispatch.color = m2m_ffx_resource(input, FFX_RESOURCE_USAGE_READ_ONLY, L"color");
		dispatch.output = m2m_ffx_resource(target, FFX_RESOURCE_USAGE_UAV, L"upscaled");
		dispatch.renderSize = (FfxDimensions2D){(uint32_t)input.width, (uint32_t)input.height};
		dispatch.enableSharpening = true;
		dispatch.sharpness = sharpness;
		if (ffxFsr1ContextDispatch(&upscaler->_fsr1, &dispatch) != FFX_OK) {
			return 0;
		}
		m2m_end_encoding(ctx);
		if (upscaler.staging) {
			[m2m_copier(ctx) copyFromTexture:upscaler.staging toTexture:output];
		}
		return 1;
	}
}
