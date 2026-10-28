// Upscaler comparison (docs/fsr-frame-gen.md): GPU time per frame of AMD's FSR 3.1 and FSR 1 upscalers through the
// Metal backend, next to Apple's MetalFX temporal and spatial scalers, on the same input and sizes. The input is one
// image at render size with flat depth and no motion; the timings do not depend on the content much.
//
//   fsrupscale <msl dir> <image.png> <display width> <display height> <render scale> <frames>
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>

#include <FidelityFX/host/ffx_fsr1.h>
#include <FidelityFX/host/ffx_fsr3upscaler.h>

#include <algorithm>
#include <functional>
#include <vector>

#include "ffx_metal.h"

static NSMutableArray *keepAlive;

static id<MTLTexture> newTexture(id<MTLDevice> device, MTLPixelFormat format, NSUInteger width, NSUInteger height, MTLTextureUsage usage) {
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
	descriptor.usage = usage;
	descriptor.storageMode = MTLStorageModePrivate;
	id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
	[keepAlive addObject:texture];
	return texture;
}

static id<MTLTexture> loadImage(id<MTLDevice> device, NSString *path, NSUInteger width, NSUInteger height) {
	CGImageSourceRef source = CGImageSourceCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:path], NULL);
	CGImageRef image = source ? CGImageSourceCreateImageAtIndex(source, 0, NULL) : NULL;
	if (!image) {
		fprintf(stderr, "Could not read %s\n", path.UTF8String);
		exit(1);
	}
	std::vector<uint8_t> pixels(width * height * 4);
	CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
	CGContextRef context = CGBitmapContextCreate(pixels.data(), width, height, 8, width * 4, space,
	                                             kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
	CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
	CGContextRelease(context);
	CGColorSpaceRelease(space);
	CGImageRelease(image);
	CFRelease(source);
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
	descriptor.usage = MTLTextureUsageShaderRead;
	id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
	[texture replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:pixels.data() bytesPerRow:width * 4];
	[keepAlive addObject:texture];
	return texture;
}

static FfxResourceDescription description(FfxSurfaceFormat format, uint32_t width, uint32_t height, uint32_t usage) {
	FfxResourceDescription result = {};
	result.type = FFX_RESOURCE_TYPE_TEXTURE2D;
	result.format = format;
	result.width = width;
	result.height = height;
	result.depth = 1;
	result.mipCount = 1;
	result.usage = (FfxResourceUsage)usage;
	return result;
}

static void check(FfxErrorCode code, const char *what) {
	if (code != FFX_OK) {
		fprintf(stderr, "%s failed: 0x%x\n", what, (unsigned)code);
		exit(1);
	}
}

