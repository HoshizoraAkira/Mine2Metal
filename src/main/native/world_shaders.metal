// mine2metal's world shaders, compiled at runtime (shaders.m embeds this file), in stages:
//
// 1. m2m_lighting, at the lighting scale, right after the opaque terrain: sun visibility (voxel ray traced near the
//    camera, screen-space further out), ambient occlusion and the sky's clouds, each accumulated over frames.
// 2. m2m_resolve, at full size, after the opaque terrain and solid entities: lights them from their G-buffer (albedo,
//    face normal, block and sky light written by the patched Sodium terrain and entity shaders) and draws the sky,
//    tonemapped to the display; light above SDR white goes to a bloom source. Pixels other shaders marked (material
//    0, not sky) keep what they drew.
// 2b. m2m_water, once the translucent terrain is drawn with the G-buffer: waves, refraction, absorption and
//    reflections over a copy of the scene without the water.
// 3. m2m_bloom_* and m2m_post, before the hand: a dual Kawase bloom pyramid and light shafts over the finished world.
//
// Positions are camera-relative world space. Textures keep GL's bottom-up rows, so a pixel's NDC is uv * 2 - 1.
// Minecraft 26.3 projects with reversed Z (near 1, far and the cleared sky 0); depth is only ever read through
// linear_depth and is_sky, which follow whichever convention the projection has.

#include <metal_stdlib>
using namespace metal;

struct ShaderUniforms {
    float4x4 inverseCurrent;   // NDC to camera-relative position, this frame
    float4x4 previous;         // camera-relative position (previous frame's camera) to clip, previous frame
    float4 cameraDelta;        // xyz camera movement since the previous frame, w 1 when there is history
    float4x4 currentViewProj;  // camera-relative position to clip, this frame
    float4 cameraPos;          // xyz world position, w seconds
    float4 sunDir;             // xyz towards the sun (the moon at night), w daylight 0..1
    float4 weather;            // x twilight, y rain, z thunder, w camera fog (0 air, 1 water, 2 lava, 3 powder snow, blindness, darkness)
    float4 sceneInfo;          // x dimension, y HDR peak, z projection m22, w projection m32
    float4 skyColor;           // rgb vanilla sky (sRGB), w frame index
    float4 fogColor;           // rgb vanilla fog (sRGB), w sun elevation -1..1
    float4 fogRange;           // environmental fog start, end; render distance fog start, end
    float4 viewInfo;           // x brightness option 0..1, y night vision 0..1, z debug view (0 off), w highest voxel y
    float4 waterInfo;          // x water surface above the camera, y 1 when the shaders draw the camera's water fog,
                               // z voxel grid version, w 1 when rain (not snow) falls at the camera
    int4 quality0;             // shadows, ambient occlusion, reflections, global illumination
    int4 quality1;             // volumetrics, bloom, lighting scale %, point light count | IO_LINEAR
};

// Function constant 0 of the resolve, water and post pipelines: true when the color attachment and the scene copy are
// sRGB views, so the hardware decodes what those passes read and encodes what they write and the shaders work in
// linear light throughout; false compiles the conversions in. (quality1.w carries the same flag as IO_LINEAR.)
constant bool linearIo [[function_constant(0)]];
constant int IO_LINEAR = 1 << 16;

struct PointLight {
    float4 posRadius;          // xyz camera-relative, w radius
    float4 colorPower;         // rgb linear, w intensity
};

constant uint VOXEL_SOLID = 1;
constant uint VOXEL_LEAVES = 2;
constant uint VOXEL_WATER = 3;
constant uint VOXEL_EMISSIVE = 16;
constant float VOXEL_REACH = 45.0;

constant uint MATERIAL_SOLID = 1;
constant uint MATERIAL_FOLIAGE = 2;
constant uint MATERIAL_PARTICLE = 4;
constant uint MATERIAL_FULL_BRIGHT = 8;

// ---------------------------------------------------------------------------------------------------------------
// Shared helpers

vertex float4 m2m_fullscreen(uint vid [[vertex_id]]) {
    float2 p = float2((vid << 1) & 2, vid & 2);
    return float4(p * 2.0 - 1.0, 0.0, 1.0);
}

static inline float3 srgb_to_linear(float3 c) {
    return select(pow((c + 0.055) / 1.055, 2.4), c / 12.92, c <= 0.04045);
}

static inline float3 linear_to_srgb(float3 c) {
    c = max(c, 0.0);
    return select(1.055 * pow(c, 1.0 / 2.4) - 0.055, c * 12.92, c <= 0.0031308);
}

static inline bool linear_io(constant ShaderUniforms &u) {
    (void)u;
    return linearIo;
}

// A color read from the scene (linear already with sRGB views), and one written to it.
static inline float3 decode_io(float3 c, constant ShaderUniforms &u) {
    return linear_io(u) ? c : srgb_to_linear(c);
}

static inline float3 encode_io(float3 c, constant ShaderUniforms &u) {
    return linear_io(u) ? c : linear_to_srgb(c);
}

