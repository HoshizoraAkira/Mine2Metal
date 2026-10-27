# Roadmap mine2metal

## Backend inti

| Milestone | Isi | Hasil yang bisa dicek |
|---|---|---|
| **M0 Skeleton** ✅ | Mixin `getBackendsToTry`, `MetalBackend`, dylib FFM, cek Metal 3, fallback ke OpenGL | Log: `Found Metal 3 device: Apple M3`, lalu `Using graphics backend OpenGL` (diverifikasi 2026-10-27, Sodium 0.9.3 ikut termuat) |
| **M1 Device & resource** ✅ | `GpuDeviceBackend`: `DeviceInfo`/limits/features, `MTLBuffer` shared, tekstur + view (pemetaan `GpuFormat`, D24S8→D32S8), `MTLSamplerState` | Diverifikasi 2026-10-27: `Using graphics backend Metal`, semua atlas tekstur terbuat |
| **M2 Surface** ✅ | `SDL_Metal_CreateView` → `CAMetalLayer`, PresentMode → `displaySyncEnabled`, present lewat pass fullscreen ke drawable BGRA8 | Diverifikasi: frame dipresentasikan ~57 fps (terkunci vsync) |
| **M3 Command encoder** ✅ (hampir) | 1 command buffer per submit, ≤ 3 frame in-flight, transient memory (Mojang `TransientBlockAllocator`), write/copy via blit, clear penuh, fence, readback dengan callback. Clear sebagian: segitiga layar penuh dengan scissor ke region dan depth dari vertex shader (dipakai `GuiItemAtlas` untuk satu slot ikon item). Timestamp GPU: Apple GPU hanya bisa sampling di batas encoder, jadi timestamp memecah command buffer dan nilainya `GPUEndTime` pekerjaan sebelumnya (semantik sama dengan timestamp query GL), dalam nanodetik host time; offset kalibrasi `System.nanoTime()` − `CACurrentMediaTime()`. Timestamp di dalam render pass tetap tidak tersedia. Belum: ekspansi format RGB 3 komponen | Smoke test native: clear merah → readback `255 0 0 255`; upload → readback cocok. Gametest: dua timestamp di sekitar clear tersedia dan berurutan (0,5 ms); clear sebagian 4×4 di tekstur 16×16 cocok per piksel; semuanya di bawah Metal API validation |
| **M4 Pipeline & shader** ✅ | SPIR-V → MSL via SPIRV-Cross (MSL 3.0, flip Y vertex, uniform `i` → slot `i`, push constant slot 26, vertex buffer slot 27+), library per shader, `MTLRenderPipelineState` per format depth/stencil (dibuat saat dipakai), `MTLDepthStencilState` | 0 pipeline gagal; 204 pipeline butuh total 254 ms (SPIR-V → MSL) + 37 ms (MSL → library) di 4–8 thread, jadi cache MSL ke disk tidak diperlukan |
| **M5 Render pass** ✅ (sebagian) | Binding uniform buffer, tekstur + sampler, texel buffer, push constant, scissor, draw / drawIndexed / multiDraw / indirect (di-batch native: 1 panggilan FFM per batch), fan non-indexed → index, fan ber-index → segitiga (index dibaca di CPU dari memori shared; dilewati kalau GPU masih menulis buffer index, atau untuk draw indirect), wireframe, cull, depth bias, present terpacu per refresh saat vsync mati. Belum: multi-draw interleaved, gl_DrawID | Layar judul ([screenshot](screenshots/m5-title-screen.png)) dan dunia singleplayer tampil; selisih piksel vs OpenGL kecil (lihat bawah) |
| **M6 Sodium** ✅ | Sodium 0.9.3-beta.1 berjalan di Metal lewat jalur `gl_multidraw` miliknya (multi-draw langsung dengan array terpisah, pas dengan Metal), jadi mixin `DrawBackend` tidak diperlukan. Fitur Sodium yang dicek `backendName` "OpenGL" hanya fitur khusus GL (no-error context, WGL) dan memang mati dengan benar | Dunia dengan Sodium tampil identik dengan GL ([dunia](screenshots/m6-world-sodium.png), [dari atas](screenshots/m6-world-above-sodium.png), [malam](screenshots/m6-world-night-sodium.png), [jauh](screenshots/m7-world-far-sodium.png)) |
| **M7 Performa** ✅ | Clear ditunda dan dilipat ke load action pass berikutnya; pass berurutan dengan attachment sama digabung dalam satu encoder; tulis/salin buffer lewat CPU saat buffer tidak dipakai GPU (memori shared), atau buffer "di-rename" ke memori cadangan saat seluruh isinya diganti; alat ukur (`-Dmine2metal.stats`, `-Dmine2metal.passTimes`, label encoder untuk Instruments). Sengaja tidak dikerjakan: `MTLResidencySet` dan depth memoryless (alasan di bawah) | Per frame: render encoder 8,9 → 3,1, blit encoder 3,1 → 0; GPU 0,3–0,4 ms/frame di skenario ringan |
| **M8 Ketahanan** ✅ (uji manual menunggu) | Metal API validation untuk `--vulkanValidation` atau `MTL_DEBUG_LAYER=1`; command buffer dengan status per-encoder saat debug; error GPU dicatat ke log dan `getLastDebugMessages`, device hilang → `GpuDeviceLossException` (crash report); label encoder untuk `--renderDebugLabels`; `config/mine2metal.properties` + Metal hanya untuk Graphics API "Default"; dylib diekstrak ke `.mine2metal/natives/<sha-256>/` secara atomik; buffer dibulatkan ke 16 byte | Gametest lolos di bawah Metal API **dan** shader validation; fullscreen (1920×1080) dan resize (1000×600) benar; improved transparency identik dengan GL (0,00%); [daftar uji manual](uji-manual.md) |

