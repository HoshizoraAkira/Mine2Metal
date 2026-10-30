# Design: FSR 3 frame generation on Metal

*English version of [docs/fsr-frame-gen.md](../fsr-frame-gen.md).*

Status: FSR 3.1 frame generation is in the game (R1, 2026-10-28); see "R1 results" below. F0 (the spike) and the upscaler research are below too.

## Why

MetalFX frame interpolation works, but the interpolator itself is expensive on the MacBook Air M3's GPU: ~10–40 ms per frame at 1080p and ~50 ms at 2560×1600 (see roadmap M12). At Retina resolution that cost alone exceeds the budget of 30 native FPS. AMD's FSR 3 frame generator consists of ordinary compute shaders: coarse-block optical flow, then interpolation and inpainting. Those shaders were designed for console and mid-range PC GPUs. If their cost on the M3 is far below MetalFX, interpolation becomes genuinely useful on this Mac. **That has to be proven first (phase F0).**

## Source code: FidelityFX SDK v1.1.4 (MIT, with GLSL shaders)

- **v1.1.4 (May 2025): MIT license.** It has a complete Vulkan backend with **GLSL** shaders, C++ host code for optical flow and frame interpolation, and a backend interface (`FfxInterface`) we can implement for Metal.
- **v2.x (2.0–2.3, 2025–2026): the FSR 3 code is MIT too.** Correction of 2026-10-28: the first draft of this design said v2.x could not be used, because the opening paragraph of `docs/license.md` in v2.3.0 is a "binary only, no reverse engineering" license. That paragraph turned out to apply only to files outside an exception list. The exception list is MIT and holds every FSR 3 file (frame generation 77/77 files, upscaler 72/72, API and backends), checked one by one. The only closed parts are the signed DLLs in `signedbin/`, including FSR 4 (ML-based, RDNA 4 only, no source).
- v2.3.0 holds newer versions (frame interpolation **3.1.6** vs 1.1.3 in v1.1.4; upscaler 3.1.5 vs 3.1.4), but its shaders are HLSL only and its backend DX12 only. Using it needs `dxc` (HLSL → SPIR-V) in place of `glslangValidator`. That is roadmap item R10. Until it is done, v1.1.4 stays, since its GLSL is proven to translate automatically.

Taken from v1.1.4 (vendored as is into `third_party/fidelityfx-1.1.4/` with its `LICENSE.txt`):

| Part | Contents | Size |
|---|---|---|
| Optical flow shaders | 7 passes: prepare luma, luminance pyramid, histogram + divergence (scene-change detection), optical flow v5, filter, scale | GPU headers + `.glsl` |
| Frame interpolation shaders | 11 passes: reconstruct & dilate (= "prepare"), setup, reconstruct previous depth, game motion vector field, optical flow vector field, disocclusion mask, interpolation, inpainting pyramid, inpainting, game vector field inpainting pyramid, debug view | ~5,800 lines of GPU headers |
| Host C++ | `ffx_frameinterpolation.cpp`, `ffx_opticalflow.cpp`, host headers and the backend interface | ~2,300 lines |