static inline float luminance(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

// Linear through the mid-tones, so sunlit blocks keep vanilla's brightness, with a shoulder that rolls highlights
// smoothly towards white. Lighting is scaled so that full sun on an upward face is about 1.
static inline float3 tonemap(float3 x) {
    x = max(x, 0.0);
    const float knee = 0.6;
    float3 shoulder = knee + (1.0 - knee) * (1.0 - exp(-(x - knee) / (1.0 - knee)));
    float3 c = select(shoulder, x, x < knee);
    // A little saturation back where the shoulder washed it out.
    float l = luminance(c);
    return saturate(mix(float3(l), c, 1.08));
}

static inline float ign(float2 pixel, float frame) {
    pixel += frame * float2(5.588238, 3.141592);
    return fract(52.9829189 * fract(dot(pixel, float2(0.06711056, 0.00583715))));
}

static inline float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

static inline float hash13(float3 p) {
    p = fract(p * float3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

// Smooth value noise, 0..1, from a texture that repeats every NOISE_PERIOD units (shaders.m fills it from the same
// kind of hash, at NOISE_TEXELS texels per unit, so the clouds, waves and puddles keep their look at a fraction of
// the arithmetic). Sampled with a repeating trilinear sampler.
constant float NOISE_PERIOD = 32.0;
constant float NOISE_TEXELS = 4.0;

static inline float noise3(float3 x, texture3d<half> noise, sampler noiseSampler) {
    return float(noise.sample(noiseSampler, x / NOISE_PERIOD).r);
}

// Distance along the view axis: device depth is m32 / distance - m22 for either Z convention.
static inline float linear_depth(float z, constant ShaderUniforms &u) {
    return u.sceneInfo.w / max(z + u.sceneInfo.z, 1.0e-7);
}

// Depth where nothing was drawn: 0 with reversed Z (m22 is a small positive number), 1 otherwise (m22 near -1).
static inline bool is_sky(float z, constant ShaderUniforms &u) {
    return u.sceneInfo.z > -0.5 ? z <= 0.0 : z >= 1.0;
}

// The Nether (1) and the End (2), and dimensions drawn like them: no sun, no sky light, vanilla's own sky and fog.
static inline bool overworld(constant ShaderUniforms &u) {
    return u.sceneInfo.x < 0.5;
}
static inline float3 unproject(float2 uv, float z, constant ShaderUniforms &u) {
    float4 p = u.inverseCurrent * float4(uv * 2.0 - 1.0, z, 1.0);
    return p.xyz / p.w;
}

// uv and device depth of a camera-relative position; z < 0 when it is behind the camera.
static inline float3 project(float3 rel, float4x4 m) {
    float4 clip = m * float4(rel, 1.0);
    if (clip.w <= 1.0e-4) {
        return float3(-1.0);
    }
    float3 ndc = clip.xyz / clip.w;
    return float3(ndc.xy * 0.5 + 0.5, ndc.z);
}

// Marching in clip space: clip coordinates are linear in the position, so a ray's clip start and direction are
// projected once and each sample is a multiply-add instead of a matrix product (the same values project() gives).
static inline float4 to_clip(float3 rel, float4x4 m) {
    return m * float4(rel, 1.0);
}

static inline float4 clip_direction(float3 dir, float4x4 m) {
    return m * float4(dir, 0.0);
}

// uv and device depth of a clip position, as project(); z < 0 when it is behind the camera.
static inline float3 clip_to_screen(float4 clip) {
    if (clip.w <= 1.0e-4) {
        return float3(-1.0);
    }
    float3 ndc = clip.xyz / clip.w;
    return float3(ndc.xy * 0.5 + 0.5, ndc.z);
}

static inline float3 oct_decode(float2 e) {
    e = e * 2.0 - 1.0;
    float3 n = float3(e, 1.0 - abs(e.x) - abs(e.y));
    float t = saturate(-n.z);
    n.xy += select(float2(t), float2(-t), n.xy >= 0.0);
    return normalize(n);
}

static inline uint voxel_at(int3 p, device const uchar *voxels) {
    return voxels[uint(p.x & 127) | (uint(p.y & 127) << 7) | (uint(p.z & 127) << 14)];
}

// The coarse grid: one byte per 4x4x4 block of voxels, 0 when all of them are air (m2m_voxel_coarse builds it).
constant int COARSE_SHIFT = 2;

static inline bool coarse_empty(int3 p, device const uchar *coarse) {
    int3 c = p >> COARSE_SHIFT;
    return coarse[uint(c.x & 31) | (uint(c.y & 31) << 5) | (uint(c.z & 31) << 10)] == 0;
}

// Fills the coarse grid from a voxel copy: one thread per coarse cell.
kernel void m2m_voxel_coarse(uint3 gid [[thread_position_in_grid]],
                             device const uchar *voxels [[buffer(2)]],
                             device uchar *coarse [[buffer(5)]]) {
    if (any(gid >= 32)) {
        return;
    }
    uint occupied = 0;
    for (uint z = 0; z < 4; z++) {
        for (uint y = 0; y < 4; y++) {
            for (uint x = 0; x < 4; x++) {
                occupied |= voxels[(gid.x * 4 + x) | ((gid.y * 4 + y) << 7) | ((gid.z * 4 + z) << 14)];
            }
        }
    }
    coarse[gid.x | (gid.y << 5) | (gid.z << 10)] = occupied != 0 ? 1 : 0;
}

static inline uint voxel_at_world(float3 worldPos, float3 camPos, device const uchar *voxels) {
    if (any(abs(worldPos - camPos) >= VOXEL_REACH)) {
        return 0;
    }
    return voxel_at(int3(floor(worldPos)), voxels);
}

// Where vanilla draws the sun, day or night: sunDir is the moon's direction once the sun is below the horizon.
static inline float3 true_sun(constant ShaderUniforms &u) {
    float y = u.fogColor.w;
    float sign = y >= -0.06 ? 1.0 : -1.0;
    return normalize(float3(copysign(sqrt(max(1.0 - y * y, 0.0)), u.sunDir.x * sign), y, 0.0));
}

// The body that lights the world: the sun by day, the moon (opposite it) by night.
static inline float3 body_direction(constant ShaderUniforms &u) {
    float3 sun = true_sun(u);
    return u.fogColor.w >= -0.06 ? sun : -sun;
}

// Bright ridges of sunlight focused by the waves, 0..1.
static float caustics(float2 p, float time) {
    float2 a = p * 1.3 + float2(time * 0.35, -time * 0.27);
    float2 b = p * 1.9 + float2(-time * 0.31, time * 0.41);
    float w1 = sin(a.x + cos(a.y * 1.3)) * cos(a.y - sin(a.x * 1.2));
    float w2 = sin(b.x * 1.1 - cos(b.y)) * cos(b.y * 1.1 + sin(b.x));
    float ridge = saturate(1.0 - abs(w1 + w2) * 0.85);
    return ridge * ridge * ridge;
}

// Rings spreading from raindrops: the slope they add to a wet horizontal surface at p (world xz).
static float2 rain_ripples(float2 p, float time) {
    float2 slope = float2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        float2 q = p * 1.6 + float(layer) * float2(0.37, 0.61);
        float2 cell = floor(q);
        float2 f = fract(q) - 0.5;
        float seed = hash12(cell + float(layer) * 13.1);
        float phase = fract(time * 1.25 + seed);
        float2 center = (float2(hash12(cell + 3.1), hash12(cell + 7.7)) - 0.5) * 0.5;
        float2 d = f - center;
        float r = length(d);
        float ring = (r - phase * 0.45) * 16.0;
        slope += d / max(r, 1.0e-3) * exp(-ring * ring) * (1.0 - phase) * 0.5;
    }
    return slope;
}

// Where rain pools on a flat surface: patches fixed in the world that grow as the rain gets heavier.
static float puddles(float2 p, float rain, texture3d<half> noise, sampler noiseSampler) {
    float n = noise3(float3(p * 0.11, 0.0), noise, noiseSampler) * 0.7 + noise3(float3(p * 0.37, 7.0), noise, noiseSampler) * 0.3;
    return smoothstep(0.62 - rain * 0.12, 0.70 - rain * 0.12, n);
}

// ---------------------------------------------------------------------------------------------------------------
// Sky and clouds

// The sky's color by direction, computed once per frame into a lat-long texture (m2m_sky_lut) and sampled from it
// everywhere else: the function is smooth, and it used to be evaluated several times per pixel.
static float3 atmosphere_exact(float3 dir, constant ShaderUniforms &u) {
    float day = u.sunDir.w;
    float twilight = u.weather.x;
    float rain = u.weather.y;
    float mu = dot(dir, true_sun(u));

    float3 vanillaSky = srgb_to_linear(u.skyColor.rgb);
    float3 vanillaFog = srgb_to_linear(u.fogColor.rgb);
    float3 zenith = mix(float3(0.010, 0.016, 0.040), mix(float3(0.10, 0.27, 0.72), vanillaSky, 0.35), day);
    float3 horizon = mix(float3(0.022, 0.034, 0.075), mix(float3(0.48, 0.66, 0.92), vanillaFog, 0.35), day);
    float sunSide = 0.35 + 0.65 * saturate(mu * 0.5 + 0.5);
    float band = exp(-dir.y * dir.y * 22.0);
    horizon = mix(horizon, float3(1.10, 0.42, 0.13) * sunSide, twilight * band * 0.85);
    zenith = mix(zenith, float3(0.20, 0.17, 0.32), twilight * 0.45);

    float up = smoothstep(-0.15, 0.55, dir.y);
    float3 sky = mix(horizon, zenith, up);
    // Below the horizon the sky darkens towards the ground's bounce.
    sky *= mix(0.55, 1.0, smoothstep(-0.35, 0.0, dir.y));

    float glow = pow(saturate(mu), 8.0) * (0.25 + 0.75 * twilight) + pow(saturate(mu), 64.0) * 0.6;
    float3 glowTint = mix(float3(1.0, 0.85, 0.62), float3(1.0, 0.45, 0.15), twilight);
    sky += glowTint * glow * (0.15 + 0.85 * day) * (1.0 - rain * 0.8);

    float l = luminance(sky);
    sky = mix(sky, float3(l * 0.85, l * 0.9, l), rain * 0.75);
    return sky * (1.0 - rain * 0.35);
}

static inline float3 atmosphere(float3 dir, texture2d<half> skyLut, sampler skySampler) {
    float2 uv = float2(atan2(dir.z, dir.x) * (0.5 / M_PI_F) + 0.5, dir.y * 0.5 + 0.5);
    return float3(skyLut.sample(skySampler, uv).rgb);
}

// Fills the sky lookup: column = azimuth around the full circle (repeating), row = elevation from -1 to 1.
fragment half4 m2m_sky_lut(float4 position [[position]], constant ShaderUniforms &u [[buffer(0)]], texture2d<half> self [[texture(0)]]) {
    float2 size = float2(self.get_width(), self.get_height());
    float azimuth = (position.x / size.x - 0.5) * 2.0 * M_PI_F;
    float y = (position.y / size.y) * 2.0 - 1.0;
    float r = sqrt(max(1.0 - y * y, 0.0));
    return half4(half3(atmosphere_exact(float3(cos(azimuth) * r, y, sin(azimuth) * r), u)), 1.0h);
}

static float3 sun_disc(float3 dir, constant ShaderUniforms &u) {
    if (dir.y < -0.05) {
        return float3(0.0);
    }
    float c = dot(dir, true_sun(u));
    float visible = smoothstep(-0.04, 0.04, dir.y) * (1.0 - u.weather.y * 0.95);
    float disc = smoothstep(0.99935, 0.99965, c);
    return mix(float3(20.0, 17.0, 13.0), float3(20.0, 8.0, 2.0), u.weather.x) * disc * visible;
}

static float cloud_density(float3 p, float height, float rain, float time, texture3d<half> noise, sampler noiseSampler) {
    float3 q = (p + float3(time * 2.4, 0.0, time * 0.9)) * float3(0.0028, 0.012, 0.0028);
    float n = noise3(q, noise, noiseSampler) * 0.56 + noise3(q * 2.15, noise, noiseSampler) * 0.30 + noise3(q * 4.5, noise, noiseSampler) * 0.14;
    float profile = smoothstep(0.0, 0.2, height) * smoothstep(1.0, 0.35, height);
    float threshold = mix(0.42, 0.28, rain);
    return smoothstep(threshold, threshold + 0.30, n * profile);
}

// One octave of cloud_density, for the light reaching a cloud sample: soft by nature, and taken at every sample.
static float cloud_density_coarse(float3 p, float height, float rain, float time, texture3d<half> noise, sampler noiseSampler) {
    float3 q = (p + float3(time * 2.4, 0.0, time * 0.9)) * float3(0.0028, 0.012, 0.0028);
    float profile = smoothstep(0.0, 0.2, height) * smoothstep(1.0, 0.35, height);
    float threshold = mix(0.42, 0.28, rain);
    return smoothstep(threshold, threshold + 0.30, noise3(q, noise, noiseSampler) * 0.9 * profile);
}

static float4 clouds(float3 dir, float jitter, int steps, constant ShaderUniforms &u, texture3d<half> noise, sampler noiseSampler,
                     texture2d<half> skyLut, sampler skySampler) {
    if (dir.y <= 0.02) {
        return float4(0.0);
    }
    float day = u.sunDir.w;
    float twilight = u.weather.x;
    float rain = u.weather.y;
    float time = u.cameraPos.w;
    float3 sun = normalize(u.sunDir.xyz);
    float base = max(u.cameraPos.y + 75.0, 195.0);
    float top = base + 60.0;
    float inv = 1.0 / max(dir.y, 0.035);
    float t0 = (base - u.cameraPos.y) * inv;
    float t1 = (top - u.cameraPos.y) * inv;
    if (t0 > 1800.0) {
        return float4(0.0);
    }
    float stepLength = (t1 - t0) / float(steps);
    float t = t0 + stepLength * jitter;
    float cosSun = dot(dir, body_direction(u));
    float phase = 0.4 + 0.9 * pow(saturate(cosSun), 6.0) + 0.15 * saturate(-cosSun);

    float3 sunLight = mix(float3(0.06, 0.08, 0.14), float3(1.15, 1.08, 0.95), day);
    sunLight = mix(sunLight, float3(1.1, 0.45, 0.18), twilight) * (1.0 - rain * 0.65);
    float3 ambient = mix(float3(0.015, 0.022, 0.045), float3(0.36, 0.46, 0.64), day);
    ambient = mix(ambient, float3(0.32, 0.22, 0.24), twilight * 0.6);

    float3 color = float3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < steps; i++) {
        float3 p = u.cameraPos.xyz + dir * t;
        float h = saturate((p.y - base) / 60.0);
        float d = cloud_density(p, h, rain, time, noise, noiseSampler);
        if (d > 0.005) {
            float3 ps = p + sun * 16.0;
            float ds = cloud_density_coarse(ps, saturate((ps.y - base) / 60.0), rain, time, noise, noiseSampler);
            float energy = exp(-ds * 2.6) * (1.0 - 0.45 * exp(-d * 4.0));
            float3 sample = ambient * (0.45 + 0.55 * h) + sunLight * energy * phase;
            float alpha = 1.0 - exp(-d * 0.7);
            color += sample * alpha * transmittance;
            transmittance *= 1.0 - alpha;
            if (transmittance < 0.04) {
                break;
            }
        }
        t += stepLength;
    }
    float opacity = (1.0 - transmittance) * smoothstep(0.02, 0.16, dir.y) * smoothstep(1800.0, 650.0, t0);
    if (opacity <= 1.0e-3) {
        return float4(0.0);
    }
    float3 resolved = color / max(1.0 - transmittance, 1.0e-4);
    resolved = mix(resolved, atmosphere(dir, skyLut, skySampler), smoothstep(450.0, 1600.0, t0) * 0.55);
    return float4(resolved * opacity, opacity);
}

// ---------------------------------------------------------------------------------------------------------------
// Stage 1: sun visibility, ambient occlusion and clouds at the lighting scale

// Amanatides & Woo through the voxel grid around the camera. Starts just off the surface and skips the block the
// surface belongs to, so slabs and stairs do not shadow their own top. Leaves let light through in a dappled
// pattern fixed in the world; stained glass dims it. Rays towards the sky stop above the highest block in the grid,
// which in open terrain is after a few steps rather than at the edge of the grid.
static float voxel_shadow(float3 worldPos, float3 normal, float3 dir, int maxSteps, device const uchar *voxels,
                          device const uchar *coarse, float3 camPos, float ceiling) {
    int3 self = int3(floor(worldPos - normal * 0.02));
    float3 origin = worldPos + normal * 0.03;
    int3 cell = int3(floor(origin));
    float3 inv = 1.0 / select(dir, float3(1.0e-6), abs(dir) < 1.0e-6);
    int3 stepDir = select(int3(-1), int3(1), dir >= 0.0);
    float3 delta = abs(inv);
    float3 positive = select(float3(0.0), float3(1.0), dir >= 0.0);
    float3 side = (float3(cell) + positive - origin) * inv;
    float visibility = 1.0;
    float t = 0.0;
    for (int i = 0; i < maxSteps; i++) {
        if (any(abs(float3(cell) + 0.5 - camPos) >= VOXEL_REACH) || float(cell.y) > ceiling) {
            break;
        }
        if (coarse_empty(cell, coarse)) {
            // Nothing in this 4x4x4 block: continue from where the ray leaves it, at the voxel it enters there.
            float3 coarseBase = float3(cell >> COARSE_SHIFT << COARSE_SHIFT);
            float3 exits = (coarseBase + positive * float(1 << COARSE_SHIFT) - origin) * inv;
            t = min(exits.x, min(exits.y, exits.z)) + 1.0e-4;
            cell = int3(floor(origin + dir * t));
            side = (float3(cell) + positive - origin) * inv;
            continue;
        }
        if (any(cell != self)) {
            uint v = voxel_at(cell, voxels);
            if (v == VOXEL_SOLID) {
                return 0.0;
            }
            if (v == VOXEL_LEAVES) {
                float3 entry = origin + dir * (t + 0.05);
                visibility *= hash13(floor(entry * 4.0)) > 0.62 ? 0.85 : 0.08;
                if (visibility < 0.02) {
                    return 0.0;
                }
            } else if (v >= 4 && v <= 10) {
                visibility *= 0.8;
            }
        }
        if (side.x < side.y && side.x < side.z) {
            t = side.x;
            side.x += delta.x;
            cell.x += stepDir.x;
        } else if (side.y < side.z) {
            t = side.y;
            side.y += delta.y;
            cell.y += stepDir.y;
        } else {
            t = side.z;
            side.z += delta.z;
            cell.z += stepDir.z;
        }
    }
    return visibility;
}

// Marches the depth buffer towards the sun in linear depth; 0 at the first occluder thinner than maxThickness.
static float screen_shadow(float3 rel, float lin, float3 dir, float distance, int steps, float maxThickness, float jitter,
                           depth2d<float> depth, sampler pointSampler, constant ShaderUniforms &u) {
    float3 end = project(rel + dir * distance, u.currentViewProj);
    float3 start = project(rel, u.currentViewProj);
    if (end.z <= 0.0 || end.z >= 1.0) {
        return 1.0;
    }
    float3 a = float3(start.xy, lin);
    float3 b = float3(end.xy, linear_depth(end.z, u));
    float3 stepVec = (b - a) / float(steps);
    float3 p = a + stepVec * (0.3 + jitter * 0.7);
    float bias = max(0.02, lin * 0.004);
    for (int i = 0; i < steps; i++) {
        if (any(p.xy <= 0.0) || any(p.xy >= 1.0)) {
            break;
        }
        float z = depth.sample(pointSampler, p.xy);
        if (!is_sky(z, u)) {
            float diff = p.z - linear_depth(z, u);
            if (diff > bias && diff < maxThickness) {
                return 0.0;
            }
        }
        p += stepVec;
    }
    return 1.0;
}

// Normal-oriented hemisphere samples checked against the depth buffer, nearer ones counting more.
static float ambient_occlusion(float3 rel, float3 normal, float lin, int samples, float jitter, float rotation,
                               depth2d<float> depth, sampler pointSampler, constant ShaderUniforms &u) {
    float3 helper = abs(normal.y) < 0.99 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
    float3 tangent = normalize(cross(helper, normal));
    float3 bitangent = cross(normal, tangent);
    // Capped on screen, so close up the taps stay near the pixel (and in the texture cache).
    float radius = min(0.75, lin * 0.15);
    float4 clipBase = to_clip(rel + normal * 0.03, u.currentViewProj);
    float4 clipTangent = clip_direction(tangent * radius, u.currentViewProj);
    float4 clipBitangent = clip_direction(bitangent * radius, u.currentViewProj);
    float4 clipNormal = clip_direction(normal * radius, u.currentViewProj);
    float occlusion = 0.0;
    for (int i = 0; i < samples; i++) {
        float fi = (float(i) + jitter) / float(samples);
        float angle = (float(i) * 2.399963 + rotation * 6.283185);
        float r = sqrt(fi);
        float3 h = float3(cos(angle) * r, sin(angle) * r, sqrt(max(1.0 - fi, 0.0))) * mix(0.25, 1.0, fi * fi);
        float3 s = clip_to_screen(clipBase + clipTangent * h.x + clipBitangent * h.y + clipNormal * h.z);
        if (s.z <= 0.0 || any(s.xy <= 0.0) || any(s.xy >= 1.0)) {
            continue;
        }
        float sceneLin = linear_depth(depth.sample(pointSampler, s.xy), u);
        float sampleLin = linear_depth(s.z, u);
        float diff = sampleLin - sceneLin;
        if (diff > 0.02) {
            occlusion += smoothstep(1.2, 0.3, diff);
        }
    }
    return 1.0 - occlusion / float(samples);
}

static float cloud_shadow(float3 worldPos, float3 sun, constant ShaderUniforms &u, texture3d<half> noise, sampler noiseSampler) {
    if (sun.y < 0.08) {
        return 1.0;
    }
    float base = max(u.cameraPos.y + 75.0, 195.0);
    float t = (base + 18.0 - worldPos.y) / sun.y;
    if (t <= 0.0 || t > 900.0) {
        return 1.0;
    }
    // One octave of the cloud noise: the shadow is soft anyway, and this runs for every lit pixel.
    float3 p = worldPos + sun * t;
    float3 q = (p + float3(u.cameraPos.w * 2.4, 0.0, u.cameraPos.w * 0.9)) * float3(0.0028, 0.012, 0.0028);
    float threshold = mix(0.42, 0.28, u.weather.y);
    float density = smoothstep(threshold, threshold + 0.30, noise3(q, noise, noiseSampler) * 0.62);
    return 1.0 - density * 0.35 * u.sunDir.w;
}

// Clouds over the sky, at the lighting scale; a pass of its own so the terrain lighting does not carry its registers.
fragment half4 m2m_clouds(float4 position [[position]],
                          depth2d<float> depth [[texture(0)]],
                          texture2d<half> previousClouds [[texture(3)]],
                          texture3d<half> noise [[texture(7)]],
                          texture2d<half> skyLut [[texture(8)]],
                          sampler linearSampler [[sampler(0)]],
                          sampler noiseSampler [[sampler(2)]],
                          sampler skySampler [[sampler(3)]],
                          constant ShaderUniforms &u [[buffer(0)]]) {
    float2 lightSize = float2(previousClouds.get_width(), previousClouds.get_height());
    float2 fullSize = float2(depth.get_width(), depth.get_height());
    uint2 px = min(uint2(position.xy / lightSize * fullSize), uint2(fullSize) - 1);
    if (!is_sky(depth.read(px), u)) {
        return half4(0.0h);
    }
    float2 uv = (float2(px) + 0.5) / fullSize;
    float3 view = normalize(unproject(uv, 1.0, u));
    float frame = u.skyColor.w;
    float4 h = float4(0.0);
    bool reused = false;
    if (u.cameraDelta.w > 0.5) {
        // Clouds are far away: reproject along the view direction only.
        float3 prev = project(view * 1000.0 + u.cameraDelta.xyz, u.previous);
        if (prev.z > 0.0 && all(prev.xy > 0.0) && all(prev.xy < 1.0)) {
            h = float4(previousClouds.sample(linearSampler, prev.xy));
            reused = true;
        }
    }
    // Half of the pixels march each frame (alternating); the others carry their reprojected history forward.
    if (reused && ((uint(position.x) + uint(position.y) + uint(frame)) & 1u) != 0u) {
        return half4(h);
    }
    int quality = u.quality1.x;
    float4 c = clouds(view, ign(position.xy, frame), quality == 1 ? 6 : (quality == 2 ? 8 : 11), u, noise, noiseSampler, skyLut, skySampler);
    return half4(reused ? mix(c, h, 0.75) : c);
}

// r sun visibility, g ambient occlusion, b linear depth (for reprojection and the bilateral upsample).
fragment half4 m2m_lighting(float4 position [[position]],
                            depth2d<float> depth [[texture(0)]],
                            texture2d<half> gData [[texture(1)]],
                            texture2d<half> previousLight [[texture(2)]],
                            texture2d<half> gAlbedo [[texture(4)]],
                            texture3d<half> noiseTexture [[texture(7)]],
                            sampler linearSampler [[sampler(0)]],
                            sampler pointSampler [[sampler(1)]],
                            sampler noiseSampler [[sampler(2)]],
                            constant ShaderUniforms &u [[buffer(0)]],
                            device const uchar *voxels [[buffer(2)]],
                            device const uchar *coarse [[buffer(5)]]) {
    float2 lightSize = float2(previousLight.get_width(), previousLight.get_height());
    float2 fullSize = float2(depth.get_width(), depth.get_height());
    uint2 px = min(uint2(position.xy / lightSize * fullSize), uint2(fullSize) - 1);
    float z = depth.read(px);
    if (is_sky(z, u)) {
        return half4(1.0h, 1.0h, 65000.0h, 1.0h);
    }
    float2 uv = (float2(px) + 0.5) / fullSize;
    float3 rel = unproject(uv, z, u);
    float dist = length(rel);
    float frame = u.skyColor.w;
    float noise = ign(position.xy, frame);
    float noise2 = ign(position.xy + 17.0, frame + 7.0);
    bool history = u.cameraDelta.w > 0.5;
    bool checkerboardSkip = ((uint(position.x) + uint(position.y) + uint(frame)) & 1u) != 0u;

    float lin = linear_depth(z, u);
    half2 previous = half2(0.0h);
    bool reused = false;
    if (history) {
        float3 prev = project(rel + u.cameraDelta.xyz, u.previous);
        if (prev.z > 0.0 && all(prev.xy > 0.0) && all(prev.xy < 1.0)) {
            half4 h = previousLight.sample(linearSampler, prev.xy);
            float expected = linear_depth(prev.z, u);
            // Disocclusion: the history saw a different surface there.
            if (abs(float(h.b) - expected) < expected * 0.03 + 0.05) {
                previous = h.rg;
                reused = true;
            }
        }
    }
    if (reused && checkerboardSkip) {
        return half4(previous, half(lin), 1.0h);
    }

    float3 normal = oct_decode(float2(gData.read(px).rg));
    float3 worldPos = rel + u.cameraPos.xyz;
    float3 sun = normalize(u.sunDir.xyz);

    float shadow = 1.0;
    int shadowQuality = overworld(u) ? u.quality0.x : 0;
    if (shadowQuality > 0) {
        float ndotl = dot(normal, sun);
        // Foliage is lit through and particles have no facing, so they trace even when facing away.
        uint kind = uint(float(gAlbedo.read(px).a) * 255.0 + 0.5) & 7u;
        if (ndotl <= 0.0 && kind != MATERIAL_FOLIAGE && kind != MATERIAL_PARTICLE) {
            shadow = 0.0;
        } else {
            // A cone around the sun direction gives soft penumbrae once frames are accumulated.
            float cone = shadowQuality == 1 ? 0.006 : (shadowQuality == 2 ? 0.012 : 0.02);
            float3 t1 = normalize(cross(sun, abs(sun.y) < 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0)));
            float3 t2 = cross(sun, t1);
            float a = noise * 6.283185;
            float r = sqrt(noise2) * cone;
            float3 dir = normalize(sun + (t1 * cos(a) + t2 * sin(a)) * r);

            float voxelFade = smoothstep(VOXEL_REACH - 12.0, VOXEL_REACH - 3.0, dist);
            if (voxelFade < 1.0) {
                int steps = shadowQuality == 1 ? 24 : (shadowQuality == 2 ? 36 : (shadowQuality == 3 ? 48 : 64));
                shadow = mix(voxel_shadow(worldPos, normal, dir, steps, voxels, coarse, u.cameraPos.xyz, u.viewInfo.w), 1.0, voxelFade);
            }
            if (shadow > 0.0) {
                // Contact shadows for detail the voxels do not hold (fences, flowers, slopes of partial blocks),
                // and the only terrain shadows past the voxel grid.
                float near = screen_shadow(rel + normal * 0.02, lin, dir, 1.0, 6, 0.4, noise2, depth, pointSampler, u);
                shadow *= near;
                if (voxelFade > 0.0 && shadow > 0.0) {
                    float reach = mix(8.0, 32.0, smoothstep(40.0, 160.0, dist));
                    int steps = shadowQuality <= 2 ? 10 : 16;
                    float far = screen_shadow(rel + normal * 0.1, lin, dir, reach, steps, reach * 0.5, noise2, depth, pointSampler, u);
                    shadow *= mix(1.0, far, voxelFade);
                }
            }
            if (u.quality1.x > 0 && shadow > 0.0) {
                shadow *= cloud_shadow(worldPos, sun, u, noiseTexture, noiseSampler);
            }
        }
    }

    float ao = 1.0;
    int aoQuality = u.quality0.y;
    if (aoQuality > 0 && dist < 96.0) {
        int samples = aoQuality == 1 ? 3 : (aoQuality == 2 ? 4 : 6);
        ao = ambient_occlusion(rel, normal, lin, samples, noise2, noise, depth, pointSampler, u);
        ao = mix(ao, 1.0, smoothstep(64.0, 96.0, dist));
    }

    half4 current = half4(half(shadow), half(ao), half(lin), 1.0h);
    if (reused) {
        // Traced every other frame, so a little less history than tracing every frame would keep.
        current.rg = mix(current.rg, previous, 0.75h);
    }
    return current;
}

