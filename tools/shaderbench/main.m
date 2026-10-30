// Offline benchmark of the world shaders (src/main/native/world_shaders.metal): GPU time per pass at a chosen size,
// on synthetic inputs that stand in for the game's G-buffer, depth and voxel grid (a hilly terrain with trees and a
// lake, the camera a few blocks above the ground looking at the horizon). The passes are bound exactly as shaders.m
// binds them, each in a command buffer of its own, and the median over the frames after a warm-up is reported.
//
//   shaderbench <world_shaders.metal> <width> <height> <lighting scale %> <preset 1..4> <frames> [dump.png]
//
// The numbers are for comparing versions of the shaders with each other on the same Mac; the game's own content
// differs (the lighting cost depends on how many rays hit something, and how soon).
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
	float matrices[52];
	float environment[36];
	int32_t quality[8];
} Uniforms;

typedef struct {
	float pos_radius[4];
	float color_power[4];
} Light;

typedef struct {
	float inverseViewProj[16];
	float cameraPos[4];
	float forward[4];
	float planes[4];     // m22, m32, translucent pass, unused
	float size[4];
} SynthParams;

// The same terrain on the CPU (voxels) and the GPU (depth and G-buffer).
static NSString *const TERRAIN_FUNCTIONS =
    @"static float bench_height(float x, float z) {\n"
     "    return 64.0 + 7.0 * sin(x * 0.061) + 5.0 * cos(z * 0.047 + 1.3) + 3.0 * sin((x + z) * 0.11) + 2.0 * cos((x - z) * 0.19);\n"
     "}\n"
     "static float bench_hash(float a, float b) {\n"
     "    return fract(sin(a * 12.9898 + b * 78.233) * 43758.5453);\n"
     "}\n"
     // 0 air, 1 solid, 2 leaves, 3 water, for a block position.
     "static int bench_voxel(float x, float y, float z) {\n"
     "    float h = floor(bench_height(x + 0.5, z + 0.5));\n"
     "    if (y <= h) { return 1; }\n"
     "    if (y <= 61.0) { return 3; }\n"
     "    float cx = floor(x / 7.0), cz = floor(z / 7.0);\n"
     "    float r = bench_hash(cx, cz);\n"
     "    if (r > 0.55) {\n"
     "        float tx = cx * 7.0 + 1.0 + floor(bench_hash(cx + 3.0, cz) * 5.0);\n"
     "        float tz = cz * 7.0 + 1.0 + floor(bench_hash(cx, cz + 5.0) * 5.0);\n"
     "        float th = floor(bench_height(tx + 0.5, tz + 0.5));\n"
     "        if (th > 61.0) {\n"
     "            if (x == tx && z == tz && y <= th + 5.0) { return 1; }\n"
     "            float dx = x - tx, dy = y - (th + 6.0), dz = z - tz;\n"
     "            if (dx * dx + dy * dy * 1.4 + dz * dz <= 7.0) { return 2; }\n"
     "        }\n"
     "    }\n"
     "    return 0;\n"
     "}\n";

static NSString *const SYNTH_SHADER =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct SynthParams { float4x4 inverseViewProj; float4 cameraPos; float4 forward; float4 planes; float4 size; };\n"
     "struct SynthOut { half4 color [[color(0)]]; half4 albedo [[color(1)]]; half4 data [[color(2)]]; float depth [[depth(any)]]; };\n"
     "vertex float4 bench_vertex(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    return float4(p * 2.0 - 1.0, 0.0, 1.0);\n"
     "}\n"
     "static float2 bench_oct(float3 n) {\n"
     "    n /= abs(n.x) + abs(n.y) + abs(n.z);\n"
     "    float2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);\n"
     "    return e * 0.5 + 0.5;\n"
     "}\n"
     "static float3 bench_unproject(float2 uv, float z, constant SynthParams &p) {\n"
     "    float4 r = p.inverseViewProj * float4(uv * 2.0 - 1.0, z, 1.0);\n"
     "    return r.xyz / r.w;\n"
     "}\n"
     "fragment SynthOut bench_synth(float4 position [[position]], constant SynthParams &p [[buffer(0)]],\n"
     "                              depth2d<float> opaqueDepth [[texture(0)]]) {\n"
     "    float2 uv = position.xy / p.size.xy;\n"
     "    float3 a = bench_unproject(uv, 0.9, p);\n"
     "    float3 b = bench_unproject(uv, 0.5, p);\n"
     "    float3 dir = normalize(b - a);\n"
     "    float3 cam = p.cameraPos.xyz;\n"
     "    bool translucent = p.planes.z > 0.5;\n"
     "    SynthOut out;\n"
     "    out.color = half4(0.55h, 0.72h, 0.95h, 1.0h);\n"
     "    out.albedo = half4(0.0h);\n"
     "    out.data = half4(0.0h);\n"
     "    out.depth = 0.0;\n"
     "    if (translucent) {\n"
     "        if (dir.y >= -1.0e-4) { discard_fragment(); }\n"
     "        float t = (61.9 - cam.y) / dir.y;\n"
     "        float3 w = cam + dir * t;\n"
     "        if (t > 700.0 || bench_height(w.x, w.z) >= 61.9) { discard_fragment(); }\n"
     "        float d = dot(w - cam, p.forward.xyz);\n"
     "        out.depth = p.planes.y / d - p.planes.x;\n"
     "        if (opaqueDepth.read(uint2(position.xy)) > out.depth) { discard_fragment(); }\n"
     "        out.color = half4(0.2h, 0.35h, 0.7h, 1.0h);\n"
     "        out.albedo = half4(0.25h, 0.45h, 0.9h, half(17.0 / 255.0));\n"
     "        out.data = half4(half2(bench_oct(float3(0.0, 1.0, 0.0))), 0.0h, 1.0h);\n"
     "        return out;\n"
     "    }\n"
     "    float t = 0.2;\n"
     "    bool hit = false;\n"
     "    int kind = 1;\n"
     "    float3 w = cam;\n"
     "    for (int i = 0; i < 900 && t < 700.0; i++) {\n"
     "        w = cam + dir * t;\n"
     "        float h = bench_height(w.x, w.z);\n"
     "        float above = w.y - h;\n"
     "        if (above <= 0.0) { hit = true; break; }\n"
     "        int v = bench_voxel(floor(w.x), floor(w.y), floor(w.z));\n"
     "        if (v == 1 || v == 2) { hit = true; kind = v; break; }\n"
     "        t += max(0.12, min(above * 0.45, 0.5 + t * 0.01));\n"
     "    }\n"
     "    if (!hit) { return out; }\n"
     "    float d = dot(w - cam, p.forward.xyz);\n"
     "    out.depth = p.planes.y / d - p.planes.x;\n"
     "    float3 n;\n"
     "    half3 albedo;\n"
     "    if (kind == 2) {\n"
     "        n = normalize(float3(bench_hash(floor(w.x), floor(w.z)) - 0.5, 0.8, bench_hash(floor(w.z), floor(w.y)) - 0.5));\n"
     "        albedo = half3(0.22h, 0.5h, 0.14h);\n"
     "    } else {\n"
     "        float e = 0.05;\n"
     "        float hx = bench_height(w.x + e, w.z) - bench_height(w.x - e, w.z);\n"
     "        float hz = bench_height(w.x, w.z + e) - bench_height(w.x, w.z - e);\n"
     "        n = normalize(float3(-hx, 2.0 * e, -hz));\n"
     "        float grass = smoothstep(0.75, 0.9, n.y);\n"
     "        albedo = half3(mix(float3(0.45, 0.42, 0.4), float3(0.35, 0.6, 0.2), grass));\n"
     "        if (w.y < 62.5) { albedo = half3(0.7h, 0.65h, 0.45h); }\n"
     "    }\n"
     "    float sky = w.y < 61.0 ? 0.3 : 0.95 - 0.1 * bench_hash(floor(w.x), floor(w.z));\n"
     "    float block = bench_hash(floor(w.z), floor(w.x) + 9.0) > 0.9 ? 0.6 : 0.0;\n"
     "    out.color = half4(albedo * half(0.6 + 0.4 * saturate(n.y)), 1.0h);\n"
     "    out.albedo = half4(albedo, half(float(kind) / 255.0));\n"
     "    out.data = half4(half2(bench_oct(n)), half(block), half(sky));\n"
     "    return out;\n"
     "}\n";