## Hasil uji otomatis (2026-10-27)

`./gradlew runClientGameTest` membuat dunia (seed `mine2metal`, preset normal, mode kreatif sambil terbang), mengambil screenshot lewat jalur readback Minecraft sendiri, dan mengukur FPS rata-rata 10 detik. Mesin: MacBook Air 15" M3 (Mac15,13), macOS 27.0.1, listrik AC. Vsync mati, frame rate tanpa batas, limiter AFK dimatikan.

- **Ringan**: window 854×480 (Retina 1708×960), render distance default gametest, melihat ke bawah dari 40 blok di atas spawn.
- **Berat**: window 1920×1080, render distance 16, melihat ke horizon setelah 45 detik generasi dunia.

| Konfigurasi | Ringan (FPS) | Berat (FPS) |
|---|---|---|
| Metal + Sodium, setelah M7 lengkap | 2045 – 2772 | 664 – 1528 |
| Metal + Sodium, setelah lipat clear + gabung pass | 1625 / 1675 | 1178 / 1453 |
| Metal + Sodium, sebelum M7 | 1112 – 1378 | 80 – 150 |
| OpenGL + Sodium | 321 – 488 | 200 – 273 |
| Metal tanpa Sodium (setelah lipat clear + gabung pass) | 1794 | 416 |
| OpenGL tanpa Sodium | 224 | 244 |

Rentang yang lebar di baris pertama berasal dari suhu: setelah sekitar satu jam benchmark, MacBook Air (tanpa kipas) masuk thermal state "fair", dan waktu GPU per frame naik pelan-pelan dari 0,78 ke 2,5 ms tanpa perubahan beban. Angka tertinggi diukur saat thermal state "nominal".

Sebelum optimasi M7, skenario berat di Metal lebih lambat dari GL. Metal System Trace menunjukkan GPU sebagian besar berada di *performance state* "Minimum": pekerjaan GPU yang terpecah (sekitar 5 pass clear terpisah, 9 render encoder, dan 3 blit encoder per frame) membuat governor tidak menaikkan clock.

Keputusan berbasis data:
- **Cache MSL ke disk tidak dibuat**: 204 pipeline hanya butuh ~290 ms total, terbagi ke beberapa thread.
- **`MTLResidencySet` ditunda**: residency set mengurangi overhead CPU/driver, sedangkan setelah M7 render thread menghabiskan 55–70% waktu frame menunggu GPU. FPS tidak akan naik dari situ.
- **Depth memoryless / store `DontCare` tidak dibuat**: renderpearl tidak memberi informasi masa hidup attachment, dan depth utama dimuat ulang oleh pass berikutnya. Eksperimen yang memaksa depth `DontCare` tidak menunjukkan peningkatan di atas noise (kemungkinan karena GPU Apple sudah mengompresi depth tanpa loss), sementara tebakan yang salah merusak gambar tanpa error.