Not taken: the `FrameInterpolationSwapchain` (AMD's pacing and present, since our present thread already does that), the FSR upscaler (we use MetalFX), and Anti-Lag.

FSR 3.1 separates frame generation from the upscaler. `ffxFrameInterpolationPrepare` builds dilated depth and motion vectors from **the game's depth + motion vectors**, so the generator can be used without the FSR upscaler, next to MetalFX temporal.

## Architecture

```
render thread (per frame)
  world ─► [MetalFX upscale] ─► capture W (no HUD), depth, motion vectors   (exists)
       └─► hand + GUI ─► finished frame F
  publish ─► encode FSR:
       prepare(depth, motion, jitter, camera)      → dilated depth/motion, prev depth
       optical flow(previous W, current W)         → flow + scene-change detection
       interpolation(F, W without HUD, the above)  → generated frame (with frame F's GUI)
  ─► present thread slot: generated frame + F
present thread (exists): show the generated frame, then F, on consecutive refreshes
```

Every input FSR needs we already produce for MetalFX and HDR: the world's color without the HUD (`currentBackBuffer_HUDLess`), the finished frame (`currentBackBuffer`), depth, motion vectors from the `m2m_encode_motion` pass, jitter, near/far/FOV, and the time between frames. FSR uses the HUD-less image to track motion and then pastes the GUI from the current frame, much like MetalFX's composited-UI mode. The newly finished single-path present just needs a generated-frame texture from another source.

In the Metal menu, "Frame Interpolation" becomes the choice **Off / MetalFX / FSR 3**.

### Shaders: GLSL → SPIR-V → MSL → metallib at build time

1. `glslangValidator` (already installed) compiles the Vulkan backend's `.glsl` with `-DFFX_GLSL=1 -DFFX_GPU=1` and the permutations we use:
   - `LOW_RES_MOTION_VECTORS`: 1 when upscaling, 0 without.
   - `JITTER_MOTION_VECTORS = 0`: our motion vectors carry no jitter.
   - `INVERTED_DEPTH = 0`.
   - `HDR_COLOR_INPUT`: 0 for SDR, 1 for EDR.

   About 18 passes × a few permutations.
2. `spirv-cross` (needs `brew install spirv-cross`) translates the SPIR-V to MSL 3.1 and emits JSON reflection (name and binding slot of every resource).
3. The MSL is compiled **at run time** through `newLibraryWithSource`, like the backend's other shaders. This needs no Metal Toolchain (in Xcode 26+ that toolchain is a separate download). Compiling the 18 passes takes ~2.7 s once, then Metal's cache (~20 ms afterwards).
4. A small generator turns the reflection into C tables shaped like AMD's shader blobs. AMD's host code matches resources **by name** (`r_…`, `rw_…`, `cb…`), so the names must match exactly.

All of this becomes a Gradle task (`buildFsrShaders`) before `buildNative`. Intermediate artifacts are not committed. For F0 the script is `scripts/build-fsr-shaders.sh`.

The GPU features the shaders use were checked in the source:
- **Atomics:** only 32-bit `imageAtomicMax/Min/Add` on `r32ui` textures. In Metal these become texture atomics (Metal 3.1, macOS 14; the texture needs `MTLTextureUsageShaderAtomic`).
- **Subgroups:** `subgroupShuffleXor`, ballot and quad ops become `simd_*`/`quad_*` functions.
- **Shared memory:** `groupshared` (SPD 16×16) becomes `threadgroup`.
- No 64-bit atomics in the passes we use. The int64 extension in `ffx_core_glsl.h` is only enabled under certain conditions.

### Host: the `ffx_metal` backend

AMD's host code is unchanged. It calls the backend through `FfxInterface` to create resources and pipelines, schedule GPU jobs (dispatch, copy, clear), and execute them. We write the Metal implementation in Objective-C++ (`src/main/native/fsr/ffx_metal.mm`) on top of the existing helpers (`gpu.m`):

| FfxInterface function | Metal implementation |
|---|---|
| CreateBackendContext / GetDeviceCapabilities | Context from `M2MContext`. Capabilities: wave 32, FP16, texture atomics |
| CreateResource / RegisterResource / GetResourceDescription / DestroyResource | Private `MTLTexture`/`MTLBuffer` through `m2m_new_private_texture` (residency-tracked, released through `m2m_retire`); the game's textures registered per frame |
| CreatePipeline | `MTLComputePipelineState` from the metallib + reflection tables |
| ScheduleGpuJob / ExecuteGpuJobs | Metal 3 or Metal 4 compute encoders (argument tables, barriers) in the frame's command buffer; copy/clear through the existing encoders |

To add in `gpu.m`: a compute-encoder helper (`m2m_open_compute_encoder`) for Metal 3 and Metal 4. The backend so far only has render and copy encoders.

Estimated new code: backend ~1,500 lines, shader build + reflection generator ~300 lines, integration and settings ~400 lines. The vendored AMD code is not counted.

### Conventions to calibrate

Calibration uses the PSNR method already in the game tests: the camera turns 0° → 4°, then the generated frame is compared with the real frame at 2°.
- **Motion vectors:** we write `prev − cur` in pixels, rows bottom-to-top. FSR expects vectors with `motionVectorScale` and a top-to-bottom UV orientation. Probably a `1/renderSize` scale and a flipped Y sign; the right one is found from PSNR, like the jitter sign before.
- **Depth:** zero-to-one, not reversed. `viewSpaceToMetersFactor = 1` (1 block = 1 m).
- **Color:** SDR uses the sRGB transfer. EDR uses HDR input with a linear transfer, with `minMaxLuminance` from the headroom.

## Risks

1. **Performance on the M3 is unknown.** This is the biggest risk, which is why F0 comes first.
2. **SPIRV-Cross translation:** half types, quad ops and image atomics sometimes need options or small patches. A pass that cannot be translated automatically gets written by hand in MSL.
3. **Texture atomics on older Macs** (M1) are unverified. If unsupported, SPIRV-Cross can emulate them through buffers, at a cost.
4. **Naming and branding:** the feature is called "FSR 3 Frame Generation (AMD FidelityFX, MIT)" with attribution in the README and the license bundled, without implying official AMD endorsement.
5. **Maintenance:** the version is pinned to v1.1.4. AMD no longer releases MIT versions, so upstream fixes will not arrive.

## Phases

| Phase | Contents | Gate |
|---|---|---|
| **F0 Spike** | Vendor shaders + headers, compile every pass to a metallib, a minimal host running optical flow + interpolation on captured game-test frames, GPU time at 1920×1080 and 2560×1600 | Total ≤ 6 ms at 1080p and ≤ 12 ms at 2560×1600 (about ⅓ of MetalFX). If it fails, stop here and record the result |
| F1 Backend | Full `ffx_metal` + AMD host code, full dispatch (prepare, optical flow, interpolation), clean Metal 3 and 4 validation | Generated frame ≥ 31 dB in the yaw test (on par with MetalFX) |
| F2 Integration | The Off/MetalFX/FSR 3 choice, the present-thread slot, combination with upscaling and HDR, license attribution | Full game test passes; FPS in the walk-to-the-forest test better than MetalFX |
| F3 Tuning | FP16 permutations, a separate queue so interpolation runs in parallel with the next frame, debug view | — |

## F0 results (2026-10-28)

What was made:
- `scripts/vendor-fidelityfx.sh`: copies 75 files of v1.1.4 (MIT) into `third_party/fidelityfx-1.1.4/`. The only change: the default context size doubled, because `wchar_t` on macOS is 4 bytes while AMD counts 2.
- `scripts/build-fsr-shaders.sh`: 18 passes GLSL → SPIR-V → MSL, all successful without manual patches. Texture atomics become native `atomic_fetch_max`, subgroups become `simd_*`.
- `src/main/native/fsr/ffx_metal.{h,mm}`: an `FfxInterface` backend for Metal 3, ~600 lines.
  - **Binding:** read from the Metal pipeline's reflection, by AMD's name prefixes (`r_`/`rw_`/`cb`). Access qualifiers cannot be used, since a pass that only reads an `rw_` resource yields read-only MSL.
  - **Debug:** `FFX_METAL_TRACE` and `FFX_METAL_PROFILE` (GPU time per pass).
  - **Windows compatibility:** `ffx_macos_compat.h` and replacements for `_countof`/`wcscpy_s`/`swprintf_s`. AMD's host code is unchanged.
- `tools/fsrbench`: runs prepare → optical flow → interpolation with AMD's original host code on two game-test frames (yaw 0° and 4°).

Results on an Apple M3 (MacBook Air), without validation. Under validation there are no errors.

| Output | Full render | 50 % render (as with temporal upscaling) | MetalFX, full render (M12) |
|---|---|---|---|
| 1920×1080 | 11.6–12.8 ms | 8.7 ms | ~10–40 ms |
| 2560×1600 | 23 ms | 17.2 ms | ~47–54 ms |
| 3840×2486 (full Retina) | 54 ms | 40 ms | — |

Breakdown at 1080p, 50 % render: the main interpolation pass 2.1 ms, optical flow ~2.4 ms (7 levels), reconstruct previous depth 0.7, game motion vector field 0.6, disocclusion 0.55, inpainting pyramid 0.7, the rest < 0.4 per pass. At full render, disocclusion jumps to 4.4 ms.

Quality: the generated frame already sits between the two real frames (25.6 dB against the real frame at yaw 2°, while the real frames at both ends score 21.8 dB). That is from optical flow alone, since the benchmark feeds flat depth and zero motion vectors. Real motion vectors and depth from the game only come in F1. MetalFX with full inputs: 31.3 dB.

**The F0 gate is not met by the numbers** (targets ≤ 6 ms at 1080p and ≤ 12 ms at 2560×1600; results 8.7–12 and 17–23 ms). But:
- FSR 3 is **2–3× cheaper** than MetalFX at the same size, and its time is stable. MetalFX varies between 10 and 40 ms at 1080p.
- At 1080p-class output with 50 % render, 8.7 ms still fits the 33 ms budget per real frame for 30 + 30 FPS. At full Retina (40–54 ms) no frame generator reaches 30 + 30 on the M3 Air's GPU.
- Visible optimisation room: the main interpolation pass and optical flow (threadgroup sizes, manual FP16 since AMD's GLSL path does not use it), and disocclusion at full render.

## Further research and optimisation (2026-10-28)

### Frame generation backend optimisation

| Experiment | Result |
|---|---|
| Empty 1×1 textures for optional inputs not provided (distortion field, HUD-less), like null descriptors in AMD's Vulkan backend | Kept: more correct (no `nil` texture sampled), same performance |
| `spirv-opt --loop-unroll -O` before spirv-cross, so small arrays become registers | AMD's loops hold `if`/`continue` so they do not unroll; no change; dropped |
| spirv-cross `--msl-force-native-arrays` (no `spvUnsafeArray`) | Same time; dropped |
| Passes reading a plain copy of the `r32ui` atomic texture (testing the atomic memory layout) | Same time; dropped |

Conclusion: the GLSL → MSL translation slows nothing down. The cost of the main interpolation pass, optical flow and disocclusion (full render) is the algorithm's cost on the M3 GPU. Further savings need manual rewrites (FP16, since AMD's GLSL path hardly uses `half`).

Fixes found along the way (now in `ffx_metal.mm`):
- Constant buffers are padded to the size the shader declares. std140 rounds structs to 16 bytes, AMD's C structs do not (148 vs 160 bytes in the FSR 3.1 upscaler).
- Clears of optional resources that were not provided are skipped.

### Upscalers: FSR 1 and FSR 3.1 vs MetalFX

Both AMD upscalers are vendored too (v1.1.4, MIT). Their 12 passes translate automatically, and `tools/fsrbench/upscale.mm` (`fsrupscale`) measures them side by side with MetalFX on the same input. No errors under validation. GPU time per frame on an Apple M3:

| Output (render) | FSR 3.1 | MetalFX temporal | FSR 1 (EASU + RCAS) | MetalFX spatial |
|---|---|---|---|---|
| 1920×1080 (50 %) | 2.67 ms | **1.09 ms** | 0.83 ms | **0.51 ms** |
| 1920×1080 (75 %) | 3.40 | **1.37** | 0.88 | **0.51** |
| 2560×1600 (50 %) | 4.87 | **2.14** | 1.60 | **1.01** |
| 3840×2486 (50 %) | 10.77 | **4.94** | 3.70 | **2.35** |

MetalFX is 2–2.5× cheaper for upscaling, while FSR is 2–3× cheaper for frame generation. The most efficient combination on this Mac: **MetalFX upscaling + FSR 3.1 frame generation**. The FSR upscalers can be offered as options, but will always be heavier. Quality was not compared, since that needs real motion from the game.

### Relevant Apple features (from the macOS 27 SDK headers)

- `MTLFXTemporalScaler.reactiveMaskTexture` (macOS 14.4+): a mask that reduces the faint trails of mobs and particles in temporal upscaling.
- `CAMetalDisplayLink` (macOS 14+): drawables delivered on time, with `targetPresentationTimestamp` and `preferredFrameLatency`. A replacement for `nextDrawable` on the present thread, so a generated frame can sit halfway between two real frames with measurable latency.
- `jitteredMotionVectorsEnabled` / `outputResolutionMotionVectors` for the temporal scaler (macOS 27).

## R1 results: FSR 3.1 frame generation in the game (2026-10-28)

New menu choice **Frame Generation: Off / MetalFX / FSR 3.1** (one at a time). `frameInterpolation=true` from older versions reads as MetalFX.

How it works:
- **Build.** `scripts/build-fsr-embedded.sh` (Gradle task `buildFsrShaders`) translates the optical flow and frame interpolation passes (two variants: render-resolution and full-resolution motion vectors), then embeds the MSL as C tables. `scripts/build-native.sh` compiles the Objective-C backend, `ffx_metal.mm`, `fsr_framegen.mm`, AMD's host code (C++, with `ffx_macos_compat.h`) and those shader tables into one `libmine2metal.dylib`. The MSL is compiled at run time (~3 s the first time, then from Metal's cache).
- **Backend.** `ffx_metal.mm` holds resources and pipelines; recording goes through the `FfxMetalHost` protocol.
  - **Game:** `M2MFfxHost` records into the frame's command buffer through new helpers in `gpu.m` (`m2m_compute_begin`/`m2m_compute_dispatch`, binding onto compute encoders). On Metal 4 there is a shared argument table and dispatch → dispatch barriers, since the FSR passes depend on each other; resources join the residency set and are released through `m2m_retire`.
  - **Benchmark tool:** `FfxMetalCommandBufferHost` (plain Metal 3).
