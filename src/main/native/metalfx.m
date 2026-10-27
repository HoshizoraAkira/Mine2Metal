#import <MetalFX/MetalFX.h>

#include "m2m_context.h"

// One spatial scaler is kept and rebuilt only when the sizes or formats change (window resize, render scale).
@interface M2MSpatialUpscaler : NSObject
@property(nonatomic, strong) id<MTLFXSpatialScaler> scaler;
@property(nonatomic) NSUInteger inputWidth, inputHeight, outputWidth, outputHeight;
@property(nonatomic) MTLPixelFormat format;
// MetalFX may need output usages the game's render target was not created with; it then writes here and the
// result is copied into the target.
@property(nonatomic, strong) id<MTLTexture> staging;
@end

@implementation M2MSpatialUpscaler
@end

int32_t m2m_metalfx_spatial_supported(int64_t ctx) {
	return [MTLFXSpatialScalerDescriptor supportsDevice:m2m_ctx(ctx).device] ? 1 : 0;
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
	created.scaler = [descriptor newSpatialScalerWithDevice:ctx.device];
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
		MTLTextureDescriptor *staging = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:output.pixelFormat
		                                                                                   width:output.width
		                                                                                  height:output.height
		                                                                               mipmapped:NO];
		staging.storageMode = MTLStorageModePrivate;
		staging.usage = needed;
		created.staging = [ctx.device newTextureWithDescriptor:staging];
	}
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

		m2m_end_encoding(ctx);
		id<MTLCommandBuffer> commandBuffer = m2m_command_buffer(ctx);
		upscaler.scaler.inputContentWidth = input.width;
		upscaler.scaler.inputContentHeight = input.height;
		upscaler.scaler.colorTexture = input;
		upscaler.scaler.outputTexture = upscaler.staging ?: output;
		[upscaler.scaler encodeToCommandBuffer:commandBuffer];

		if (upscaler.staging) {
			[m2m_blit_encoder(ctx) copyFromTexture:upscaler.staging toTexture:output];
		}
		return 1;
	}
}
