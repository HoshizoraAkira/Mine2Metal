# mine2metal: Riset Awal

Tanggal: 2026-10-27
Tujuan: mod Fabric yang membuat Minecraft Java dirender dengan Apple Metal di macOS, berjalan bersama Sodium + Fabric API.

Penanda keyakinan pada dokumen ini:
- **[terverifikasi]** dibaca langsung dari sumber primer (bytecode `client.jar`, source Sodium, JSON dokumentasi Apple, metadata Mojang).
- **[inferensi]** kesimpulan atau pengetahuan umum yang belum dicek ke sumber primer dalam sesi ini.

---

## 1. Ringkasan

1. **Minecraft 26.3 sudah punya lapisan abstraksi GPU bernama `com.mojang.renderpearl`** dengan SPI backend yang bersih, plus dua implementasi: OpenGL dan Vulkan. Shader dikompilasi GLSL → SPIR-V oleh `shaderc` sebelum diserahkan ke backend. Artinya backend Metal cukup mengimplementasikan SPI ini, tanpa perlu mencegat panggilan OpenGL satu per satu. **[terverifikasi]**
2. **Sodium (branch `dev`, MC 26.3) sudah berjalan di atas renderpearl**: 56 file memakai renderpearl, hanya 5 yang menyentuh OpenGL langsung. Satu titik penting: pemilihan jalur draw Sodium memakai `instanceof VulkanDevice`, sehingga backend selain Vulkan dianggap OpenGL. **[terverifikasi]**
3. **Semua dependensi native yang dibutuhkan sudah dikirim Mojang**: SDL3 dengan `SDL_Metal_CreateView`/`SDL_Metal_GetLayer`, SPIRV-Cross (`spvc`) dengan backend MSL, dan `ObjCRuntime` LWJGL. Java 25 juga menyediakan FFM (Panama). **[terverifikasi]**
4. **Prior art terdekat: [mcopt](https://github.com/noahdunnagan/mcopt)** (Apache-2.0, 507★). Pendekatannya sama persis (implementasi `GpuBackend` + dylib Objective-C via FFM), tetapi **sejak 0.3 mcopt memakai terrain renderer sendiri dan tidak bisa berjalan bersama Sodium**. Ini celah yang bisa diisi mine2metal: backend Metal yang kompatibel dengan Sodium dan mod lain yang hanya bicara ke renderpearl. **[terverifikasi]**
5. Baseline yang harus dikalahkan adalah vanilla 26.3 di Mac, yang sudah bisa memakai Vulkan → MoltenVK → Metal (MoltenVK dibundel di natives LWJGL) atau OpenGL-over-Metal milik Apple. **[terverifikasi: MoltenVK dibundel; inferensi: performa relatif]**

---

## 2. Lingkungan target

| Komponen | Versi | Sumber |
|---|---|---|
| Minecraft rilis terbaru | **26.3** (2026-09-15); snapshot 26.4-snapshot-5 | `version_manifest_v2.json` |
| Java | **25** (`java-runtime-epsilon`) | metadata versi 26.3 |
| LWJGL | **3.4.3**: core, opengl, **vulkan, vma, shaderc, spvc, sdl**, freetype, openal, stb, jemalloc | metadata versi 26.3 |
| Windowing | **SDL3** (bukan GLFW lagi) | `lwjgl-sdl`, `SDLVideo.SDL_CreateWindow` di `VulkanBackend` |
| JVM arg macOS | `-XstartOnFirstThread` | metadata versi 26.3 |
| Obfuscation | Tidak ada `client_mappings`: jar 26.3 sudah **tidak diobfuscate** | metadata versi 26.3 |
| Fabric Loader | 0.19.3 (minimum di Sodium), 0.19.5 (dipakai mcopt) | `buildSrc` Sodium, README mcopt |
| Fabric API | 0.160.5+26.3 | `buildSrc` Sodium |
| Sodium | `mc26.3-0.9.3-beta.1` (2026-10-15); `dev` @ `148151c` (2026-10-16) | GitHub releases / clone |
| Mesin dev | Apple M3, 16 GB, macOS 27.0.1, SDK 27.0 (Xcode beta) | `sysctl`, `sw_vers`, `xcrun` |

Catatan mesin dev: **Metal Toolchain belum terpasang** (`xcrun metal` gagal). Ini hanya diperlukan untuk kompilasi `.metallib` offline. Kompilasi MSL saat runtime (`newLibraryWithSource`) tidak membutuhkannya. Instal dengan `xcodebuild -downloadComponent MetalToolchain` bila perlu.

---

## 3. renderpearl: abstraksi GPU milik Mojang

### 3.1 Struktur paket (`client.jar` 26.3)

```
com.mojang.renderpearl
├── api/          GpuDevice, CommandEncoder, RenderPass, GpuBuffer, GpuTexture, RenderPipeline, GpuFormat, ...
├── frontend/     FrontendGpuDevice, FrontendCommandEncoder, FrontendRenderPass, shaders/GlslCompiler
├── backend/api/  SPI: GpuDeviceBackend, GpuSurfaceBackend, CommandEncoderBackend, RenderPassBackend,
│                 BackendRenderPipeline, SpvModule
├── backend/common/  BaseGpuBuffer, BaseGpuTexture, BaseGpuTextureView
├── backend/opengl/  GlBackend, GlDevice, ... (30 kelas)
└── backend/vulkan/  VulkanBackend, VulkanDevice, ... (36 kelas)
```

Pola yang dipakai: `GpuBackend.createDevice()` mengembalikan `new FrontendGpuDevice(<implementasi GpuDeviceBackend>)`. Frontend menangani validasi dan state tingkat tinggi, backend hanya menerjemahkan ke API GPU. mcopt memakai pola ini persis. **[terverifikasi]**

### 3.2 SPI yang harus diimplementasikan **[terverifikasi, `javap`]**

**`GpuBackend`**: `getName`, `loadLibrary`, `unloadLibrary`, `createWindow(title, w, h, flags) → long` (handle SDL window), `createDevice(GpuDebugOptions)`.

**`GpuDeviceBackend`**: `createSurface(window, BooleanSupplier)`, `createCommandEncoder`, `createSampler`, `createTexture`, `createTextureView`, `createBuffer` (2 overload), `compilePipeline(CreateInfo) → Pending`, `createTimestampQueryPool`, `getTimestampCalibrationOffset`, `getDeviceInfo`, `getLastDebugMessages`, `isDebuggingEnabled`, `close`.

**`GpuSurfaceBackend`**: `configure(width, height, PresentMode)`, `isSuboptimal`, `acquireNextTexture`, `blitFromTexture(encoder, view)`, `present`, `supportedPresentModes`. PresentMode berisi `IMMEDIATE`, `MAILBOX`, `FIFO`, `FIFO_RELAXED`.

**`CommandEncoderBackend`**: `submit`, `transientMemory`, `createRenderPass`, `submitRenderPass`, clear color/depth (3 varian), `writeToBuffer`, `copyToBuffer`, `writeToTexture`, `copyBufferToTexture`, `copyTextureToBuffer` (2), `copyTextureToTexture`, `createFence`, `writeTimestamp`.

**`RenderPassBackend`**: `setPipeline`, `setUniform(int, Object)`, `pushConstants(ByteBuffer)`, scissor, `setVertexBuffer(slot, slice)`, `setIndexBuffer`, `drawIndexed`, `multiDrawIndexed` (2 varian), `drawIndexedIndirect`, `draw`, `multiDraw` (2), `drawIndirect`, debug group, `writeTimestamp`.

**`BackendRenderPipeline.CreateInfo`**: nama, daftar shader (SPIR-V), vertex buffer + attrib binding, uniform (bind group), `pushConstantsSize`, `DepthStencilState`, `PolygonMode` (FILL/WIREFRAME), `cull`, `ColorTargetState[]`, `PrimitiveTopology`.

**`SpvModule`**: `spv() → ByteBuffer`, `type()`, `reflect()` (inputs, outputs, descriptors per set, push constants).

**`DeviceFeatures`** (dilaporkan backend): `wireframeFillMode`, `shaderDrawParameters`, `multiDrawDirectInterleaved`, `multiDrawDirectSeparate`, `multiDrawIndirect`, `drawIndirect`, `nonZeroFirstInstance`, `persistentMapping`. `DeviceInfo` juga punya `isZZeroToOne` dan `backendName`.

### 3.3 Pemilihan backend **[terverifikasi]**

`net.minecraft.client.PreferredGraphicsApi` adalah enum `DEFAULT | OPENGL | VULKAN`. Method `getBackendsToTry()` mengembalikan `[Vulkan, GL]` bila VULKAN, selain itu `[GL, Vulkan]`. Titik kait paling sederhana adalah **mixin pada `getBackendsToTry()` untuk menyisipkan `MetalBackend` di depan** saat berjalan di macOS.

Jangan menambah nilai enum baru. Sodium membuat dropdown opsi ini dengan tepat tiga label (`SodiumConfigBuilder`), jadi nilai keempat berisiko memecah UI Sodium. **[terverifikasi: 3 label; inferensi: dampaknya]**

### 3.4 Shader **[terverifikasi]**

- Ada 89 shader core (`assets/minecraft/shaders/**.vsh|fsh|glsl`) dalam GLSL.
- `GlslCompiler(isZeroToOne, shaderDrawParameters)` mengompilasi GLSL → SPIR-V lewat `shaderc`, lengkap dengan resolver `#moj_import`. Flag `isZeroToOne` mengikuti `DeviceInfo.isZZeroToOne`, sehingga backend Metal cukup melaporkan `true` (rentang depth clip-space Metal adalah [0,1]).
- Backend menerima SPIR-V, lalu menerjemahkannya ke MSL dengan **SPIRV-Cross via `org.lwjgl.util.spvc.Spvc`** (`SPVC_BACKEND_MSL`, `SPVC_MSL_PLATFORM_MACOS`, `SPVC_COMPILER_OPTION_MSL_VERSION`, `..._MSL_ARGUMENT_BUFFERS`). Hasilnya dikompilasi saat runtime dengan `MTLDevice newLibraryWithSource`. Resource pack yang mengganti shader otomatis ikut jalur ini.

### 3.5 Window dan surface **[terverifikasi]**

- `VulkanBackend.createWindow` memanggil `SDL_CreateWindow(..., flags)` dan memuat loader Vulkan via `SDL_Vulkan_LoadLibrary`.
- `MetalBackend.createWindow` di mcopt memakai `SDL_WINDOW_METAL | flags`.
- `SDLMetal` di LWJGL 3.4.3 menyediakan `SDL_Metal_CreateView(window)` dan `SDL_Metal_GetLayer(view)`, yang mengembalikan pointer `CAMetalLayer`. Tidak perlu `NSWindow`/`NSView` manual.

---

## 4. Sodium di atas renderpearl

**[terverifikasi, source `dev` @ 148151c]**

| Temuan | Lokasi | Dampak bagi Metal |
|---|---|---|
| `DrawBackend.chooseBackend()`: `instanceof VulkanDevice` → `VK_MULTIDRAW` atau `VK_INDIRECT`, selain itu → `OPENGL` | `client/gpu/device/backend/DrawBackend.java` | Backend Metal akan diberi jalur `OPENGL` (multidraw ala GL). Perlu mixin agar memilih `VK_INDIRECT`/`VK_MULTIDRAW` berdasarkan `DeviceFeatures`, atau usulan PR ke upstream supaya pemilihannya berbasis fitur, bukan `instanceof`. |
| `MappedStagingBuffer` mengimpor `GlDevice`/`GlBuffer` | `client/gpu/arena/staging/MappedStagingBuffer.java` | Perlu dicek apakah jalur ini dipakai di backend non-GL. |
| `GlSurfaceMixin`, `WindowMixin` (`@Mixin(GlBackend.class)`), `NvidiaWorkarounds`, `GlContextInfo` | `mixin/workarounds/...` | Hanya aktif di GL. Aman selama kita tidak memakai `GlBackend`. |
| Opsi menyembunyikan beberapa setting bila `backendName` tidak mengandung "OpenGL" | `SodiumConfigBuilder:549` | Perilaku UI, bukan blocker. |
| Draw call yang dipakai: `multiDrawIndexed` (2×), `drawIndexedIndirect` (1×), `draw` (2×) | seluruh source | Ini yang harus cepat di backend kita. |
| Shader Sodium: `blocks/block_layer_opaque.{vsh,fsh}` + include `chunk_vertex`, `chunk_material`, `fog`, `globals` | `assets/sodium/shaders` | Lewat jalur GLSL → SPIR-V → MSL yang sama. |

---

## 5. Apple Metal: yang relevan

### 5.1 Metal 3 vs Metal 4 **[terverifikasi, dokumentasi Apple]**

| Fitur | Ketersediaan |
|---|---|
| `MTL4CommandQueue`, `MTL4CommandBuffer`, `MTL4ArgumentTable`, `MTL4Compiler` | **macOS 26.0+** |
| `MTLResidencySet` | macOS 15.0+ |
| `CAMetalLayer` | macOS 10.11+ |
| `drawIndexedPrimitives(...indirectBuffer...)` | macOS 10.11+ |
| `MTLIndirectCommandBuffer` | macOS 10.14+ |

Poin penting Metal 4 ("Understanding the Metal 4 core API"):
- Tipe `MTL4*` sepenuhnya terpisah dari `MTL*`. Aplikasi bisa mendeteksi dukungan dan mengadopsinya **bertahap**. Sinkronisasi antar queue Metal 3 dan Metal 4 memakai `MTLEvent`/`MTLSharedEvent`.
- Command buffer dibuat dari device, bisa dipakai ulang, dan **tidak me-retain resource**. Memorinya berasal dari `MTL4CommandAllocator` (satu per frame in-flight, lalu `reset()`).
- Encoder Metal 4 **tidak punya `setBuffer`/`setTexture`**. Binding dilakukan lewat `MTL4ArgumentTable`.
- Di Metal 4 **semua resource untracked**, jadi aplikasi wajib memasang barrier sendiri.
- `MTL4ComputeCommandEncoder` menggabungkan blit, compute, dan acceleration structure.

**Rekomendasi:** mulai dengan **API Metal 3** (`MTLCommandQueue`, `MTLRenderCommandEncoder` dengan `setVertexBuffer`/`setFragmentTexture`). Model binding per-pass di SPI (`setUniform`, `setVertexBuffer`) memetakan langsung ke Metal 3, dan hazard tracking otomatis mengurangi bug sinkronisasi di tahap awal. Tambahkan `MTLResidencySet` (macOS 15+) untuk mengurangi overhead. Pertimbangkan Metal 4 setelah backend stabil dan ada data profiling. **[inferensi]**

### 5.2 Pemetaan SPI renderpearl → Metal

| renderpearl | Metal | Catatan |
|---|---|---|
| `createWindow` | `SDL_CreateWindow(SDL_WINDOW_METAL)` | [terverifikasi di mcopt] |
| `createSurface` / `acquireNextTexture` / `present` | `SDL_Metal_CreateView` → `CAMetalLayer` → `nextDrawable` → `presentDrawable` | |
| PresentMode `FIFO` / `IMMEDIATE` | `displaySyncEnabled = YES / NO` | [inferensi] |
| PresentMode `MAILBOX` | `displaySyncEnabled = NO` + `maximumDrawableCount = 3` (perkiraan) | [inferensi] |
| `compilePipeline` | SPIR-V → `spvc` MSL → `MTLLibrary` → `MTLRenderPipelineState` + `MTLDepthStencilState` terpisah | Depth/stencil adalah objek terpisah di Metal |
| `cull`, `PolygonMode.WIREFRAME` | `setCullMode`, `setTriangleFillMode(.lines)` (state encoder, dinamis) | |
| `PrimitiveTopology.TRIANGLE_FAN`, `QUADS` | **Tidak ada di Metal** → konversi lewat index buffer | Kausik memakai `triangle_fan.metal` |
| `pushConstants` | `setVertexBytes`/`setFragmentBytes` (≤ 4 KB) | [inferensi] |
| `setUniform(int, Object)` | `setVertexBuffer`/`setFragmentBuffer`/`setFragmentTexture`/`setFragmentSamplerState` | Indeks dari reflection SPIRV-Cross |
| `drawIndexedIndirect` | `drawIndexedPrimitives(...indirectBuffer:...)` | Satu draw per panggilan |
| `multiDrawIndexed` | loop `drawIndexedPrimitives` di native, atau ICB | Metal tidak punya multi-draw-indirect-count, jadi ICB + compute adalah jalur GPU-driven |
| `createFence` | `MTLSharedEvent` / `addCompletedHandler` | |
| `writeTimestamp` | `MTLCounterSampleBuffer` | Di Apple GPU sampling hanya di batas stage (bukan per draw) [inferensi] |
| copy/clear | `MTLBlitCommandEncoder`, load action `.clear` | |

### 5.3 Format tekstur yang perlu perhatian **[inferensi: tabel feature set Metal belum dibaca ulang]**

- `RGB8_*`, `RGB16_*`, `RGB32_*` (3 komponen) tidak tersedia sebagai pixel format tekstur Metal, jadi harus dipetakan ke RGBA (data diperluas saat upload). Untuk vertex format, versi 3 komponen tetap ada.
- `D24_UNORM_S8_UINT` tidak didukung di GPU Apple Silicon. Petakan ke `depth32Float_stencil8`.

### 5.4 Fitur performa untuk chunk (nanti)

Untuk tahap optimasi: indirect command buffer (GPU-driven culling), memoryless render target dan tile memory (TBDR Apple GPU), mesh shader. mcopt dan fork-nya memakai tile memory dan render target terkompresi untuk efek tambahan.

---

## 6. Prior art

| Proyek | Pendekatan | Status | Relevansi |
|---|---|---|---|
| [noahdunnagan/mcopt](https://github.com/noahdunnagan/mcopt) | `MetalBackend implements GpuBackend` → `FrontendGpuDevice(new MetalDevice())`. Native `mcmetal.m` (Objective-C) dipanggil lewat **FFM** (`Linker.nativeLinker()`, semua pointer sebagai `long`). Sejak 0.3 punya terrain renderer Metal sendiri. | Apache-2.0, 507★, push 2026-10-08. Klaim di M4: vanilla ~156 fps, Sodium ~278, mcopt ~1210 (1080p, RD 16). | Validasi arsitektur. **Tidak kompatibel dengan Sodium/Iris**, dan mod yang memanggil GL langsung tidak bisa menggambar. |
| [Kausik-Velaga/minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) | Native Objective-C++ (`MetalCommands.mm`, `MetalContext.mm`, ...), shader `triangle_fan.metal`. Fabric API tidak diwajibkan. | MIT, 0★, v0.2.2; dinyatakan sepenuhnya dibuat AI (Codex). | Referensi pemetaan detail (fan, format, presentasi). Kualitas belum teraudit. |
| [GaelQuinta/minecraft-metal-shaders](https://github.com/GaelQuinta/minecraft-metal-shaders) | Fork mcopt + efek shader yang ditulis langsung di MSL (bayangan, awan volumetrik, HDR, upscaler temporal). | Apache-2.0, 113★. | Contoh efek Metal-native, di luar lingkup awal. |
| [williamjasperbehnke/Minecraft-Metal-MacOS](https://github.com/williamjasperbehnke/Minecraft-Metal-MacOS) | Clone voxel C++/Obj-C++ yang berdiri sendiri, bukan mod. | — | Tidak relevan langsung. |

Belum diriset (penelusuran web diblokir kebijakan, lihat §9): VulkanMod untuk 26.x, mod "renderpearl" milik Luracasmus (Iris shaders), dan sikap tim Sodium terhadap backend pihak ketiga.

---

## 7. Usulan arsitektur

```
Fabric mod "mine2metal"
├── Java (Fabric, Java 25)
│   ├── mixin PreferredGraphicsApi.getBackendsToTry → sisipkan MetalBackend (macOS + Apple Silicon)
│   ├── MetalBackend / MetalDevice / MetalSurface / MetalCommandEncoder / MetalRenderPass / MetalPipeline ...
│   │     mengimplementasikan renderpearl backend SPI
│   ├── ShaderTranslator: SPIR-V → MSL via LWJGL spvc (sudah dikirim Mojang)
│   ├── compat/sodium: mixin DrawBackend.chooseBackend → pilih jalur berdasarkan DeviceFeatures
│   └── Native.java: downcall FFM ke libmine2metal.dylib
└── native (Objective-C atau C + metal-cpp, dibangun dengan clang/CMake)
    ├── C ABI kecil: device, buffer, texture, sampler, pipeline, surface/layer, encoder
    └── "command stream": Java menulis perintah draw ke buffer off-heap, native men-decode
        dan meng-encode ke MTLRenderCommandEncoder dalam SATU downcall per pass
```

Alasan desain:
1. **FFM, bukan JNI**: Java 25 sudah final, tanpa header JNI, dan mcopt membuktikannya bekerja di 26.3. **[terverifikasi: mcopt memakai FFM]**
2. **Batching command stream**: Sodium bisa mengeluarkan ribuan draw per frame. Satu downcall per draw (apalagi `objc_msgSend` dari Java) menambah overhead per panggilan. Mengirim satu buffer perintah per pass menekan jumlah crossing Java↔native. **[inferensi, perlu dibuktikan dengan profiling]**
3. **Distribusi**: `.dylib` di dalam jar diekstrak ke folder config/cache, lalu dimuat via `SymbolLookup.libraryLookup`. Linker arm64 otomatis memberi tanda tangan ad-hoc. Untuk file yang diekstrak oleh Java sendiri, atribut quarantine kemungkinan tidak terpasang. **[inferensi, perlu diuji]**

---

## 8. Risiko

1. **Sodium berubah cepat** (0.9.3 masih beta). `DrawBackend` dan staging buffer bisa berubah antar versi. Mitigasi: usulkan pemilihan berbasis fitur ke upstream.
2. **API renderpearl bukan API publik yang stabil**, jadi akan bergeser tiap versi MC (26.4 sudah snapshot 5).
3. **Mod lain yang memanggil OpenGL langsung** tidak akan menggambar di backend Metal (sama seperti di mcopt dan di backend Vulkan Mojang).
4. **Iris/shaderpack**: Iris bergantung pada OpenGL dan Sodium, sehingga kemungkinan tidak bekerja di awal. **[inferensi]**
5. **Kebenaran terjemahan shader**: orientasi Y/origin tekstur, binding index SPIRV-Cross, dan topologi fan/quad.
6. **Target performa**: harus lebih cepat daripada vanilla-Vulkan via MoltenVK dan vanilla-GL. Kalau tidak, mod ini tidak punya nilai.

---

## 9. Celah riset

Sesi ini tidak bisa memakai penelusuran web atau subagent karena kebijakan organisasi akun, jadi riset dilakukan langsung lewat `curl`/`gh` ke sumber primer. Akibatnya, hal-hal berikut **belum** dibaca:
- Sesi WWDC 2025/2026 tentang Metal 4 dan game porting, tabel Metal feature set (PDF), dan spesifikasi MSL.
- Dokumentasi Fabric (Loom untuk 26.x, mappings setelah jar tidak lagi diobfuscate, policy mixin).
- Changelog/blog Mojang tentang renderpearl dan backend Vulkan, termasuk apakah API ini dimaksudkan untuk dipakai mod.
- Kebijakan tim Sodium terhadap mod yang di-mixin ke dalamnya.
- Detail implementasi `MetalDevice`/`mcmetal.m` milik mcopt (baru README, `MetalBackend`, dan daftar FFM yang dibaca).

---

## 10. Sumber

- Metadata Mojang: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` dan JSON versi 26.3
- `client.jar` 26.3: `https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar` (diinspeksi dengan `javap`)
- LWJGL 3.4.3: `lwjgl-sdl`, `lwjgl-spvc`, `lwjgl-vulkan:natives-macos-arm64` (berisi `libMoltenVK.dylib`)
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