// ---------------------------------------------------------------------------------------------------------------
// Point light tiles: for each LIGHT_TILE square of the screen, a bit per light that can reach a pixel in it. A light
// is kept if its sphere overlaps the tile's frustum (the four planes through the camera and the tile's edges) and
// its depth range; what the resolve then tests per pixel is unchanged, so nothing it would have lit is skipped.
// One threadgroup of (LIGHT_TILE / 2)^2 threads per tile, each reading a 2x2 block; lanes 0..31 of the first SIMD
// group then test one light each and vote.

constant uint LIGHT_TILE = 32;

kernel void m2m_light_tiles(uint2 gid [[thread_position_in_grid]],
                            uint2 tile [[threadgroup_position_in_grid]],
                            uint2 tiles [[threadgroups_per_grid]],
                            uint lane [[thread_index_in_simdgroup]],
                            uint simd [[simdgroup_index_in_threadgroup]],
                            uint simds [[simdgroups_per_threadgroup]],
                            depth2d<float> depth [[texture(0)]],
                            constant ShaderUniforms &u [[buffer(0)]],
                            constant PointLight *lights [[buffer(1)]],
                            device uint *masks [[buffer(4)]]) {
    threadgroup float nearest[32];
    threadgroup float farthest[32];
    float2 size = float2(depth.get_width(), depth.get_height());
    float lin = INFINITY;
    float linFar = -INFINITY;
    uint2 base = tile * LIGHT_TILE + (gid - tile * (LIGHT_TILE / 2)) * 2;
    for (uint k = 0; k < 4; k++) {
        uint2 p = base + uint2(k & 1, k >> 1);
        if (all(float2(p) < size)) {
            float z = depth.read(p);
            if (!is_sky(z, u)) {
                float l = linear_depth(z, u);
                lin = min(lin, l);
                linFar = max(linFar, l);
            }
        }
    }
    lin = simd_min(lin);
    linFar = simd_max(linFar);
    if (lane == 0) {
        nearest[simd] = lin;
        farthest[simd] = linFar;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (simd == 0) {
        float a = lane < simds ? nearest[lane] : INFINITY;
        float b = lane < simds ? farthest[lane] : -INFINITY;
        a = simd_min(a);
        b = simd_max(b);
        int count = min(u.quality1.w & 0xFFFF, 32);
        bool inside = false;
        if (a <= b && int(lane) < count) {
            float2 ndc0 = (float2(tile * LIGHT_TILE)) / size * 2.0 - 1.0;
            float2 ndc1 = min(float2((tile + 1) * LIGHT_TILE) / size, 1.0) * 2.0 - 1.0;
            float3 c00 = unproject(ndc0 * 0.5 + 0.5, 0.5, u);
            float3 c10 = unproject(float2(ndc1.x, ndc0.y) * 0.5 + 0.5, 0.5, u);
            float3 c01 = unproject(float2(ndc0.x, ndc1.y) * 0.5 + 0.5, 0.5, u);
            float3 c11 = unproject(ndc1 * 0.5 + 0.5, 0.5, u);
            float3 centre = c00 + c10 + c01 + c11;
            float3 planes[4] = {cross(c00, c10), cross(c10, c11), cross(c11, c01), cross(c01, c00)};
            float3 light = lights[lane].posRadius.xyz;
            float radius = lights[lane].posRadius.w;
            inside = true;
            for (int i = 0; i < 4; i++) {
                float3 n = normalize(planes[i]);
                n = dot(n, centre) < 0.0 ? -n : n;
                inside = inside && dot(n, light) >= -radius;
            }
            float lightLin = to_clip(light, u.currentViewProj).w;
            inside = inside && lightLin + radius >= a && lightLin - radius <= b;
        }
        simd_vote vote = simd_ballot(inside);
        if (lane == 0) {
            masks[tile.y * tiles.x + tile.x] = uint((simd_vote::vote_t)vote);
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Stage 2: deferred lighting of the terrain, and the sky

struct ResolveOut {
    half4 color [[color(0)]];
    half4 bloom [[color(1)]];
};

static inline float3 sun_color(constant ShaderUniforms &u) {
    if (!overworld(u)) {
        return float3(0.0);
    }
    float day = u.sunDir.w;
    float3 c = mix(float3(0.20, 0.27, 0.42) * 0.30, float3(1.0, 0.96, 0.90) * 0.95, day);
    c = mix(c, float3(1.0, 0.55, 0.25) * 0.85, u.weather.x * 0.85);
    return c * (1.0 - u.weather.y * 0.75);
}

static inline float3 ambient_color(constant ShaderUniforms &u) {
    if (!overworld(u)) {
        // Lit by the haze of the dimension itself: the Nether's in its biome's fog color, the End's a dim violet.
        float3 fog = srgb_to_linear(u.fogColor.rgb);
        return u.sceneInfo.x < 1.5 ? fog * 0.55 + float3(0.035, 0.030, 0.030) : fog * 0.3 + float3(0.30, 0.26, 0.38);
    }
    float day = u.sunDir.w;
    float3 skyTint = srgb_to_linear(u.skyColor.rgb);
    float3 c = mix(float3(0.030, 0.045, 0.095), mix(float3(0.34, 0.50, 0.85), skyTint, 0.3) * 0.5, day);
    c = mix(c, float3(0.42, 0.36, 0.48) * 0.4, u.weather.x * 0.5);
    float l = luminance(c);
    return mix(c, float3(l), u.weather.y * 0.6);
}

static float3 exposed(float3 hdr, constant ShaderUniforms &u) {
    float exposure = overworld(u) ? mix(1.8, 1.0, u.sunDir.w) : 1.15;
    return hdr * exposure;
}

fragment ResolveOut m2m_resolve(float4 position [[position]],
                                half4 vanilla [[color(0)]],
                                depth2d<float> depth [[texture(0)]],
                                texture2d<half> gAlbedo [[texture(1)]],
                                texture2d<half> gData [[texture(2)]],
                                texture2d<half> lightTex [[texture(3)]],
                                texture2d<half> cloudTex [[texture(4)]],
                                texture3d<half> noise [[texture(7)]],
                                texture2d<half> skyLut [[texture(8)]],
                                sampler linearSampler [[sampler(0)]],
                                sampler noiseSampler [[sampler(2)]],
                                sampler skySampler [[sampler(3)]],
                                constant ShaderUniforms &u [[buffer(0)]],
                                constant PointLight *lights [[buffer(1)]],
                                device const uchar *voxels [[buffer(2)]],
                                device const uint *lightTiles [[buffer(4)]],
                                device const uchar *coarse [[buffer(5)]]) {
    ResolveOut out;
    out.bloom = half4(0.0h);
    uint2 px = uint2(position.xy);
    float2 fullSize = float2(depth.get_width(), depth.get_height());
    float2 uv = position.xy / fullSize;
    float z = depth.read(px);
    half4 g0 = gAlbedo.read(px);
    uint material = uint(float(g0.a) * 255.0 + 0.5);
    float3 rel = unproject(uv, z, u);
    float dist = length(rel);
    float3 view = rel / max(dist, 1.0e-5);

    if (material == 0) {
        if (!is_sky(z, u) || u.weather.w > 0.5 || !overworld(u)) {
            out.color = vanilla;
            return out;
        }
        // Sky: the atmosphere, vanilla's stars, moon and sunrise colors on top of it, a round sun, and clouds.
        float3 vanillaLin = decode_io(float3(vanilla.rgb), u);
        float3 vanillaSky = srgb_to_linear(u.skyColor.rgb);
        float3 bodies = max(vanillaLin - vanillaSky * 1.05, 0.0) * smoothstep(-0.02, 0.06, view.y);
        // The square vanilla sun (its corners reach about 23 degrees out) is replaced by the round one.
        bodies *= 1.0 - smoothstep(0.88, 0.92, dot(view, true_sun(u)));
        float3 sky = atmosphere(view, skyLut, skySampler) + bodies + sun_disc(view, u);
        float4 c = float4(cloudTex.sample(linearSampler, uv));
        sky = sky * (1.0 - c.a) + c.rgb;
        float3 hdr = exposed(sky, u);
        out.color = half4(half3(encode_io(tonemap(hdr), u)), vanilla.a);
        out.bloom = half4(half3(max(hdr - 1.0, 0.0)), 1.0h);
        return out;
    }

    half4 g1 = gData.read(px);
    float3 albedo = srgb_to_linear(float3(g0.rgb));
    float3 normal = oct_decode(float2(g1.rg));
    float blockLight = float(g1.b);
    float skyLight = float(g1.a);
    uint kind = material & 7u;
    bool fullBright = (material & MATERIAL_FULL_BRIGHT) != 0;
    float3 worldPos = rel + u.cameraPos.xyz;
    float lin = linear_depth(z, u);

    // Rain: what is open to the sky gets wet (darker, more saturated), and puddles gather on flat ground.
    float wet = u.waterInfo.w > 0.5 ? u.weather.y * smoothstep(0.86, 0.97, skyLight) : 0.0;
    float puddle = 0.0;
    if (wet > 0.0 && kind != MATERIAL_PARTICLE) {
        float porous = normal.y > 0.5 ? 1.0 : 0.6;
        albedo = pow(albedo, float3(1.0 + 0.45 * wet * porous)) * (1.0 - 0.18 * wet * porous);
        if (normal.y > 0.9 && kind == MATERIAL_SOLID) {
            puddle = puddles(worldPos.xz, u.weather.y, noise, noiseSampler) * wet;
        }
    }

    // Shadow and AO at full size: bilinear, but taps whose depth differs from this pixel's do not count, so they
    // do not bleed across silhouettes.
    float2 lightSize = float2(lightTex.get_width(), lightTex.get_height());
    float2 lp = uv * lightSize - 0.5;
    float2 base = floor(lp);
    float2 f = lp - base;
    float2 shadowAo = float2(0.0);
    float weightSum = 0.0;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            uint2 q = uint2(clamp(base + float2(i, j), float2(0.0), lightSize - 1.0));
            half4 s = lightTex.read(q);
            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
            w *= exp(-abs(float(s.b) - lin) / (lin * 0.02 + 0.05)) + 1.0e-4;
            shadowAo += float2(s.rg) * w;
            weightSum += w;
        }
    }
    shadowAo /= weightSum;
    float shadow = u.quality0.x > 0 ? shadowAo.x : 1.0;
    float ao = shadowAo.y;

    int debugView = int(u.viewInfo.z);
    if (debugView > 0) {
        float3 v = debugView == 1 ? float3(shadow) : debugView == 2 ? float3(ao) : debugView == 3 ? normal * 0.5 + 0.5
                 : debugView == 4 ? float3(blockLight, skyLight, 0.0)
                 : debugView == 5 ? float3(voxel_at_world(worldPos - normal * 0.05, u.cameraPos.xyz, voxels) != 0 ? 1.0 : 0.0)
                 : debugView == 6 ? float3(voxel_shadow(worldPos, normal, normalize(u.sunDir.xyz), 48, voxels, coarse, u.cameraPos.xyz, u.viewInfo.w))
                 : debugView == 8 ? float3(voxel_shadow(worldPos, normal, float3(0.0, 1.0, 0.0), 48, voxels, coarse, u.cameraPos.xyz, u.viewInfo.w))
                 : float3(voxel_at_world(worldPos + float3(0.0, 4.5, 0.0), u.cameraPos.xyz, voxels) / 2.0);
        out.color = half4(half3(v), 1.0h);
        return out;
    }

    float3 sun = normalize(u.sunDir.xyz);
    float3 sunCol = sun_color(u);
    float3 ambientCol = ambient_color(u);
    float ndotl = dot(normal, sun);
    bool foliage = kind == MATERIAL_FOLIAGE;

    // Direct sun. Without the voxel grid (far away) only open sky lets sunlight in, which keeps caves dark.
    float sunGate = dist < VOXEL_REACH - 4.0 ? smoothstep(0.2, 0.7, skyLight) : smoothstep(0.75, 0.98, skyLight);
    // Under water the sunlight arrives through the waves, focused into caustics.
    if (voxel_at_world(worldPos + normal * 0.5, u.cameraPos.xyz, voxels) == VOXEL_WATER) {
        float2 p = worldPos.xz + sun.xz / max(sun.y, 0.3) * worldPos.y * 0.25;
        sunGate *= 0.4 + 1.8 * caustics(p, u.cameraPos.w);
    }
    float diffuse = foliage ? (0.3 + 0.5 * abs(ndotl)) : (kind == MATERIAL_PARTICLE ? 0.7 : saturate(ndotl));
    float3 direct = sunCol * diffuse * shadow * sunGate;
    if (foliage) {
        // Light scattered through leaves towards the viewer.
        float scatter = pow(saturate(dot(view, sun)), 4.0) * 0.8 + 0.1;
        direct += sunCol * scatter * shadow * sunGate * 0.25;
    }

    // Sky light from the hemisphere above, and a little sunlight bounced off the ground. Without a sky the dimension's
    // ambient light reaches everywhere.
    float skyFactor = overworld(u) ? skyLight * skyLight : 1.0;
    float3 ambient = ambientCol * skyFactor * (0.6 + 0.4 * (normal.y * 0.5 + 0.5));
    ambient += sunCol * 0.06 * u.sunDir.w * skyFactor * saturate(-normal.y * 0.5 + 0.5);

    // Block light follows Minecraft's light levels, so walls still stop it; nearby light sources tint it. The tint
    // only shows where there is block light, and only the lights that can reach this pixel's tile are looked at
    // (m2m_light_tiles).
    float3 blockTint = float3(1.0, 0.60, 0.30);
    int count = min(u.quality1.w & 0xFFFF, 32);
    if (u.quality0.w > 0 && count > 0 && blockLight > 0.0) {
        uint tilesX = (uint(fullSize.x) + LIGHT_TILE - 1) / LIGHT_TILE;
        uint mask = lightTiles[(px.y / LIGHT_TILE) * tilesX + px.x / LIGHT_TILE];
        float3 sum = float3(0.0);
        while (mask != 0u) {
            int i = int(ctz(mask));
            mask &= mask - 1u;
            float3 toLight = lights[i].posRadius.xyz - rel;
            float d2 = dot(toLight, toLight);
            float r = lights[i].posRadius.w;
            if (d2 < r * r) {
                float a = 1.0 - sqrt(d2) / r;
                sum += lights[i].colorPower.rgb * lights[i].colorPower.w * a * a;
            }
        }
        float s = max(sum.r, max(sum.g, sum.b));
        if (s > 1.0e-3) {
            blockTint = mix(blockTint, sum / s, smoothstep(0.0, 0.6, s));
        }
    }
    float b2 = blockLight * blockLight;
    float3 block = blockTint * (b2 * b2 * 1.4 + blockLight * 0.04);

    float brightness = u.viewInfo.x;
    float3 minimum = float3(0.6, 0.7, 1.0) * (0.004 + brightness * 0.02) + u.viewInfo.y * 0.35;

    float3 lighting = direct + (ambient + block + minimum) * ao;
    float3 hdr = albedo * lighting;

    // Emissive surfaces glow at their bright texels: light sources the voxel grid knows, and anything drawn full bright.
    uint voxel = voxel_at_world(worldPos - normal * 0.05, u.cameraPos.xyz, voxels);
    if (voxel >= VOXEL_EMISSIVE || fullBright) {
        bool molten = voxel >= VOXEL_EMISSIVE && ((voxel >> 4) & 7u) == 1u && (voxel & 15u) >= 12u;
        float peak = max(albedo.r, max(albedo.g, albedo.b));
        float mask = molten ? 1.0 : smoothstep(0.25, 0.7, peak);
        hdr = mix(hdr, albedo * 2.5, mask);
    }

    // Wet surfaces gloss; puddles mirror the sky, broken up by the rings of falling drops.
    float rain = u.weather.y;
    if (wet > 0.0) {
        float3 wetNormal = normal;
        if (puddle > 0.0) {
            float2 slope = rain_ripples(worldPos.xz, u.cameraPos.w) * puddle;
            wetNormal = normalize(float3(-slope.x, 1.0, -slope.y));
        }
        float3 r = reflect(view, wetNormal);
        float fresnel = 0.02 + 0.98 * pow(1.0 - saturate(dot(-view, wetNormal)), 5.0);
        float3 sky = atmosphere(normalize(float3(r.x, max(r.y, 0.02), r.z)), skyLut, skySampler);
        float mirror = max(puddle * 0.9, wet * 0.25);
        hdr = mix(hdr * (1.0 - puddle * 0.35), sky, fresnel * mirror);
        hdr += sunCol * pow(saturate(dot(r, sun)), 64.0) * shadow * wet * 0.6;
    }

    // Fog: haze towards the sky's own color in that direction, and the render distance edge fading into the sky.
    float haze = 1.0 - exp(-dist * (0.0011 + rain * 0.005));
    float cylinder = max(length(rel.xz), abs(rel.y));
    float edge = smoothstep(u.fogRange.z, u.fogRange.w, cylinder);
    if (overworld(u) && (haze > 0.004 || edge > 0.0)) {
        float3 skyView = atmosphere(view, skyLut, skySampler);
        hdr = mix(hdr, skyView, haze * 0.6);
        hdr = mix(hdr, skyView, edge);
    }

    float3 exposedHdr = exposed(hdr, u);
    float3 display = tonemap(exposedHdr);
    // Vanilla's environmental fog (water, lava, powder snow, blindness, and all of it outside the overworld) as vanilla
    // blends it, so the terrain matches the water and entities drawn after it. Under water in the overworld the post
    // pass draws the fog over everything instead.
    if ((u.weather.w > 0.5 && u.waterInfo.y < 0.5) || !overworld(u)) {
        float e = saturate((dist - u.fogRange.x) / max(u.fogRange.y - u.fogRange.x, 1.0e-3));
        if (!overworld(u)) {
            e = max(e, saturate((cylinder - u.fogRange.z) / max(u.fogRange.w - u.fogRange.z, 1.0e-3)));
        }
        // Blended in display (sRGB) space, as vanilla blends it.
        display = srgb_to_linear(mix(linear_to_srgb(display), u.fogColor.rgb, e));
        exposedHdr *= 1.0 - e;
    }
    out.color = half4(half3(encode_io(display, u)), 1.0h);
    out.bloom = half4(half3(max(exposedHdr - 1.0, 0.0)), 1.0h);
    return out;
}

// ---------------------------------------------------------------------------------------------------------------
// Skyline: the highest row that is not sky, per band of columns of the opaque depth, so a screen space ray that is
// about to rise above every surface in the columns it still crosses can stop (it could only read sky from there on,
// which is the same miss it would have marched to). Built in two small passes: the top of each band per block of
// rows, then a table where row k holds the top over 2^k consecutive bands, so any range of bands is the maximum of
// two entries.

constant uint SKYLINE_BAND = 16;
constant uint SKYLINE_ROWS = 64;

// One threadgroup per band and block of rows (16 x 64 threads): the highest row in it that is not sky, plus one, goes
// into the band's entry by atomic maximum. bands must be zeroed first.
kernel void m2m_skyline_reduce(uint2 gid [[thread_position_in_grid]],
                               uint lane [[thread_index_in_simdgroup]],
                               uint simd [[simdgroup_index_in_threadgroup]],
                               uint simds [[simdgroups_per_threadgroup]],
                               depth2d<float> depth [[texture(0)]],
                               device atomic_uint *bands [[buffer(1)]],
                               constant ShaderUniforms &u [[buffer(0)]]) {
    threadgroup uint partial[32];
    uint2 size = uint2(depth.get_width(), depth.get_height());
    uint top = 0;
    if (all(gid < size) && !is_sky(depth.read(gid), u)) {
        top = gid.y + 1;
    }
    top = simd_max(top);
    if (lane == 0) {
        partial[simd] = top;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (simd == 0) {
        uint value = lane < simds ? partial[lane] : 0;
        value = simd_max(value);
        if (lane == 0 && value > 0) {
            atomic_fetch_max_explicit(&bands[gid.x / SKYLINE_BAND], value, memory_order_relaxed);
        }
    }
}

// Entry k * bandCount + i: the top over bands i .. i + 2^k - 1 (clamped to the last band).
kernel void m2m_skyline_levels(uint2 gid [[thread_position_in_grid]],
                               device const uint *bands [[buffer(1)]],
                               device uint *levels [[buffer(2)]],
                               constant uint &bandCount [[buffer(3)]]) {
    if (gid.x >= bandCount) {
        return;
    }
    uint last = min(gid.x + (1u << gid.y), bandCount);
    uint top = 0;
    for (uint b = gid.x; b < last; b++) {
        top = max(top, bands[b]);
    }
    levels[gid.y * bandCount + gid.x] = top;
}

// The top (highest non-sky row plus one) over the columns x0 .. x1 of the screen.
static inline uint skyline_top(float x0, float x1, uint bandCount, device const uint *skyline) {
    uint b0 = min(uint(min(x0, x1)) / SKYLINE_BAND, bandCount - 1);
    uint b1 = min(uint(max(x0, x1)) / SKYLINE_BAND, bandCount - 1);
    uint n = b1 - b0 + 1;
    uint k = 31 - clz(n);
    return max(skyline[k * bandCount + b0], skyline[k * bandCount + b1 + 1 - (1u << k)]);
}

// The distance along a clip space ray (start c0, direction cd) from which every sample reads sky, or infinity: the
// ray's screen path from tStart to its exit is a line, so the skyline over the columns it crosses bounds it.
static float sky_from(float4 c0, float4 cd, float tStart, float tMax, float2 size, device const uint *skyline) {
    float tEnd = tMax;
    if (cd.w < 0.0) {
        tEnd = min(tEnd, (1.0e-4 - c0.w) / cd.w);
    }
    // Leaving the screen through x = +-w or y = +-w: the first root past tStart.
    float4 roots = (float4(c0.w, -c0.w, c0.w, -c0.w) - float4(c0.x, c0.x, c0.y, c0.y)) / (float4(cd.x, cd.x, cd.y, cd.y) - float4(cd.w, -cd.w, cd.w, -cd.w));
    for (int i = 0; i < 4; i++) {
        if (roots[i] > tStart && roots[i] < tEnd) {
            tEnd = roots[i];
        }
    }
    float w0 = c0.w + tStart * cd.w;
    float w1 = max(c0.w + tEnd * cd.w, 1.0e-4);
    float x0 = ((c0.x + tStart * cd.x) / w0 * 0.5 + 0.5) * size.x;
    float x1 = ((c0.x + tEnd * cd.x) / w1 * 0.5 + 0.5) * size.x;
    uint bandCount = (uint(size.x) + SKYLINE_BAND - 1) / SKYLINE_BAND;
    uint top = skyline_top(clamp(x0, 0.0, size.x - 1.0), clamp(x1, 0.0, size.x - 1.0), bandCount, skyline);
    if (float(top) >= size.y) {
        return INFINITY;
    }
    // Samples at or above row top read sky; rows are point sampled, so one row of margin.
    float yn = (float(top) + 1.0) / size.y * 2.0 - 1.0;
    float a = cd.y - yn * cd.w;
    float b = yn * c0.w - c0.y;
    return a > 0.0 ? max(b / a, tStart) : INFINITY;
}

// ---------------------------------------------------------------------------------------------------------------
// Stage 2b: water, right after the translucent terrain (before particles and weather)

constant uint MATERIAL_TINTED = 16;

// Small directional waves summed with their slopes; returns the slope of the height field over xz.
static float2 wave_slope(float2 p, float time, texture3d<half> noise, sampler noiseSampler) {
    const float4 waves[4] = {
        float4(0.82, 0.57, 1.6, 0.040), // direction xy, frequency, amplitude
        float4(-0.45, 0.89, 2.7, 0.022),
        float4(0.97, -0.24, 4.3, 0.011),
        float4(-0.71, -0.70, 7.1, 0.006),
    };
    float2 slope = float2(0.0);
    for (int i = 0; i < 4; i++) {
        float2 d = waves[i].xy;
        float k = waves[i].z;
        float phase = dot(d, p) * k + time * sqrt(9.8 * k) * 0.55;
        slope += d * (waves[i].w * k * cos(phase));
    }
    // Fine ripples that keep the surface from looking like a few long swells.
    float2 q = p * 3.1 + time * float2(0.31, -0.23);
    slope += float2(noise3(float3(q, time * 0.4), noise, noiseSampler) - 0.5, noise3(float3(q.yx + 17.0, time * 0.4), noise, noiseSampler) - 0.5) * 0.05;
    return slope;
}

// Marches the reflected ray through the opaque scene's depth; the hit's color, or alpha 0 when it leaves the screen
// (or, by the skyline, once it could only read sky). The ray's distance along the view axis is its clip w.
static float4 reflect_screen(float3 rel, float3 dir, int steps, float jitter, depth2d<float> opaqueDepth, texture2d<half> opaque,
                             device const uint *skyline, sampler linearSampler, sampler pointSampler, constant ShaderUniforms &u) {
    float stepLength = 1.2;
    float t = stepLength * (0.5 + jitter);
    float4 c0 = to_clip(rel, u.currentViewProj);
    float4 cd = clip_direction(dir, u.currentViewProj);
    float2 size = float2(opaqueDepth.get_width(), opaqueDepth.get_height());
    float tMax = t + stepLength * (pow(1.18, float(steps)) - 1.0) / 0.18;
    float tSky = sky_from(c0, cd, t, tMax, size, skyline);
    for (int i = 0; i < steps; i++) {
        if (t >= tSky) {
            return float4(0.0);
        }
        float4 clip = c0 + cd * t;
        if (clip.w <= 1.0e-4) {
            return float4(0.0);
        }
        float rcpW = 1.0 / clip.w;
        float2 uv = clip.xy * rcpW * 0.5 + 0.5;
        float z = clip.z * rcpW;
        if (z <= 0.0 || z >= 1.0 || any(uv <= 0.0) || any(uv >= 1.0)) {
            return float4(0.0);
        }
        float sceneZ = opaqueDepth.sample(pointSampler, uv);
        if (!is_sky(sceneZ, u)) {
            float diff = clip.w - linear_depth(sceneZ, u);
            if (diff > 0.0 && diff < stepLength * 2.5 + t * 0.05) {
                float2 edge = min(uv, 1.0 - uv);
                float fade = smoothstep(0.0, 0.08, min(edge.x, edge.y)) * (1.0 - float(i) / float(steps));
                return float4(decode_io(float3(opaque.sample(linearSampler, uv).rgb), u), fade);
            }
        }
        t += stepLength;
        stepLength *= 1.18;
    }
    return float4(0.0);
}

// The surface seen from under water: within Snell's window the world above, bent by the waves; outside it total
// internal reflection of the water below. The post pass then fogs it by its distance like everything else.
static float3 water_from_below(float2 pixel, float2 uv, float3 rel, float3 view, float3 normal, depth2d<float> opaqueDepth,
                               texture2d<half> opaque, device const uint *skyline, sampler linearSampler, sampler pointSampler,
                               constant ShaderUniforms &u, texture2d<half> skyLut, sampler skySampler) {
    float cosI = saturate(dot(-view, normal));
    float sinT = 1.333 * sqrt(max(1.0 - cosI * cosI, 0.0));
    float3 deep = srgb_to_linear(u.fogColor.rgb);
    float3 reflected = deep;
    int quality = u.quality0.z;
    if (quality >= 2) {
        float3 r = reflect(view, normal);
        float4 hit = reflect_screen(rel + normal * 0.05, r, quality == 2 ? 10 : 16, ign(pixel, u.skyColor.w), opaqueDepth, opaque, skyline,
                                    linearSampler, pointSampler, u);
        reflected = mix(deep, hit.rgb, hit.a);
    }
    if (sinT >= 1.0) {
        return reflected;
    }
    float cosT = sqrt(1.0 - sinT * sinT);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosT, 5.0);
    // The world above: what was drawn behind the surface, or the sky along the refracted direction.
    float3 t = refract(view, normal, 1.333);
    float2 refractedUv = clamp(uv + normal.xz * 0.04, 0.001, 0.999);
    float behind = opaqueDepth.sample(pointSampler, refractedUv);
    float3 above = is_sky(behind, u) ? tonemap(exposed(atmosphere(normalize(t), skyLut, skySampler) + sun_disc(normalize(t), u) * 0.3, u))
                                     : decode_io(float3(opaque.sample(linearSampler, refractedUv).rgb), u);
    return mix(above, reflected, fresnel);
}

// Redraws water from its G-buffer over the scene without it: waves, refraction with absorption by depth, and screen
// space plus sky reflections by Fresnel. Works in linear light; the scene is read and written through decode_io and
// encode_io.
fragment half4 m2m_water(float4 position [[position]],
                         half4 current [[color(0)]],
                         depth2d<float> depth [[texture(0)]],
                         depth2d<float> opaqueDepth [[texture(1)]],
                         texture2d<half> opaque [[texture(2)]],
                         texture2d<half> gAlbedo [[texture(3)]],
                         texture2d<half> gData [[texture(4)]],
                         texture3d<half> noise [[texture(7)]],
                         texture2d<half> skyLut [[texture(8)]],
                         sampler linearSampler [[sampler(0)]],
                         sampler pointSampler [[sampler(1)]],
                         sampler noiseSampler [[sampler(2)]],
                         sampler skySampler [[sampler(3)]],
                         constant ShaderUniforms &u [[buffer(0)]],
                         device const uint *skyline [[buffer(2)]]) {
    uint2 px = uint2(position.xy);
    half4 g0 = gAlbedo.read(px);
    uint material = uint(float(g0.a) * 255.0 + 0.5);
    if ((material & MATERIAL_TINTED) == 0) {
        return current;
    }
    float2 size = float2(depth.get_width(), depth.get_height());
    float2 uv = position.xy / size;
    float z = depth.read(px);
    float3 rel = unproject(uv, z, u);
    float dist = length(rel);
    float3 view = rel / max(dist, 1.0e-5);
    float3 worldPos = rel + u.cameraPos.xyz;
    float time = u.cameraPos.w;
    half4 g1 = gData.read(px);
    float3 normal = oct_decode(float2(g1.rg));
    float skyLight = float(g1.a);

    // Waves on the horizontal surfaces, fading out with distance where they would only shimmer.
    if (abs(normal.y) > 0.5) {
        float2 slope = wave_slope(worldPos.xz, time, noise, noiseSampler) * (1.0 - smoothstep(24.0, 96.0, dist) * 0.8);
        if (u.waterInfo.w > 0.5 && normal.y > 0.0) {
            slope += rain_ripples(worldPos.xz, time) * u.weather.y * smoothstep(0.86, 0.97, skyLight) * (1.0 - smoothstep(16.0, 48.0, dist));
        }
        normal = normal.y > 0.0 ? normalize(float3(-slope.x, 1.0, -slope.y)) : normalize(float3(slope.x, -1.0, slope.y));
    }

    if (u.waterInfo.y > 0.5 && normal.y < 0.0) {
        return half4(half3(encode_io(water_from_below(position.xy, uv, rel, view, normal, opaqueDepth, opaque, skyline,
                                                       linearSampler, pointSampler, u, skyLut, skySampler), u)), current.a);
    }

    // Refraction: the scene behind, pushed by the waves, as long as what is found there is still behind the water.
    float linSurface = linear_depth(z, u);
    float2 push = normal.xz * (0.06 / max(linSurface, 1.0));
    float2 refractedUv = clamp(uv + push, 0.001, 0.999);
    float behind = opaqueDepth.sample(pointSampler, refractedUv);
    if (!is_sky(behind, u) && linear_depth(behind, u) < linSurface) {
        refractedUv = uv;
        behind = opaqueDepth.sample(pointSampler, uv);
    }
    float3 below = decode_io(float3(opaque.sample(linearSampler, refractedUv).rgb), u);
    float thickness = is_sky(behind, u) ? 64.0 : max(linear_depth(behind, u) - linSurface, 0.0);

    // Absorption by depth (red first), and light scattered back out of the water body in its biome color.
    float3 tint = saturate(srgb_to_linear(float3(g0.rgb)) * 1.6);
    float3 absorption = (1.0 - tint) * 0.22 + float3(0.03, 0.012, 0.006);
    float3 transmittance = exp(-absorption * thickness);
    float3 sun = normalize(u.sunDir.xyz);
    float3 lightOnWater = ambient_color(u) * (skyLight * skyLight) + sun_color(u) * saturate(sun.y) * 0.35 * smoothstep(0.5, 1.0, skyLight);
    float3 scatter = tonemap(exposed(tint * lightOnWater * 0.22, u));
    float3 body = below * transmittance + scatter * (1.0 - transmittance);

    // Reflection: what is on screen along the reflected ray, else the sky (only where the sky can be seen).
    float3 r = reflect(view, normal);
    r.y = max(r.y, 0.005);
    r = normalize(r);
    float3 skyHdr = atmosphere(r, skyLut, skySampler) + sun_disc(r, u) * 0.6;
    float3 reflection = overworld(u) ? tonemap(exposed(skyHdr, u)) * mix(0.15, 1.0, skyLight * skyLight) : srgb_to_linear(u.fogColor.rgb);
    int quality = u.quality0.z;
    if (quality >= 2) {
        int steps = quality == 2 ? 12 : (quality == 3 ? 18 : 26);
        float4 hit = reflect_screen(rel + normal * 0.05, r, steps, ign(position.xy, u.skyColor.w), opaqueDepth, opaque, skyline,
                                    linearSampler, pointSampler, u);
        reflection = mix(reflection, hit.rgb, hit.a);
    }
    float cosView = saturate(dot(-view, normal));
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosView, 5.0);
    float3 color = mix(body, reflection, fresnel);

    // The same haze as the terrain around it.
    float haze = 1.0 - exp(-dist * (0.0011 + u.weather.y * 0.005));
    if (overworld(u) && haze > 0.004) {
        color = mix(color, tonemap(exposed(atmosphere(view, skyLut, skySampler), u)), haze * 0.6);
    }
    return half4(half3(encode_io(color, u)), current.a);
}

// ---------------------------------------------------------------------------------------------------------------
// Stage 3: bloom and light shafts

// 13 taps would ring less, but the 5-tap dual Kawase filter is enough at these sizes and halves the reads.
fragment half4 m2m_bloom_down(float4 position [[position]], texture2d<half> source [[texture(0)]], sampler linearSampler [[sampler(0)]]) {
    float2 halfTexel = 0.5 / float2(source.get_width(), source.get_height());
    float2 uv = position.xy * 2.0 / float2(source.get_width(), source.get_height());
    half4 sum = source.sample(linearSampler, uv) * 4.0h;
    sum += source.sample(linearSampler, uv - halfTexel);
    sum += source.sample(linearSampler, uv + halfTexel);
    sum += source.sample(linearSampler, uv + float2(halfTexel.x, -halfTexel.y));
    sum += source.sample(linearSampler, uv - float2(halfTexel.x, -halfTexel.y));
    return min(sum * 0.125h, half4(64.0h));
}

fragment half4 m2m_bloom_up(float4 position [[position]], texture2d<half> source [[texture(0)]], sampler linearSampler [[sampler(0)]]) {
    float2 halfTexel = 0.5 / float2(source.get_width(), source.get_height());
    float2 uv = position.xy * 0.5 / float2(source.get_width(), source.get_height());
    half4 sum = source.sample(linearSampler, uv + float2(-halfTexel.x * 2.0, 0.0));
    sum += source.sample(linearSampler, uv + float2(-halfTexel.x, halfTexel.y)) * 2.0h;
    sum += source.sample(linearSampler, uv + float2(0.0, halfTexel.y * 2.0));
    sum += source.sample(linearSampler, uv + float2(halfTexel.x, halfTexel.y)) * 2.0h;
    sum += source.sample(linearSampler, uv + float2(halfTexel.x * 2.0, 0.0));
    sum += source.sample(linearSampler, uv + float2(halfTexel.x, -halfTexel.y)) * 2.0h;
    sum += source.sample(linearSampler, uv + float2(0.0, -halfTexel.y * 2.0));
    sum += source.sample(linearSampler, uv + float2(-halfTexel.x, -halfTexel.y)) * 2.0h;
    return sum / 12.0h;
}

// The camera's water: light absorbed along the way (red first) and scattered back in the biome's water fog color.
// Replaces vanilla's water fog, which is left out of every shader.
static float3 under_water(float2 uv, float3 c, depth2d<float> depth, sampler pointSampler, constant ShaderUniforms &u) {
    float z = depth.sample(pointSampler, uv);
    float dist = is_sky(z, u) ? 1.0e4 : length(unproject(uv, z, u));
    // Vanilla's water fog ends at this distance (longer with water vision); most of the light is gone by then.
    float density = 2.6 / max(u.fogRange.y, 8.0);
    float3 transmittance = exp(-dist * density * float3(1.35, 0.95, 0.8));
    return c * transmittance + srgb_to_linear(u.fogColor.rgb) * (1.0 - transmittance);
}

// Shafts of sunlight coming down from the waves, through the water between the camera and the pixel.
static float3 underwater_shafts(float2 pixel, float2 uv, depth2d<float> depth, sampler pointSampler, constant ShaderUniforms &u) {
    float3 sun = normalize(u.sunDir.xyz);
    float surface = u.waterInfo.x;
    if (u.quality1.x <= 0 || u.sunDir.w <= 0.05 || surface < -1.0e5) {
        return float3(0.0);
    }
    float z = depth.sample(pointSampler, uv);
    float3 rel = unproject(uv, is_sky(z, u) ? 0.5 : z, u);
    float3 view = normalize(rel);
    float dist = is_sky(z, u) ? 1.0e4 : length(rel);
    float density = 2.6 / max(u.fogRange.y, 8.0);
    int steps = u.quality1.x == 1 ? 6 : (u.quality1.x == 2 ? 10 : 14);
    float reach = min(dist, 28.0);
    float stepLength = reach / float(steps);
    float t = stepLength * ign(pixel, u.skyColor.w);
    float shafts = 0.0;
    for (int i = 0; i < steps; i++) {
        float3 p = u.cameraPos.xyz + view * t;
        float below = surface - p.y;
        if (below > 0.0) {
            float2 q = p.xz + sun.xz / max(sun.y, 0.3) * below;
            shafts += caustics(q * 0.5, u.cameraPos.w) * exp(-t * density * 1.2 - below * 0.06);
        }
        t += stepLength;
    }
    float3 tint = srgb_to_linear(u.fogColor.rgb) * 2.5 + float3(0.05, 0.08, 0.08);
    return tint * shafts / float(steps) * 1.1 * u.sunDir.w * (1.0 - u.weather.y * 0.7);
}

// Light shafts in the air: how much open sky lies between this pixel and the sun on screen.
static float3 sky_shafts(float2 pixel, float2 uv, depth2d<float> depth, sampler pointSampler, constant ShaderUniforms &u) {
    int quality = u.quality1.x;
    float3 body = body_direction(u);
    float3 sunScreen = project(body * 1000.0, u.currentViewProj);
    if (quality <= 0 || sunScreen.z <= 0.0 || u.weather.w > 0.5 || !overworld(u)) {
        return float3(0.0);
    }
    float facing = saturate(dot(normalize(unproject(uv, 1.0, u)), body));
    if (facing <= 0.0) {
        return float3(0.0);
    }
    int steps = quality == 1 ? 8 : (quality == 2 ? 12 : 16);
    float2 stepUv = (sunScreen.xy - uv) * 0.6 / float(steps);
    float2 p = uv + stepUv * ign(pixel, u.skyColor.w);
    float open = 0.0;
    for (int i = 0; i < steps; i++) {
        if (all(p > 0.0) && all(p < 1.0)) {
            open += is_sky(depth.sample(pointSampler, p), u) ? 1.0 : 0.0;
        }
        p += stepUv;
    }
    float shaft = open / float(steps) * pow(facing, 6.0) * (1.0 - u.weather.y * 0.9);
    float3 tint = mix(float3(1.0, 0.88, 0.70), float3(1.0, 0.50, 0.20), u.weather.x);
    return tint * shaft * (0.10 + 0.25 * u.weather.x) * mix(0.15, 1.0, u.sunDir.w);
}

// Light shafts at half size: they are soft, and marching them at every pixel was most of the post pass's cost.
fragment half4 m2m_shafts(float4 position [[position]],
                          depth2d<float> depth [[texture(0)]],
                          texture2d<half> target [[texture(1)]],
                          sampler pointSampler [[sampler(1)]],
                          constant ShaderUniforms &u [[buffer(0)]]) {
    float2 uv = position.xy / float2(target.get_width(), target.get_height());
    float3 c = u.waterInfo.y > 0.5 ? underwater_shafts(position.xy, uv, depth, pointSampler, u) : sky_shafts(position.xy, uv, depth, pointSampler, u);
    return half4(half3(c), 1.0h);
}

fragment half4 m2m_post(float4 position [[position]],
                        half4 color [[color(0)]],
                        depth2d<float> depth [[texture(0)]],
                        texture2d<half> bloom [[texture(1)]],
                        texture2d<half> shafts [[texture(2)]],
                        sampler linearSampler [[sampler(0)]],
                        sampler pointSampler [[sampler(1)]],
                        constant ShaderUniforms &u [[buffer(0)]],
                        constant float2 &strengths [[buffer(1)]]) {
    float2 size = float2(depth.get_width(), depth.get_height());
    float2 uv = position.xy / size;
    float3 c = decode_io(float3(color.rgb), u);
    // x: bloom, y: whether the shafts texture was drawn this frame.
    if (strengths.x > 0.0) {
        c += float3(bloom.sample(linearSampler, uv).rgb) * strengths.x;
    }
    if (u.waterInfo.y > 0.5) {
        c = under_water(uv, c, depth, pointSampler, u);
    }
    if (strengths.y > 0.0) {
        c += float3(shafts.sample(linearSampler, uv).rgb);
    }
    return half4(half3(encode_io(c, u)), color.a);
}