// Encodes one frame per call into a fresh command buffer and reports the median GPU time after a warm-up.
static void measure(id<MTLCommandQueue> queue, const char *name, int frames, std::function<void(id<MTLCommandBuffer>, int)> encode) {
	std::vector<double> times;
	for (int frame = 0; frame < frames; frame++) {
		id<MTLCommandBuffer> commandBuffer = [queue commandBuffer];
		encode(commandBuffer, frame);
		[commandBuffer commit];
		[commandBuffer waitUntilCompleted];
		if (commandBuffer.error) {
			fprintf(stderr, "%s: GPU error %s\n", name, commandBuffer.error.description.UTF8String);
			exit(1);
		}
		if (frame >= 10) {
			times.push_back((commandBuffer.GPUEndTime - commandBuffer.GPUStartTime) * 1000.0);
		}
	}
	std::sort(times.begin(), times.end());
	printf("  %-22s %6.2f ms (p90 %.2f)\n", name, times[times.size() / 2], times[times.size() * 9 / 10]);
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc != 7) {
			fprintf(stderr, "usage: fsrupscale <msl dir> <image.png> <display width> <display height> <render scale> <frames>\n");
			return 2;
		}
		keepAlive = [NSMutableArray array];
		NSString *shaderDirectory = @(argv[1]);
		uint32_t width = (uint32_t)atoi(argv[3]);
		uint32_t height = (uint32_t)atoi(argv[4]);
		float scale = (float)atof(argv[5]);
		int frames = atoi(argv[6]);
		uint32_t renderWidth = (uint32_t)(width * scale + 0.5f);
		uint32_t renderHeight = (uint32_t)(height * scale + 0.5f);
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> queue = [device newCommandQueue];
		printf("%s, %ux%u from %ux%u\n", device.name.UTF8String, width, height, renderWidth, renderHeight);

		id<MTLTexture> color = loadImage(device, @(argv[2]), renderWidth, renderHeight);
		id<MTLTexture> depth = newTexture(device, MTLPixelFormatDepth32Float, renderWidth, renderHeight,
		                                  MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget);
		id<MTLTexture> motion = newTexture(device, MTLPixelFormatRG16Float, renderWidth, renderHeight,
		                                   MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget);
		id<MTLTexture> output = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height,
		                                   MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget);
		{
			// Far depth, no motion.
			id<MTLCommandBuffer> commandBuffer = [queue commandBuffer];
			MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
			pass.colorAttachments[0].texture = motion;
			pass.colorAttachments[0].loadAction = MTLLoadActionClear;
			pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
			pass.colorAttachments[0].storeAction = MTLStoreActionStore;
			pass.depthAttachment.texture = depth;
			pass.depthAttachment.loadAction = MTLLoadActionClear;
			pass.depthAttachment.clearDepth = 0.999;
			pass.depthAttachment.storeAction = MTLStoreActionStore;
			[[commandBuffer renderCommandEncoderWithDescriptor:pass] endEncoding];
			[commandBuffer commit];
			[commandBuffer waitUntilCompleted];
		}

		static uint8_t scratch[64];
		FfxInterface backend;
		check(ffxGetInterfaceMetal(&backend, device, ^NSString *(NSString *pass) {
			      return [NSString stringWithContentsOfFile:[shaderDirectory stringByAppendingFormat:@"/%@.metal", pass]
			                                       encoding:NSUTF8StringEncoding
			                                          error:nil];
		      }, scratch, sizeof(scratch)),
		      "ffxGetInterfaceMetal");

		FfxResource colorResource = ffxGetResourceMetal(color, description(FFX_SURFACE_FORMAT_R8G8B8A8_UNORM, renderWidth, renderHeight, 0), L"color");
		FfxResource depthResource = ffxGetResourceMetal(depth, description(FFX_SURFACE_FORMAT_R32_FLOAT, renderWidth, renderHeight, 0), L"depth");
		FfxResource motionResource = ffxGetResourceMetal(motion, description(FFX_SURFACE_FORMAT_R16G16_FLOAT, renderWidth, renderHeight, 0), L"motion");
		FfxResource outputResource = ffxGetResourceMetal(output, description(FFX_SURFACE_FORMAT_R8G8B8A8_UNORM, width, height, FFX_RESOURCE_USAGE_UAV), L"output");

		FfxFsr3UpscalerContext fsr3;
		FfxFsr3UpscalerContextDescription fsr3Description = {};
		fsr3Description.maxRenderSize = {renderWidth, renderHeight};
		fsr3Description.maxUpscaleSize = {width, height};
		fsr3Description.backendInterface = backend;
		check(ffxFsr3UpscalerContextCreate(&fsr3, &fsr3Description), "ffxFsr3UpscalerContextCreate");
		int32_t phases = ffxFsr3UpscalerGetJitterPhaseCount((int32_t)renderWidth, (int32_t)width);

		FfxFsr1Context fsr1;
		FfxFsr1ContextDescription fsr1Description = {};
		fsr1Description.flags = FFX_FSR1_ENABLE_RCAS;
		fsr1Description.outputFormat = FFX_SURFACE_FORMAT_R8G8B8A8_UNORM;
		fsr1Description.maxRenderSize = {renderWidth, renderHeight};
		fsr1Description.displaySize = {width, height};
		fsr1Description.backendInterface = backend;
		check(ffxFsr1ContextCreate(&fsr1, &fsr1Description), "ffxFsr1ContextCreate");

		MTLFXTemporalScalerDescriptor *temporalDescriptor = [MTLFXTemporalScalerDescriptor new];
		temporalDescriptor.inputWidth = renderWidth;
		temporalDescriptor.inputHeight = renderHeight;
		temporalDescriptor.outputWidth = width;
		temporalDescriptor.outputHeight = height;
		temporalDescriptor.colorTextureFormat = MTLPixelFormatRGBA8Unorm;
		temporalDescriptor.depthTextureFormat = MTLPixelFormatDepth32Float;
		temporalDescriptor.motionTextureFormat = MTLPixelFormatRG16Float;
		temporalDescriptor.outputTextureFormat = MTLPixelFormatRGBA8Unorm;
		id<MTLFXTemporalScaler> temporal = [temporalDescriptor newTemporalScalerWithDevice:device];

		MTLFXSpatialScalerDescriptor *spatialDescriptor = [MTLFXSpatialScalerDescriptor new];
		spatialDescriptor.inputWidth = renderWidth;
		spatialDescriptor.inputHeight = renderHeight;
		spatialDescriptor.outputWidth = width;
		spatialDescriptor.outputHeight = height;
		spatialDescriptor.colorTextureFormat = MTLPixelFormatRGBA8Unorm;
		spatialDescriptor.outputTextureFormat = MTLPixelFormatRGBA8Unorm;
		spatialDescriptor.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
		id<MTLFXSpatialScaler> spatial = [spatialDescriptor newSpatialScalerWithDevice:device];

		measure(queue, "FSR 3.1 upscaler", frames, [&](id<MTLCommandBuffer> commandBuffer, int frame) {
			FfxFsr3UpscalerDispatchDescription dispatch = {};
			dispatch.commandList = ffxGetCommandListMetal(commandBuffer);
			dispatch.color = colorResource;
			dispatch.depth = depthResource;
			dispatch.motionVectors = motionResource;
			dispatch.output = outputResource;
			ffxFsr3UpscalerGetJitterOffset(&dispatch.jitterOffset.x, &dispatch.jitterOffset.y, frame, phases);
			dispatch.motionVectorScale = {1.0f, 1.0f};
			dispatch.renderSize = {renderWidth, renderHeight};
			dispatch.upscaleSize = {width, height};
			dispatch.frameTimeDelta = 16.7f;
			dispatch.preExposure = 1.0f;
			dispatch.reset = frame == 0;
			dispatch.cameraNear = 0.05f;
			dispatch.cameraFar = 512.0f;
			dispatch.cameraFovAngleVertical = 70.0f * 3.14159265f / 180.0f;
			dispatch.viewSpaceToMetersFactor = 1.0f;
			check(ffxFsr3UpscalerContextDispatch(&fsr3, &dispatch), "ffxFsr3UpscalerContextDispatch");
		});
		measure(queue, "MetalFX temporal", frames, [&](id<MTLCommandBuffer> commandBuffer, int frame) {
			temporal.colorTexture = color;
			temporal.depthTexture = depth;
			temporal.motionTexture = motion;
			temporal.outputTexture = output;
			temporal.inputContentWidth = renderWidth;
			temporal.inputContentHeight = renderHeight;
			float jitterX = 0.0f, jitterY = 0.0f;
			ffxFsr3UpscalerGetJitterOffset(&jitterX, &jitterY, frame, phases);
			temporal.jitterOffsetX = jitterX;
			temporal.jitterOffsetY = jitterY;
			temporal.reset = frame == 0;
			[temporal encodeToCommandBuffer:commandBuffer];
		});
		measure(queue, "FSR 1 (EASU + RCAS)", frames, [&](id<MTLCommandBuffer> commandBuffer, int) {
			FfxFsr1DispatchDescription dispatch = {};
			dispatch.commandList = ffxGetCommandListMetal(commandBuffer);
			dispatch.color = colorResource;
			dispatch.output = outputResource;
			dispatch.renderSize = {renderWidth, renderHeight};
			dispatch.enableSharpening = true;
			dispatch.sharpness = 0.2f;
			check(ffxFsr1ContextDispatch(&fsr1, &dispatch), "ffxFsr1ContextDispatch");
		});
		measure(queue, "MetalFX spatial", frames, [&](id<MTLCommandBuffer> commandBuffer, int) {
			spatial.colorTexture = color;
			spatial.outputTexture = output;
			spatial.inputContentWidth = renderWidth;
			spatial.inputContentHeight = renderHeight;
			[spatial encodeToCommandBuffer:commandBuffer];
		});
		ffxFsr3UpscalerContextDestroy(&fsr3);
		ffxFsr1ContextDestroy(&fsr1);
	}
	return 0;
}