static float benchHeight(float x, float z) {
	return 64.0f + 7.0f * sinf(x * 0.061f) + 5.0f * cosf(z * 0.047f + 1.3f) + 3.0f * sinf((x + z) * 0.11f) + 2.0f * cosf((x - z) * 0.19f);
}

static float benchHash(float a, float b) {
	float v = sinf(a * 12.9898f + b * 78.233f) * 43758.5453f;
	return v - floorf(v);
}

static int benchVoxel(float x, float y, float z) {
	float h = floorf(benchHeight(x + 0.5f, z + 0.5f));
	if (y <= h) {
		return 1;
	}
	if (y <= 61.0f) {
		return 3;
	}
	float cx = floorf(x / 7.0f), cz = floorf(z / 7.0f);
	float r = benchHash(cx, cz);
	if (r > 0.55f) {
		float tx = cx * 7.0f + 1.0f + floorf(benchHash(cx + 3.0f, cz) * 5.0f);
		float tz = cz * 7.0f + 1.0f + floorf(benchHash(cx, cz + 5.0f) * 5.0f);
		float th = floorf(benchHeight(tx + 0.5f, tz + 0.5f));
		if (th > 61.0f) {
			if (x == tx && z == tz && y <= th + 5.0f) {
				return 1;
			}
			float dx = x - tx, dy = y - (th + 6.0f), dz = z - tz;
			if (dx * dx + dy * dy * 1.4f + dz * dz <= 7.0f) {
				return 2;
			}
		}
	}
	return 0;
}

static NSMutableArray *keepAlive;

static id<MTLTexture> newTexture(id<MTLDevice> device, MTLPixelFormat format, NSUInteger width, NSUInteger height, MTLTextureUsage usage) {
	MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
	descriptor.usage = usage;
	descriptor.storageMode = MTLStorageModePrivate;
	id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
	[keepAlive addObject:texture];
	return texture;
}

static id<MTLRenderPipelineState> pipeline(id<MTLDevice> device, id<MTLLibrary> library, NSString *vertex, NSString *fragment,
                                           const MTLPixelFormat *formats, NSUInteger count, MTLPixelFormat depth, BOOL additive) {
	MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
	desc.label = fragment;
	desc.vertexFunction = [library newFunctionWithName:vertex];
	// Function constant 0 (linear I/O) for the passes that have it; older shader versions have none.
	NSError *fcError = nil;
	MTLFunctionConstantValues *values = [MTLFunctionConstantValues new];
	bool linear = true;
	[values setConstantValue:&linear type:MTLDataTypeBool atIndex:0];
	id<MTLFunction> withConstants = [library newFunctionWithName:fragment constantValues:values error:&fcError];
	desc.fragmentFunction = withConstants ?: [library newFunctionWithName:fragment];
	for (NSUInteger i = 0; i < count; i++) {
		desc.colorAttachments[i].pixelFormat = formats[i];
		if (additive) {
			desc.colorAttachments[i].blendingEnabled = YES;
			desc.colorAttachments[i].sourceRGBBlendFactor = MTLBlendFactorOne;
			desc.colorAttachments[i].destinationRGBBlendFactor = MTLBlendFactorOne;
			desc.colorAttachments[i].sourceAlphaBlendFactor = MTLBlendFactorOne;
			desc.colorAttachments[i].destinationAlphaBlendFactor = MTLBlendFactorOne;
		}
	}
	desc.depthAttachmentPixelFormat = depth;
	NSError *error = nil;
	id<MTLRenderPipelineState> state = [device newRenderPipelineStateWithDescriptor:desc error:&error];
	if (!state) {
		fprintf(stderr, "Pipeline %s: %s\n", fragment.UTF8String, error.localizedDescription.UTF8String);
		exit(1);
	}
	return state;
}

static MTLRenderPassDescriptor *colorPass(id<MTLTexture> target, MTLLoadAction load) {
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = target;
	pass.colorAttachments[0].loadAction = load;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	return pass;
}

// Column-major 4x4 helpers.
static void matMul(const float *a, const float *b, float *out) {
	for (int c = 0; c < 4; c++) {
		for (int r = 0; r < 4; r++) {
			float s = 0.0f;
			for (int k = 0; k < 4; k++) {
				s += a[k * 4 + r] * b[c * 4 + k];
			}
			out[c * 4 + r] = s;
		}
	}
}

static BOOL matInvert(const float *m, float *out) {
	float inv[16];
	inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
	inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
	inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
	inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
	inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
	inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
	inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
	inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
	inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
	inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
	inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
	inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
	inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
	inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
	inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
	inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
	float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
	if (fabsf(det) < 1.0e-20f) {
		return NO;
	}
	for (int i = 0; i < 16; i++) {
		out[i] = inv[i] / det;
	}
	return YES;
}

typedef struct {
	const char *name;
	double *samples;
	int count;
} Timing;

static int compareDouble(const void *a, const void *b) {
	double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}