| Screenshot Metal vs OpenGL | Piksel berbeda > 8 level | > 48 level |
|---|---|---|
| Dunia, dari atas (Sodium) | 0,21% | 0,00% |
| Dunia, malam (Sodium) | 0,00% | 0,00% |
| Dunia, jauh, RD 16 (Sodium) | 0,04% | 0,01% |
| Dunia, dari atas (tanpa Sodium) | 0,17% | 0,00% |
| Dunia, jauh, RD 16 (tanpa Sodium) | 0,06% | 0,02% |

Screenshot layar judul dan titik spawn bisa berbeda 0,5–2,4% karena waktu pengambilan: layar judul sedang fade-in dan Metal memuat lebih cepat, sementara di spawn rumput dan awan bergerak. Perbedaannya tidak terlihat mata.

Catatan pengukuran: FPS menghitung frame yang dirender. Saat vsync mati, backend Metal hanya mempresentasikan satu frame per refresh layar (frame lain tetap dirender penuh tapi tidak dikirim ke compositor), karena mempresentasikan semuanya membuat `nextDrawable` memblokir dan FPS turun ke ~60. Hasil yang terlihat di layar sama: layar memang tidak bisa menampilkan lebih dari refresh rate-nya.

## Berikutnya: instalasi mudah dan GUI MetalFX (M9–M12)

| Milestone | Isi |
|---|---|
| **M9 Instalasi** | Siap untuk Modrinth/CurseForge (metadata, ikon, deskripsi, dependensi: Fabric Loader wajib, Sodium disarankan, Fabric API tidak perlu), modpack `.mrpack` sekali klik untuk Modrinth App dan Prism Launcher, pesan jelas di game kalau Metal tidak bisa dipakai (Mac Intel, macOS lama) dan otomatis kembali ke OpenGL |
| **M10 Layar pengaturan** ✅ | Halaman "mine2metal → Metal" di Video Settings Sodium lewat `ConfigEntryPoint` (`sodium:config_api_user`), dan tombol "Metal Settings..." + layar sendiri di Video Settings vanilla kalau Sodium tidak terpasang. Opsi: Upscaling (Off / Spatial; Temporal tampil sebagai "menyusul"), Render scale 50–100% (langkah 5), Frame interpolation (tampil tapi nonaktif, "menyusul"). Bahasa Inggris dan Indonesia. Disimpan di `config/mine2metal.properties` dan langsung berlaku tanpa restart |
| **M11 Upscaling** (spatial ✅) | Dunia dan tangan dirender ke target lebih kecil (menukar `GameRenderer.mainRenderTarget` selama `renderLevel`), lalu diperbesar dengan `MTLFXSpatialScaler` (mode perceptual) ke target ukuran window sebelum post effect dan GUI. Berikutnya: temporal (`MTLFXTemporalScaler`), butuh jitter kamera, depth, dan motion vector |
| **M12 Frame interpolation** | `MTLFXFrameInterpolator` (macOS 26+). Encode ke `MTLCommandBuffer` biasa, jadi **tidak** perlu Metal 4. Butuh warna frame sekarang dan sebelumnya, depth, motion, tekstur UI terpisah, matriks kamera, dan present dua frame per frame yang dirender. Menambah latensi input, jadi opsional |

### Perbaikan selama M10–M11: antrean present terpisah

Performa yang kadang anjlok (~100–150 FPS) ternyata bukan pekerjaan GPU. Metal System Trace saat mode lambat menunjukkan GPU menganggur ~84% dan latensi CPU→GPU p50 12 ms, maksimum 16,4 ms (satu periode refresh 60 Hz), dibanding 0,8 ms saat cepat. Command buffer frame yang berisi pass present ke drawable harus menunggu di GPU sampai compositor melepas drawable itu, dan karena antreannya berurutan, semua frame berikutnya ikut menunggu.

Perbaikan: frame disalin ke salah satu dari dua tekstur staging di antrean render, lalu digambar ke drawable di **antrean present terpisah**, disinkronkan dengan dua `MTLEvent`. Saat vsync mati, frame baru dipresentasikan hanya setelah frame sebelumnya tampil di layar (`addPresentedHandler`), sehingga `nextDrawable` tidak pernah memblokir CPU (kemudian diganti thread present, lihat di bawah). Setelah itu 3 dari 3 run cepat: skenario ringan 1607–2513 FPS, berat 935–1113 FPS, waktu present 0,001 ms/frame.

