# mine2metal: Initial Research

*English version of [docs/riset-awal.md](../riset-awal.md).*

Date: 2026-10-27
Goal: a Fabric mod that makes Minecraft Java render with Apple Metal on macOS, running alongside Sodium + Fabric API.

Confidence markers in this document:
- **[verified]** read directly from a primary source (`client.jar` bytecode, Sodium source, Apple documentation JSON, Mojang metadata).
- **[inference]** a conclusion or general knowledge not checked against a primary source in this session.

---

## 1. Summary

1. **Minecraft 26.3 already has a GPU abstraction layer named `com.mojang.renderpearl`** with a clean backend SPI, plus two implementations: OpenGL and Vulkan. Shaders are compiled GLSL → SPIR-V by `shaderc` before they reach the backend. So a Metal backend only has to implement this SPI, with no need to intercept OpenGL calls one by one. **[verified]**
2. **Sodium (branch `dev`, MC 26.3) already runs on renderpearl**: 56 files use renderpearl, only 5 touch OpenGL directly. One important point: Sodium's draw-path selection uses `instanceof VulkanDevice`, so any backend other than Vulkan is treated as OpenGL. **[verified]**
3. **Every native dependency needed is already shipped by Mojang**: SDL3 with `SDL_Metal_CreateView`/`SDL_Metal_GetLayer`, SPIRV-Cross (`spvc`) with the MSL backend, and LWJGL's `ObjCRuntime`. Java 25 also provides FFM (Panama). **[verified]**
4. **The closest prior art is [mcopt](https://github.com/noahdunnagan/mcopt)** (Apache-2.0, 507★). Its approach is exactly the same (a `GpuBackend` implementation + an Objective-C dylib through FFM), but **since 0.3 mcopt uses its own terrain renderer and cannot run with Sodium**. That is the gap mine2metal can fill: a Metal backend compatible with Sodium and with other mods that only talk to renderpearl. **[verified]**
5. The baseline to beat is vanilla 26.3 on a Mac, which can already use Vulkan → MoltenVK → Metal (MoltenVK is bundled in the LWJGL natives) or Apple's OpenGL-over-Metal. **[verified: MoltenVK is bundled; inference: relative performance]**

---

## 2. Target environment

| Component | Version | Source |
|---|---|---|
| Latest Minecraft release | **26.3** (2026-09-15); snapshot 26.4-snapshot-5 | `version_manifest_v2.json` |
| Java | **25** (`java-runtime-epsilon`) | 26.3 version metadata |
| LWJGL | **3.4.3**: core, opengl, **vulkan, vma, shaderc, spvc, sdl**, freetype, openal, stb, jemalloc | 26.3 version metadata |
| Windowing | **SDL3** (no longer GLFW) | `lwjgl-sdl`, `SDLVideo.SDL_CreateWindow` in `VulkanBackend` |
| macOS JVM arg | `-XstartOnFirstThread` | 26.3 version metadata |
| Obfuscation | No `client_mappings`: the 26.3 jar is **no longer obfuscated** | 26.3 version metadata |
| Fabric Loader | 0.19.3 (Sodium's minimum), 0.19.5 (used by mcopt) | Sodium `buildSrc`, mcopt README |
| Fabric API | 0.160.5+26.3 | Sodium `buildSrc` |
| Sodium | `mc26.3-0.9.3-beta.1` (2026-10-15); `dev` @ `148151c` (2026-10-16) | GitHub releases / clone |
| Dev machine | Apple M3, 16 GB, macOS 27.0.1, SDK 27.0 (Xcode beta) | `sysctl`, `sw_vers`, `xcrun` |

Dev machine note: **the Metal Toolchain is not installed** (`xcrun metal` fails). It is only needed for offline `.metallib` compilation. Run-time MSL compilation (`newLibraryWithSource`) does not need it. Install with `xcodebuild -downloadComponent MetalToolchain` if required.

---

## 3. renderpearl: Mojang's GPU abstraction

### 3.1 Package layout (`client.jar` 26.3)

```
com.mojang.renderpearl
├── api/          GpuDevice, CommandEncoder, RenderPass, GpuBuffer, GpuTexture, RenderPipeline, GpuFormat, ...
├── frontend/     FrontendGpuDevice, FrontendCommandEncoder, FrontendRenderPass, shaders/GlslCompiler
├── backend/api/  SPI: GpuDeviceBackend, GpuSurfaceBackend, CommandEncoderBackend, RenderPassBackend,
│                 BackendRenderPipeline, SpvModule
├── backend/common/  BaseGpuBuffer, BaseGpuTexture, BaseGpuTextureView
├── backend/opengl/  GlBackend, GlDevice, ... (30 classes)
└── backend/vulkan/  VulkanBackend, VulkanDevice, ... (36 classes)
```

The pattern: `GpuBackend.createDevice()` returns `new FrontendGpuDevice(<GpuDeviceBackend implementation>)`. The frontend handles validation and high-level state; the backend only translates to the GPU API. mcopt uses exactly this pattern. **[verified]**

### 3.2 The SPI to implement **[verified, `javap`]**

**`GpuBackend`**: `getName`, `loadLibrary`, `unloadLibrary`, `createWindow(title, w, h, flags) → long` (SDL window handle), `createDevice(GpuDebugOptions)`.

**`GpuDeviceBackend`**: `createSurface(window, BooleanSupplier)`, `createCommandEncoder`, `createSampler`, `createTexture`, `createTextureView`, `createBuffer` (2 overloads), `compilePipeline(CreateInfo) → Pending`, `createTimestampQueryPool`, `getTimestampCalibrationOffset`, `getDeviceInfo`, `getLastDebugMessages`, `isDebuggingEnabled`, `close`.

**`GpuSurfaceBackend`**: `configure(width, height, PresentMode)`, `isSuboptimal`, `acquireNextTexture`, `blitFromTexture(encoder, view)`, `present`, `supportedPresentModes`. PresentMode holds `IMMEDIATE`, `MAILBOX`, `FIFO`, `FIFO_RELAXED`.

**`CommandEncoderBackend`**: `submit`, `transientMemory`, `createRenderPass`, `submitRenderPass`, clear color/depth (3 variants), `writeToBuffer`, `copyToBuffer`, `writeToTexture`, `copyBufferToTexture`, `copyTextureToBuffer` (2), `copyTextureToTexture`, `createFence`, `writeTimestamp`.

**`RenderPassBackend`**: `setPipeline`, `setUniform(int, Object)`, `pushConstants(ByteBuffer)`, scissor, `setVertexBuffer(slot, slice)`, `setIndexBuffer`, `drawIndexed`, `multiDrawIndexed` (2 variants), `drawIndexedIndirect`, `draw`, `multiDraw` (2), `drawIndirect`, debug groups, `writeTimestamp`.

**`BackendRenderPipeline.CreateInfo`**: name, shader list (SPIR-V), vertex buffer + attribute bindings, uniforms (bind group), `pushConstantsSize`, `DepthStencilState`, `PolygonMode` (FILL/WIREFRAME), `cull`, `ColorTargetState[]`, `PrimitiveTopology`.

**`SpvModule`**: `spv() → ByteBuffer`, `type()`, `reflect()` (inputs, outputs, descriptors per set, push constants).

**`DeviceFeatures`** (reported by the backend): `wireframeFillMode`, `shaderDrawParameters`, `multiDrawDirectInterleaved`, `multiDrawDirectSeparate`, `multiDrawIndirect`, `drawIndirect`, `nonZeroFirstInstance`, `persistentMapping`. `DeviceInfo` also has `isZZeroToOne` and `backendName`.

### 3.3 Backend selection **[verified]**

`net.minecraft.client.PreferredGraphicsApi` is the enum `DEFAULT | OPENGL | VULKAN`. `getBackendsToTry()` returns `[Vulkan, GL]` for VULKAN, otherwise `[GL, Vulkan]`. The simplest hook is **a mixin on `getBackendsToTry()` that inserts `MetalBackend` at the front** when running on macOS.

Do not add a new enum value. Sodium builds the dropdown for this option with exactly three labels (`SodiumConfigBuilder`), so a fourth value risks breaking Sodium's UI. **[verified: 3 labels; inference: the effect]**

### 3.4 Shaders **[verified]**

- There are 89 core shaders (`assets/minecraft/shaders/**.vsh|fsh|glsl`) in GLSL.
- `GlslCompiler(isZeroToOne, shaderDrawParameters)` compiles GLSL → SPIR-V through `shaderc`, complete with a `#moj_import` resolver. The `isZeroToOne` flag follows `DeviceInfo.isZZeroToOne`, so a Metal backend simply reports `true` (Metal's clip-space depth range is [0,1]).
- The backend receives SPIR-V and translates it to MSL with **SPIRV-Cross through `org.lwjgl.util.spvc.Spvc`** (`SPVC_BACKEND_MSL`, `SPVC_MSL_PLATFORM_MACOS`, `SPVC_COMPILER_OPTION_MSL_VERSION`, `..._MSL_ARGUMENT_BUFFERS`). The result is compiled at run time with `MTLDevice newLibraryWithSource`. Resource packs that replace shaders go through the same path automatically.

### 3.5 Window and surface **[verified]**

- `VulkanBackend.createWindow` calls `SDL_CreateWindow(..., flags)` and loads the Vulkan loader through `SDL_Vulkan_LoadLibrary`.
- mcopt's `MetalBackend.createWindow` uses `SDL_WINDOW_METAL | flags`.
- `SDLMetal` in LWJGL 3.4.3 provides `SDL_Metal_CreateView(window)` and `SDL_Metal_GetLayer(view)`, which returns a `CAMetalLayer` pointer. No manual `NSWindow`/`NSView` needed.

---

## 4. Sodium on top of renderpearl

**[verified, source `dev` @ 148151c]**

| Finding | Location | Effect on Metal |
|---|---|---|
| `DrawBackend.chooseBackend()`: `instanceof VulkanDevice` → `VK_MULTIDRAW` or `VK_INDIRECT`, otherwise → `OPENGL` | `client/gpu/device/backend/DrawBackend.java` | A Metal backend gets the `OPENGL` path (GL-style multidraw). Needs a mixin to pick `VK_INDIRECT`/`VK_MULTIDRAW` from `DeviceFeatures`, or an upstream PR to select by feature rather than `instanceof`. |
| `MappedStagingBuffer` imports `GlDevice`/`GlBuffer` | `client/gpu/arena/staging/MappedStagingBuffer.java` | To be checked whether this path is used on non-GL backends. |
| `GlSurfaceMixin`, `WindowMixin` (`@Mixin(GlBackend.class)`), `NvidiaWorkarounds`, `GlContextInfo` | `mixin/workarounds/...` | Only active on GL. Safe as long as we do not use `GlBackend`. |
| Some settings are hidden when `backendName` does not contain "OpenGL" | `SodiumConfigBuilder:549` | UI behaviour, not a blocker. |
| Draw calls used: `multiDrawIndexed` (2×), `drawIndexedIndirect` (1×), `draw` (2×) | whole source | These must be fast in our backend. |
| Sodium shaders: `blocks/block_layer_opaque.{vsh,fsh}` + includes `chunk_vertex`, `chunk_material`, `fog`, `globals` | `assets/sodium/shaders` | Through the same GLSL → SPIR-V → MSL path. |

---

## 5. Apple Metal: what matters

### 5.1 Metal 3 vs Metal 4 **[verified, Apple documentation]**

| Feature | Availability |
|---|---|
| `MTL4CommandQueue`, `MTL4CommandBuffer`, `MTL4ArgumentTable`, `MTL4Compiler` | **macOS 26.0+** |
| `MTLResidencySet` | macOS 15.0+ |
| `CAMetalLayer` | macOS 10.11+ |
| `drawIndexedPrimitives(...indirectBuffer...)` | macOS 10.11+ |
| `MTLIndirectCommandBuffer` | macOS 10.14+ |

Key points of Metal 4 ("Understanding the Metal 4 core API"):
- The `MTL4*` types are fully separate from `MTL*`. An app can detect support and adopt them **gradually**. Synchronisation between Metal 3 and Metal 4 queues uses `MTLEvent`/`MTLSharedEvent`.
- Command buffers are created from the device, can be reused, and **do not retain resources**. Their memory comes from an `MTL4CommandAllocator` (one per frame in flight, then `reset()`).
- Metal 4 encoders **have no `setBuffer`/`setTexture`**. Binding goes through an `MTL4ArgumentTable`.
- In Metal 4 **every resource is untracked**, so the app must place barriers itself.
- `MTL4ComputeCommandEncoder` merges blit, compute and acceleration-structure work.

**Recommendation:** start with the **Metal 3 API** (`MTLCommandQueue`, `MTLRenderCommandEncoder` with `setVertexBuffer`/`setFragmentTexture`). The SPI's per-pass binding model (`setUniform`, `setVertexBuffer`) maps directly onto Metal 3, and automatic hazard tracking reduces synchronisation bugs early on. Add `MTLResidencySet` (macOS 15+) to reduce overhead. Consider Metal 4 once the backend is stable and there is profiling data. **[inference]**

### 5.2 Mapping the renderpearl SPI to Metal

| renderpearl | Metal | Notes |
|---|---|---|
| `createWindow` | `SDL_CreateWindow(SDL_WINDOW_METAL)` | [verified in mcopt] |
| `createSurface` / `acquireNextTexture` / `present` | `SDL_Metal_CreateView` → `CAMetalLayer` → `nextDrawable` → `presentDrawable` | |
| PresentMode `FIFO` / `IMMEDIATE` | `displaySyncEnabled = YES / NO` | [inference] |
| PresentMode `MAILBOX` | `displaySyncEnabled = NO` + `maximumDrawableCount = 3` (estimate) | [inference] |
| `compilePipeline` | SPIR-V → `spvc` MSL → `MTLLibrary` → `MTLRenderPipelineState` + a separate `MTLDepthStencilState` | Depth/stencil is a separate object in Metal |
| `cull`, `PolygonMode.WIREFRAME` | `setCullMode`, `setTriangleFillMode(.lines)` (encoder state, dynamic) | |
| `PrimitiveTopology.TRIANGLE_FAN`, `QUADS` | **Not in Metal** → convert through an index buffer | Kausik uses `triangle_fan.metal` |
| `pushConstants` | `setVertexBytes`/`setFragmentBytes` (≤ 4 KB) | [inference] |
| `setUniform(int, Object)` | `setVertexBuffer`/`setFragmentBuffer`/`setFragmentTexture`/`setFragmentSamplerState` | Indices from SPIRV-Cross reflection |
| `drawIndexedIndirect` | `drawIndexedPrimitives(...indirectBuffer:...)` | One draw per call |
| `multiDrawIndexed` | a `drawIndexedPrimitives` loop in native code, or an ICB | Metal has no multi-draw-indirect-count, so ICB + compute is the GPU-driven path |
| `createFence` | `MTLSharedEvent` / `addCompletedHandler` | |
| `writeTimestamp` | `MTLCounterSampleBuffer` | On Apple GPUs sampling only at stage boundaries (not per draw) [inference] |
| copy/clear | `MTLBlitCommandEncoder`, load action `.clear` | |

### 5.3 Texture formats that need care **[inference: the Metal feature-set table has not been re-read]**

- `RGB8_*`, `RGB16_*`, `RGB32_*` (3 components) do not exist as Metal texture pixel formats, so they must map to RGBA (data expanded on upload). As vertex formats the 3-component versions do exist.
- `D24_UNORM_S8_UINT` is not supported on Apple silicon GPUs. Map it to `depth32Float_stencil8`.

### 5.4 Performance features for chunks (later)

For the optimisation stage: indirect command buffers (GPU-driven culling), memoryless render targets and tile memory (Apple's TBDR GPU), mesh shaders. mcopt and its fork use tile memory and compressed render targets for extra effects.

---

## 6. Prior art

| Project | Approach | Status | Relevance |
|---|---|---|---|
| [noahdunnagan/mcopt](https://github.com/noahdunnagan/mcopt) | `MetalBackend implements GpuBackend` → `FrontendGpuDevice(new MetalDevice())`. Native `mcmetal.m` (Objective-C) called through **FFM** (`Linker.nativeLinker()`, every pointer as `long`). Since 0.3 it has its own Metal terrain renderer. | Apache-2.0, 507★, pushed 2026-10-08. Claims on an M4: vanilla ~156 fps, Sodium ~278, mcopt ~1210 (1080p, RD 16). | Validates the architecture. **Not compatible with Sodium/Iris**, and mods that call GL directly cannot draw. |
| [Kausik-Velaga/minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) | Native Objective-C++ (`MetalCommands.mm`, `MetalContext.mm`, ...), a `triangle_fan.metal` shader. Fabric API not required. | MIT, 0★, v0.2.2; declared fully AI-made (Codex). | Reference for detailed mappings (fans, formats, presentation). Quality not audited. |
| [GaelQuinta/minecraft-metal-shaders](https://github.com/GaelQuinta/minecraft-metal-shaders) | A fork of mcopt + shader effects written directly in MSL (shadows, volumetric clouds, HDR, a temporal upscaler). | Apache-2.0, 113★. | An example of Metal-native effects, outside the initial scope. |
| [williamjasperbehnke/Minecraft-Metal-MacOS](https://github.com/williamjasperbehnke/Minecraft-Metal-MacOS) | A standalone C++/Obj-C++ voxel clone, not a mod. | — | Not directly relevant. |

Not researched yet (web search blocked by policy, see §9): VulkanMod for 26.x, Luracasmus's "renderpearl" mod (Iris shaders), and the Sodium team's stance on third-party backends.

---

## 7. Proposed architecture

```
Fabric mod "mine2metal"
├── Java (Fabric, Java 25)
│   ├── mixin PreferredGraphicsApi.getBackendsToTry → insert MetalBackend (macOS + Apple silicon)
│   ├── MetalBackend / MetalDevice / MetalSurface / MetalCommandEncoder / MetalRenderPass / MetalPipeline ...
│   │     implementing the renderpearl backend SPI
│   ├── ShaderTranslator: SPIR-V → MSL via LWJGL spvc (already shipped by Mojang)
│   ├── compat/sodium: mixin DrawBackend.chooseBackend → choose the path from DeviceFeatures
│   └── Native.java: FFM downcalls into libmine2metal.dylib
└── native (Objective-C or C + metal-cpp, built with clang/CMake)
    ├── a small C ABI: device, buffer, texture, sampler, pipeline, surface/layer, encoder
    └── "command stream": Java writes draw commands into an off-heap buffer, native decodes
        and encodes them into an MTLRenderCommandEncoder in ONE downcall per pass
```

Design reasons:
1. **FFM, not JNI**: final in Java 25, no JNI headers, and mcopt proves it works on 26.3. **[verified: mcopt uses FFM]**
2. **Command-stream batching**: Sodium can issue thousands of draws per frame. One downcall per draw (let alone `objc_msgSend` from Java) adds per-call overhead. Sending one command buffer per pass keeps the number of Java↔native crossings down. **[inference, to be proven by profiling]**
3. **Distribution**: the `.dylib` inside the jar is extracted to a config/cache folder, then loaded through `SymbolLookup.libraryLookup`. The arm64 linker signs it ad hoc automatically. For a file extracted by Java itself, the quarantine attribute is probably not set. **[inference, to be tested]**

---

## 8. Risks

1. **Sodium changes fast** (0.9.3 is still a beta). `DrawBackend` and the staging buffers may change between versions. Mitigation: propose feature-based selection upstream.
2. **The renderpearl API is not a public, stable API**, so it will shift with every MC version (26.4 is already at snapshot 5).
3. **Other mods that call OpenGL directly** will not draw on a Metal backend (as with mcopt and Mojang's Vulkan backend).
4. **Iris/shader packs**: Iris depends on OpenGL and Sodium, so it probably will not work at first. **[inference]**
5. **Shader translation correctness**: Y orientation/texture origin, SPIRV-Cross binding indices, fan/quad topologies.
6. **Performance target**: must beat vanilla Vulkan through MoltenVK and vanilla GL. If not, the mod has no value.

---

## 9. Research gaps

This session could not use web search or subagents because of the account's organisation policy, so research went directly through `curl`/`gh` to primary sources. As a result, the following have **not** been read:
- WWDC 2025/2026 sessions on Metal 4 and game porting, the Metal feature-set tables (PDF), and the MSL specification.
- Fabric documentation (Loom for 26.x, mappings after the jar stopped being obfuscated, mixin policy).
- Mojang changelogs/blogs about renderpearl and the Vulkan backend, including whether the API is meant for mods.
- The Sodium team's policy on mods that mixin into it.
- The implementation details of mcopt's `MetalDevice`/`mcmetal.m` (only the README, `MetalBackend` and the FFM list were read).

---

## 10. Sources

- Mojang metadata: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` and the 26.3 version JSON
- `client.jar` 26.3: `https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar` (inspected with `javap`)
- LWJGL 3.4.3: `lwjgl-sdl`, `lwjgl-spvc`, `lwjgl-vulkan:natives-macos-arm64` (contains `libMoltenVK.dylib`)
- Sodium: `https://github.com/CaffeineMC/sodium` branch `dev` commit `148151c`; release `mc26.3-0.9.3-beta.1`
- Apple:
  - https://developer.apple.com/documentation/metal/understanding-the-metal-4-core-api
  - https://developer.apple.com/documentation/metal/mtl4commandqueue
  - https://developer.apple.com/documentation/metal/mtl4argumenttable
  - https://developer.apple.com/documentation/metal/mtlresidencyset
  - https://developer.apple.com/documentation/metal/mtl4compiler
  - https://developer.apple.com/documentation/quartzcore/cametallayer
  - https://developer.apple.com/documentation/metal/mtlindirectcommandbuffer
- Prior art: https://github.com/noahdunnagan/mcopt, https://github.com/Kausik-Velaga/minecraft-metal-renderer, https://github.com/GaelQuinta/minecraft-metal-shaders
