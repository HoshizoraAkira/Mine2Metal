// FSR 3 frame generation spike (docs/fsr-frame-gen.md, phase F0): runs AMD's optical flow and frame interpolation
// through the Metal backend on two frames, alternating, and reports GPU time per frame. The frame generated between
// the first and the second image is written out for a look at the quality.
//
//   fsrbench <msl dir> <first.png> <second.png> <width> <height> <frames> <output.png> [render scale]
//
// A render scale below 1 renders depth and motion smaller, as with upscaling; the shaders must then be built with
// low resolution motion vectors (the default of scripts/build-fsr-shaders.sh).
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <Metal/Metal.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

#include <FidelityFX/host/ffx_frameinterpolation.h>
#include <FidelityFX/host/ffx_opticalflow.h>

#include <algorithm>
#include <vector>

#include "ffx_metal.h"

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
	CGContextSetInterpolationQuality(context, kCGInterpolationHigh);
	CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
	CGContextRelease(context);
	CGColorSpaceRelease(space);
	CGImageRelease(image);
	CFRelease(source);

	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
	descriptor.usage = MTLTextureUsageShaderRead;
	id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
	[texture replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:pixels.data() bytesPerRow:width * 4];
	return texture;
}

static void writeImage(id<MTLDevice> device, id<MTLCommandQueue> queue, id<MTLTexture> texture, NSString *path) {
	NSUInteger width = texture.width, height = texture.height;
	id<MTLBuffer> buffer = [device newBufferWithLength:width * height * 4 options:MTLResourceStorageModeShared];
	id<MTLCommandBuffer> commandBuffer = [queue commandBuffer];
	id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
	[blit copyFromTexture:texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(width, height, 1)
	             toBuffer:buffer destinationOffset:0 destinationBytesPerRow:width * 4 destinationBytesPerImage:width * height * 4];
	[blit endEncoding];
	[commandBuffer commit];
	[commandBuffer waitUntilCompleted];
	CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
	CGContextRef context = CGBitmapContextCreate(buffer.contents, width, height, 8, width * 4, space,
	                                             kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
	CGImageRef image = CGBitmapContextCreateImage(context);
	CGImageDestinationRef destination = CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:path],
	                                                                    (__bridge CFStringRef)UTTypePNG.identifier, 1, NULL);
	CGImageDestinationAddImage(destination, image, NULL);
	CGImageDestinationFinalize(destination);
	CFRelease(destination);
	CGImageRelease(image);
	CGContextRelease(context);
	CGColorSpaceRelease(space);
}

static id<MTLTexture> filledTexture(id<MTLDevice> device, MTLPixelFormat format, NSUInteger width, NSUInteger height, const void *pixel, NSUInteger pixelSize) {
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
	descriptor.usage = MTLTextureUsageShaderRead;
	id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
	std::vector<uint8_t> row(width * pixelSize);
	for (NSUInteger x = 0; x < width; x++) {
		memcpy(row.data() + x * pixelSize, pixel, pixelSize);
	}
	for (NSUInteger y = 0; y < height; y++) {
		[texture replaceRegion:MTLRegionMake2D(0, y, width, 1) mipmapLevel:0 withBytes:row.data() bytesPerRow:width * pixelSize];
	}
	return texture;
}

// FfxResource holds a plain pointer, so every texture handed to FidelityFX is also kept here.
static NSMutableArray *keepAlive;

static FfxResource wrap(id<MTLResource> resource, FfxResourceDescription description, const wchar_t *name) {
	[keepAlive addObject:resource];
	return ffxGetResourceMetal(resource, description, name);
}

static FfxResource shared(id<MTLDevice> device, const FfxCreateResourceDescription &description) {
	return wrap(ffxCreateResourceMetal(device, &description), description.resourceDescription, description.name);
}

static FfxResourceDescription textureDescription(FfxSurfaceFormat format, uint32_t width, uint32_t height, uint32_t usage) {
	FfxResourceDescription description = {};
	description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
	description.format = format;
	description.width = width;
	description.height = height;
	description.depth = 1;
	description.mipCount = 1;
	description.usage = (FfxResourceUsage)usage;
	return description;
}

static void check(FfxErrorCode code, const char *what) {
	if (code != FFX_OK) {
		fprintf(stderr, "%s failed: 0x%x\n", what, (unsigned)code);
		exit(1);
	}
}