- **Inputs.** Same as MetalFX:
  - the world without the HUD (captured before the hand and GUI) as `currentBackBuffer_HUDLess`;
  - the finished frame as `currentBackBuffer`;
  - Depth32Float depth and motion vectors from the `m2m_encode_motion` pass (in pixels, `motionVectorScale` = 1: AMD's code already divides by the size itself);
  - near/far/FOV (radians), jitter, time between frames (ms).
  - With HDR, the color is extended linear (scRGB transfer, maximum luminance = the EDR peak).
- **Frame history.** FSR keeps the previous frame itself, so every captured frame is sent to FSR. After a reset, its generated frame is not shown.
- **Presentation.** Still through the same present thread: the generated frame, then the real one.

Game test results (Apple M3, 854×480; yaw test 0° → 4°, compared with the real frame at 2°):

| Engine | No upscaling | Temporal 50 % |
|---|---|---|
| MetalFX | 31.30 dB | 29.81 dB |
| **FSR 3.1** (Metal 4) | **31.31 dB** | **29.78 dB** |
| FSR 3.1 (Metal 3) | 31.31 dB | 29.81 dB |

- Motion vector sign calibration: (+1, +1) is right (31.2 dB); other signs 22–25 dB. The first bug found: motion vectors divided by the size twice (by us and by AMD), so nearly zero, and FSR used optical flow only (24.3 dB).
- HDR + FSR and HDR + MetalFX: the hotbar stays at 1.0× SDR white, the world's highlights at 1.72×. Passes on Metal 4 and Metal 3.
- The full game test passes under Metal API validation (Metal 4), no errors.
- **Not measured on a real display yet:** MetalFX vs FSR FPS in the game. Game-test FPS varies with window state and temperature (example at 2560×1600: MetalFX 29 then 53, FSR 30 then 48 within one run). The reliable comparison stays the benchmark's GPU time: FSR 2–3× cheaper. Manual test item 28.

## R6 results: FSR frame gen optimisation (2026-10-28)

Measured in `tools/fsrbench`, Apple M3, game-test frames yaw 0° → 4°. Quality measured against the real frame at yaw 2°.

| Output (render) | Before R6 | After R6 |
|---|---|---|
| 1920×1080 (50 %) | 8.7 ms | **3.7 ms** |
| 1920×1080 (100 %) | 11.0 ms | **6.5 ms** |
| 2560×1600 (50 %) | 16.9 ms | **7.0 ms** |
| 3840×2486 (50 %) | 40 ms | **16.2 ms** |

A profile per dispatch size (`FFX_METAL_PROFILE=1` now logs the grid size) shows where the cost really is:
- The finest optical flow level, at full screen resolution, takes 1.4–2.0 ms.
- The main interpolation pass reads 16 color texels per pixel by hand.

The changes, all in `src/main/native/fsr/glsl`. AMD's files in `third_party` are untouched; `build-fsr-shaders.sh` takes a pass from there when the name matches.

1. **Optical flow at half resolution.**
   - The HUD-less world is downscaled 2× (2×2 average) by a small kernel in `fsr_framegen.mm`. The optical flow context's resolution and `opticalFlowScale` follow that size.
   - AMD does provide that parameter. Only the `optical_flow_vector_field` pass needs a bounds check, since AMD's host computes its dispatch from the screen size.
   - The ¼-size vector field also makes the passes that read it cheaper: the main interpolation pass drops from ~1.1 to ~0.55 ms.
   - Quality does not drop. In the bench (optical flow only) 25.63 dB vs 25.40 dB before. In the game, FSR 31.23 dB and FSR + temporal 29.61 dB, as before.
2. **Hardware bilinear in the main interpolation pass.**
   - AMD reads 4 texels and renormalises the weights inside the rect. For a rect the size of the texture, one bilinear sample with clamp-to-edge gives the same result, since the clamped taps repeat the edge texel.
   - The result is 61.6 dB against AMD's version (filter precision only), and 2.8 → 2.2 ms before step 1.
   - Side finding: frame interpolation's `s_LinearClamp` gets the pipeline's first sampler, which is the **point** sampler, in AMD's DirectX/Vulkan backends too (register s0). Our backend is faithful to that behaviour. Our linear sampler is the second binding.
3. **No distortion field.**
   - The game never provides a distortion field, so three passes sampled a 1×1 zero texture at every tap (8× per pixel in disocclusion).
   - The pass overrides do not bind it and return zero. The result is bit-identical, and 0.1–0.2 ms faster.

Tried without benefit:
- `FFX_HALF=1`: AMD's GLSL path uses no 16-bit types, so the MSL is identical.
- Computing the optical flow SAD from bytes extracted once, since Apple GPUs have no `msad4`. Bit-identical but no faster; Metal's compiler already handles AMD's version.

Not done: manual FP16 and concurrent dispatch. The bench uses flat depth and zero motion vectors, so disocclusion's heavy branches rarely run. Further optimisation needs a profile with game data, for example by capturing FSR's inputs from the game test.

In the game (covered game-test window, 2560×1600, FSR only), the cost logged by the R4 guard dropped from 22.5 to 15.3 ms. Numbers from a covered window are unstable (see R4), so the bench stays the reference. The full game test passes under validation on Metal 4, and interpolation and HDR on Metal 3 too.

## R10 research: FSR 3.1.6 from SDK v2.3.0 (2026-10-28)

Before porting, the FSR 3 code in v2.3.0 was compared with the v1.1.4 already in use. The comparison ignores comments and whitespace, and normalises the `FFX_API_*`/`FfxApi*` names renamed in v2.

| Part | Difference |
|---|---|
| GPU algorithm headers: frame interpolation (14 files), optical flow (10), the FSR 3 upscaler | **0 lines**, except the HLSL callbacks (bindings and root signatures) |
| Core GPU headers (`ffx_core*.h`, `ffx_common_types.h`) | Double `;;` on 3 lines and include paths |
| Upscaler RCAS | Include paths |
| Host frame interpolation, optical flow, upscaler | Restructured: resource descriptions moved into functions, parameter validation under debug flags, GPU memory queries. Also dropped: the frame interpolation Lanczos table our path does not use (`UPSAMPLE_USE_LANCZOS_TYPE=2`) |

So "3.1.6" (frame interpolation) and "3.1.5" (upscaler) are AMD's DLL/provider package versions; the computation is the same as v1.1.4. An upgrade would change neither quality nor speed.

The port cost is large:
- **The backend interface changed.** `fpCreatePipeline` takes compiled `FfxShaderBlob`s, the host code includes `*_permutations.h` headers (DXIL blobs from AMD's build tools, not in the repo), and there are `FfxApiResource` types, heaps and `fpQueryNextGpuJob`. `ffx_metal.mm` would need porting and the blob tables for three effects rebuilding.
- **FSR 1** has no host code in v2. Its v1.1.4 host would need porting to the new interface.
- **The R6 overrides** (`src/main/native/fsr/glsl`) would need rewriting in HLSL.

Proven in case the port is done anyway:
- `dxc` is not needed. `glslangValidator`'s HLSL front end (`-D -e CS --auto-map-bindings`) translates v2.3.0's main interpolation pass to SPIR-V, and `spirv-cross` turns it into MSL.
- Two names need tidying after translation, since AMD's host matches bindings by name: the constant buffer comes out anonymous (`_418` instead of `cbFI`), and the counters buffer as `r_counters_1`.
- Microsoft does not release `dxc` for macOS, and Homebrew does not provide it.

Advice: stay on v1.1.4 until AMD changes the algorithm. The comparison above can be repeated for the next release.
