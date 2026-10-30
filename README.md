# mine2metal

A Fabric mod that renders Minecraft Java with **Apple Metal** on Macs with Apple silicon, through Minecraft's own `renderpearl` graphics layer, and is built to run alongside Sodium. On top of the backend it adds MetalFX and AMD FSR upscaling, MetalFX and FSR 3.1 frame generation, HDR (EDR) output, and a built-in set of world shaders ("Natural Cinematic") written directly in Metal Shading Language.

![Minecraft 26.3 with Sodium, rendered with Metal](docs/screenshots/m6-world-sodium.png)

Status: **0.0.1, ready for public testing**. All milestones (M1–M14) and the follow-up roadmap (R1–R9) are done; the automated tests pass under Metal API validation on Metal 4 and Metal 3. Still experimental: it has been tested on one Mac so far (see [Test results](#test-results)), and mods that call OpenGL directly will not draw.

The detailed documentation under `docs/` is written in Indonesian; this README covers everything needed to use, build and contribute to the mod.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [In-game settings](#in-game-settings)
- [Test results](#test-results)
- [How it works and code layout](#how-it-works-and-code-layout)
- [Documentation](#documentation)
- [Building, testing and debugging](#building-testing-and-debugging)
- [References and credits](#references-and-credits)
- [License and etiquette](#license-and-etiquette)
- [Known limits](#known-limits)
- [Contributing](#contributing)

## Features

- **A Metal backend for `renderpearl`**: Minecraft 26.3's `GpuBackend`/`GpuDevice` SPI implemented on Metal, with the SPIR-V shaders translated to MSL. Metal 4 (argument tables, residency sets) on macOS 26 or later, Metal 3 on macOS 14 and 15. The picture matches OpenGL almost pixel for pixel (≤ 0.2 % of pixels differ by more than 8 levels in the automated tests).
- **Works with Sodium** 0.9.3 (its chunk renderer runs on this backend) and with the vanilla chunk renderer.
- **Upscaling**: MetalFX Spatial, MetalFX Temporal (with motion vectors and a reactive mask), AMD FSR 1 and AMD FSR 3.1. The hand and the HUD stay at full resolution.
- **Frame generation**: MetalFX frame interpolation (macOS 26+) or AMD FSR 3.1, paced to the display's refresh rate through `CAMetalDisplayLink`. A guard puts it on standby while the game reaches the refresh rate by itself, and switches it off when it costs more than it gives.
- **HDR (EDR)**: the world's highlights go brighter than SDR white on displays that support it; the GUI stays SDR.
- **Built-in world shaders** (presets Low / Medium / High / Ultra / Custom): a G-buffer filled by Sodium's terrain, two-level voxel DDA plus screen-space shadows, GTAO, volumetric clouds, an atmosphere, water with screen-space reflections, bloom, light shafts, ACES tone mapping. Details in [docs/metal-shaders.md](docs/metal-shaders.md).
- **A settings page** in Sodium's Video Settings (or a "Metal Settings..." button without Sodium), in English and Indonesian.
- **A one-click `.mrpack`** for the Modrinth App and Prism Launcher.

## Requirements

- A Mac with Apple silicon (M1 or later); macOS 14 or later (Metal 4 needs macOS 26).
- Minecraft Java 26.3, Fabric Loader ≥ 0.20.1, Java 25 (the launcher's own).
- Recommended: Sodium `mc26.3-0.9.3-beta.1`. Fabric API is not needed.

On Intel Macs, older macOS or other systems the mod does nothing: the game uses OpenGL as usual and the title screen says why.

## Installation

**One click (Modrinth App or Prism Launcher):** open `mine2metal-<version>.mrpack` (from the [releases](https://github.com/HoshizoraAkira/Mine2Metal/releases), or `./gradlew mrpack` into `build/distributions/`).
- Modrinth App: *Add instance* → *Import* → pick the `.mrpack`.
- Prism Launcher: *Add Instance* → *Import* → pick the `.mrpack`.

The pack installs Minecraft 26.3, Fabric Loader 0.20.1, Sodium 0.9.3 (downloaded by the launcher from Modrinth, not bundled) and mine2metal.

**Manual (official launcher or any other):**
1. Install [Fabric Loader](https://fabricmc.net/use/installer/) 0.20.1 or later for Minecraft 26.3.
2. Put `mine2metal-<version>.jar` in the `mods` folder (default: `~/Library/Application Support/minecraft/mods`).
3. Recommended: put [Sodium](https://modrinth.com/mod/sodium) for Minecraft 26.3 in the same folder.
4. Start the game. In Options → Video Settings → Graphics API, leave **Default** so Metal is used. Press F3: the right column shows `Metal 4 (mine2metal)` or `Metal 3 (mine2metal)`.

## In-game settings

- With Sodium: Options → Video Settings → "mine2metal" in the left sidebar → the **Metal** and **Metal Shaders** pages.
- Without Sodium: Options → Video Settings → the **Metal Settings...** button at the bottom.

| Setting | What it does |
|---|---|
| Metal version | Auto / Metal 4 / Metal 3 (takes effect after a restart) |
| Upscaling | Off / MetalFX Spatial / MetalFX Temporal / FSR 1 / FSR 3.1, with a render scale of 50–100 % |
| Frame generation | Off / MetalFX / FSR 3.1: one generated frame between each pair the game renders, up to the refresh rate |
| HDR | EDR output with an HDR brightness slider (125–400 % of SDR white) |
| Shader preset | Off / Low / Medium / High / Ultra / Custom; changing the preset moves the options under it (shadows, GI, AO, reflections & water, godrays, bloom, lighting scale) |

Changes apply at once and are saved to `config/mine2metal.properties`. To turn Metal off: Graphics API → OpenGL (then restart), `enabled=false` in the config file, or `-Dmine2metal.enabled=false`.

## Test results

Every number below was measured on **one machine**: a MacBook Air 15" M3 (Mac15,13, 16 GB, fanless, 60 Hz panel, desktop at 1920×1243 points), macOS 27.0.1, on AC power. Other chips (M1, M2, M4 and later) **have not been measured**; an estimate for the M1 is in [docs/roadmap.md](docs/roadmap.md#optimasi-shader-dunia-dan-jalur-cpu-2026-10-30). The MacBook Air heats up during benchmarks and GPU time per frame can double without any change in load, so the ranges are wide; the highest numbers were taken in the "nominal" thermal state. Methods and full tables are in [docs/roadmap.md](docs/roadmap.md).

**Backend only, no shaders** (game test `./gradlew runClientGameTest`, 2026-10-27; vsync off, uncapped frame rate, 10-second average FPS):

| Configuration | Light: 854×480, looking down from 40 blocks | Heavy: 1920×1080, render distance 16, horizon |
|---|---|---|
| Metal + Sodium | 2045 – 2772 FPS | 664 – 1528 FPS |
| OpenGL + Sodium | 321 – 488 FPS | 200 – 273 FPS |
| Metal without Sodium | 1794 FPS | 416 FPS |
| OpenGL without Sodium | 224 FPS | 244 FPS |

An honest note: the game test world is small and has almost no water. In the author's real survival worlds, Metal + Sodium in fullscreen at 1920×1243 runs around 150–200 FPS, bound by the CPU and Minecraft's own work (JFR profile: mine2metal's code is about 10 % of the render thread). The GPU takes only about 0.7–0.9 ms per frame at 1900×1200.

**Built-in world shaders** (game test `-Dmine2metal.test.shadersOnly=true`, 2026-10-31, 1600×900 window, render distance 12, Metal 4 with API validation on):

| Scene | FPS |
|---|---|
| From above, shaders off | 1720 |
| From above, preset High | 360 |
| From above, preset Ultra | 274 |
| On the ground, High, no upscaling | 289 |
| On the ground, High, MetalFX Temporal at 67 % | 241 |

GPU time per shader pass at 1920×1243 (lighting scale 75 %, synthetic scene in `tools/shaderbench`, median): preset High about 3.7 ms in total (water + reflections 1.35, lighting 0.78, resolve 0.50, water copies + skyline 0.27, bloom 0.23, clouds 0.19, post 0.16, light tiles 0.07), down from 5.1 ms before the optimisation of 2026-10-30 without changing the picture (compared per pixel). Ultra about 3.9 ms, Medium about 3.5 ms, Low (50 %) about 2.4 ms.

**Upscaling and frame generation** (GPU cost at 1080p–1200p): MetalFX Temporal about 3 ms, FSR 3.1 upscaler about 1.1 ms, MetalFX Spatial about 0.8 ms, FSR 1 about 0.5 ms. In plain Minecraft without shaders that is more than the whole frame, so upscaling only pays on a GPU-bound Mac (for example with the Ultra shaders or a large window). Frame generation holds the game at half the refresh rate and only helps when the game is below it; its guard was tested with a 40 FPS cap → 59–60 FPS on screen (FSR 3 and MetalFX alike). Temporal quality: 30–35 dB PSNR against the native render, about 1 dB lower right after the camera turns.

**Correctness**: the game tests pass under `MTL_DEBUG_LAYER=1` (Metal API validation) on Metal 4 and Metal 3, including the shaders, HDR, frame generation, the Nether and the End; Metal vs OpenGL screenshots differ in ≤ 0.21 % of pixels (> 8 levels). The manual checks still open are listed in [docs/uji-manual.md](docs/uji-manual.md).

## How it works and code layout

Minecraft 26.3 puts its rendering behind the `renderpearl` SPI (`GpuBackend`, `GpuDevice`, `CommandEncoder`, ...). This mod places a Metal implementation first in the list of backends to try, translates Mojang's SPIR-V shaders to MSL at run time, and issues every GPU command through an Objective-C dylib called with Java's FFM API (no JNI). Sodium uses the same SPI, so its chunk renderer runs on Metal too.

| Path | Contents |
|---|---|
| `src/main/java/dev/mine2metal/metal/MetalBackend.java` | `GpuBackend`: the SDL window with the Metal flag, device creation |
| `src/main/java/dev/mine2metal/metal/MetalDevice.java` | `GpuDeviceBackend`: device info, buffers, textures, samplers, pipelines |
| `src/main/java/dev/mine2metal/metal/MetalCommandEncoder.java` | Command recording, submission, fences, the texel buffer view cache |
| `src/main/java/dev/mine2metal/metal/MetalSurface.java` | `CAMetalLayer` and presentation |
| `src/main/java/dev/mine2metal/metal/ShaderTranslator.java` | SPIR-V → MSL with SPIRV-Cross (through `lwjgl-spvc`) |
| `src/main/java/dev/mine2metal/metal/MetalRenderPipeline.java` | MSL libraries, vertex/blend layouts, pipeline states per format |
| `src/main/java/dev/mine2metal/metal/MetalRenderPass.java` | Uniform binding, scissor, every draw variant |
| `src/main/java/dev/mine2metal/metal/MetalNative.java` | FFM bindings to `libmine2metal.dylib` |
| `src/main/java/dev/mine2metal/frame/` | The per-frame flow: upscaling, frame generation, HDR, world shaders (`WorldShader`, `SodiumTerrain`) |
| `src/main/java/dev/mine2metal/config/` | Settings, the Sodium page, the vanilla screen |
| `src/main/java/dev/mine2metal/mixin/` | Mixins: backend order, projection capture, G-buffer, the GLSL patch for Sodium |
| `src/main/native/` | The Objective-C library (C ABI `m2m_*` in `mine2metal.h`): device, rendering, MetalFX, HDR, FSR, `shaders.m` + `world_shaders.metal` |
| `src/main/native/fsr/glsl` | FSR passes rewritten for Apple GPUs, kept apart from AMD's files |
| `third_party/fidelityfx-1.1.4` | The parts of the AMD FidelityFX SDK that are used (MIT), copied by `scripts/vendor-fidelityfx.sh` |
| `tools/shaderbench` | Offline per-pass shader benchmark, no Minecraft window needed |
| `src/gametest/` | Fabric client game tests: screenshots, FPS, PSNR, validation, the `shadersOnly`, `hdrOnly`, `walkOnly` and `pacing` modes |

## Documentation

All in Indonesian, under `docs/`:

- [roadmap.md](docs/roadmap.md): milestones M1–M14 and R1–R9, automated test results, the performance audit, the shader optimisation, bug notes and data-driven decisions.
- [riset-awal.md](docs/riset-awal.md): research on the `renderpearl` SPI (verified with `javap` on `client.jar`), Sodium on top of renderpearl, Metal 3 vs Metal 4, prior art and the list of sources.
- [metal-shaders.md](docs/metal-shaders.md): the design of the built-in world shaders (G-buffer architecture, lighting model, memory layout, quality presets).
- [fsr-frame-gen.md](docs/fsr-frame-gen.md): porting AMD's FSR 3.1 frame generation and upscalers to Metal.
- [uji-manual.md](docs/uji-manual.md): the checklist of things that can only be judged by playing.
- [rilis.md](docs/rilis.md): how to build the release files and the Modrinth/CurseForge page text.

## Building, testing and debugging

Needs JDK 25 or later and the Xcode Command Line Tools (`xcrun clang`). To rebuild the FSR shaders from AMD's GLSL: `glslangValidator` and `spirv-cross` (both in Homebrew).

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew build mrpack
```

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew runClient
```

Automated tests (create a world, take screenshots into `build/run/clientGameTest/screenshots/`, log the FPS):

```bash
MTL_DEBUG_LAYER=1 JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew runClientGameTest
```

Add `-PnoSodium` to test the vanilla chunk renderer; `JAVA_TOOL_OPTIONS="-Dmine2metal.enabled=false"` for an OpenGL comparison; `-Dmine2metal.metal=3` to force Metal 3; `-Dmine2metal.test.shadersOnly=true`, `hdrOnly`, `walkOnly` or `pacing` for one part only.

Shader benchmark without Minecraft:

```bash
sh tools/shaderbench/build.sh build/shaderbench && build/shaderbench/shaderbench src/main/native/world_shaders.metal 1920 1243 75 3 60 dump.png
```

Debugging: `MTL_DEBUG_LAYER=1` (Metal API validation), `MTL_SHADER_VALIDATION=1` (checks inside the shaders, slow), `--renderDebugLabels` (encoder names for Xcode GPU capture). A command buffer that fails on the GPU is logged as `Metal: Command buffer failed ...`.

| JVM property | Effect |
|---|---|
| `-Dmine2metal.enabled=false` | Turns the Metal backend off without removing the mod |
| `-Dmine2metal.metal=3` | Forces Metal 3 |
| `-Dmine2metal.dumpFrame=/path/frame.png` | Saves the presented frame as a PNG |
| `-Dmine2metal.stats=true` | Logs every 5 s: FPS, GPU time per frame, CPU time waiting for the GPU, encoder counts, transfer volume |
| `-Dmine2metal.passTimes=true` | Like `stats`, plus GPU time per pass (`M2M_SHADER_TIMES=1` for the shader passes) |
| `-Dmine2metal.labels=true` | Names render encoders per pass, for Xcode GPU capture or Instruments |
| `-Dmine2metal.frameGenerationGuard=false` | Disables the frame generation guard (for experiments) |

With `runClient`, pass properties through `JAVA_TOOL_OPTIONS`.

## References and credits

Documentation and sources used during development:

- **Mojang**: Minecraft 26.3's `client.jar` (the `renderpearl` SPI inspected with `javap`; no Mojang code is included) and the version metadata from `piston-meta.mojang.com`.
- **Apple Developer Documentation**: [Understanding the Metal 4 core API](https://developer.apple.com/documentation/metal/understanding-the-metal-4-core-api), [MTL4CommandQueue](https://developer.apple.com/documentation/metal/mtl4commandqueue), [MTL4ArgumentTable](https://developer.apple.com/documentation/metal/mtl4argumenttable), [MTLResidencySet](https://developer.apple.com/documentation/metal/mtlresidencyset), [MTL4Compiler](https://developer.apple.com/documentation/metal/mtl4compiler), [CAMetalLayer](https://developer.apple.com/documentation/quartzcore/cametallayer), [MTLIndirectCommandBuffer](https://developer.apple.com/documentation/metal/mtlindirectcommandbuffer), and the MetalFX (spatial/temporal scaler, frame interpolation) and EDR documentation.
- **AMD FidelityFX SDK v1.1.4** ([GPUOpen-LibrariesAndSDKs/FidelityFX-SDK](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK), MIT): host code and GLSL shaders for FSR 1, the FSR 3.1 upscaler, optical flow and frame interpolation. The parts used are in `third_party/fidelityfx-1.1.4` with their `LICENSE.txt`, and the license ships inside the jar (`META-INF/licenses`). The passes rewritten for Apple GPUs are kept apart from AMD's files.
- **Sodium** ([CaffeineMC/sodium](https://github.com/CaffeineMC/sodium), Polyform Shield 1.0.0): its source was read to understand its `renderpearl` path, its config API and its render lists. Sodium is **not bundled**; players or launchers download it from Modrinth.
- **Fabric** (Loader, Loom, the Fabric API client game test framework; Apache 2.0) and **Mixin**.
- **LWJGL 3.4.3** (`lwjgl-sdl`, `lwjgl-spvc`): SDL for the window and **SPIRV-Cross** (Khronos, Apache 2.0) for SPIR-V → MSL. The FSR shaders are built with **glslang** and **spirv-cross** from Homebrew.
- **Prior art** read as a comparison before writing this: [noahdunnagan/mcopt](https://github.com/noahdunnagan/mcopt) (Apache 2.0; a Metal backend through FFM, without Sodium), [Kausik-Velaga/minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) (MIT), [GaelQuinta/minecraft-metal-shaders](https://github.com/GaelQuinta/minecraft-metal-shaders) (Apache 2.0). Thanks to their authors; the comparison notes are in [docs/riset-awal.md](docs/riset-awal.md#6-prior-art).
- The **visual direction** of the built-in shaders is inspired by the look of *Complementary Reimagined* (EminGT); no shader pack code or assets are used.
- The shader techniques are standard ones from the real-time graphics literature: GTAO, interleaved gradient noise, voxel DDA (Amanatides & Woo), screen-space reflections with a skyline table, dual Kawase bloom, the ACES tone mapping fit (Stephen Hill / Krzysztof Narkowicz), and FSR/MetalFX as above.

This project was developed with the help of Claude (Anthropic) as a programming assistant; the design decisions, the testing on real hardware and the responsibility for the code are the project owner's.

## License and etiquette

- mine2metal's code is licensed under **MIT** ([LICENSE](LICENSE)). The included AMD FidelityFX code stays under AMD's MIT license.
- Minecraft is the property of Mojang Studios/Microsoft. This project is not affiliated with, or endorsed by, Mojang, Microsoft, Apple, AMD, CaffeineMC or Fabric. No Minecraft assets or code are redistributed.
- The mod changes no game mechanics, touches no networking, and is client-side only.
- If you use parts of this project elsewhere, keep the MIT license notice and, for the FidelityFX parts, AMD's license.

## Known limits

- Apple silicon Macs with macOS ≥ 14 only; MetalFX frame interpolation and Metal 4 need macOS 26.
- Mods that call OpenGL directly (not through `renderpearl`) will not draw. Iris and GLSL shader packs are not supported yet; use the built-in shaders.
- Minecraft 26.3 on macOS renders at point size, not at full Retina pixel size (a fullscreen framebuffer is "1920×1243", not 3840×2486).
- Tested on the M3 only. Reports from other chips help a lot (see [Contributing](#contributing)).
- Sodium has only been tested with `mc26.3-0.9.3-beta.1`; the translucent-terrain check that skips the water stage reads Sodium internals and falls back to the safe path if they change.

## Contributing

Bug reports and pull requests are welcome at [HoshizoraAkira/Mine2Metal](https://github.com/HoshizoraAkira/Mine2Metal). For a bug report, include: your Mac model and macOS version, the Minecraft/Fabric/Sodium versions, the F3 line in the right column (`Metal 3/4 (mine2metal)`), `logs/latest.log`, and if possible a screenshot or a frame from `-Dmine2metal.dumpFrame`. FPS numbers are most useful together with `-Dmine2metal.stats=true` and the window size and render distance.

Before sending a PR: `./gradlew build` and `MTL_DEBUG_LAYER=1 ./gradlew runClientGameTest` must pass; shader changes should come with `tools/shaderbench` numbers and a per-pixel comparison as in [docs/roadmap.md](docs/roadmap.md).