Upscaling spasial di window gametest kecil (854×480, Retina 1708×960) justru lebih lambat (1075–1163 FPS vs 1607–2513 tanpa upscaling): di resolusi ini GPU tidak dibatasi jumlah piksel, jadi pass MetalFX hanya menambah kerja. Upscaling bermanfaat saat GPU sibuk dengan piksel (resolusi tinggi, shader berat); belum diukur di skenario seperti itu.

### Perbaikan setelah uji manual: thread present untuk vsync mati

Saat uji manual di window hampir 4K dengan vsync mati, game terasa patah-patah walau FPS tinggi. Statistik `presentedTime` menunjukkan hanya ~25–40 frame per detik yang benar-benar tampil di layar, dengan jeda p95 33,3 ms dan hampir separuh frame terlambat satu refresh. Saat GPU sibuk (~150 FPS), frame yang dipresentasikan dari thread render sering selesai sedikit setelah batas refresh, jadi tampilnya bergantian 16,7 dan 33,3 ms. Dengan vsync nyala semuanya mulus (gap 16,7 ms, 0 terlambat).

Perbaikan ("fast sync"): saat vsync mati, layer tetap `displaySyncEnabled`, tapi thread render tidak lagi mempresentasikan. Setiap frame disalin ke salah satu dari tiga slot, lalu ditandai sebagai frame terbaru setelah GPU selesai. Thread present terpisah (`mine2metal present`) menunggu `nextDrawable`, yang berdetak sesuai refresh layar, lalu selalu menampilkan frame terbaru yang sudah selesai. Game tetap merender tanpa batas tanpa pernah menunggu layar, dan layar mendapat satu frame baru di setiap refresh tanpa tearing. Thread dihentikan saat surface dikonfigurasi ulang atau ditutup. Di gametest (window terlihat, vsync mati, render ~1400 FPS) hasilnya 300 frame tampil per 5 detik, gap p50/p95 16,7 ms, 0 terlambat. Uji manual di MacBook Air M3 (window 1920×1243 Retina, Sodium, render distance 16, vsync mati): 153–203 FPS, 300 frame tampil per 5 detik, gap p50/p95 16,7 ms, 0 terlambat (sebelumnya ~200 tampil, p95 33,3 ms, ~90 terlambat). Saat vsync nyala, jalurnya tetap seperti sebelumnya.

## Fitur khas Metal (setelah M5)

| Fitur | API Apple | macOS | Input yang dibutuhkan | Kesulitan |
|---|---|---|---|---|
| Overlay performa ✅ | `CAMetalLayer.developerHUDProperties` | 13 | — | Rendah |
| Frame pacing / ProMotion | `present(afterMinimumDuration:)`, `displaySyncEnabled` | 10.15.4 | — | Rendah |
| **Upscaling spasial** | `MTLFXSpatialScaler` | 13 | Warna dunia saja (GUI dirender terpisah di resolusi native) | Sedang |
| **Upscaling temporal** | `MTLFXTemporalScaler` | 13 | Warna + depth + **motion vector** + jitter kamera | Tinggi |
| Upscaling temporal + denoise | `MTLFXTemporalDenoisedScaler` | 26 | Seperti temporal, plus buffer untuk ray tracing | Di luar lingkup |
| **Frame interpolation** | `MTLFXFrameInterpolator` | 26 | Warna, depth, motion, **tekstur UI terpisah**, frame sebelumnya | Sangat tinggi |
| HDR/EDR | `CAMetalLayer.wantsExtendedDynamicRangeContent` | 10.11 | Shader yang menghasilkan nilai > 1.0 | Tinggi (butuh shader sendiri) |

Catatan:
- Minecraft tidak menghasilkan motion vector. Untuk geometri statis, motion vector bisa dihitung dari depth + matriks kamera frame sebelumnya. Entitas, partikel, dan tekstur animasi butuh penanganan khusus, atau akan ghosting.
- `MTLFXFrameInterpolator` meng-encode ke `MTLCommandBuffer` biasa (`encode(commandBuffer:)`); varian `MTL4FXFrameInterpolator` hanya untuk jalur Metal 4. Jadi backend Metal 3 cukup, asalkan macOS 26+ (dicek di dokumentasi Apple, 2026-10-27).
- Frame interpolation menambah latensi input, yang terasa di game dengan gerakan kamera cepat seperti Minecraft. Fitur ini sebaiknya opsional.