static void writePng(id<MTLDevice> device, id<MTLCommandQueue> queue, id<MTLTexture> texture, NSString *path) {
	NSUInteger width = texture.width, height = texture.height;
	id<MTLBuffer> buffer = [device newBufferWithLength:width * height * 4 options:MTLResourceStorageModeShared];
	id<MTLCommandBuffer> commandBuffer = [queue commandBuffer];
	id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
	[blit copyFromTexture:texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(width, height, 1)
	             toBuffer:buffer destinationOffset:0 destinationBytesPerRow:width * 4 destinationBytesPerImage:width * height * 4];
	[blit endEncoding];
	[commandBuffer commit];
	[commandBuffer waitUntilCompleted];
	// Rows are bottom-up, as in the game.
	uint8_t *flipped = malloc(width * height * 4);
	for (NSUInteger y = 0; y < height; y++) {
		memcpy(flipped + y * width * 4, (uint8_t *)buffer.contents + (height - 1 - y) * width * 4, width * 4);
	}
	CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
	CGContextRef context = CGBitmapContextCreate(flipped, width, height, 8, width * 4, space, kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
	CGImageRef image = CGBitmapContextCreateImage(context);
	CGImageDestinationRef destination = CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:path],
	                                                                    (__bridge CFStringRef)UTTypePNG.identifier, 1, NULL);
	CGImageDestinationAddImage(destination, image, NULL);
	CGImageDestinationFinalize(destination);
	CFRelease(destination);
	CGImageRelease(image);
	CGContextRelease(context);
	CGColorSpaceRelease(space);
	free(flipped);
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc < 7) {
			fprintf(stderr, "usage: shaderbench <world_shaders.metal> <width> <height> <lighting scale %%> <preset 1..4> <frames> [dump.png]\n");
			return 2;
		}
		NSString *shaderPath = @(argv[1]);
		NSUInteger width = (NSUInteger)atoi(argv[2]);
		NSUInteger height = (NSUInteger)atoi(argv[3]);
		int scale = atoi(argv[4]);
		int preset = atoi(argv[5]);
		int frames = atoi(argv[6]);
		NSString *dumpPath = argc > 7 ? @(argv[7]) : nil;
		keepAlive = [NSMutableArray array];

		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> queue = [device newCommandQueue];
		NSError *error = nil;
		NSString *source = [NSString stringWithContentsOfFile:shaderPath encoding:NSUTF8StringEncoding error:&error];
		if (!source) {
			fprintf(stderr, "Could not read %s: %s\n", argv[1], error.localizedDescription.UTF8String);
			return 1;
		}
		MTLCompileOptions *options = [MTLCompileOptions new];
		options.languageVersion = MTLLanguageVersion3_0;
		options.mathMode = MTLMathModeFast;
		CFTimeInterval compileStart = CACurrentMediaTime();
		id<MTLLibrary> library = [device newLibraryWithSource:source options:options error:&error];
		if (!library) {
			fprintf(stderr, "World shaders: %s\n", error.localizedDescription.UTF8String);
			return 1;
		}
		printf("device %s, world shaders compiled in %.0f ms\n", device.name.UTF8String, (CACurrentMediaTime() - compileStart) * 1000.0);
		id<MTLLibrary> synthLibrary = [device newLibraryWithSource:[@"#include <metal_stdlib>\nusing namespace metal;\n" stringByAppendingString:[TERRAIN_FUNCTIONS stringByAppendingString:SYNTH_SHADER]]
		                                                   options:options error:&error];
		if (!synthLibrary) {
			fprintf(stderr, "Synth shader: %s\n", error.localizedDescription.UTF8String);
			return 1;
		}

		// Textures, as shaders.m makes them.
		MTLTextureUsage rt = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		id<MTLTexture> color = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height, rt);
		id<MTLTexture> depth = newTexture(device, MTLPixelFormatDepth32Float, width, height, rt);
		id<MTLTexture> gAlbedo = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height, rt);
		id<MTLTexture> gData = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height, rt);
		id<MTLTexture> bloomSource = newTexture(device, MTLPixelFormatRG11B10Float, width, height, rt);
		NSMutableArray *bloomLevels = [NSMutableArray array];
		for (int i = 0; i < 4; i++) {
			[bloomLevels addObject:newTexture(device, MTLPixelFormatRG11B10Float, MAX(1u, width >> (i + 1)), MAX(1u, height >> (i + 1)), rt)];
		}
		NSUInteger lightWidth = MAX(1u, width * (NSUInteger)scale / 100u);
		NSUInteger lightHeight = MAX(1u, height * (NSUInteger)scale / 100u);
		NSUInteger maxHeight = 1200u * (NSUInteger)scale / 100u;
		if (lightHeight > maxHeight) {
			lightWidth = MAX(1u, width * maxHeight / height);
			lightHeight = maxHeight;
		}
		id<MTLTexture> light0 = newTexture(device, MTLPixelFormatRGBA16Float, lightWidth, lightHeight, rt);
		id<MTLTexture> light1 = newTexture(device, MTLPixelFormatRGBA16Float, lightWidth, lightHeight, rt);
		id<MTLTexture> clouds0 = newTexture(device, MTLPixelFormatRGBA16Float, MAX(1u, lightWidth / 2), MAX(1u, lightHeight / 2), rt);
		id<MTLTexture> clouds1 = newTexture(device, MTLPixelFormatRGBA16Float, MAX(1u, lightWidth / 2), MAX(1u, lightHeight / 2), rt);
		id<MTLTexture> shafts = newTexture(device, MTLPixelFormatRG11B10Float, MAX(1u, width / 2), MAX(1u, height / 2), rt);
		id<MTLTexture> skyLut = newTexture(device, MTLPixelFormatRGBA16Float, 512, 256, rt);
		id<MTLTexture> opaqueColor = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height, MTLTextureUsageShaderRead);
		id<MTLTexture> colorSrgb = [color newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm_sRGB];
		id<MTLTexture> opaqueSrgb = [opaqueColor newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm_sRGB];
		[keepAlive addObject:colorSrgb];
		[keepAlive addObject:opaqueSrgb];
		id<MTLTexture> opaqueDepth = newTexture(device, MTLPixelFormatDepth32Float, width, height, MTLTextureUsageShaderRead);
		// Synthesized once: the opaque world, and the translucent terrain (the water surface) with its G-buffer and depth.
		id<MTLTexture> vanillaColor = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height, rt);
		id<MTLTexture> depthWater = newTexture(device, MTLPixelFormatDepth32Float, width, height, rt);
		id<MTLTexture> gAlbedoWater = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height, rt);
		id<MTLTexture> gDataWater = newTexture(device, MTLPixelFormatRGBA8Unorm, width, height, rt);
		id<MTLBuffer> voxels = [device newBufferWithLength:128 * 128 * 128 options:MTLResourceStorageModeShared];
		NSUInteger bands = (width + 15) / 16;
		NSUInteger rowBlocks = (height + 63) / 64;
		NSUInteger levels = 1;
		while ((1u << levels) <= bands) {
			levels++;
		}
		(void)rowBlocks;
		id<MTLBuffer> skylineBands = [device newBufferWithLength:bands * 4 options:MTLResourceStorageModePrivate];
		id<MTLBuffer> skyline = [device newBufferWithLength:bands * levels * 4 options:MTLResourceStorageModePrivate];
		uint32_t bandCount = (uint32_t)bands;
		NSUInteger hizFloats = 0;
		uint32_t hizSizes[6][2];
		uint32_t hizOffsets[6];
		{
			NSUInteger w = width, h = height;
			for (int l = 0; l < 6; l++) {
				hizSizes[l][0] = (uint32_t)w;
				hizSizes[l][1] = (uint32_t)h;
				hizOffsets[l] = (uint32_t)hizFloats;
				if (l > 0) {
					hizFloats += w * h;
				}
				w = MAX(1u, (w + 1) / 2);
				h = MAX(1u, (h + 1) / 2);
			}
		}
		id<MTLBuffer> hiz = [device newBufferWithLength:hizFloats * 8 options:MTLResourceStorageModePrivate];
		NSUInteger tilesX = (width + 31) / 32, tilesY = (height + 31) / 32;
		id<MTLBuffer> lightTiles = [device newBufferWithLength:tilesX * tilesY * 4 options:MTLResourceStorageModePrivate];
		// The noise texture, as shaders.m fills it: value noise with a periodic hash, 4 texels per unit.
		id<MTLTexture> noiseTexture;
		{
			const int period = 32, texels = 4, n = period * texels;
			MTLTextureDescriptor *d = [MTLTextureDescriptor new];
			d.textureType = MTLTextureType3D;
			d.pixelFormat = MTLPixelFormatR16Unorm;
			d.width = n;
			d.height = n;
			d.depth = n;
			d.usage = MTLTextureUsageShaderRead;
			noiseTexture = [device newTextureWithDescriptor:d];
			[keepAlive addObject:noiseTexture];
			uint16_t *data = malloc((size_t)n * n * n * 2);
			float *lattice = malloc((size_t)period * period * period * sizeof(float));
			for (int z = 0; z < period; z++) {
				for (int y = 0; y < period; y++) {
					for (int x = 0; x < period; x++) {
						// hash13 of world_shaders.metal on the lattice point.
						float px = (float)x * 0.1031f, py = (float)y * 0.1030f, pz = (float)z * 0.0973f;
						px -= floorf(px);
						py -= floorf(py);
						pz -= floorf(pz);
						float dp = px * (py + 33.33f) + py * (px + 33.33f) + pz * (pz + 33.33f);
						px += dp;
						py += dp;
						pz += dp;
						float v = (px + py) * pz;
						lattice[(z * period + y) * period + x] = v - floorf(v);
					}
				}
			}
			for (int z = 0; z < n; z++) {
				for (int y = 0; y < n; y++) {
					for (int x = 0; x < n; x++) {
						float fx = ((float)x + 0.5f) / texels, fy = ((float)y + 0.5f) / texels, fz = ((float)z + 0.5f) / texels;
						int ix = (int)floorf(fx), iy = (int)floorf(fy), iz = (int)floorf(fz);
						float tx = fx - ix, ty = fy - iy, tz = fz - iz;
						tx = tx * tx * (3.0f - 2.0f * tx);
						ty = ty * ty * (3.0f - 2.0f * ty);
						tz = tz * tz * (3.0f - 2.0f * tz);
						float c[8];
						for (int k = 0; k < 8; k++) {
							int cx = (ix + (k & 1)) % period, cy = (iy + ((k >> 1) & 1)) % period, cz = (iz + (k >> 2)) % period;
							c[k] = lattice[(cz * period + cy) * period + cx];
						}
						float v = ((c[0] * (1 - tx) + c[1] * tx) * (1 - ty) + (c[2] * (1 - tx) + c[3] * tx) * ty) * (1 - tz)
						        + ((c[4] * (1 - tx) + c[5] * tx) * (1 - ty) + (c[6] * (1 - tx) + c[7] * tx) * ty) * tz;
						data[((size_t)z * n + y) * n + x] = (uint16_t)(v * 65535.0f + 0.5f);
					}
				}
			}
			[noiseTexture replaceRegion:MTLRegionMake3D(0, 0, 0, n, n, n) mipmapLevel:0 slice:0 withBytes:data bytesPerRow:n * 2 bytesPerImage:(NSUInteger)n * n * 2];
			free(data);
			free(lattice);
		}

		// Camera: 4 blocks above the ground at the origin, looking along +z, 8 degrees down, 70 degree vertical FOV.
		float camX = 0.5f, camZ = 0.5f;
		float camY = floorf(benchHeight(camX, camZ)) + 1.0f + 1.62f + 3.0f;
		float fov = 70.0f * (float)M_PI / 180.0f;
		float aspect = (float)width / (float)height;
		float h = 1.0f / tanf(fov / 2.0f);
		float zNear = 1024.0f, zFar = 0.05f; // swapped, as Minecraft 26.3 does: reversed Z
		float projection[16] = {0};
		projection[0] = h / aspect;
		projection[5] = h;
		projection[10] = zFar / (zNear - zFar);
		projection[11] = -1.0f;
		projection[14] = zFar * zNear / (zNear - zFar);
		float pitch = -8.0f * (float)M_PI / 180.0f;
		float cp = cosf(pitch), sp = sinf(pitch);
		// View: rotate pitch about x, then the camera looks down -z in view space; the world's +z is the camera's forward.
		// Build view = Rx(pitch) * Ry(180deg) so that world +z maps to view -z.
		float ry[16] = {-1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
		float rx[16] = {1, 0, 0, 0, 0, cp, sp, 0, 0, -sp, cp, 0, 0, 0, 0, 1};
		float view[16], viewProj[16], inverse[16];
		matMul(rx, ry, view);
		matMul(projection, view, viewProj);
		if (!matInvert(viewProj, inverse)) {
			fprintf(stderr, "Singular projection\n");
			return 1;
		}
		// Forward in world space: the view's -z row.
		float forward[3] = {-view[2], -view[6], -view[10]};

		// Voxel grid around the camera, toroidally addressed.
		uint8_t *grid = voxels.contents;
		int cx = (int)floorf(camX), cy = (int)floorf(camY), cz = (int)floorf(camZ);
		float ceiling = -1.0e6f;
		for (int z = cz - 48; z <= cz + 48; z++) {
			for (int x = cx - 48; x <= cx + 48; x++) {
				for (int y = cy - 44; y <= cy + 44; y++) {
					int v = benchVoxel((float)x, (float)y, (float)z);
					if (v != 0) {
						int code = v;
						// Lava-like emissive blocks here and there under the ground.
						if (v == 1 && benchHash((float)x * 0.37f, (float)z + (float)y * 0.11f) > 0.997f) {
							code = (1 << 4) | 15;
						}
						grid[(x & 127) | ((y & 127) << 7) | ((z & 127) << 14)] = (uint8_t)code;
						if ((float)y > ceiling) {
							ceiling = (float)y;
						}
					}
				}
			}
		}
		ceiling += 1.0f;
		id<MTLBuffer> coarse = [device newBufferWithLength:32 * 32 * 32 options:MTLResourceStorageModeShared];
		{
			uint8_t *c = coarse.contents;
			for (int z = 0; z < 128; z++) {
				for (int y = 0; y < 128; y++) {
					for (int x = 0; x < 128; x++) {
						if (grid[x | (y << 7) | (z << 14)] != 0) {
							c[(x >> 2) | ((y >> 2) << 5) | ((z >> 2) << 10)] = 1;
						}
					}
				}
			}
		}
		id<MTLTexture> voxelTexture;
		{
			MTLTextureDescriptor *d = [MTLTextureDescriptor new];
			d.textureType = MTLTextureType3D;
			d.pixelFormat = MTLPixelFormatR8Uint;
			d.width = 128;
			d.height = 128;
			d.depth = 128;
			d.usage = MTLTextureUsageShaderRead;
			voxelTexture = [device newTextureWithDescriptor:d];
			[keepAlive addObject:voxelTexture];
			[voxelTexture replaceRegion:MTLRegionMake3D(0, 0, 0, 128, 128, 128) mipmapLevel:0 slice:0 withBytes:grid bytesPerRow:128 bytesPerImage:128 * 128];
		}

		Uniforms u = {0};
		memcpy(u.matrices, inverse, sizeof(inverse));
		memcpy(u.matrices + 16, viewProj, sizeof(viewProj));
		u.matrices[32] = 0.0f;
		u.matrices[33] = 0.0f;
		u.matrices[34] = 0.0f;
		u.matrices[35] = 1.0f;
		memcpy(u.matrices + 36, viewProj, sizeof(viewProj));
		float *env = u.environment;
		env[0] = camX;
		env[1] = camY;
		env[2] = camZ;
		env[3] = 100.0f;
		float sun[3] = {0.36f, 0.84f, 0.28f};
		float sl = sqrtf(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2]);
		env[4] = sun[0] / sl;
		env[5] = sun[1] / sl;
		env[6] = sun[2] / sl;
		env[7] = 1.0f;
		env[8] = 0.0f;
		env[9] = 0.0f;
		env[10] = 0.0f;
		env[11] = 0.0f;
		env[12] = 0.0f;
		env[13] = 0.0f;
		env[14] = projection[10];
		env[15] = projection[14];
		env[16] = 0.44f;
		env[17] = 0.69f;
		env[18] = 1.0f;
		env[19] = 0.0f;
		env[20] = 0.62f;
		env[21] = 0.76f;
		env[22] = 0.92f;
		env[23] = 0.84f;
		env[24] = 1.0e30f;
		env[25] = 1.0e30f;
		env[26] = 192.0f;
		env[27] = 256.0f;
		env[28] = 0.5f;
		env[29] = 0.0f;
		env[30] = 0.0f;
		env[31] = ceiling;
		env[32] = -1.0e6f;
		env[33] = 0.0f;
		env[34] = 1.0f;
		env[35] = 0.0f;
		int shadows[] = {0, 1, 2, 3, 4}, ao[] = {0, 1, 2, 2, 3}, reflections[] = {0, 1, 2, 3, 4}, gi[] = {0, 1, 2, 3, 3}, vol[] = {0, 1, 2, 3, 3},
		    bloom[] = {0, 1, 2, 3, 3};
		preset = MAX(1, MIN(4, preset));
		u.quality[0] = shadows[preset];
		u.quality[1] = ao[preset];
		u.quality[2] = reflections[preset];
		u.quality[3] = gi[preset];
		u.quality[4] = vol[preset];
		u.quality[5] = bloom[preset];
		u.quality[6] = scale;
		Light lightArray[32];
		Light *lights = lightArray;
		int lightCount = 24;
		for (int i = 0; i < lightCount; i++) {
			float a = (float)i * 0.7f;
			lights[i].pos_radius[0] = cosf(a) * (4.0f + (float)(i % 5) * 5.0f);
			lights[i].pos_radius[1] = -2.0f + (float)(i % 3);
			lights[i].pos_radius[2] = 6.0f + sinf(a) * (4.0f + (float)(i % 5) * 5.0f);
			lights[i].pos_radius[3] = 5.0f + 15.0f * 0.7f;
			lights[i].color_power[0] = 1.0f;
			lights[i].color_power[1] = 0.6f;
			lights[i].color_power[2] = 0.3f;
			lights[i].color_power[3] = 1.25f;
		}
		u.quality[7] = lightCount | (1 << 16);

		// Pipelines, as shaders.m makes them.
		MTLPixelFormat lightingFormat = MTLPixelFormatRGBA16Float;
		MTLPixelFormat bloomFormat = MTLPixelFormatRG11B10Float;
		MTLPixelFormat colorFormat = MTLPixelFormatRGBA8Unorm;
		MTLPixelFormat srgbFormat = MTLPixelFormatRGBA8Unorm_sRGB;
		MTLPixelFormat resolveFormats[2] = {srgbFormat, bloomFormat};
		id<MTLRenderPipelineState> lightingPipeline = pipeline(device, library, @"m2m_fullscreen", @"m2m_lighting", &lightingFormat, 1, MTLPixelFormatInvalid, NO);
		id<MTLRenderPipelineState> cloudsPipeline = pipeline(device, library, @"m2m_fullscreen", @"m2m_clouds", &lightingFormat, 1, MTLPixelFormatInvalid, NO);
		id<MTLRenderPipelineState> resolvePipeline = pipeline(device, library, @"m2m_fullscreen", @"m2m_resolve", resolveFormats, 2, MTLPixelFormatInvalid, NO);
		id<MTLRenderPipelineState> waterPipeline = pipeline(device, library, @"m2m_fullscreen", @"m2m_water", &srgbFormat, 1, MTLPixelFormatInvalid, NO);
		id<MTLRenderPipelineState> bloomDown = pipeline(device, library, @"m2m_fullscreen", @"m2m_bloom_down", &bloomFormat, 1, MTLPixelFormatInvalid, NO);
		id<MTLRenderPipelineState> bloomUp = pipeline(device, library, @"m2m_fullscreen", @"m2m_bloom_up", &bloomFormat, 1, MTLPixelFormatInvalid, YES);
		id<MTLRenderPipelineState> shaftsPipeline = pipeline(device, library, @"m2m_fullscreen", @"m2m_shafts", &bloomFormat, 1, MTLPixelFormatInvalid, NO);
		id<MTLRenderPipelineState> postPipeline = pipeline(device, library, @"m2m_fullscreen", @"m2m_post", &srgbFormat, 1, MTLPixelFormatInvalid, NO);
		MTLPixelFormat lutFormat = MTLPixelFormatRGBA16Float;
		id<MTLRenderPipelineState> skyLutPipeline = [library newFunctionWithName:@"m2m_sky_lut"]
		    ? pipeline(device, library, @"m2m_fullscreen", @"m2m_sky_lut", &lutFormat, 1, MTLPixelFormatInvalid, NO) : nil;
		// Kernels newer shaders have; a version without them runs without those passes (for comparisons).
		id<MTLFunction> reduceFunction = [library newFunctionWithName:@"m2m_skyline_reduce"];
		id<MTLFunction> levelsFunction = [library newFunctionWithName:@"m2m_skyline_levels"];
		id<MTLFunction> tilesFunction = [library newFunctionWithName:@"m2m_light_tiles"];
		id<MTLFunction> hizBaseFunction = [library newFunctionWithName:@"m2m_hiz_base"];
		id<MTLFunction> hizLevelFunction = [library newFunctionWithName:@"m2m_hiz_level"];
		id<MTLComputePipelineState> hizBase = hizBaseFunction ? [device newComputePipelineStateWithFunction:hizBaseFunction error:&error] : nil;
		id<MTLComputePipelineState> hizLevel = hizLevelFunction ? [device newComputePipelineStateWithFunction:hizLevelFunction error:&error] : nil;
		id<MTLComputePipelineState> skylineReduce = reduceFunction ? [device newComputePipelineStateWithFunction:reduceFunction error:&error] : nil;
		id<MTLComputePipelineState> skylineLevels = levelsFunction ? [device newComputePipelineStateWithFunction:levelsFunction error:&error] : nil;
		id<MTLComputePipelineState> lightTilesPipeline = tilesFunction ? [device newComputePipelineStateWithFunction:tilesFunction error:&error] : nil;
		if ((reduceFunction && !skylineReduce) || (levelsFunction && !skylineLevels) || (tilesFunction && !lightTilesPipeline)) {
			fprintf(stderr, "Kernels: %s\n", error.localizedDescription.UTF8String);
			return 1;
		}
		if (!skylineReduce) {
			printf("(no skyline kernels in this version)\n");
		}
		if (!lightTilesPipeline) {
			printf("(no light tile kernel in this version)\n");
		}
		MTLPixelFormat synthFormats[3] = {colorFormat, MTLPixelFormatRGBA8Unorm, MTLPixelFormatRGBA8Unorm};
		id<MTLRenderPipelineState> synthPipeline = pipeline(device, synthLibrary, @"bench_vertex", @"bench_synth", synthFormats, 3, MTLPixelFormatDepth32Float, NO);
		MTLDepthStencilDescriptor *depthDesc = [MTLDepthStencilDescriptor new];
		depthDesc.depthCompareFunction = MTLCompareFunctionAlways;
		depthDesc.depthWriteEnabled = YES;
		id<MTLDepthStencilState> depthState = [device newDepthStencilStateWithDescriptor:depthDesc];

		MTLSamplerDescriptor *samplerDesc = [MTLSamplerDescriptor new];
		samplerDesc.sAddressMode = MTLSamplerAddressModeClampToEdge;
		samplerDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
		samplerDesc.minFilter = MTLSamplerMinMagFilterLinear;
		samplerDesc.magFilter = MTLSamplerMinMagFilterLinear;
		id<MTLSamplerState> linear = [device newSamplerStateWithDescriptor:samplerDesc];
		samplerDesc.minFilter = MTLSamplerMinMagFilterNearest;
		samplerDesc.magFilter = MTLSamplerMinMagFilterNearest;
		id<MTLSamplerState> point = [device newSamplerStateWithDescriptor:samplerDesc];
		samplerDesc.minFilter = MTLSamplerMinMagFilterLinear;
		samplerDesc.magFilter = MTLSamplerMinMagFilterLinear;
		samplerDesc.sAddressMode = MTLSamplerAddressModeRepeat;
		samplerDesc.tAddressMode = MTLSamplerAddressModeRepeat;
		samplerDesc.rAddressMode = MTLSamplerAddressModeRepeat;
		id<MTLSamplerState> noiseSampler = [device newSamplerStateWithDescriptor:samplerDesc];
		samplerDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
		samplerDesc.rAddressMode = MTLSamplerAddressModeClampToEdge;
		id<MTLSamplerState> skySampler = [device newSamplerStateWithDescriptor:samplerDesc];

		SynthParams synth = {0};
		memcpy(synth.inverseViewProj, inverse, sizeof(inverse));
		synth.cameraPos[0] = camX;
		synth.cameraPos[1] = camY;
		synth.cameraPos[2] = camZ;
		synth.forward[0] = forward[0];
		synth.forward[1] = forward[1];
		synth.forward[2] = forward[2];
		synth.planes[0] = projection[10];
		synth.planes[1] = projection[14];
		synth.size[0] = (float)width;
		synth.size[1] = (float)height;

		// The synthetic world, once: the opaque terrain into vanillaColor, gAlbedo, gData and depth; then the water surface
		// over a copy of that depth, with its own G-buffer.
		{
			id<MTLCommandBuffer> cb = [queue commandBuffer];
			MTLRenderPassDescriptor *pass = colorPass(vanillaColor, MTLLoadActionClear);
			pass.colorAttachments[0].clearColor = MTLClearColorMake(0.55, 0.72, 0.95, 1.0);
			pass.colorAttachments[1].texture = gAlbedo;
			pass.colorAttachments[1].loadAction = MTLLoadActionClear;
			pass.colorAttachments[1].storeAction = MTLStoreActionStore;
			pass.colorAttachments[2].texture = gData;
			pass.colorAttachments[2].loadAction = MTLLoadActionClear;
			pass.colorAttachments[2].storeAction = MTLStoreActionStore;
			pass.depthAttachment.texture = depth;
			pass.depthAttachment.loadAction = MTLLoadActionClear;
			pass.depthAttachment.clearDepth = 0.0;
			pass.depthAttachment.storeAction = MTLStoreActionStore;
			synth.planes[2] = 0.0f;
			SynthParams opaqueParams = synth;
			id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:pass];
			[e setRenderPipelineState:synthPipeline];
			[e setDepthStencilState:depthState];
			[e setFragmentBytes:&opaqueParams length:sizeof(opaqueParams) atIndex:0];
			[e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[e endEncoding];
			id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
			[blit copyFromTexture:depth toTexture:depthWater];
			[blit endEncoding];
			pass = colorPass(color, MTLLoadActionDontCare);
			pass.colorAttachments[1].texture = gAlbedoWater;
			pass.colorAttachments[1].loadAction = MTLLoadActionClear;
			pass.colorAttachments[1].storeAction = MTLStoreActionStore;
			pass.colorAttachments[2].texture = gDataWater;
			pass.colorAttachments[2].loadAction = MTLLoadActionClear;
			pass.colorAttachments[2].storeAction = MTLStoreActionStore;
			pass.depthAttachment.texture = depthWater;
			pass.depthAttachment.loadAction = MTLLoadActionLoad;
			pass.depthAttachment.storeAction = MTLStoreActionStore;
			synth.planes[2] = 1.0f;
			SynthParams waterParams = synth;
			e = [cb renderCommandEncoderWithDescriptor:pass];
			[e setRenderPipelineState:synthPipeline];
			[e setDepthStencilState:depthState];
			[e setFragmentBytes:&waterParams length:sizeof(waterParams) atIndex:0];
			[e setFragmentTexture:depth atIndex:0];
			[e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[e endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
			printf("synthetic world drawn in %.1f ms GPU\n", (cb.GPUEndTime - cb.GPUStartTime) * 1000.0);
		}

		enum { T_RESTORE, T_SKY, T_CLOUDS, T_LIGHTING, T_TILES, T_RESOLVE, T_WATER_COPY, T_HIZ, T_WATER, T_BLOOM, T_SHAFTS, T_POST, T_COUNT };
		const char *names[T_COUNT] = {"restore (not ours)", "sky lut", "clouds", "lighting", "light tiles", "resolve", "water copies + skyline", "hi-z pyramid", "water", "bloom", "shafts", "post"};
		Timing timings[T_COUNT];
		for (int i = 0; i < T_COUNT; i++) {
			timings[i].name = names[i];
			timings[i].samples = calloc((size_t)frames, sizeof(double));
			timings[i].count = 0;
		}
		int warmup = 5;
		int historySlot = 0;

		for (int frame = 0; frame < frames + warmup; frame++) {
			@autoreleasepool {
				env[19] = (float)(frame & 1023);
				id<MTLTexture> previousLight = historySlot == 0 ? light1 : light0;
				id<MTLTexture> currentLight = historySlot == 0 ? light0 : light1;
				id<MTLTexture> previousClouds = historySlot == 0 ? clouds1 : clouds0;
				id<MTLTexture> currentClouds = historySlot == 0 ? clouds0 : clouds1;
				u.matrices[35] = frame == 0 ? 0.0f : 1.0f;
				NSMutableArray<id<MTLCommandBuffer>> *buffers = [NSMutableArray array];
				int kinds[T_COUNT];
				int n = 0;

				id<MTLCommandBuffer> (^begin)(void) = ^{
					id<MTLCommandBuffer> cb = [queue commandBuffer];
					[buffers addObject:cb];
					return cb;
				};
				void (^draw)(id<MTLCommandBuffer>, MTLRenderPassDescriptor *, id<MTLRenderPipelineState>, void (^)(id<MTLRenderCommandEncoder>)) =
				    ^(id<MTLCommandBuffer> cb, MTLRenderPassDescriptor *pass, id<MTLRenderPipelineState> state, void (^bind)(id<MTLRenderCommandEncoder>)) {
					    id<MTLRenderCommandEncoder> encoder = [cb renderCommandEncoderWithDescriptor:pass];
					    [encoder setRenderPipelineState:state];
					    bind(encoder);
					    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
					    [encoder endEncoding];
				    };

				// The frame starts from the synthetic world again.
				{
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_RESTORE;
					id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
					[blit copyFromTexture:vanillaColor toTexture:color];
					[blit endEncoding];
				}
				// Sky lookup.
				if (skyLutPipeline) {
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_SKY;
					draw(cb, colorPass(skyLut, MTLLoadActionDontCare), skyLutPipeline, ^(id<MTLRenderCommandEncoder> e) {
						[e setFragmentTexture:skyLut atIndex:0];
						[e setFragmentBytes:&u length:sizeof(u) atIndex:0];
					});
				}
				// Clouds.
				{
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_CLOUDS;
					if (u.quality[4] > 0) {
						draw(cb, colorPass(currentClouds, MTLLoadActionDontCare), cloudsPipeline, ^(id<MTLRenderCommandEncoder> e) {
							[e setFragmentTexture:depth atIndex:0];
							[e setFragmentTexture:previousClouds atIndex:3];
							[e setFragmentTexture:noiseTexture atIndex:7];
							[e setFragmentTexture:skyLut atIndex:8];
							[e setFragmentSamplerState:linear atIndex:0];
							[e setFragmentSamplerState:noiseSampler atIndex:2];
							[e setFragmentSamplerState:skySampler atIndex:3];
							[e setFragmentBytes:&u length:sizeof(u) atIndex:0];
						});
					} else {
						id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:colorPass(currentClouds, MTLLoadActionClear)];
						[e endEncoding];
					}
				}
				// Lighting.
				{
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_LIGHTING;
					draw(cb, colorPass(currentLight, MTLLoadActionDontCare), lightingPipeline, ^(id<MTLRenderCommandEncoder> e) {
						[e setFragmentTexture:depth atIndex:0];
						[e setFragmentTexture:gData atIndex:1];
						[e setFragmentTexture:previousLight atIndex:2];
						[e setFragmentTexture:gAlbedo atIndex:4];
						[e setFragmentTexture:noiseTexture atIndex:7];
						[e setFragmentSamplerState:linear atIndex:0];
						[e setFragmentSamplerState:point atIndex:1];
						[e setFragmentSamplerState:noiseSampler atIndex:2];
						[e setFragmentBytes:&u length:sizeof(u) atIndex:0];
						[e setFragmentBuffer:voxels offset:0 atIndex:2];
						[e setFragmentBuffer:coarse offset:0 atIndex:5];
						[e setFragmentTexture:voxelTexture atIndex:6];
					});
				}
				// Light tiles.
				if (lightTilesPipeline) {
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_TILES;
					id<MTLComputeCommandEncoder> compute = [cb computeCommandEncoder];
					[compute setComputePipelineState:lightTilesPipeline];
					[compute setTexture:depth atIndex:0];
					[compute setBytes:&u length:sizeof(u) atIndex:0];
					[compute setBytes:lights length:sizeof(Light) * (NSUInteger)lightCount atIndex:1];
					[compute setBuffer:lightTiles offset:0 atIndex:4];
					[compute dispatchThreadgroups:MTLSizeMake(tilesX, tilesY, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
					[compute endEncoding];
				}
				// Resolve.
				{
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_RESOLVE;
					MTLRenderPassDescriptor *pass = colorPass(colorSrgb, MTLLoadActionLoad);
					pass.colorAttachments[1].texture = bloomSource;
					pass.colorAttachments[1].loadAction = MTLLoadActionDontCare;
					pass.colorAttachments[1].storeAction = MTLStoreActionStore;
					draw(cb, pass, resolvePipeline, ^(id<MTLRenderCommandEncoder> e) {
						[e setFragmentTexture:depth atIndex:0];
						[e setFragmentTexture:gAlbedo atIndex:1];
						[e setFragmentTexture:gData atIndex:2];
						[e setFragmentTexture:currentLight atIndex:3];
						[e setFragmentTexture:currentClouds atIndex:4];
						[e setFragmentTexture:noiseTexture atIndex:7];
						[e setFragmentTexture:skyLut atIndex:8];
						[e setFragmentSamplerState:linear atIndex:0];
						[e setFragmentSamplerState:noiseSampler atIndex:2];
						[e setFragmentSamplerState:skySampler atIndex:3];
						[e setFragmentBytes:&u length:sizeof(u) atIndex:0];
						[e setFragmentBytes:lights length:sizeof(Light) * (NSUInteger)lightCount atIndex:1];
						[e setFragmentBuffer:voxels offset:0 atIndex:2];
						[e setFragmentTexture:voxelTexture atIndex:6];
						[e setFragmentBuffer:lightTiles offset:0 atIndex:4];
						[e setFragmentBuffer:coarse offset:0 atIndex:5];
					});
				}
				// Water: the copies, the translucent terrain with the G-buffer, the water pass.
				if (u.quality[2] > 0) {
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_WATER_COPY;
					id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
					[blit copyFromTexture:color toTexture:opaqueColor];
					[blit copyFromTexture:depth toTexture:opaqueDepth];
					[blit fillBuffer:skylineBands range:NSMakeRange(0, skylineBands.length) value:0];
					[blit endEncoding];
					if (skylineReduce) {
					id<MTLComputeCommandEncoder> compute = [cb computeCommandEncoder];
					[compute setComputePipelineState:skylineReduce];
					[compute setTexture:opaqueDepth atIndex:0];
					[compute setBuffer:skylineBands offset:0 atIndex:1];
					[compute setBytes:&u length:sizeof(u) atIndex:0];
					[compute dispatchThreadgroups:MTLSizeMake(bands, (height + 63) / 64, 1) threadsPerThreadgroup:MTLSizeMake(16, 64, 1)];
					[compute setComputePipelineState:skylineLevels];
					[compute setBuffer:skylineBands offset:0 atIndex:1];
					[compute setBuffer:skyline offset:0 atIndex:2];
					[compute setBytes:&bandCount length:sizeof(bandCount) atIndex:3];
					[compute dispatchThreadgroups:MTLSizeMake((bands + 63) / 64, levels, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
					[compute endEncoding];
					}

					if (hizBase && hizLevel) {
						cb = begin();
						kinds[n++] = T_HIZ;
						id<MTLComputeCommandEncoder> compute = [cb computeCommandEncoder];
						[compute setComputePipelineState:hizBase];
						[compute setTexture:opaqueDepth atIndex:0];
						[compute setBytes:&u length:sizeof(u) atIndex:0];
						[compute setBuffer:hiz offset:0 atIndex:6];
						[compute dispatchThreadgroups:MTLSizeMake((hizSizes[1][0] + 15) / 16, (hizSizes[1][1] + 15) / 16, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
						[compute setComputePipelineState:hizLevel];
						for (int l = 2; l < 6; l++) {
							uint32_t params[6] = {hizOffsets[l - 1], hizSizes[l - 1][0], hizSizes[l - 1][1], hizOffsets[l], hizSizes[l][0], hizSizes[l][1]};
							[compute setBytes:params length:sizeof(params) atIndex:7];
							[compute dispatchThreadgroups:MTLSizeMake((hizSizes[l][0] + 15) / 16, (hizSizes[l][1] + 15) / 16, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
						}
						[compute endEncoding];
					}

					cb = begin();
					kinds[n++] = T_WATER;
					draw(cb, colorPass(colorSrgb, MTLLoadActionLoad), waterPipeline, ^(id<MTLRenderCommandEncoder> e) {
						[e setFragmentTexture:depthWater atIndex:0];
						[e setFragmentTexture:opaqueDepth atIndex:1];
						[e setFragmentTexture:opaqueSrgb atIndex:2];
						[e setFragmentTexture:gAlbedoWater atIndex:3];
						[e setFragmentTexture:gDataWater atIndex:4];
						[e setFragmentTexture:noiseTexture atIndex:7];
						[e setFragmentTexture:skyLut atIndex:8];
						[e setFragmentSamplerState:noiseSampler atIndex:2];
						[e setFragmentSamplerState:skySampler atIndex:3];
						[e setFragmentBuffer:skyline offset:0 atIndex:2];
						[e setFragmentBuffer:hiz offset:0 atIndex:6];
						[e setFragmentSamplerState:linear atIndex:0];
						[e setFragmentSamplerState:point atIndex:1];
						[e setFragmentBytes:&u length:sizeof(u) atIndex:0];
					});
				}
				// Bloom pyramid.
				if (u.quality[5] > 0) {
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_BLOOM;
					id<MTLTexture> src = bloomSource;
					for (id<MTLTexture> level in bloomLevels) {
						id<MTLTexture> from = src;
						draw(cb, colorPass(level, MTLLoadActionDontCare), bloomDown, ^(id<MTLRenderCommandEncoder> e) {
							[e setFragmentTexture:from atIndex:0];
							[e setFragmentSamplerState:linear atIndex:0];
						});
						src = level;
					}
					for (NSInteger i = 2; i >= 0; i--) {
						id<MTLTexture> from = bloomLevels[(NSUInteger)i + 1];
						draw(cb, colorPass(bloomLevels[(NSUInteger)i], MTLLoadActionLoad), bloomUp, ^(id<MTLRenderCommandEncoder> e) {
							[e setFragmentTexture:from atIndex:0];
							[e setFragmentSamplerState:linear atIndex:0];
						});
					}
				}
				// Shafts and post.
				if (u.quality[4] > 0) {
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_SHAFTS;
					draw(cb, colorPass(shafts, MTLLoadActionDontCare), shaftsPipeline, ^(id<MTLRenderCommandEncoder> e) {
						[e setFragmentTexture:depthWater atIndex:0];
						[e setFragmentTexture:shafts atIndex:1];
						[e setFragmentSamplerState:point atIndex:1];
						[e setFragmentBytes:&u length:sizeof(u) atIndex:0];
					});
				}
				{
					id<MTLCommandBuffer> cb = begin();
					kinds[n++] = T_POST;
					static const float bloomStrengths[4] = {0.0f, 0.03f, 0.05f, 0.08f};
					float strengthArray[2] = {u.quality[5] > 0 ? bloomStrengths[u.quality[5]] : 0.0f, u.quality[4] > 0 ? 1.0f : 0.0f};
					float *strengths = strengthArray;
					draw(cb, colorPass(colorSrgb, MTLLoadActionLoad), postPipeline, ^(id<MTLRenderCommandEncoder> e) {
						[e setFragmentTexture:depthWater atIndex:0];
						[e setFragmentTexture:u.quality[5] > 0 ? bloomLevels.firstObject : shafts atIndex:1];
						[e setFragmentTexture:shafts atIndex:2];
						[e setFragmentSamplerState:linear atIndex:0];
						[e setFragmentSamplerState:point atIndex:1];
						[e setFragmentBytes:&u length:sizeof(u) atIndex:0];
						[e setFragmentBytes:strengths length:sizeof(float) * 2 atIndex:1];
					});
				}

				// One at a time: committed together, the buffers report one shared start time.
				for (id<MTLCommandBuffer> cb in buffers) {
					[cb commit];
					[cb waitUntilCompleted];
				}
				if (frame >= warmup) {
					for (int i = 0; i < n; i++) {
						id<MTLCommandBuffer> cb = buffers[(NSUInteger)i];
						Timing *t = &timings[kinds[i]];
						t->samples[t->count++] = (cb.GPUEndTime - cb.GPUStartTime) * 1000.0;
					}
				}
				historySlot = 1 - historySlot;
			}
		}

		printf("%lux%lu, lighting %lux%lu, preset %d, %d frames: GPU ms per pass (median / p90)\n", (unsigned long)width, (unsigned long)height,
		       (unsigned long)lightWidth, (unsigned long)lightHeight, preset, frames);
		double total = 0.0;
		for (int i = 0; i < T_COUNT; i++) {
			Timing *t = &timings[i];
			if (t->count == 0) {
				continue;
			}
			qsort(t->samples, (size_t)t->count, sizeof(double), compareDouble);
			double median = t->samples[t->count / 2];
			double p90 = t->samples[MIN(t->count - 1, (int)(t->count * 0.9))];
			printf("  %-30s %7.3f  %7.3f\n", t->name, median, p90);
			if (i != T_RESTORE) {
				total += median;
			}
		}
		printf("  %-30s %7.3f\n", "total (ours)", total);
		if (dumpPath) {
			writePng(device, queue, color, dumpPath);
			// The raw RGBA bytes too, for exact comparisons between versions.
			id<MTLBuffer> raw = [device newBufferWithLength:width * height * 4 options:MTLResourceStorageModeShared];
			id<MTLCommandBuffer> cb = [queue commandBuffer];
			id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
			[blit copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(width, height, 1)
			             toBuffer:raw destinationOffset:0 destinationBytesPerRow:width * 4 destinationBytesPerImage:width * height * 4];
			[blit endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
			[[NSData dataWithBytes:raw.contents length:raw.length] writeToFile:[dumpPath stringByAppendingString:@".raw"] atomically:NO];
			printf("wrote %s (+ .raw)\n", dumpPath.UTF8String);
		}
		return 0;
	}
}
