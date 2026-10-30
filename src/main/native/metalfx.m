#import <MetalFX/MetalFX.h>

#include "m2m_context.h"

// See m2m_context.h: the Metal 4 branches run only when ctx.metal4 is set, which requires macOS 26.
#pragma clang diagnostic ignored "-Wunguarded-availability-new"

// One spatial scaler is kept and rebuilt only when the sizes or formats change (window resize, render scale).
@interface M2MSpatialUpscaler : NSObject
// An MTLFXSpatialScaler, or an MTL4FXSpatialScaler with Metal 4.
@property(nonatomic, strong) id<MTLFXSpatialScalerBase> scaler;
@property(nonatomic) NSUInteger inputWidth, inputHeight, outputWidth, outputHeight;
@property(nonatomic) MTLPixelFormat format;
// MetalFX may need output usages the game's render target was not created with; it then writes here and the
// result is copied into the target.
@property(nonatomic, strong) id<MTLTexture> staging;
@end

@implementation M2MSpatialUpscaler
@end

int32_t m2m_metalfx_spatial_supported(int64_t ctx) {
	M2MContext *context = m2m_ctx(ctx);
	if (context.metal4) {
		return [MTLFXSpatialScalerDescriptor supportsMetal4FX:context.device] ? 1 : 0;
	}
	return [MTLFXSpatialScalerDescriptor supportsDevice:context.device] ? 1 : 0;
}

static M2MSpatialUpscaler *m2m_spatial_upscaler(M2MContext *ctx, id<MTLTexture> input, id<MTLTexture> output) {
	M2MSpatialUpscaler *current = ctx.spatialUpscaler;
	if (current && current.inputWidth == input.width && current.inputHeight == input.height && current.outputWidth == output.width &&
	    current.outputHeight == output.height && current.format == output.pixelFormat) {
		return current;
	}

	MTLFXSpatialScalerDescriptor *descriptor = [MTLFXSpatialScalerDescriptor new];
	descriptor.inputWidth = input.width;
	descriptor.inputHeight = input.height;
	descriptor.outputWidth = output.width;
	descriptor.outputHeight = output.height;
	descriptor.colorTextureFormat = input.pixelFormat;
	descriptor.outputTextureFormat = output.pixelFormat;
	// Minecraft renders in display (gamma-encoded) space, not linear light.
	descriptor.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;

	M2MSpatialUpscaler *created = [M2MSpatialUpscaler new];
	if (ctx.metal4) {
		created.scaler = [descriptor newSpatialScalerWithDevice:ctx.device compiler:ctx.compiler];
	} else {
		created.scaler = [descriptor newSpatialScalerWithDevice:ctx.device];
	}
	if (!created.scaler) {
		return nil;
	}
	created.inputWidth = input.width;
	created.inputHeight = input.height;
	created.outputWidth = output.width;
	created.outputHeight = output.height;
	created.format = output.pixelFormat;

	MTLTextureUsage needed = created.scaler.outputTextureUsage;
	if ((output.usage & needed) != needed) {
		created.staging = m2m_new_private_texture(ctx, output.pixelFormat, output.width, output.height, needed);
	}
	m2m_retire(ctx, current.staging);
	m2m_retire(ctx, current.scaler);
	ctx.spatialUpscaler = created;
	return created;
}

int32_t m2m_metalfx_spatial_upscale(int64_t handle, int64_t input_handle, int64_t output_handle) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(handle);
		id<MTLTexture> input = m2m_borrow(input_handle);
		id<MTLTexture> output = m2m_borrow(output_handle);
		M2MSpatialUpscaler *upscaler = m2m_spatial_upscaler(ctx, input, output);
		if (!upscaler) {
			return 0;
		}

		m2m_end_encoding_for_external(ctx);
		upscaler.scaler.inputContentWidth = input.width;
		upscaler.scaler.inputContentHeight = input.height;
		upscaler.scaler.colorTexture = input;
		upscaler.scaler.outputTexture = upscaler.staging ?: output;
		if (ctx.metal4) {
			[(id<MTL4FXSpatialScaler>)upscaler.scaler encodeToCommandBuffer:m2m_command_buffer4(ctx)];
		} else {
			[(id<MTLFXSpatialScaler>)upscaler.scaler encodeToCommandBuffer:m2m_command_buffer(ctx)];
		}

		if (upscaler.staging) {
			[m2m_copier(ctx) copyFromTexture:upscaler.staging toTexture:output];
		}
		return 1;
	}
}
