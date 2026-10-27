# Roadmap mine2metal

## Backend inti

| Milestone | Isi | Hasil yang bisa dicek |
|---|---|---|
| **M0 Skeleton** ✅ | Mixin `getBackendsToTry`, `MetalBackend`, dylib FFM, cek Metal 3, fallback ke OpenGL | Log: `Found Metal 3 device: Apple M3`, lalu `Using graphics backend OpenGL` (diverifikasi 2026-10-27, Sodium 0.9.3 ikut termuat) |
| **M1 Device & resource** ✅ | `GpuDeviceBackend`: `DeviceInfo`/limits/features, `MTLBuffer` shared, tekstur + view (pemetaan `GpuFormat`, D24S8→D32S8), `MTLSamplerState` | Diverifikasi 2026-10-27: `Using graphics backend Metal`, semua atlas tekstur terbuat |
| **M2 Surface** ✅ | `SDL_Metal_CreateView` → `CAMetalLayer`, PresentMode → `displaySyncEnabled`, present lewat pass fullscreen ke drawable BGRA8 | Diverifikasi: frame dipresentasikan ~57 fps (terkunci vsync); isi frame hitam karena draw belum diimplementasikan |
| **M3 Command encoder** (sebagian) | Selesai: 1 command buffer per submit, ≤ 3 frame in-flight, transient memory (Mojang `TransientBlockAllocator`), write/copy via blit, clear penuh, fence, readback dengan callback. Belum: clear sebagian (region), timestamp, ekspansi format RGB 3 komponen | Smoke test native: clear merah → readback `255 0 0 255`; upload → readback cocok |
| **M4 Pipeline & shader** ✅ (tanpa cache) | SPIR-V → MSL via SPIRV-Cross (MSL 3.0, flip Y vertex, uniform `i` → slot `i`, push constant slot 26, vertex buffer slot 27+), library per shader, `MTLRenderPipelineState` per format depth/stencil (dibuat saat dipakai), `MTLDepthStencilState`. Belum: cache MSL ke disk | Diverifikasi 2026-10-27: 0 pipeline gagal saat sampai layar judul |
| **M5 Render pass** ✅ (sebagian) | Binding uniform buffer, tekstur + sampler, texel buffer, push constant, scissor, draw / drawIndexed / multiDraw / indirect (di-batch native: 1 panggilan FFM per batch), fan non-indexed → index, wireframe, cull, depth bias, present terpacu per refresh saat vsync mati. Belum: fan ber-index, multi-draw interleaved, gl_DrawID, debug group | Layar judul ([screenshot](screenshots/m5-title-screen.png)) dan dunia singleplayer tampil; selisih piksel vs OpenGL ≤ 0,22% (lihat bawah) |
| **M6 Sodium** ✅ | Sodium 0.9.3-beta.1 berjalan di Metal lewat jalur `gl_multidraw` miliknya (multi-draw langsung dengan array terpisah, pas dengan Metal), jadi mixin `DrawBackend` tidak diperlukan. Fitur Sodium yang dicek `backendName` "OpenGL" hanya fitur khusus GL (no-error context, WGL) dan memang mati dengan benar | Dunia dengan Sodium tampil identik dengan GL ([dunia](screenshots/m6-world-sodium.png), [dari atas](screenshots/m6-world-above-sodium.png), [malam](screenshots/m6-world-night-sodium.png)) |

## Hasil uji otomatis (2026-10-27)

`./gradlew runClientGameTest` membuat dunia (seed `mine2metal`, preset normal), lalu mengambil screenshot lewat jalur readback Minecraft sendiri dan mengukur FPS. Kondisi: M3, window 854×480 (Retina 1708×960), vsync mati, frame rate tanpa batas.

| Konfigurasi | FPS rata-rata (10 detik) |
|---|---|
| Metal + Sodium | 1266 / 1540 (dua kali jalan) |
| OpenGL + Sodium | 465 / 484 |
| Metal tanpa Sodium | 1123 |
| OpenGL tanpa Sodium | 293 |

| Screenshot Metal vs OpenGL | Piksel berbeda > 8 level | > 48 level |
|---|---|---|
| Layar judul | 0,00% | 0% |
| Dunia, titik spawn (Sodium) | 0,05% | 0% |
| Dunia, dari atas (Sodium) | 0,22% | 0% |
| Dunia, dari atas (tanpa Sodium) | 0,74% | 0% |

Catatan pengukuran: FPS menghitung frame yang dirender. Saat vsync mati, backend Metal hanya mempresentasikan satu frame per refresh layar (frame lain tetap dirender penuh tapi tidak dikirim ke compositor), karena mempresentasikan semuanya membuat `nextDrawable` memblokir dan FPS turun ke ~60. Hasil yang terlihat di layar sama: layar memang tidak bisa menampilkan lebih dari refresh rate-nya. Window gametest kecil, jadi angka ini mengukur overhead CPU/driver lebih dari GPU.
| **M7 Performa** | Command stream batching, `MTLResidencySet`, depth memoryless, prewarm pipeline, jalur GPU-driven (ICB), profiling | Lebih cepat dari GL dan Vulkan/MoltenVK |
| **M8 Ketahanan** | Validation layer, label debug, resize/fullscreen/device loss, toggle config, pemaketan dylib | Stabil untuk pemakai |

## Fitur khas Metal (setelah M5)

| Fitur | API Apple | macOS | Input yang dibutuhkan | Kesulitan |
|---|---|---|---|---|
| Overlay performa | `CAMetalLayer.developerHUDProperties` | 13 | — | Rendah |
| Frame pacing / ProMotion | `present(afterMinimumDuration:)`, `displaySyncEnabled` | 10.15.4 | — | Rendah |
| **Upscaling spasial** | `MTLFXSpatialScaler` | 13 | Warna dunia saja (GUI dirender terpisah di resolusi native) | Sedang |
| **Upscaling temporal** | `MTLFXTemporalScaler` | 13 | Warna + depth + **motion vector** + jitter kamera | Tinggi |
| Upscaling temporal + denoise | `MTLFXTemporalDenoisedScaler` | 26 | Seperti temporal, plus buffer untuk ray tracing | Di luar lingkup |
| **Frame interpolation** | `MTLFXFrameInterpolator` | 26 | Warna, depth, motion, **tekstur UI terpisah**, frame sebelumnya | Sangat tinggi |
| HDR/EDR | `CAMetalLayer.wantsExtendedDynamicRangeContent` | 10.11 | Shader yang menghasilkan nilai > 1.0 | Tinggi (butuh shader sendiri) |

Catatan:
- Minecraft tidak menghasilkan motion vector. Untuk geometri statis, motion vector bisa dihitung dari depth + matriks kamera frame sebelumnya. Entitas, partikel, dan tekstur animasi butuh penanganan khusus, atau akan ghosting.
- `MTLFXFrameInterpolatorDescriptor` punya `makeFrameInterpolator(device:compiler:)` dan `supportsMetal4FX`. Kemungkinan besar interpolator perlu jalur Metal 4, padahal backend dimulai dengan Metal 3 **[inferensi, perlu dicek]**.
- Frame interpolation menambah latensi input, yang terasa di game dengan gerakan kamera cepat seperti Minecraft. Fitur ini sebaiknya opsional.