static double percentile(std::vector<double> values, double fraction) {
	std::sort(values.begin(), values.end());
	return values[std::min(values.size() - 1, (size_t)(fraction * (values.size() - 1) + 0.5))];
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc != 8 && argc != 9) {
			fprintf(stderr, "usage: fsrbench <msl dir> <first.png> <second.png> <width> <height> <frames> <output.png> [render scale]\n");
			return 2;
		}
		NSString *shaderDirectory = @(argv[1]);
		uint32_t width = (uint32_t)atoi(argv[4]);
		uint32_t height = (uint32_t)atoi(argv[5]);
		int frames = atoi(argv[6]);
		float renderScale = argc == 9 ? (float)atof(argv[8]) : 1.0f;
		uint32_t renderWidth = (uint32_t)(width * renderScale + 0.5f);
		uint32_t renderHeight = (uint32_t)(height * renderScale + 0.5f);
		keepAlive = [NSMutableArray array];
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> queue = [device newCommandQueue];
		printf("%s, %ux%u (rendered at %ux%u), %d frames\n", device.name.UTF8String, width, height, renderWidth, renderHeight, frames);

		static uint8_t scratch[64];
		FfxInterface backend;
		FfxMetalCommandBufferHost *host = [FfxMetalCommandBufferHost new];
		check(ffxGetInterfaceMetal(&backend, device, host, ^NSString *(NSString *pass, uint32_t) {
			      return [NSString stringWithContentsOfFile:[shaderDirectory stringByAppendingFormat:@"/%@.metal", pass]
			                                       encoding:NSUTF8StringEncoding
			                                          error:nil];
		      }, scratch, sizeof(scratch)),
		      "ffxGetInterfaceMetal");

		NSDate *start = [NSDate date];
		FfxOpticalflowContext opticalFlow;
		FfxOpticalflowContextDescription opticalFlowDescription = {};
		opticalFlowDescription.backendInterface = backend;
		opticalFlowDescription.resolution = {width, height};
		check(ffxOpticalflowContextCreate(&opticalFlow, &opticalFlowDescription), "ffxOpticalflowContextCreate");

		FfxFrameInterpolationContext interpolation;
		FfxFrameInterpolationContextDescription interpolationDescription = {};
		interpolationDescription.backendInterface = backend;
		interpolationDescription.flags = renderScale < 1.0f ? 0 : FFX_FRAMEINTERPOLATION_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS;
		interpolationDescription.maxRenderSize = {renderWidth, renderHeight};
		interpolationDescription.displaySize = {width, height};
		interpolationDescription.backBufferFormat = FFX_SURFACE_FORMAT_R8G8B8A8_UNORM;
		interpolationDescription.previousInterpolationSourceFormat = FFX_SURFACE_FORMAT_R8G8B8A8_UNORM;
		check(ffxFrameInterpolationContextCreate(&interpolation, &interpolationDescription), "ffxFrameInterpolationContextCreate");
		printf("Contexts and pipelines created in %.0f ms\n", -start.timeIntervalSinceNow * 1000.0);

		FfxOpticalflowSharedResourceDescriptions opticalFlowShared;
		check(ffxOpticalflowGetSharedResourceDescriptions(&opticalFlow, &opticalFlowShared), "optical flow shared resources");
		FfxFrameInterpolationSharedResourceDescriptions interpolationShared;
		check(ffxFrameInterpolationGetSharedResourceDescriptions(&interpolation, &interpolationShared), "interpolation shared resources");
		FfxResource opticalFlowVector = shared(device, opticalFlowShared.opticalFlowVector);
		FfxResource opticalFlowSCD = shared(device, opticalFlowShared.opticalFlowSCD);
		FfxResource dilatedDepth = shared(device, interpolationShared.dilatedDepth);
		FfxResource dilatedMotionVectors = shared(device, interpolationShared.dilatedMotionVectors);
		FfxResource previousDepth = shared(device, interpolationShared.reconstructedPrevNearestDepth);

		id<MTLTexture> images[2] = {loadImage(device, @(argv[2]), width, height), loadImage(device, @(argv[3]), width, height)};
		FfxResource colors[2];
		for (int i = 0; i < 2; i++) {
			colors[i] = wrap(images[i], textureDescription(FFX_SURFACE_FORMAT_R8G8B8A8_UNORM, width, height, FFX_RESOURCE_USAGE_READ_ONLY), L"color");
		}
		// A static camera: depth far away, no game motion. The motion between the images is left to optical flow.
		float depthValue = 0.999f;
		uint16_t noMotion[2] = {0, 0};
		FfxResource depth = wrap(filledTexture(device, MTLPixelFormatR32Float, renderWidth, renderHeight, &depthValue, 4),
		                         textureDescription(FFX_SURFACE_FORMAT_R32_FLOAT, renderWidth, renderHeight, FFX_RESOURCE_USAGE_READ_ONLY), L"depth");
		FfxResource motion = wrap(filledTexture(device, MTLPixelFormatRG16Float, renderWidth, renderHeight, noMotion, 4),
		                          textureDescription(FFX_SURFACE_FORMAT_R16G16_FLOAT, renderWidth, renderHeight, FFX_RESOURCE_USAGE_READ_ONLY), L"motion");
		MTLTextureDescriptor *outputDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
		outputDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
		outputDescriptor.storageMode = MTLStorageModePrivate;
		id<MTLTexture> outputTexture = [device newTextureWithDescriptor:outputDescriptor];
		FfxResource output = wrap(outputTexture, textureDescription(FFX_SURFACE_FORMAT_R8G8B8A8_UNORM, width, height, FFX_RESOURCE_USAGE_UAV), L"output");

		std::vector<double> opticalFlowTimes, interpolationTimes, totals;
		const float frameTime = 1000.0f / 30.0f;
		for (int frame = 0; frame < frames; frame++) {
			FfxResource current = colors[frame % 2];
			bool reset = frame == 0;

			id<MTLCommandBuffer> first = [queue commandBuffer];
			host.commandBuffer = first;
			FfxFrameInterpolationPrepareDescription prepare = {};
			prepare.commandList = ffxGetCommandListMetal(host);
			prepare.renderSize = {renderWidth, renderHeight};
			prepare.motionVectorScale = {1.0f, 1.0f};
			prepare.frameTimeDelta = frameTime;
			prepare.cameraNear = 0.05f;
			prepare.cameraFar = 512.0f;
			prepare.viewSpaceToMetersFactor = 1.0f;
			prepare.cameraFovAngleVertical = 70.0f * 3.14159265f / 180.0f;
			prepare.depth = depth;
			prepare.motionVectors = motion;
			prepare.frameID = (uint64_t)frame;
			prepare.dilatedDepth = dilatedDepth;
			prepare.dilatedMotionVectors = dilatedMotionVectors;
			prepare.reconstructedPrevDepth = previousDepth;
			prepare.cameraUp[1] = 1.0f;
			prepare.cameraRight[0] = 1.0f;
			prepare.cameraForward[2] = 1.0f;
			check(ffxFrameInterpolationPrepare(&interpolation, &prepare), "ffxFrameInterpolationPrepare");

			FfxOpticalflowDispatchDescription flow = {};
			flow.commandList = ffxGetCommandListMetal(host);
			flow.color = current;
			flow.opticalFlowVector = opticalFlowVector;
			flow.opticalFlowSCD = opticalFlowSCD;
			flow.reset = reset;
			flow.backbufferTransferFunction = FFX_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
			flow.minMaxLuminance = {0.0f, 1.0f};
			check(ffxOpticalflowContextDispatch(&opticalFlow, &flow), "ffxOpticalflowContextDispatch");
			[first commit];

			id<MTLCommandBuffer> second = [queue commandBuffer];
			host.commandBuffer = second;
			FfxFrameInterpolationDispatchDescription dispatch = {};
			dispatch.commandList = ffxGetCommandListMetal(host);
			dispatch.displaySize = {width, height};
			dispatch.renderSize = {renderWidth, renderHeight};
			dispatch.currentBackBuffer = current;
			dispatch.output = output;
			dispatch.interpolationRect = {0, 0, (int32_t)width, (int32_t)height};
			dispatch.opticalFlowVector = opticalFlowVector;
			dispatch.opticalFlowSceneChangeDetection = opticalFlowSCD;
			dispatch.opticalFlowBufferSize = {opticalFlowVector.description.width, opticalFlowVector.description.height};
			dispatch.opticalFlowScale = {1.0f / width, 1.0f / height};
			dispatch.opticalFlowBlockSize = 8;
			dispatch.cameraNear = prepare.cameraNear;
			dispatch.cameraFar = prepare.cameraFar;
			dispatch.cameraFovAngleVertical = prepare.cameraFovAngleVertical;
			dispatch.viewSpaceToMetersFactor = 1.0f;
			dispatch.frameTimeDelta = frameTime;
			dispatch.reset = reset;
			dispatch.backBufferTransferFunction = FFX_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
			dispatch.minMaxLuminance[0] = 0.0f;
			dispatch.minMaxLuminance[1] = 1.0f;
			dispatch.frameID = (uint64_t)frame;
			dispatch.dilatedDepth = dilatedDepth;
			dispatch.dilatedMotionVectors = dilatedMotionVectors;
			dispatch.reconstructedPrevDepth = previousDepth;
			check(ffxFrameInterpolationDispatch(&interpolation, &dispatch), "ffxFrameInterpolationDispatch");
			[second commit];
			[second waitUntilCompleted];
			if (first.error || second.error) {
				fprintf(stderr, "GPU error: %s\n", (first.error ?: second.error).description.UTF8String);
				return 1;
			}
			if (frame >= 10) {
				double flowMs = (first.GPUEndTime - first.GPUStartTime) * 1000.0;
				double interpolationMs = (second.GPUEndTime - second.GPUStartTime) * 1000.0;
				opticalFlowTimes.push_back(flowMs);
				interpolationTimes.push_back(interpolationMs);
				totals.push_back(flowMs + interpolationMs);
			}
			// Odd frames interpolate from the first image to the second.
			if (frame == frames - 1 - ((frames - 1) % 2 == 0 ? 1 : 0)) {
				writeImage(device, queue, outputTexture, @(argv[7]));
			}
		}
		printf("GPU ms per frame (median / p90): prepare + optical flow %.2f / %.2f, interpolation %.2f / %.2f, total %.2f / %.2f\n",
		       percentile(opticalFlowTimes, 0.5), percentile(opticalFlowTimes, 0.9), percentile(interpolationTimes, 0.5),
		       percentile(interpolationTimes, 0.9), percentile(totals, 0.5), percentile(totals, 0.9));
		ffxFrameInterpolationContextDestroy(&interpolation);
		ffxOpticalflowContextDestroy(&opticalFlow);
	}
	return 0;
}
