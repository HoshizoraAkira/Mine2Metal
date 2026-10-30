# mine2metal roadmap

*English version of [docs/roadmap.md](../roadmap.md).*

## Core backend

| Milestone | Contents | Checkable result |
|---|---|---|
| **M0 Skeleton** ✅ | `getBackendsToTry` mixin, `MetalBackend`, FFM dylib, Metal 3 check, fallback to OpenGL | Log: `Found Metal 3 device: Apple M3`, then `Using graphics backend OpenGL` (verified 2026-10-27, Sodium 0.9.3 loaded too) |
| **M1 Device & resources** ✅ | `GpuDeviceBackend`: `DeviceInfo`/limits/features, shared `MTLBuffer`, textures + views (`GpuFormat` mapping, D24S8→D32S8), `MTLSamplerState` | Verified 2026-10-27: `Using graphics backend Metal`, every texture atlas created |
| **M2 Surface** ✅ | `SDL_Metal_CreateView` → `CAMetalLayer`, PresentMode → `displaySyncEnabled`, present through a fullscreen pass into a BGRA8 drawable | Verified: frames presented at ~57 fps (vsync-locked) |
| **M3 Command encoder** ✅ (nearly) | 1 command buffer per submit, ≤ 3 frames in flight, transient memory (Mojang's `TransientBlockAllocator`), write/copy through blit, full clears, fences, readback with callbacks. Partial clears: a fullscreen triangle with a scissor to the region and depth from the vertex shader (used by `GuiItemAtlas` for one item icon slot). GPU timestamps: Apple GPUs sample only at encoder boundaries, so a timestamp splits the command buffer and its value is the `GPUEndTime` of the previous work (the same semantics as GL timestamp queries), in host-time nanoseconds; calibration offset `System.nanoTime()` − `CACurrentMediaTime()`. Timestamps inside a render pass remain unavailable. Not yet: expansion of 3-component RGB formats | Native smoke test: clear red → readback `255 0 0 255`; upload → readback matches. Game test: two timestamps around a clear available and ordered (0.5 ms); a 4×4 partial clear in a 16×16 texture matches per pixel; all under Metal API validation |
| **M4 Pipelines & shaders** ✅ | SPIR-V → MSL via SPIRV-Cross (MSL 3.0, vertex Y flip, uniform `i` → slot `i`, push constants slot 26, vertex buffers slot 27+), one library per shader, an `MTLRenderPipelineState` per depth/stencil format (made on first use), `MTLDepthStencilState` | 0 pipelines failed; 204 pipelines take 254 ms in total (SPIR-V → MSL) + 37 ms (MSL → library) on 4–8 threads, so an MSL disk cache is not needed |
| **M5 Render pass** ✅ (partly) | Uniform buffer, texture + sampler, texel buffer and push constant binding, scissor, draw / drawIndexed / multiDraw / indirect (batched natively: 1 FFM call per batch), non-indexed fans → indices, indexed fans → triangles (indices read on the CPU from shared memory; skipped when the GPU is still writing the index buffer, or for indirect draws), wireframe, cull, depth bias, presents paced per refresh when vsync is off. Not yet: interleaved multi-draw, gl_DrawID | The title screen ([screenshot](../screenshots/m5-title-screen.png)) and a singleplayer world show; the pixel difference against OpenGL is small (see below) |
| **M6 Sodium** ✅ | Sodium 0.9.3-beta.1 runs on Metal through its own `gl_multidraw` path (direct multi-draw with separate arrays, a good fit for Metal), so no `DrawBackend` mixin is needed. The Sodium features that check `backendName` for "OpenGL" are GL-only ones (no-error context, WGL) and are rightly off | The world with Sodium looks identical to GL ([world](../screenshots/m6-world-sodium.png), [from above](../screenshots/m6-world-above-sodium.png), [night](../screenshots/m6-world-night-sodium.png), [far](../screenshots/m7-world-far-sodium.png)) |
| **M7 Performance** ✅ | Clears deferred and folded into the next pass's load action; consecutive passes with the same attachments merged into one encoder; buffer writes/copies on the CPU when the GPU is not using the buffer (shared memory), or the buffer "renamed" onto spare memory when its whole contents change; measurement tools (`-Dmine2metal.stats`, `-Dmine2metal.passTimes`, encoder labels for Instruments). Deliberately not done: `MTLResidencySet` and memoryless depth (reasons below) | Per frame: render encoders 8.9 → 3.1, blit encoders 3.1 → 0; GPU 0.3–0.4 ms/frame in the light scenario |
| **M8 Robustness** ✅ (manual tests pending) | Metal API validation for `--vulkanValidation` or `MTL_DEBUG_LAYER=1`; per-encoder status on command buffers when debugging; GPU errors logged and in `getLastDebugMessages`, device loss → `GpuDeviceLossException` (crash report); encoder labels for `--renderDebugLabels`; `config/mine2metal.properties` + Metal only for Graphics API "Default"; the dylib extracted atomically into `.mine2metal/natives/<sha-256>/`; buffers rounded to 16 bytes | The game test passes under Metal API **and** shader validation; fullscreen (1920×1080) and resize (1000×600) correct; improved transparency identical to GL (0.00 %); [manual test list](uji-manual.md) |

## Automated test results (2026-10-27)

`./gradlew runClientGameTest` creates a world (seed `mine2metal`, normal preset, creative mode while flying), takes screenshots through Minecraft's own readback path, and measures the 10-second average FPS. Machine: MacBook Air 15" M3 (Mac15,13), macOS 27.0.1, on AC power. Vsync off, unlimited frame rate, AFK limiter off.

- **Light**: 854×480 window (Retina 1708×960), the game test's default render distance, looking down from 40 blocks above spawn.
- **Heavy**: 1920×1080 window, render distance 16, looking at the horizon after 45 seconds of world generation.

| Configuration | Light (FPS) | Heavy (FPS) |
|---|---|---|
| Metal + Sodium, after M7 complete | 2045 – 2772 | 664 – 1528 |
| Metal + Sodium, after folded clears + merged passes | 1625 / 1675 | 1178 / 1453 |
| Metal + Sodium, before M7 | 1112 – 1378 | 80 – 150 |
| OpenGL + Sodium | 321 – 488 | 200 – 273 |
| Metal without Sodium (after folded clears + merged passes) | 1794 | 416 |
| OpenGL without Sodium | 224 | 244 |

The wide range on the first row comes from temperature: after about an hour of benchmarks the (fanless) MacBook Air enters the "fair" thermal state, and GPU time per frame creeps from 0.78 to 2.5 ms with no change in load. The highest numbers were taken in the "nominal" thermal state.

Before the M7 optimisation, the heavy scenario on Metal was slower than GL. Metal System Trace showed the GPU mostly in the "Minimum" performance state: fragmented GPU work (about 5 separate clear passes, 9 render encoders and 3 blit encoders per frame) kept the governor from raising the clock.

Data-driven decisions:
- **No MSL disk cache**: 204 pipelines take only ~290 ms in total, spread over several threads.
- **`MTLResidencySet` postponed**: residency sets reduce CPU/driver overhead, while after M7 the render thread spends 55–70 % of the frame waiting for the GPU. FPS would not rise from there.
- **No memoryless depth / `DontCare` store**: renderpearl gives no attachment lifetime information, and the main depth is reloaded by the next pass. An experiment forcing depth `DontCare` showed no gain above noise (probably because Apple GPUs already compress depth losslessly), while a wrong guess corrupts the image without an error.

| Metal vs OpenGL screenshot | Pixels differing > 8 levels | > 48 levels |
|---|---|---|
| World, from above (Sodium) | 0.21 % | 0.00 % |
| World, night (Sodium) | 0.00 % | 0.00 % |
| World, far, RD 16 (Sodium) | 0.04 % | 0.01 % |
| World, from above (no Sodium) | 0.17 % | 0.00 % |
| World, far, RD 16 (no Sodium) | 0.06 % | 0.02 % |

Title screen and spawn screenshots can differ by 0.5–2.4 % because of timing: the title screen is fading in and Metal loads faster, while at spawn grass and clouds move. The difference is invisible to the eye.

Measurement note: FPS counts rendered frames. With vsync off, the Metal backend presents only one frame per display refresh (the other frames are still fully rendered but not sent to the compositor), because presenting all of them makes `nextDrawable` block and FPS fall to ~60. What is seen on screen is the same: a display cannot show more than its refresh rate.

## Next: easy installation and the MetalFX GUI (M9–M12)

| Milestone | Contents |
|---|---|
| **M9 Installation** | Ready for Modrinth/CurseForge (metadata, icon, description, dependencies: Fabric Loader required, Sodium recommended, Fabric API not needed), a one-click `.mrpack` for the Modrinth App and Prism Launcher, a clear in-game message when Metal cannot be used (Intel Macs, old macOS) with automatic fallback to OpenGL |
| **M10 Settings screen** ✅ | A "mine2metal → Metal" page in Sodium's Video Settings through `ConfigEntryPoint` (`sodium:config_api_user`), and a "Metal Settings..." button + screen in vanilla's Video Settings when Sodium is absent. Options: Upscaling (Off / Spatial / Temporal, only the modes this Mac supports), Render scale 50–100 % (step 5), Frame interpolation (shown but disabled, "coming"). English and Indonesian. Saved in `config/mine2metal.properties` and applied without a restart |
| **M11 Upscaling** ✅ | The world is rendered into a smaller target (the `GameRenderer.mainRenderTarget` texture is lent out during `renderLevel`), then enlarged with MetalFX at the start of `render3dHud`, so the hand, screen effects, post effects and the GUI draw at full resolution. **Spatial**: `MTLFXSpatialScaler` (perceptual mode). **Temporal**: `MTLFXTemporalScaler`; the world projection is shifted sub-pixel every frame (Halton 2,3; phase count 8 × ratio², at least 8) through `CameraRenderState.projectionMatrix`, so Sodium shifts too; the final projection (with bobbing and nausea) is captured in `ProjectionMatrixBuffer.getBuffer` and its jitter removed again. Motion vectors are computed in their own pass from depth: each pixel is unprojected with this frame's matrices and projected with the previous frame's, plus the camera displacement (camera-relative positions, double precision on the CPU). The same pass copies depth to Depth32Float, since the game's depth has stencil. History resets on the first frame, on a size change, or when the camera moves > 16 blocks. Temporal 100 % = anti-aliasing only |
| **M12 Frame interpolation** ✅ | `MTLFXFrameInterpolator` (macOS 26+). Encodes into an ordinary `MTLCommandBuffer`, so Metal 4 is **not** needed. Needs this and the previous frame's color, depth, motion, a separate UI texture, camera matrices, and presenting two frames per rendered frame. Adds input latency, so it is optional |

### Fix during M10–M11: a separate present queue

The occasional performance collapse (~100–150 FPS) was not GPU work. Metal System Trace in the slow mode showed the GPU ~84 % idle and CPU→GPU latency p50 12 ms, maximum 16.4 ms (one 60 Hz refresh period), against 0.8 ms when fast. The frame's command buffer holding the present pass to the drawable has to wait on the GPU until the compositor releases that drawable, and since the queue is serial, every following frame waits too.

Fix: the frame is copied into one of two staging textures on the render queue, then drawn into the drawable on a **separate present queue**, synchronised with two `MTLEvent`s. With vsync off, a new frame is presented only after the previous one showed on screen (`addPresentedHandler`), so `nextDrawable` never blocks the CPU (later replaced by the present thread, see below). After that 3 of 3 runs were fast: light scenario 1607–2513 FPS, heavy 935–1113 FPS, present time 0.001 ms/frame.

Spatial upscaling in the small game-test window (854×480, Retina 1708×960) is actually slower (1075–1163 FPS vs 1607–2513 without): at this resolution the GPU is not pixel-bound, so the MetalFX pass only adds work. Upscaling helps when the GPU is busy with pixels (high resolution, heavy shaders); not measured in such a scenario yet.

### Temporal upscaling results (2026-10-28)

The game test compares screenshots with the native-resolution one from the same camera position and direction (PSNR, higher is closer; 854×480 window):

| Mode | Still | Right after a 30° turn in 10 ticks |
|---|---|---|
| Temporal 50 % | 30.3–30.5 dB | 29.1–29.2 dB |
| Temporal 75 % | 31.8 dB | — |
| Temporal 100 % (AA only) | 34.6–34.8 dB | — |

- **The jitter sign** for MetalFX was chosen by measuring all four combinations: (+1, +1) best in two runs, (−1, −1) worst (~0.8 dB lower). Changeable for experiments with `-Dmine2metal.temporal.jitterSign=x,y`.
- **Motion vectors are right**: after the camera turns, quality drops only ~1 dB. For comparison, the native images before and after that turn differ by 18 dB, so an unshifted history would be obvious.
- In the small game-test window, temporal is slower than native rendering (MetalFX + the motion vector pass cost more than the pixels saved), as with spatial. The benefit only shows at large resolutions; not measured in a 4K window yet.
- Not handled yet: entities, particles and animated textures move on their own, but the motion vectors only know the camera's motion, so they can leave faint trails. Can be reduced later with a reactive mask.

### Fix after manual testing: a present thread for vsync off

In a manual test in a nearly 4K window with vsync off, the game felt choppy despite the high FPS. `presentedTime` statistics showed only ~25–40 frames per second actually reaching the screen, with a p95 gap of 33.3 ms and nearly half the frames a refresh late. With the GPU busy (~150 FPS), frames presented from the render thread often finished just after the refresh deadline, so they showed alternately 16.7 and 33.3 ms apart. With vsync on everything was smooth (16.7 ms gaps, 0 late).

Fix ("fast sync"): with vsync off the layer stays `displaySyncEnabled`, but the render thread no longer presents. Each frame is copied into one of three slots, then marked as the latest once the GPU finishes. A separate present thread (`mine2metal present`) waits on `nextDrawable`, which ticks with the display's refresh, and always shows the latest finished frame. The game keeps rendering without limit and never waits for the display, and the display gets one new frame per refresh without tearing. The thread stops when the surface is reconfigured or closed. In the game test (visible window, vsync off, rendering ~1400 FPS) the result is 300 frames shown per 5 seconds, p50/p95 gap 16.7 ms, 0 late. Manual test on the MacBook Air M3 (1920×1243 Retina window, Sodium, render distance 16, vsync off): 153–203 FPS, 300 frames shown per 5 seconds, p50/p95 gap 16.7 ms, 0 late (before: ~200 shown, p95 33.3 ms, ~90 late). With vsync on the path is as before.

### One present path (2026-10-28)

Presentation used to have three paths: VSync on presented from the render thread (two staging textures, `frameReady`/`presentDone` events, the present queue), VSync off through the present thread, and interpolation through a present thread started specially. Two interpolation bugs came from the interaction of these paths. Now **every frame goes through the present thread**:
- The present thread runs as long as the layer is attached, and shows the latest finished frame once per refresh (the generated frame first when there is one).
- The only difference is how far ahead the render thread may run. VSync off: never waits, frames are skipped when their slot is not free (the game unlimited, the display still getting a new frame per refresh without tearing). VSync on or interpolation: waits until the present thread has taken the previous frame, so every frame is shown and the game follows the refresh rate (or half of it with interpolation). A 250 ms wait limit so a stuck display (hidden window) does not hold the game.
- Removed: `m2m_present_synced`, the staging textures, the `frameReady`/`presentDone` events, and the interpolation-only thread flag. The render thread never holds a drawable.

Result: the full game test passes under validation (Metal 4 and Metal 3), including fullscreen, resize, upscaling, interpolation (31.2 dB) and HDR. VSync off stays unlimited (~2700 FPS, present 0.005 ms per frame). VSync-on pacing on a real display is unmeasured because the test window is not visible (macOS reports every frame as not shown), so it needs a manual check: manual test item 9.

### R2: presenting through `CAMetalDisplayLink` (2026-10-28)

The present thread no longer calls `nextDrawable` in a loop. It runs a run loop with a `CAMetalDisplayLink` (macOS 14+, `preferredFrameLatency` 1), which calls `M2MPresenter` once per refresh with a drawable ready:
- **Without frame gen:** the latest finished frame is shown, if there is a new one.
- **With frame gen:** the generated frame is shown first and held for about half the native frame interval (in refreshes, rounded, 1–4), then the real frame. Example: a game at 15 FPS on 60 Hz now shows 2 + 2 refreshes, before 1 + 3. The interval is measured from the frame's GPU completion time, which does not stretch from waiting on the display, so there is no feedback loop like the first pacing in M12.
- **Covered window:** macOS stops the display link for a fully covered window. With no callback for 100 ms, the present thread enters idle mode. New frames are treated as taken at once (not shown), and the render thread only waits for the GPU copy, so the frame-gen history is not broken. Without this, a game with VSync on or frame gen fell to ~4 FPS while the window was covered (seen in the game test).

Game test results in a visible window (MacBook Air M3, 60 Hz):

| Mode | Frames shown / 5 s | Gap p50 / p95 | Late | CPU per frame |
|---|---|---|---|---|
| VSync off | 299–300 | 16.7 / 16.7 ms | 0–1 | 0.4 ms (unlimited) |
| VSync on | 301 | 16.7 / 16.7 ms | 0 | 16.66 ms |
| Frame gen MetalFX | 300–302 | 16.7 / 16.7 ms | 0 | 33.3 ms (30 real + 30 generated) |

Generated-frame quality is unchanged (FSR 31.21 dB, MetalFX 31.20 dB), and the full game test passes under validation. Not measured: a game below 30 FPS with frame gen (holds > 1 refresh) and ProMotion displays.

### Performance audit (2026-10-29)

Measured on the MacBook Air M3 with Sodium and render distance 16, walking towards the trees in the game-test world, visible window. The game-test mode `-Dmine2metal.test.interpolationPerf=true` now has `perfFullscreen`, a `hud` case, `-Dmine2metal.test.fpsCap`, and logs the framebuffer size and the F3 frame-gen line.

**The real size.** Minecraft 26.3 on macOS renders at *point* size, not Retina pixels: a 1900×1200 window's framebuffer is 1900×1200, although the display's pixel density is 2.0. The player's fullscreen on a "1920×1243" desktop means a 1920×1243 framebuffer (an earlier note saying 3840×2486 was wrong).

**Where the time goes** (1900×1200):
- GPU about 0.7–0.9 ms per frame, mostly not the world: GUI 0.2, screen effects 0.2, sky 0.14, hand 0.13, world (Main) 0.12 ms.
- CPU about 0.5–1 ms per frame. JFR profile of the render thread: mine2metal's code about 10 % (`MetalRenderPass.prepareDraw`, `bindUniform`, `setPipeline`, `createRenderPass`, clears). The rest is Minecraft and Sodium themselves, for example `StagedVertexBuffer.uploadDrawsToBuffers` 12 % self.
- Result: about 750–1,800 FPS, depending on heat. Within one run FPS fell by half as the (fanless) MacBook Air warmed up. The limit is the CPU and Minecraft's own work. 3,000 FPS is not reachable at this size, and the backend cannot improve it by more than about 10 %.
- The Metal Performance HUD makes no measurable difference (785 vs 743–817 FPS).

**Upscaling does not pay in plain Minecraft.** MetalFX temporal takes about 3 ms of GPU at 1080p–1200p at 100 %, and about 2.8–4 ms at 75 %. That is several times the whole Minecraft frame. FPS drops from about 800–1,000 to 150–200. The numbers are the same on Metal 4, without the reactive mask, and on Metal 3, so this cost belongs to MetalFX, not to our path. The tooltip and the config comments now say so. Temporal 100 % remains the best anti-aliasing.

**Fixes:**
- **Frames in flight counted per frame, not per commit.** `MAX_FRAMES_IN_FLIGHT` = 3 used to count command buffers. Frame gen adds 2 commits per frame, and per-stage statistics add 4–5. So the CPU waited for the frame it had just recorded, and CPU and GPU worked in turns. Now the last commit of each frame is recorded, and what is waited on is the 3rd previous frame. "CPU waiting on GPU" with statistics on fell from 0.66 to about 0.1 ms per frame.
- **GPU time from command buffers with no work.** `GPUStartTime` can be 0, so one sample read as the Mac's whole uptime (the log recorded 2,061 ms per frame). Such ranges now count as 0. The frame-gen guard reads the same number.
- **Frame gen stands by while the game already reaches the refresh rate.** Frame gen holds the game at half the refresh, so it is only useful when the game itself is below the refresh rate. Now frame gen *stands by* (frames shown as they are, unlimited) until the working time per frame (the gap between frames minus the present wait) exceeds 1.1× the refresh interval for 1 second. Once engaged, every 30 seconds it steps back briefly to measure again. F3 writes `standing by: the game reaches the refresh rate by itself`. Off together with `-Dmine2metal.frameGenerationGuard=false`.
  - Fast game (about 1,800 FPS without frame gen): before, FSR 3 = 30 rendered, 60 shown. Now 1,494 rendered, 60 shown, standing by.
  - Slow game (40 FPS cap): frame gen engages by itself, 30 rendered + 30 generated = 59–60 shown (up from 40), for FSR 3 and MetalFX alike.
- The full game test passes under API validation, Metal 4 and Metal 3.

### World shader and CPU path optimisation (2026-10-30)

Goal: any shader preset stays smooth from the MacBook Air M1 to the newest at the Retina framebuffer size (1920×1243 points), without lowering quality. Since the game test opens a Minecraft window, measurement uses a new tool, `tools/shaderbench` (`tools/shaderbench/build.sh <dir>`, then `shaderbench src/main/native/world_shaders.metal 1920 1243 75 3 60 [dump.png]`): a synthetic world (hills, forest, a lake, the camera 4 blocks above the ground facing the horizon) is drawn once into depth + G-buffer + voxel grid, then every shader pass runs exactly as `shaders.m` binds it, each in its own command buffer that is waited on (command buffers committed together report one shared `GPUStartTime`, so their numbers are wrong). Its `.raw` dumps are compared per pixel between versions, so "identical" below is measured, not estimated.

**Results on an Apple M3, 1920×1243, lighting scale 75 % (median GPU ms per frame, shader passes only):**

| Pass | Before (High) | After (High) | Notes |
|---|---|---|---|
| Clouds (½ lighting) | 0.57 | 0.19 | noise from a 3D texture |
| Lighting (1390×900) | 0.90 | 0.78 | two-level voxel DDA |
| Light tiles (new) | — | 0.12 | a compute kernel per 32×32 tile |
| Resolve (full) | 0.94 | 0.54 | the point-light loop only at pixels with block light, and only the lights in their tile |
| Water copies + skyline | 0.18 | 0.27 | the skyline table (2 kernels, 0.09) |
| Water | 2.02 | 1.40 | SSR stops once it can only read sky; linear depth from clip w |
| Bloom / shafts / post / sky LUT | 0.23 / 0.09 / 0.18 / — | 0.23 / 0.09 / 0.17 / 0.02 | |
| **Total** | **5.11** | **3.81** | −25 % |

Other presets: Ultra 5.47 → 3.98 ms (−27 %), Medium 4.56 → 3.58 (−21 %), Low 50 % 2.90 → 2.48 (−14 %). In this synthetic world water covers about 35 % of the screen; in scenes without water the resolve and clouds matter most, and in a torch-filled cave the light-loop saving is far larger still (before: 24 lights × every pixel).

**What changed in the shader (`world_shaders.metal`), all with the same result:**
- **A skyline for the water SSR.** Two compute kernels build a table: the highest non-sky row per 16-column band (a SIMD reduction per 16×64 threadgroup + atomic max), then a sparse table (row k = the maximum over 2^k bands) so a range-max over any band range is 2 reads. A ray's screen path is a line, so the entry and exit columns are computed once per ray, and `sky_from` gives the distance `t` from which every sample is surely sky; the ray stops there, with the same "no hit" result. Per pixel ≤ 1 level different at 0.004 % of pixels (linear depth rounding = `clip.w`, not `m32/(z+m22)`).
- **Light tiles.** A compute kernel per 32×32 tile: the tile's min/max linear depth, then each lane tests one light's sphere against the tile's 4 frustum planes and depth range; the result is a bitmask per tile. The resolve iterates only the set bits, and only when `blockLight > 0` (without block light the light tint is unused). Compared with the full loop: 0 pixels differ.
- **Two-level voxel DDA.** The `m2m_voxel_coarse` kernel builds a 32³ grid (one byte per 4×4×4 block, rebuilt only when the GPU voxel copy updates). While the ray is in an empty coarse block, it jumps to that block's exit point and continues the fine DDA from the voxel it enters. 8 pixels out of 2.4 million differ by ≤ 2 levels (an epsilon at block corners).
- **Noise from a texture.** `noise3` (value noise with smoothstep interpolation, formerly 8 hashes + 7 mixes per sample, called 3–4× per cloud step) is now one trilinear `sample` from a 128³ R16Unorm 3D texture filled once by the CPU: a hash periodic over 32 units, 4 texels per unit. The random pattern of clouds, waves and puddles changes (the hash is periodic), the character is the same.
- **A sky LUT.** `atmosphere()` is computed into a 512×256 RGBA16F lat-long texture once per frame (0.02 ms) and sampled in the resolve, water and clouds; before it was evaluated 2–3× per pixel. ≤ 2 levels different at 2 % of pixels.
- **Marching in clip space.** SSR and the AO samples project once, then each step is a multiply-add (clip is linear in position).

**Measured and not helpful (not used):** reading 4 samples at a time in the DDA/SSR/screen shadows (the cost is not latency), quality as compile-time constants (−2–4 %), the voxel grid as a 3D texture (slower, 0.89 → 0.93), reading depth from a buffer (same), 16 px tiles (same), sRGB conversion through texture views (its ceiling was only 0.09 ms). The water SSR's per-step cost turned out to be ALU + SIMD divergence (lanes reflecting the sky run the full march), which is why the skyline helps.

**The CPU path:**
- **A texel buffer view cache.** Sodium's terrain shader uses an `isamplerBuffer` (`u_SectionTimeInfo`), and the backend created a new `MTLTexture` object per bound region every frame, then released it. Views are now cached per (buffer memory, offset, length, format), at most 512 entries, and dropped when the buffer is renamed or closed (important for Metal 4 residency).
- **Voxel copies per slice.** The voxel grid scan now goes per grid row (97 columns with the same world z per frame, instead of 96 scattered columns), and Java reports the changed z slices (`m2m_shader_voxels_changed`); each GPU copy copies only its own pending slices (16 KB per slice) instead of 2 MB whenever the version changes.

**Tests:** the shader game test (`-Dmine2metal.test.shadersOnly=true`) passes under Metal API validation, Metal 4 and Metal 3 (`-Dmine2metal.metal=3`), with the terrain still lit from the G-buffer. Its FPS (from above, High 318–380, Ultra 211–274; on the ground High 255–294) sits within the thermal noise of earlier runs, since the game-test world is small and nearly waterless; the per-pass numbers above are the measure. Found by Metal 3 validation along the way: `drawIndexedPrimitives` with `indexCount` 0 when rain starts (vanilla's weather renderer draws before there are drops; GL ignores it, Metal 4 tolerates it). `render.m` now skips draws with zero vertices, indices or instances.

**Second round (same day), measured the same way:**
- **Light tiles kernel 0.12 → 0.07 ms:** 16×16 threadgroups each reading a 2×2 block, and the bitmask through `simd_ballot` instead of threadgroup atomics. 0 pixels differ.
- **The water stage skipped without translucent terrain on screen** (`SodiumTerrain`): before the scene copy, Sodium's render lists are checked the way `DefaultChunkRenderer` checks them (the TRANSLUCENT pass storage per region, then each visible section's slice mask). Without translucent sections, the color+depth copies, the skyline and the water pass do not run (about 0.3 ms per frame in caves, deserts, indoors). Without Sodium, or if Sodium's internals change, the answer is always "maybe" and the path is as before.
- **Hardware sRGB conversion:** the resolve, water and post draw into sRGB views of the game's color texture and read the scene copy through an sRGB view, so the `pow` for decode/encode is gone. The choice is a Metal function constant (`linearIo`, a pipeline variant per format): with an ordinary uniform flag the compiler still computes both `select` branches and the gain is zero. Formats with no sRGB variant use the pipeline variant with explicit conversions. Resolve 0.54 → 0.50, post 0.18 → 0.16, water 1.41 → 1.35 ms. Water refraction now filters in linear space, so 0.02 % of pixels at edges differ by > 8 levels (more correct, not identical).
- **Tried and dropped because measured slower:** a skyline for the far screen-space shadows (lighting 0.79 → 0.82 ms plus 0.10 ms to build the table in the lighting stage), and **Hi-Z SSR** (a 5-level min/max linear depth pyramid, screen-space traversal with linear 1/w, skipping while the ray is in front of or behind a whole cell). The average hit needs 11.5 iterations, but 12 % of rays exhaust 48 iterations and SIMD groups wait for the slowest lane: water pass 2.1–2.9 ms vs 1.42 ms for the linear march + skyline, with starting-level and coarsening variants not helping. The code was not kept.

**Sky and clouds wobbling while walking (2026-10-31):** reported from a real install and reproduced with a new game-test mode, `-Dmine2metal.test.walkOnly=true` (walking and turning on the ground with a screenshot every 2 ticks; still screenshots never showed it): the clouds appeared as hard-edged rectangles piling up at every turn. The cause was not the cloud history itself but the per-pixel view direction: `normalize(unproject(uv, near))` used a near-plane point only 0.05 blocks from the camera, while Minecraft's view bobbing puts a translation of a few hundredths of a block into the projection matrix, so the direction swung by tens of degrees with every step (Java-side log: the same pixel went from the horizon to nearly the zenith between frames). Fresh clouds were drawn in the wrong direction, and the history reprojected with the previous matrices never matched. `view_direction()` now takes the difference of two points on the same ray, free of the translation; used by the cloud pass and the light shafts (the resolve and the water already take positions from depth). Observed along the way and ruled out: Metal 4 barriers (Metal 3 was the same), threadgroup sizes, the sRGB tables, and the water-stage skip.

**Per-chip estimates (not measurements; the M1 GPU is about 55–60 % of the M3's throughput in loads like this):** High at Retina about 7 ms of shaders + about 1.5 ms of Minecraft on the M1 (before: about 9.5 + 1.5), so GPU-bound around 115 FPS, up from about 90. MetalFX temporal stays about 3 ms (Apple's); FSR 3.1 at 1.1 ms is the lighter choice on a GPU-bound Mac.

### Frame gen at 2 FPS in a real install (2026-10-28)

Temporal + FSR 3 frame gen in fullscreen Retina once fell to about 4 FPS. The log recorded frame gen at 156 ms of GPU per frame and 250 ms between frames. With the same Mac and settings, the 19:09 session had only 24 ms. A benchmark with the same build right afterwards (1440×900 window, temporal 75 % + FSR) recorded 5–7 ms. So the code did not get slower. Most likely the Mac's state at the time: hot after several heavy game tests in a row, and 3.8 GB of swap in use. That cannot be confirmed from the log.

What was fixed is the guard. Before, a far-too-expensive frame gen still ran for 3 seconds before pausing, then fell back to a few FPS every 30 seconds on retry.
- If the cost exceeds the whole gap between frames (more than half the FPS lost), the pause comes after 0.5 seconds, not 3.
- If the retry after a pause is still too expensive, frame gen is switched off until chosen again. Its textures are released, a toast "Frame generation turned off" appears, and the F3 line writes `off: too heavy here`. The pause count restarts from zero after frame gen has been light for 10 seconds, or when the engine choice changes.
- Test: in a test window rendering unlimited, FSR was paused, retried 30 seconds later, then switched off. Off then FSR 3 again starts the count over.

### HDR flicker on a real display (2026-10-28)

A test in a real install (MacBook Air M3, auto-brightness on, HDR + temporal + frame gen) reported bright parts alternating bright and dim, and flickering pixels/blocks. Fixes:
- **The peak is computed once per frame and moves smoothly.** Before, the display's headroom was re-read every 30 calls, then used at once. With auto-brightness the headroom keeps changing, so highlights jumped. Also, the "expand" pass (frame-gen history) and "compose" within one frame could use different peaks. So the non-GUI parts were no longer exactly equal, although FSR uses that equality to separate the UI. Now the headroom is read every 0.1 s when the world is captured, and the frame's peak approaches it with a 0.5 s time constant. Expand, compose and FSR's `max_luminance` use the same number. The EDR capture happens before the frame-gen capture.
- **Gain per 4×4 block, not per pixel.** The expansion curve is steep near white. With a per-pixel gain, small vibrations on bright surfaces (texture shimmer, temporal jitter, generated frames) became about 2.7× larger than in SDR. Now the "levels" pass computes the average brightness per 4×4 block of the captured world. The gain is taken from there with a bilinear filter, so a block scales together and the brightness ratios between pixels stay as in SDR.
- The game test `-Dmine2metal.test.hdrFlicker=true` reads back 12 consecutive EDR frames with a still camera. In the test scene there is no HDR-specific flicker, before or after the fix, and the frame-to-frame change numbers are equal. The test window is covered, its headroom is 1.0, and the expanded part is mostly sky, so this test cannot show the symptom of a real display. That needs checking on a display.
- The HDR checks (world 1.72× SDR white, hotbar 1.0, also with temporal + MetalFX/FSR) still pass, clean under API and shader validation.

### FPS shown in F3 (2026-10-28)

The first test in a real install (MacBook Air M3, fullscreen Retina, Temporal 75 % + FSR 3 frame gen) was reported as "20 FPS". That number is the render FPS from F3. With frame gen the game is held at half the refresh, so about 40 show on screen.
- The present thread counts drawables that really reach the screen (`presentedTime` > 0), generated frames included (`m2m_shown_frame_count`).
- While frame gen is chosen, the F3 FPS line (`DebugEntryFps`) is followed by `N fps on screen, <engine> frame generation`, counted per second like Minecraft's FPS. If the guard is pausing frame gen, the line says so.

### R9: installation (2026-10-28)

- **MIT license** (`LICENSE`). The license ships in the jar (`META-INF/licenses`) with AMD's.
- **`fabric.mod.json`:** new author, license, icon and description. Depends on Fabric Loader, Minecraft 26.3 and Java 25; Sodium recommended; Fabric API not needed.
- **Icon:** 256×256, an isometric metal block.
- **`./gradlew mrpack`** builds the modpack for the Modrinth App and Prism Launcher.
  - Contents: Minecraft 26.3, Fabric Loader 0.20.1, Sodium from Modrinth's CDN, and this mod in `overrides/mods`.
  - Sodium's hash and size are computed from the artifact the build uses; the values match Modrinth's API data.
- **Unsupported Macs:** Intel, x86 Java under Rosetta, macOS < 14, devices without Metal 3, or a library that fails to load.
  - Minecraft already tries the next backend (OpenGL) automatically, but tells no one. The reason is now logged and shown once as a toast on the title screen.
  - The tooltip in the Metal menu names the reason too.
  - Players who deliberately chose OpenGL/Vulkan or turned Metal off get no message.
- **Tests:**
  - `JAVA_TOOL_OPTIONS="-Dos.version=13.6 -Dmine2metal.test.fallbackOnly=true" ./gradlew runClientGameTest`: the game uses OpenGL and the "Not rendering with Metal" toast appears with the reason.
  - `./gradlew runProductionClient` runs the release jar and Sodium in Loom's production client, without the dev classpath.
- **State on 2026-10-31:** the public repository and GitHub Release v0.0.1 exist (the repository links are in the metadata); the Modrinth page is being created by the owner, CurseForge not yet. macOS 14–25 untested on real machines; the Metal 4 and MetalFX paths are only guarded by `@available` and weak linking.

### R8: the world-frame architecture (2026-10-28)

The classes around the world pass moved from `upscale` into the `dev.mine2metal.frame` package:
- **`WorldPass`** is the only door from `GameRendererMixin`, in the order `begin` → `captureProjection` → `upscale` → `capture` → `end`.
- **`WorldFrame`** is created fresh every frame and holds everything the pass uses: the main target, the camera, the upscaling mode, the lent small target, the jitter and its sign, the unjittered projection, the world depth, and the capture state. The upscaler fills it; frame gen and EDR read it.
- **`WorldUpscaler`, `FrameGeneration` (formerly `FrameInterpolation`) and `HdrOutput`** are now instances that only keep cross-frame state: camera history, jitter phase, the small target, and the frame-gen guard.
- Before, `FrameInterpolation` read the projection, depth and jitter through static `WorldUpscaler`, and the three classes each had an "already captured" flag that had to be reset together.
- The only shift: the jitter sign (a test property) is read once at the start of the frame, not at upscaling time.

The full game test passes under validation, with the same numbers as before within noise:
- temporal 30.52 dB, FSR 3.1 30.79 dB, FSR 1 29.82 dB;
- frame gen 31.20 / 31.24 dB;
- particles with the reactive mask still brighter than without.

**Metal 4 barriers not narrowed.** Every encoder still has a full barrier at its start and end. The end barrier is in fact redundant with the next encoder's start barrier, except before a MetalFX encoder that has no barrier of ours. An A/B/A/B test without end barriers (1920×1080, render distance 16, walking to the forest) showed GPU time of only 0.5–0.7 ms per frame in both variants. That scene is CPU-bound, and its FPS varied 400–1400 between runs from heat. There is no cost to save, so the conservative barriers stay. Narrowing render→render is also unsafe: Minecraft's core shaders read the lightmap in the vertex stage, and the lightmap is written by a previous fragment pass.

Still left from R3: MetalFX objects and FSR contexts are created on the render thread when the settings change.

### R7: FSR upscalers as options (2026-10-28)

The Upscaling menu now holds Off / MetalFX Spatial / MetalFX Temporal / FSR 1 / FSR 3.1. This choice is separate from Frame Generation, so combinations like MetalFX Temporal + FSR 3.1 frame gen or FSR 3.1 + FSR 3.1 frame gen work. All through Metal.

- **FSR 3.1** replaces the MetalFX temporal scaler with the same inputs (`temporal.m`): the motion vector pass, the depth copy, the R5 reactive mask, and the same Halton jitter. Plus near/far/FOV and the time between frames. At 100 % FSR 3.1 becomes anti-aliasing (AMD's native mode).
- **FSR 1** (EASU then RCAS, sharpness 0.2 as in AMD's sample) replaces the spatial scaler.
- **The FSR host code** is shared with frame gen (`fsr/fsr_host.mm`). Both upscalers' shaders are embedded in the dylib too (41 passes in total).

Temporal test (`-Dmine2metal.test.temporalOnly=true`, 854×480, 50 % render), PSNR against native:

| | Still | After a 30° turn |
|---|---|---|
| MetalFX Temporal | 30.46 dB | 29.05 dB |
| FSR 3.1 | **30.73 dB** | **29.27 dB** |
| MetalFX Spatial | 29.12 dB | — |
| FSR 1 | **29.81 dB** | — |

- **Jitter sign:** with all four signs (`-Dmine2metal.test.jitterSigns=true`), FSR 3.1 is also best at `1,1`, like MetalFX.
- **Quality:** both AMD upscalers are slightly sharper (leaves, edges) and narrowly ahead in PSNR.
- **GPU cost:** from the `fsrupscale` benchmark (the upscaler section in [fsr-frame-gen.md](fsr-frame-gen.md)), FSR 3.1 2.67 ms vs MetalFX 1.09 ms, and FSR 1 0.83 ms vs MetalFX Spatial 0.51 ms at 1080p/50 %. So MetalFX remains the recommended choice.
- **Validation:** clean on Metal 3 and Metal 4; the full game test passes.

### R5: a reactive mask for temporal upscaling (2026-10-28)

MetalFX temporal (macOS 14.4+) now takes a reactive mask, a flag on pixels whose history cannot be trusted so they lean more on the current frame. The mask is computed in the existing motion vector pass, with no extra pass:
- The surface at each pixel is tracked to the previous frame with the camera motion, then compared with the previous frame's depth around that point (a 3×3 neighbourhood, the nearest used).
- If nothing is close enough, that surface moves on its own (entities, particles) or just came into view, and the pixel is flagged.
- "Close enough" is measured in pixels at that depth, plus the inter-sample distance for steeply tilted surfaces. What leaves trails is motion of a few pixels per frame, however far the object. A distance-relative threshold (the first version) never fired at 60 fps.
- The previous frame's depth is kept alternately in two textures. After a reset or a scaler rebuild, the mask is empty for one frame.

The camera matrices grew to 52 floats: the previous frame's inverse view-projection, plus a flag for whether there is a previous frame. `-Dmine2metal.temporal.reactiveMask=false` turns the mask off (on by default).

Test (`-Dmine2metal.test.reactiveOnly=true`, 60 fps, render 427×240 → 854×480):

| Case | Native | Temporal + mask | Temporal without mask |
|---|---|---|---|
| A gold block moving 3 blocks/tick (~7 px/frame), PSNR of the block area vs native | — | 29.30 dB | 29.14 dB |
| end_rod particles, light added to the frame (run 1) | 264k | 255k | 132k |
| end_rod particles, bright pixels > 200 (run 1 / run 2) | 1394 / 1760 | 211 / 536 | 0 / 300 |

- **Moving opaque entities:** MetalFX already handles them itself through color clamping. Trails are invisible with and without the mask, and the difference is within noise.
- **Particles** are the main benefit. Minecraft's particles write depth, so the mask catches them. Without the mask particles fade almost away; with it their light stays as in native.
- **Noise:** jitter flags about 0.4 % of pixels at leaf edges (cutout).
- **Regressions:** none. The temporal test stays 30.49 dB still and 29.05 dB after turning. The full game test passes under validation.

Not caught: things that do not write depth (some translucency) and animated textures such as water and lava. Those would need flagging pixels while translucent draws render, a larger design change.

### R4: GPU time per stage and the frame-gen guard (2026-10-28)

**GPU time per stage.** With `-Dmine2metal.stats=true` (without `passTimes`), the frame is split by stage markers, `MetalRenderer.markStage` at the start of `render3dHud` and at present:

> world → upscaling → world capture → hand, effects and GUI → present → frame gen

Each stage's work is committed in a labelled command buffer, and the log records `GPU ms/frame by stage`. Example (2560×1600, covered window): world 0.26, hand/effects/GUI 0.53, present 0.06, frame gen 1.2 ms per frame. The extra commits only happen with statistics on.

**The frame-gen cost is always measured.** While frame gen is active, its work (MetalFX or FSR) runs in its own command buffer, just two extra commits per frame. The time is averaged (`m2m_frame_generation_gpu_millis`).

**The guard.** Frame gen only pays when its cost is below half the gap between rendered frames: two images in T beat one image in T − cost exactly when T > 2 × cost.
- If that condition keeps failing for 3 seconds, frame gen is paused for 30 seconds and then retried. Its native state is kept, so no recompilation is needed.
- The first pause shows a toast "Frame generation paused" with the numbers; later pauses are only logged.
- Changing the engine choice cancels the pause. `-Dmine2metal.frameGenerationGuard=false` turns the guard off; the quality tests use it because the covered test window renders unlimited.

Test results: at 2560×1600, MetalFX (12.2 ms cost vs 13.8 ms gap) and FSR (22.5 vs 25.0 ms) are paused correctly. The full game test passes under validation, generated-frame quality stays 31.2 dB.

A finding to verify on a real display: in the covered game-test window, temporal upscaling at 2560×1600 recorded ~13 ms per frame, while the standalone benchmark says 2.1 ms. macOS probably gives low GPU priority to invisible apps. That might also explain why MetalFX frame-gen cost in the game test used to vary 10–90 ms.

### R3: frame spikes while chunks load (2026-10-28)

A new measurement tool valid for every backend: a mixin in the game-test mod records every `DebugScreenOverlay.logFrameDuration`. Mode `-Dmine2metal.test.chunkSpikes=true`: 1920×1080, render distance 16, VSync off, standing 20 seconds then walking 40 seconds towards the forest (new chunks keep loading). For OpenGL/Vulkan it runs with `-Dmine2metal.enabled=false` and `preferredGraphicsBackend` in `options.txt`.

| Backend (walking, chunks loading) | FPS | p50 / p99 | Max | Frames > 50 ms |
|---|---|---|---|---|
| Metal 4 (3 runs) | 2075–2324 | 0.38–0.41 / 1.47–2.12 ms | 24–48 ms | 0 |
| Metal 3 (2 runs) | 1560–2023 | 0.43–0.56 / 2.05–2.30 ms | 29–88 ms | 0 and 2 (second run, Mac already hot) |
| OpenGL | 294 | 2.67 / 7.10 ms | 11 ms | 0 |
| Vulkan | not available on this Mac; Minecraft falls back to OpenGL | | | |

- **Chunk loading causes no spikes from our backend.** The 200–600 ms spikes seen in earlier measurements appeared when settings were changed mid-test (creating MetalFX scalers/interpolators or FSR contexts, which compile shaders).
- **Remaining slow frames** are rare (0–4 per minute, 50–380 ms, mostly while standing), on Metal 3 and Metal 4 alike. The cause is not pipeline state creation: it is now logged when ≥ 20 ms, and none appeared. One case was matched against the GC log and did not coincide with a GC pause. GC pauses themselves are 15–60 ms every ~3 seconds at 2000+ FPS.
- **Memory allocation (Java Flight Recorder, 60 seconds):** about 5.4 GB of garbage per minute, 82 % on the render thread, but our backend itself only 3.7 %. The rest is Minecraft (matrices, `BlockPos`, `PoseStack`, the internal server's world generator), renderpearl and Sodium. In a game with an ordinary FPS limit (60–120) the garbage is tens of times less.
- Left for later: creating MetalFX objects and FSR contexts on settings changes is still on the render thread (once per change; the first FSR about 3 seconds). Could move to a background thread.

## M13 Metal 4 (2026-10-28)

One native codebase with two command models. The default is **Auto**: Metal 4 when `MTLGPUFamilyMetal4` is supported and macOS ≥ 26, otherwise Metal 3. It can be forced in the Metal menu → Graphics API → Metal version (after a restart), in `metalVersion=` in `config/mine2metal.properties`, or with `-Dmine2metal.metal=auto|metal4|metal3`. If Metal 4 cannot be created, it falls back to Metal 3 automatically. Vulkan/OpenGL still go through vanilla's Graphics API.

| Part | Metal 3 | Metal 4 |
|---|---|---|
| Queue & command buffers | `MTLCommandQueue`, 1 command buffer per submit | `MTL4CommandQueue`, command buffers from an `MTL4CommandAllocator` returned to a pool through commit feedback |
| Residency | automatic | one `MTLResidencySet` for every buffer/texture (added on creation, removed through `m2m_resource_release`; writes locked since resources are also created from worker threads); drawables through `CAMetalLayer.residencySet` |
| Binding | `setVertexBuffer`/`setFragmentTexture`/`setBytes` | one `MTL4ArgumentTable` used by the vertex and fragment stages (GPU addresses, `gpuResourceID`); small constants through a 1 MB upload ring; bindings captured per draw (checked with a prototype) |
| Hazards | tracked automatically | a full barrier at the start and end of every encoder (conservative, equal to Metal 3's serialisation); barriers between copies within one encoder |
| Copy/blit | `MTLBlitCommandEncoder` | `MTL4ComputeCommandEncoder` (same selectors) |
| Present | `presentDrawable`, `encodeWaitForEvent` | `waitForDrawable`/`signalDrawable` and queue-level events |
| MetalFX | `MTLFXSpatialScaler`/`MTLFXTemporalScaler` | `MTL4FX…` created with the `MTL4Compiler` |
| Resource lifetime | the command buffer holds them | Metal 4 command buffers do **not** retain resources; replaced internal textures are kept until 3 submits later (`m2m_retire`) |
| Pipelines | `MTLRenderPipelineState` from `MTLDevice` | the same (proven usable on Metal 4 encoders) |

Debug: `MINE2METAL_TRACE=1` logs every commit, queue wait/signal and completion. The first bug found: a negative wait index from Java (`recordingIndex - 3` at the start) became a huge `uint64` and waited forever; now clamped to committed submits.

Results (game test, Apple M3, macOS 27):
- The whole game test passes under Metal API validation with Metal 4, with no validation errors.
- Metal 4 vs Metal 3 screenshots: title 0.00 % of pixels differ; world 0.07–0.26 % (cloud/water animation); temporal 0.7–0.8 % (MetalFX history).
- Heavy scene (1920×1080, render distance 16, Sodium): CPU time per frame p50 **~0.75 ms** (Metal 4) vs ~1.6 ms (Metal 3), FPS 928 vs 413. This is Metal 4's main benefit: lower CPU cost per draw without hazard tracking.
- Fanless MacBook Air: the second run is always slower from heat (order reversed, the temporal result reversed with it: 1038 vs 521 FPS). Comparisons must alternate order; a single FPS number here cannot be used as a claim.
- Not optimised yet: the full barrier per encoder could be narrowed (for example render→render to fragment→fragment only).

## M12 MetalFX frame interpolation (2026-10-28)

The **Frame Interpolation** option in the Metal menu (macOS 26+, applies at once). For every rendered frame, MetalFX makes one frame halfway between it and the previous frame, then both are shown: the generated frame first, then the real one.

The flow:
- **Capture the world** at the start of `render3dHud`, after upscaling and before the hand, screen effects and GUI. The world's color is copied into one of two alternating textures (this frame and the previous one). Motion vectors and Depth32Float depth are made by the same pass as temporal upscaling (`m2m_encode_motion`), from the depth the world used. When upscaling, that depth is small, and MetalFX accepts it as an input smaller than the output.
- **Camera data:** near/far read from the projection matrix (the device reports zero-to-one depth, so `m22 = f/(n−f)` and `m32 = nf/(n−f)`), vertical FOV from `m11`, aspect from `m11/m00`, the temporal jitter, and the time between captures.
- **The UI layer:** the finished frame (world + hand + GUI) is used as the *composited* UI texture. MetalFX separates what was drawn over the world and pastes it back unshifted, so the hotbar, crosshair, text and hand do not warp.
- **Presenting through the present thread:** interpolation is encoded in the frame's own command buffer, after the frame is copied into one of the present thread's three slots (one generated-frame texture per slot). The present thread (also used with VSync off) takes the latest finished frame, draws the generated frame into one drawable, then the real frame into the next, so both show on consecutive refreshes. The render thread never waits for a drawable. It only waits if the present thread has not taken the previous frame yet, since every frame takes two refreshes and a generated frame that never shows only wastes GPU time. Result: the game ≤ half the refresh rate (30 + 30 on a 60 Hz display), with VSync on or off. With VSync on, this thread only runs while interpolation is active.
- **Fix history (after manual testing):** the first version presented both images from the render thread and paced them with `present(afterMinimumDuration:)`. Two problems. (1) That delay was computed from the frame interval, which itself grew because present was held. So one stalled frame (> 250 ms, often while chunks load) made every image held ~6 refreshes for a long time, and the game fell to 6–12 FPS. The delay is gone. (2) Taking two drawables per frame from the render thread made CPU, GPU and display work in series (~36 ms per frame held at present). That is why present moved to the present thread.

Game test results (Apple M3, macOS 27, 854×480 window): the camera turns from yaw 0° to 4° between two frames, then the generated frame is read back and compared with the real frame at yaw 2° (PSNR, higher is closer):

| Image vs the real frame at yaw 2° | PSNR |
|---|---|
| Real frames yaw 0° / 4° (reference) | 21.8 / 21.6 dB |
| Interpolation, no upscaling (Metal 4 / Metal 3) | **31.3 / 31.4 dB** |
| Interpolation, motion vectors flipped (−1) | 24.7 dB |
| Interpolation + temporal 50 % (motion scale 1 / 2) | **29.8** / 28.1 dB |

- Motion vector scale 1 (in depth pixels) is right, also when depth is smaller than the output: MetalFX scales itself. Changeable for experiments with `-Dmine2metal.interpolation.motionScale=x,y`.
- Hotbar, crosshair and arm intact in the generated frame (checked visually).
- Passes with no errors under validation, Metal 4 and Metal 3. Temporal upscaling unchanged after the motion pass was shared (30.6 / 29.0 dB).
- In a visible game-test window, render FPS drops to 29–30 (60 Hz display), as designed. Pacing on a real display unmeasured: in the game test macOS reports every `presentedTime` as 0, even without interpolation.
- As with temporal, the motion vectors only know the camera's motion, so moving mobs, particles and water may wobble a little in generated frames.

**Cost (Apple M3, MacBook Air):** the MetalFX interpolator itself is expensive on this GPU, and its cost scales with the pixel count. Measured with a separate command buffer for the interpolator. The numbers vary a lot between runs (heat, GPU clock), so ranges are given:

| Output | Depth/motion input | Interpolator GPU time |
|---|---|---|
| 1920×1080 | 1920×1080 | ~10–40 ms |
| 1920×1080 | 1440×810 (spatial 75 %) | ~8–33 ms |
| 1920×1080 | 960×540 (temporal 50 %) | ~6–19 ms |
| 2560×1600 | 2560×1600 / 1920×1200 | ~47–54 ms |

Game test on the ground, walking facing the forest, render distance 16, 1920×1080 (render FPS; hidden window, so not refresh-bound):

| Mode | Present from the render thread (first version) | Present thread |
|---|---|---|
| Interpolation | 16 | 23 |
| + Spatial 75 % | 24–26 | 33 |
| + Temporal 50 % | 40 | 50 |
| Off | 479–1220 | |

Interpolation only pays when the GPU has room to spare (the game is CPU-bound) and the resolution is not large. On a full Retina display on the M3 Air, the interpolator's own cost already exceeds the 33 ms budget for 30 native FPS. It makes most sense together with temporal upscaling (smaller input) or on a Mac with a bigger GPU. The option's tooltip says so.

## M14 HDR/EDR (2026-10-28)

The **HDR** and **HDR Brightness** options (125–400 % of SDR white, default 200 %) in the Metal menu, applied at once. Only selectable when the main display has EDR (`maximumPotentialExtendedDynamicRangeColorComponentValue > 1`). The tooltip shows the display's current headroom.

Minecraft and Sodium only draw SDR. "Real" HDR would mean rewriting both their lighting shaders, so the approach is **highlight expansion**: the brightest parts of the world are raised above SDR white.
- **Layer:** with HDR on, the layer uses `RGBA16Float`, `kCGColorSpaceExtendedLinearSRGB` and `wantsExtendedDynamicRangeContent`. 1.0 = SDR white. Re-attached (the present thread stopped first) whenever the option changes.
- **Curve:** computed from the strongest channel of the encoded color. Below the *knee* at 0.75 the color is unchanged. Above it the linear color is multiplied by `1 + (peak − 1)·t²`, rising smoothly from ×1 at the knee to ×peak at full white. The peak is the player's choice, capped by the display's current headroom (`maximumExtendedDynamicRangeColorComponentValue`, re-read every 30 presents, following the brightness slider).
- **GUI and hand stay SDR:** the world is captured at the same point as interpolation (the start of `render3dHud`). At present, the finished frame is compared per pixel with that world, and the expansion is applied only to pixels still equal (a soft transition of 1.5–6/255). The white hotbar, chat text and crosshair do not glare. Overlays that darken the whole screen (pause menu, the vignette at the edges) also keep what they cover in SDR.
- **Present:** the staging copy is replaced by a "compose" pass writing the extended-linear frame into an `RGBA16Float` texture. Float drawables use a second present pipeline. Applies to the VSync-on path and the VSync-off present thread.
- **With frame interpolation:** the world history is kept in already-expanded float, and the composed frame becomes its UI texture. All of MetalFX's inputs and outputs are then in the same extended-linear space, and in the non-GUI parts the composed frame equals the expanded world exactly.
- **Screenshots (F2)** stay SDR since they read from the game's render target, not the drawable.

Game test results (Apple M3, macOS 27, peak 300 %, the display headroom ignored through `-Dmine2metal.hdr.ignoreDisplayHeadroom=true` so the result does not depend on brightness):
- The composed frame is read back. The brightest world 1.72× SDR white (bright haze at the horizon, ~5.5 % of pixels above 1.0), **the hotbar stays 1.0**. The same with temporal 50 % + interpolation.
- Passes with no errors under validation, Metal 4 and Metal 3. Interpolation and temporal unchanged (31.4 / 30.6 dB).
- Not seen on a real display yet. The MacBook Air only has headroom with the brightness slider below maximum.
- Limitation: everything very bright is raised, snow and daytime clouds included, not only light sources. Minecraft gives no information about which blocks emit light.

## Follow-up roadmap (drawn up 2026-10-28)

Done: the core backend (M0–M8), the settings screen (M10), MetalFX upscaling (M11), MetalFX frame interpolation (M12), Metal 4 (M13), HDR/EDR (M14), one present path, and the FSR 3 spike (F0, see [fsr-frame-gen.md](fsr-frame-gen.md)). Manual tests of Metal 4, interpolation, HDR and VSync on on a real display are still pending ([uji-manual.md](uji-manual.md) items 9, 17, 22–32).

The order below is the priority. Installation (M9) stays last, as agreed.

| # | Work | Contents | Done when |
|---|---|---|---|
| R1 ✅ | **FSR 3.1 frame gen in the game** (F1 + F2; results in [fsr-frame-gen.md](fsr-frame-gen.md#r1-results-fsr-31-frame-generation-in-the-game-2026-10-28)) | The FFX backend in the dylib (AMD's C++ + `ffx_metal.mm`, MSL in the jar, compiled on a background thread + cache). Metal 4 path: MTL4 compute encoders, argument tables, barriers between passes, residency. Inputs: W without HUD, F, depth, motion vectors (scale and sign calibrated with the yaw PSNR test), camera; HDR through the scRGB transfer. Output into the present thread's slot. Menu: Frame gen Off / MetalFX / FSR 3.1 (one at a time) | ≥ 31 dB in the yaw test; faster than MetalFX in the walk-to-the-forest test; clean validation on Metal 3 and 4 |
| R2 ✅ | **Pacing with `CAMetalDisplayLink`** (results in "R2" above) | The present thread uses a display link (macOS 14+). The generated frame sits halfway between two real frames by `targetPresentationTimestamp`, and latency is set through `preferredFrameLatency`. Also measure VSync on on a real display | Even gaps between shown frames (p95 ≈ 1 refresh) with frame gen on |
| R3 ✅ | **CPU spikes while chunks load** (results in "R3" above: not from our backend) | The same walk-to-the-forest test on the built-in OpenGL/Vulkan backends as a comparison (frames can now stall 200–600 ms). If it is our backend, trace it (buffer uploads, pipeline compilation, synchronisation) | Cause known; if ours, spikes ≤ the built-in backends |
| R4 ✅ | **GPU time per stage + the frame-gen guard** (results in "R4" above) | Timestamps at encoder boundaries (counter sample buffers) for world, upscaling, frame gen, HDR, present. From that, frame gen switches itself off (with a message) when its cost exceeds the budget | Per-stage statistics in `-Dmine2metal.stats`; frame gen never lowers FPS below half without saying so |
| R5 ✅ | **A reactive mask for temporal upscaling** (results in "R5" above) | MetalFX's `reactiveMaskTexture` (macOS 14.4+): flag the pixels of mobs, particles and water that move on their own, so they leave no trails | Faint trails in the moving-mob test reduced (measured with screenshots) |
| R6 ✅ | **FSR shader optimisation** (F3; results in [fsr-frame-gen.md](fsr-frame-gen.md#r6-results-fsr-frame-gen-optimisation-2026-10-28)) | Manual FP16 for the main interpolation pass and optical flow, disocclusion at full render, concurrent dispatch for small passes | ≤ 6 ms at 1080p with 50 % render (before 8.7 ms; now 3.7 ms) |
| R7 ✅ | **FSR upscalers as options** (results in "R7" above) | FSR 1 and FSR 3.1 in the Upscaling menu next to MetalFX. Proven to work, but 2–2.5× heavier; their value is as options and for quality comparison | Quality compared with MetalFX in the temporal test; passes validation |
| R8 ✅ | **Tidy the architecture** (results in "R8" above) | One "world frame" flow object (capture/projection/depth/jitter) replacing the static state in `WorldUpscaler`, `FrameInterpolation`, `HdrOutput`; a package outside `upscale`; narrowed Metal 4 barriers | The full game test still passes; no behaviour change |
| R10 🔎 | **Upgrade to FSR 3.1.6** (SDK v2.3.0, MIT; researched, results in [fsr-frame-gen.md](fsr-frame-gen.md#r10-research-fsr-316-from-sdk-v230-2026-10-28): the algorithm is identical to v1.1.4, the port is postponed) | The FSR 3 code in SDK 2.x turned out to be MIT (see the correction in fsr-frame-gen.md), with frame interpolation 3.1.6 and upscaler 3.1.5. Its shaders are HLSL only, so translation would move to `dxc` → SPIR-V → spirv-cross; the host code and the `ffx_metal` backend stay | Yaw-test quality ≥ v1.1.4; clean validation |
| R9 ✅ | **Installation (M9)** (results in "R9" above; release steps in [rilis.md](rilis.md)) | As above: Modrinth/CurseForge release, `.mrpack`, a clear message on unsupported Macs | — |

## Metal-specific features (after M5)

| Feature | Apple API | macOS | Inputs needed | Difficulty |
|---|---|---|---|---|
| Performance overlay ✅ | `CAMetalLayer.developerHUDProperties` | 13 | — | Low |
| Frame pacing / ProMotion | `present(afterMinimumDuration:)`, `displaySyncEnabled` | 10.15.4 | — | Low |
| **Spatial upscaling** | `MTLFXSpatialScaler` | 13 | World color only (the GUI renders separately at native resolution) | Medium |
| **Temporal upscaling** ✅ | `MTLFXTemporalScaler` | 13 | Color + depth + **motion vectors** + camera jitter | High |
| Temporal upscaling + denoise | `MTLFXTemporalDenoisedScaler` | 26 | As temporal, plus buffers for ray tracing | Out of scope |
| **Frame interpolation** ✅ | `MTLFXFrameInterpolator` | 26 | Color, depth, motion, **a separate UI texture**, the previous frame | Very high |
| HDR/EDR ✅ (highlight expansion) | `CAMetalLayer.wantsExtendedDynamicRangeContent` | 10.11 | Shaders producing values > 1.0 | High (needs its own shaders) |

Notes:
- Minecraft produces no motion vectors. For static geometry, motion vectors can be computed from depth + the previous frame's camera matrices. Entities, particles and animated textures need special handling, or they ghost.
- `MTLFXFrameInterpolator` encodes into an ordinary `MTLCommandBuffer` (`encode(commandBuffer:)`); the `MTL4FXFrameInterpolator` variant is only for the Metal 4 path. So a Metal 3 backend is enough, as long as it is macOS 26+ (checked in Apple's documentation, 2026-10-27).
- Frame interpolation adds input latency, noticeable in a game with fast camera motion like Minecraft. The feature should stay optional.
