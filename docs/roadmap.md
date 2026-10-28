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
| **M10 Layar pengaturan** ✅ | Halaman "mine2metal → Metal" di Video Settings Sodium lewat `ConfigEntryPoint` (`sodium:config_api_user`), dan tombol "Metal Settings..." + layar sendiri di Video Settings vanilla kalau Sodium tidak terpasang. Opsi: Upscaling (Off / Spatial / Temporal, hanya mode yang didukung Mac ini), Render scale 50–100% (langkah 5), Frame interpolation (tampil tapi nonaktif, "menyusul"). Bahasa Inggris dan Indonesia. Disimpan di `config/mine2metal.properties` dan langsung berlaku tanpa restart |
| **M11 Upscaling** ✅ | Dunia dirender ke target lebih kecil (tekstur `GameRenderer.mainRenderTarget` dipinjamkan selama `renderLevel`), lalu diperbesar dengan MetalFX di awal `render3dHud`, sehingga tangan, efek layar, post effect, dan GUI digambar di resolusi penuh. **Spatial**: `MTLFXSpatialScaler` (mode perceptual). **Temporal**: `MTLFXTemporalScaler`; proyeksi dunia digeser subpiksel tiap frame (Halton 2,3; jumlah fase 8 × rasio², minimal 8) lewat `CameraRenderState.projectionMatrix`, jadi Sodium ikut tergeser; proyeksi akhir (dengan bobbing dan nausea) ditangkap di `ProjectionMatrixBuffer.getBuffer` lalu jitter-nya dibuang lagi. Motion vector dihitung pass sendiri dari depth: tiap piksel di-unproject dengan matriks frame ini dan di-project dengan matriks frame sebelumnya, ditambah perpindahan kamera (posisi relatif kamera, presisi double di CPU). Pass yang sama menyalin depth ke Depth32Float, karena depth game punya stencil. History di-reset saat pertama, ukuran berubah, atau kamera berpindah > 16 blok. Temporal 100% = anti-aliasing saja |
| **M12 Frame interpolation** ✅ | `MTLFXFrameInterpolator` (macOS 26+). Encode ke `MTLCommandBuffer` biasa, jadi **tidak** perlu Metal 4. Butuh warna frame sekarang dan sebelumnya, depth, motion, tekstur UI terpisah, matriks kamera, dan present dua frame per frame yang dirender. Menambah latensi input, jadi opsional |

### Perbaikan selama M10–M11: antrean present terpisah

Performa yang kadang anjlok (~100–150 FPS) ternyata bukan pekerjaan GPU. Metal System Trace saat mode lambat menunjukkan GPU menganggur ~84% dan latensi CPU→GPU p50 12 ms, maksimum 16,4 ms (satu periode refresh 60 Hz), dibanding 0,8 ms saat cepat. Command buffer frame yang berisi pass present ke drawable harus menunggu di GPU sampai compositor melepas drawable itu, dan karena antreannya berurutan, semua frame berikutnya ikut menunggu.

Perbaikan: frame disalin ke salah satu dari dua tekstur staging di antrean render, lalu digambar ke drawable di **antrean present terpisah**, disinkronkan dengan dua `MTLEvent`. Saat vsync mati, frame baru dipresentasikan hanya setelah frame sebelumnya tampil di layar (`addPresentedHandler`), sehingga `nextDrawable` tidak pernah memblokir CPU (kemudian diganti thread present, lihat di bawah). Setelah itu 3 dari 3 run cepat: skenario ringan 1607–2513 FPS, berat 935–1113 FPS, waktu present 0,001 ms/frame.

Upscaling spasial di window gametest kecil (854×480, Retina 1708×960) justru lebih lambat (1075–1163 FPS vs 1607–2513 tanpa upscaling): di resolusi ini GPU tidak dibatasi jumlah piksel, jadi pass MetalFX hanya menambah kerja. Upscaling bermanfaat saat GPU sibuk dengan piksel (resolusi tinggi, shader berat); belum diukur di skenario seperti itu.

### Hasil upscaling temporal (2026-10-28)

Gametest membandingkan screenshot dengan resolusi asli dari posisi dan arah kamera yang sama (PSNR, makin tinggi makin mirip; window 854×480):

| Mode | Diam | Tepat setelah putar 30° dalam 10 tick |
|---|---|---|
| Temporal 50% | 30,3–30,5 dB | 29,1–29,2 dB |
| Temporal 75% | 31,8 dB | — |
| Temporal 100% (hanya AA) | 34,6–34,8 dB | — |

- **Tanda jitter** untuk MetalFX dipilih dengan mengukur keempat kombinasi: (+1, +1) terbaik di dua run, (−1, −1) terburuk (~0,8 dB lebih rendah). Bisa diganti untuk eksperimen dengan `-Dmine2metal.temporal.jitterSign=x,y`.
- **Motion vector benar**: setelah kamera berputar, kualitas hanya turun ~1 dB. Sebagai pembanding, gambar asli sebelum vs sesudah putaran itu bedanya 18 dB, jadi history yang tidak ikut digeser akan terlihat jelas.
- Di window gametest yang kecil, temporal lebih lambat daripada render asli (MetalFX + pass motion vector lebih mahal daripada piksel yang dihemat), sama seperti spatial. Manfaatnya baru terasa di resolusi besar; belum diukur di window 4K.
- Belum ditangani: entity, partikel, dan tekstur animasi bergerak sendiri, tapi motion vector hanya tahu gerak kamera, jadi bisa meninggalkan jejak samar. Bisa dikurangi nanti dengan reactive mask.

### Perbaikan setelah uji manual: thread present untuk vsync mati

Saat uji manual di window hampir 4K dengan vsync mati, game terasa patah-patah walau FPS tinggi. Statistik `presentedTime` menunjukkan hanya ~25–40 frame per detik yang benar-benar tampil di layar, dengan jeda p95 33,3 ms dan hampir separuh frame terlambat satu refresh. Saat GPU sibuk (~150 FPS), frame yang dipresentasikan dari thread render sering selesai sedikit setelah batas refresh, jadi tampilnya bergantian 16,7 dan 33,3 ms. Dengan vsync nyala semuanya mulus (gap 16,7 ms, 0 terlambat).

Perbaikan ("fast sync"): saat vsync mati, layer tetap `displaySyncEnabled`, tapi thread render tidak lagi mempresentasikan. Setiap frame disalin ke salah satu dari tiga slot, lalu ditandai sebagai frame terbaru setelah GPU selesai. Thread present terpisah (`mine2metal present`) menunggu `nextDrawable`, yang berdetak sesuai refresh layar, lalu selalu menampilkan frame terbaru yang sudah selesai. Game tetap merender tanpa batas tanpa pernah menunggu layar, dan layar mendapat satu frame baru di setiap refresh tanpa tearing. Thread dihentikan saat surface dikonfigurasi ulang atau ditutup. Di gametest (window terlihat, vsync mati, render ~1400 FPS) hasilnya 300 frame tampil per 5 detik, gap p50/p95 16,7 ms, 0 terlambat. Uji manual di MacBook Air M3 (window 1920×1243 Retina, Sodium, render distance 16, vsync mati): 153–203 FPS, 300 frame tampil per 5 detik, gap p50/p95 16,7 ms, 0 terlambat (sebelumnya ~200 tampil, p95 33,3 ms, ~90 terlambat). Saat vsync nyala, jalurnya tetap seperti sebelumnya.

### Satu jalur present (2026-10-28)

Presentasi sebelumnya punya tiga jalur: VSync nyala dipresentasikan dari thread render (dua tekstur staging, event `frameReady`/`presentDone`, antrean present), VSync mati lewat thread present, dan interpolasi lewat thread present yang dinyalakan khusus. Dua bug interpolasi berasal dari interaksi jalur-jalur ini. Sekarang **semua frame lewat thread present**:
- Thread present berjalan selama layer dipasang, dan menampilkan frame terbaru yang sudah selesai sekali per refresh (frame buatan dulu kalau ada).
- Bedanya hanya seberapa jauh thread render boleh mendahului. VSync mati: tidak pernah menunggu, frame dilewati kalau slotnya belum bebas (game tanpa batas, layar tetap dapat frame baru tiap refresh tanpa tearing). VSync nyala atau interpolasi: menunggu sampai thread present mengambil frame sebelumnya, jadi setiap frame tampil dan game mengikuti refresh rate (atau separuhnya dengan interpolasi). Batas tunggu 250 ms supaya layar yang macet (window tersembunyi) tidak menahan game.
- Dihapus: `m2m_present_synced`, tekstur staging, event `frameReady`/`presentDone`, dan flag thread khusus interpolasi. Thread render tidak pernah memegang drawable.

Hasil: gametest penuh lolos di bawah validation (Metal 4 dan Metal 3), termasuk fullscreen, resize, upscaling, interpolasi (31,2 dB), dan HDR. VSync mati tetap tanpa batas (~2700 FPS, present 0,005 ms per frame). Pacing VSync nyala di layar sungguhan belum terukur karena jendela tes tidak terlihat (macOS melaporkan semua frame sebagai tidak tampil), jadi perlu dicek manual: uji-manual nomor 9.

### R2: present lewat `CAMetalDisplayLink` (2026-10-28)

Thread present tidak lagi memanggil `nextDrawable` dalam loop. Thread itu menjalankan run loop dengan `CAMetalDisplayLink` (macOS 14+, `preferredFrameLatency` 1), yang memanggil `M2MPresenter` sekali per refresh dengan drawable siap:
- **Tanpa frame gen:** frame terbaru yang sudah selesai ditampilkan, kalau ada yang baru.
- **Dengan frame gen:** frame buatan ditampilkan dulu dan ditahan kira-kira setengah interval frame asli (dalam refresh, dibulatkan, 1–4), baru frame aslinya. Contoh: game 15 FPS di 60 Hz kini tampil 2 + 2 refresh, sebelumnya 1 + 3. Interval diukur dari waktu frame selesai di GPU, yang tidak ikut memanjang karena menunggu layar, jadi tidak ada putaran balik seperti pacing pertama di M12.
- **Jendela tertutup:** macOS menghentikan display link untuk jendela yang tertutup penuh. Kalau tidak ada callback selama 100 ms, thread present masuk mode idle. Frame baru langsung dianggap diambil (tidak ditampilkan), dan thread render hanya menunggu salinan GPU, supaya riwayat frame gen tidak putus. Tanpa ini, game dengan VSync nyala atau frame gen jatuh ke ~4 FPS selama jendela tertutup (terlihat di gametest).

Hasil gametest di jendela yang terlihat (MacBook Air M3, 60 Hz):

| Mode | Frame tampil / 5 s | Jarak p50 / p95 | Terlambat | CPU per frame |
|---|---|---|---|---|
| VSync mati | 299–300 | 16,7 / 16,7 ms | 0–1 | 0,4 ms (tanpa batas) |
| VSync nyala | 301 | 16,7 / 16,7 ms | 0 | 16,66 ms |
| Frame gen MetalFX | 300–302 | 16,7 / 16,7 ms | 0 | 33,3 ms (30 asli + 30 buatan) |

Kualitas frame buatan tidak berubah (FSR 31,21 dB, MetalFX 31,20 dB), dan gametest penuh lolos di bawah validation. Belum diukur: game di bawah 30 FPS dengan frame gen (penahanan > 1 refresh) dan layar ProMotion.

### R5: reactive mask untuk upscaling temporal (2026-10-28)

MetalFX temporal (macOS 14.4+) sekarang menerima reactive mask, yaitu tanda di piksel yang riwayatnya tidak bisa dipercaya sehingga lebih bersandar ke frame sekarang. Mask dihitung di pass motion vector yang sudah ada, tanpa pass tambahan:
- Permukaan di setiap piksel dilacak ke frame sebelumnya dengan gerak kamera, lalu dibandingkan dengan depth frame sebelumnya di sekitar titik itu (tetangga 3×3, yang terdekat dipakai).
- Kalau tidak ada yang cukup dekat, permukaan itu bergerak sendiri (entitas, partikel) atau baru terlihat, dan piksel itu ditandai.
- "Cukup dekat" diukur dalam piksel di kedalaman itu, ditambah jarak antar-sampel untuk permukaan yang miring tajam. Yang membuat jejak adalah gerak beberapa piksel per frame, sejauh apa pun bendanya. Ambang relatif terhadap jarak (versi pertama) tidak pernah menyala di 60 fps.
- Depth frame sebelumnya disimpan bergantian di dua tekstur. Setelah reset atau scaler dibuat ulang, mask kosong untuk satu frame.

Matriks kamera bertambah menjadi 52 float: invers view-projection frame sebelumnya, ditambah tanda ada-tidaknya frame sebelumnya. `-Dmine2metal.temporal.reactiveMask=false` mematikan mask (bawaan nyala).

Uji (`-Dmine2metal.test.reactiveOnly=true`, 60 fps, render 427×240 → 854×480):

| Kasus | Native | Temporal + mask | Temporal tanpa mask |
|---|---|---|---|
| Blok emas bergerak 3 blok/tick (~7 px/frame), PSNR area blok vs native | — | 29,30 dB | 29,14 dB |
| Partikel end_rod, cahaya yang ditambahkan ke frame (run 1) | 264k | 255k | 132k |
| Partikel end_rod, piksel terang > 200 (run 1 / run 2) | 1394 / 1760 | 211 / 536 | 0 / 300 |

- **Entitas opak yang bergerak:** MetalFX sudah menanganinya sendiri lewat clamping warna. Jejaknya tidak terlihat dengan maupun tanpa mask, dan selisihnya masih dalam noise.
- **Partikel** adalah manfaat utamanya. Partikel Minecraft menulis depth, jadi mask menangkapnya. Tanpa mask partikel pudar hampir hilang; dengan mask cahayanya tetap seperti native.
- **Noise:** jitter membuat ~0,4% piksel ikut ditandai di tepi daun (cutout).
- **Regresi:** tidak ada. Uji temporal tetap 30,49 dB diam dan 29,05 dB setelah berputar. Gametest penuh lolos di bawah validation.

Yang tidak tertangkap: benda yang tidak menulis depth (sebagian translucency) dan tekstur animasi seperti air dan lava. Untuk itu perlu menandai piksel saat draw translucent dirender, yang merupakan perubahan desain lebih besar.

### R4: waktu GPU per tahap dan pengaman frame gen (2026-10-28)

**Waktu GPU per tahap.** Dengan `-Dmine2metal.stats=true` (tanpa `passTimes`), frame dipecah oleh penanda tahap, yaitu `MetalRenderer.markStage` di awal `render3dHud` dan di present:

> dunia → upscaling → tangkapan dunia → tangan, efek dan GUI → present → frame gen

Pekerjaan setiap tahap di-commit di command buffer berlabel, dan log mencatat `GPU ms/frame by stage`. Contoh (2560×1600, jendela tertutup): dunia 0,26, tangan/efek/GUI 0,53, present 0,06, frame gen 1,2 ms per frame. Commit tambahan hanya terjadi kalau statistik nyala.

**Biaya frame gen selalu diukur.** Selama frame gen aktif, pekerjaannya (MetalFX maupun FSR) dijalankan di command buffer sendiri, cukup dua commit tambahan per frame. Waktunya dirata-rata (`m2m_frame_generation_gpu_millis`).

**Pengaman.** Frame gen hanya menguntungkan kalau biayanya kurang dari separuh jarak antar frame render: dua gambar dalam T mengalahkan satu gambar dalam T − biaya tepat ketika T > 2 × biaya.
- Kalau syarat itu terlanggar terus selama 3 detik, frame gen dijeda 30 detik lalu dicoba lagi. State native-nya tidak dilepas, jadi tidak perlu kompilasi ulang.
- Jeda pertama memunculkan toast "Pembuatan frame dijeda" beserta angkanya; jeda berikutnya hanya dicatat di log.
- Mengganti pilihan engine membatalkan jeda. `-Dmine2metal.frameGenerationGuard=false` mematikan pengaman; tes kualitas memakainya karena jendela tes yang tertutup render tanpa batas.

Hasil uji: di 2560×1600, MetalFX (12,2 ms biaya vs 13,8 ms jarak) dan FSR (22,5 vs 25,0 ms) dijeda dengan benar. Gametest penuh lolos di bawah validation, kualitas frame buatan tetap 31,2 dB.

Temuan yang perlu diverifikasi di layar sungguhan: di jendela gametest yang tertutup, upscaling temporal 2560×1600 tercatat ~13 ms per frame, padahal benchmark mandiri 2,1 ms. Kemungkinan macOS memberi GPU prioritas rendah ke aplikasi yang tidak terlihat. Itu juga bisa menjelaskan kenapa biaya MetalFX frame gen di gametest dulu berubah-ubah 10–90 ms.

### R3: lonjakan frame saat chunk dimuat (2026-10-28)

Alat ukur baru yang berlaku untuk semua backend: mixin di mod gametest mencatat setiap `DebugScreenOverlay.logFrameDuration`. Mode `-Dmine2metal.test.chunkSpikes=true`: 1920×1080, render distance 16, VSync mati, berdiri 20 detik lalu berjalan 40 detik ke arah hutan (chunk baru terus dimuat). Untuk OpenGL/Vulkan dijalankan dengan `-Dmine2metal.enabled=false` dan `preferredGraphicsBackend` di `options.txt`.

| Backend (berjalan, chunk dimuat) | FPS | p50 / p99 | Maks | Frame > 50 ms |
|---|---|---|---|---|
| Metal 4 (3 run) | 2075–2324 | 0,38–0,41 / 1,47–2,12 ms | 24–48 ms | 0 |
| Metal 3 (2 run) | 1560–2023 | 0,43–0,56 / 2,05–2,30 ms | 29–88 ms | 0 dan 2 (run kedua, Mac sudah panas) |
| OpenGL | 294 | 2,67 / 7,10 ms | 11 ms | 0 |
| Vulkan | tidak tersedia di Mac ini; Minecraft kembali ke OpenGL | | | |

- **Pemuatan chunk tidak menimbulkan lonjakan dari backend kita.** Lonjakan 200–600 ms yang terlihat di pengukuran sebelumnya muncul saat pengaturan diganti di tengah tes (pembuatan scaler/interpolator MetalFX atau konteks FSR, yang mengompilasi shader).
- **Sisa frame lambat** muncul jarang (0–4 per menit, 50–380 ms, kebanyakan saat berdiri), di Metal 3 maupun Metal 4. Penyebabnya bukan pembuatan pipeline state: sekarang dicatat ke log kalau ≥ 20 ms, dan tidak ada yang muncul. Satu kasus dicocokkan dengan log GC dan tidak bertepatan dengan jeda GC. Jeda GC sendiri 15–60 ms tiap ~3 detik di 2000+ FPS.
- **Alokasi memori (Java Flight Recorder, 60 detik):** sekitar 5,4 GB sampah per menit, 82% di thread render, tapi backend kita sendiri hanya 3,7%. Sisanya Minecraft (matriks, `BlockPos`, `PoseStack`, generator dunia server internal), renderpearl, dan Sodium. Di game dengan batas FPS biasa (60–120), sampahnya puluhan kali lebih sedikit.
- Yang tersisa untuk nanti: pembuatan objek MetalFX dan konteks FSR saat pengaturan diganti masih di thread render (sekali per ganti; FSR pertama kali ~3 detik). Bisa dipindah ke thread latar.

## M13 Metal 4 (2026-10-28)

Satu kode native dengan dua model perintah. Default **Otomatis**: Metal 4 kalau `MTLGPUFamilyMetal4` didukung dan macOS ≥ 26, selain itu Metal 3. Bisa dipaksa di menu Metal → API Grafis → Versi Metal (berlaku setelah restart), di `metalVersion=` pada `config/mine2metal.properties`, atau `-Dmine2metal.metal=auto|metal4|metal3`. Kalau Metal 4 gagal dibuat, otomatis kembali ke Metal 3. Vulkan/OpenGL tetap lewat Graphics API vanilla.

| Bagian | Metal 3 | Metal 4 |
|---|---|---|
| Antrean & command buffer | `MTLCommandQueue`, 1 command buffer per submit | `MTL4CommandQueue`, command buffer dari `MTL4CommandAllocator` yang kembali ke pool lewat commit feedback |
| Residency | otomatis | satu `MTLResidencySet` untuk semua buffer/tekstur (ditambah saat dibuat, dilepas lewat `m2m_resource_release`; tulisan dikunci karena resource juga dibuat dari thread worker); drawable lewat `CAMetalLayer.residencySet` |
| Binding | `setVertexBuffer`/`setFragmentTexture`/`setBytes` | satu `MTL4ArgumentTable` dipakai stage vertex dan fragment (alamat GPU, `gpuResourceID`); konstanta kecil lewat ring buffer upload 1 MB; binding ditangkap per draw (dicek dengan prototipe) |
| Hazard | dilacak otomatis | barrier penuh di awal dan akhir setiap encoder (konservatif, setara serialisasi Metal 3); barrier antar salinan dalam satu encoder |
| Salin/blit | `MTLBlitCommandEncoder` | `MTL4ComputeCommandEncoder` (selector sama) |
| Present | `presentDrawable`, `encodeWaitForEvent` | `waitForDrawable`/`signalDrawable` dan event di level antrean |
| MetalFX | `MTLFXSpatialScaler`/`MTLFXTemporalScaler` | `MTL4FX…` dibuat dengan `MTL4Compiler` |
| Umur resource | command buffer menahannya | command buffer Metal 4 **tidak** menahan resource; tekstur internal yang diganti disimpan sampai 3 submit kemudian (`m2m_retire`) |
| Pipeline | `MTLRenderPipelineState` dari `MTLDevice` | sama (terbukti bisa dipakai di encoder Metal 4) |

Debug: `MINE2METAL_TRACE=1` mencatat setiap commit, wait/signal antrean, dan completion. Bug pertama yang ketemu: indeks tunggu negatif dari Java (`recordingIndex - 3` di awal) jadi angka raksasa di `uint64` dan menunggu selamanya; sekarang dibatasi ke submit yang sudah di-commit.

Hasil (gametest, Apple M3, macOS 27):
- Seluruh gametest lolos di bawah Metal API validation dengan Metal 4, tanpa error validasi.
- Screenshot Metal 4 vs Metal 3: judul 0,00% piksel beda; dunia 0,07–0,26% (animasi awan/air); temporal 0,7–0,8% (history MetalFX).
- Adegan berat (1920×1080, render distance 16, Sodium): waktu CPU per frame p50 **~0,75 ms** (Metal 4) vs ~1,6 ms (Metal 3), FPS 928 vs 413. Ini manfaat utama Metal 4: biaya CPU per draw lebih kecil tanpa pelacakan hazard.
- MacBook Air tanpa kipas: run kedua selalu lebih lambat karena panas (urutan dibalik, hasil temporal ikut terbalik: 1038 vs 521 FPS). Perbandingan harus bergantian urutan; angka FPS tunggal di sini belum bisa dipakai sebagai klaim.
- Belum dioptimalkan: barrier penuh di tiap encoder bisa dipersempit (misalnya render→render hanya fragment→fragment).

## M12 Frame interpolation MetalFX (2026-10-28)

Opsi **Interpolasi Frame** di menu Metal (macOS 26+, berlaku langsung). Untuk setiap frame yang dirender, MetalFX membuat satu frame di tengah antara frame itu dan frame sebelumnya, lalu keduanya ditampilkan: frame buatan dulu, baru frame asli.

Alurnya:
- **Tangkap dunia** di awal `render3dHud`, setelah upscaling dan sebelum tangan, efek layar, dan GUI. Warna dunia disalin ke salah satu dari dua tekstur bergantian (frame ini dan frame sebelumnya). Motion vector dan depth Depth32Float dibuat oleh pass yang sama dengan upscaling temporal (`m2m_encode_motion`), dari depth yang dipakai dunia. Saat upscaling, depth itu berukuran kecil, dan MetalFX menerimanya sebagai input yang lebih kecil dari output.
- **Data kamera:** near/far dibaca dari matriks proyeksi (device melaporkan depth zero-to-one, jadi `m22 = f/(n−f)` dan `m32 = nf/(n−f)`), FOV vertikal dari `m11`, aspek dari `m11/m00`, jitter temporal, dan selisih waktu antar tangkapan.
- **Lapisan UI:** frame jadi (dunia + tangan + GUI) dipakai sebagai tekstur UI *composited*. MetalFX memisahkan apa yang digambar di atas dunia lalu menempelkannya tanpa digeser, jadi hotbar, crosshair, teks, dan tangan tidak ikut melengkung.
- **Present lewat thread present:** interpolasi di-encode di command buffer frame itu sendiri, setelah frame disalin ke salah satu dari tiga slot thread present (satu tekstur frame buatan per slot). Thread present (yang juga dipakai saat VSync mati) mengambil frame terbaru yang sudah selesai, menggambar frame buatan ke satu drawable, lalu frame asli ke drawable berikutnya, sehingga keduanya tampil di refresh berturut-turut. Thread render tidak pernah menunggu drawable. Ia hanya menunggu kalau frame sebelumnya belum diambil thread present, karena tiap frame makan dua refresh dan frame buatan yang tidak sempat tampil cuma membuang waktu GPU. Hasilnya: game ≤ setengah refresh rate (30 + 30 di layar 60 Hz), dengan VSync nyala maupun mati. Dengan VSync nyala, thread ini hanya berjalan selama interpolasi aktif.
- **Riwayat perbaikan (setelah uji manual):** versi pertama mempresentasikan kedua gambar dari thread render dan mengatur jeda dengan `present(afterMinimumDuration:)`. Ada dua masalah. (1) Jeda itu dihitung dari interval frame, padahal interval ikut membesar karena present tertahan. Akibatnya satu frame yang tersendat (>250 ms, sering saat chunk dimuat) membuat setiap gambar ditahan ~6 refresh lama sekali, dan game jatuh ke 6–12 FPS. Jeda itu sudah dihapus. (2) Mengambil dua drawable per frame dari thread render membuat CPU, GPU, dan layar bekerja berurutan (~36 ms per frame tertahan di present). Itu sebabnya present dipindah ke thread present.

Hasil gametest (Apple M3, macOS 27, window 854×480): kamera berputar dari yaw 0° ke 4° di antara dua frame, lalu frame buatan dibaca balik dan dibandingkan dengan frame asli di yaw 2° (PSNR, makin tinggi makin mirip):

| Gambar vs frame asli di yaw 2° | PSNR |
|---|---|
| Frame asli yaw 0° / 4° (pembanding) | 21,8 / 21,6 dB |
| Interpolasi, tanpa upscaling (Metal 4 / Metal 3) | **31,3 / 31,4 dB** |
| Interpolasi, motion vector dibalik (−1) | 24,7 dB |
| Interpolasi + temporal 50% (skala motion 1 / 2) | **29,8** / 28,1 dB |

- Skala motion vector 1 (dalam piksel depth) yang benar, juga saat depth lebih kecil dari output: MetalFX menskalakan sendiri. Bisa diubah untuk eksperimen dengan `-Dmine2metal.interpolation.motionScale=x,y`.
- Hotbar, crosshair, dan lengan utuh di frame buatan (dicek visual).
- Lolos tanpa error di bawah validation, Metal 4 dan Metal 3. Upscaling temporal tidak berubah setelah pass motion dipakai bersama (30,6 / 29,0 dB).
- Di window gametest yang terlihat, FPS render turun ke 29–30 (layar 60 Hz), sesuai rancangan. Pacing di layar sungguhan belum diukur: di gametest macOS melaporkan semua `presentedTime` sebagai 0, bahkan tanpa interpolasi.
- Sama seperti temporal, motion vector hanya tahu gerak kamera, jadi mob, partikel, dan air yang bergerak bisa sedikit goyah di frame buatan.

**Biaya (Apple M3, MacBook Air):** interpolator MetalFX sendiri mahal di GPU ini, dan biayanya naik sebanding jumlah piksel. Diukur dengan command buffer tersendiri untuk interpolator. Angkanya sangat bervariasi antar run (panas, clock GPU), jadi yang dicantumkan rentangnya:

| Output | Input depth/motion | Waktu GPU interpolator |
|---|---|---|
| 1920×1080 | 1920×1080 | ~10–40 ms |
| 1920×1080 | 1440×810 (spatial 75%) | ~8–33 ms |
| 1920×1080 | 960×540 (temporal 50%) | ~6–19 ms |
| 2560×1600 | 2560×1600 / 1920×1200 | ~47–54 ms |

Gametest di darat, jalan menghadap hutan, render distance 16, 1920×1080 (FPS render; jendela tersembunyi, jadi tidak dibatasi refresh):

| Mode | Present dari thread render (versi pertama) | Thread present |
|---|---|---|
| Interpolasi | 16 | 23 |
| + Spatial 75% | 24–26 | 33 |
| + Temporal 50% | 40 | 50 |
| Mati | 479–1220 | |

Interpolasi hanya menguntungkan kalau GPU punya ruang sisa (game dibatasi CPU) dan resolusinya tidak besar. Di layar Retina penuh pada M3 Air, biaya interpolator sendiri sudah melebihi anggaran 33 ms untuk 30 FPS asli. Paling masuk akal dipakai bersama upscaling temporal (input lebih kecil) atau di Mac dengan GPU lebih besar. Tooltip opsinya menyebutkan ini.

## M14 HDR/EDR (2026-10-28)

Opsi **HDR** dan **Kecerahan HDR** (125–400% putih SDR, bawaan 200%) di menu Metal, berlaku langsung. Hanya bisa dipilih kalau layar utama punya EDR (`maximumPotentialExtendedDynamicRangeColorComponentValue > 1`). Tooltip-nya menunjukkan headroom layar saat ini.

Minecraft dan Sodium hanya menggambar SDR. HDR "asli" berarti menulis ulang shader pencahayaan keduanya, jadi pendekatan yang dipakai adalah **ekspansi highlight**: bagian dunia yang paling terang dinaikkan di atas putih SDR.
- **Layer:** saat HDR nyala, layer memakai `RGBA16Float`, `kCGColorSpaceExtendedLinearSRGB`, dan `wantsExtendedDynamicRangeContent`. Nilai 1,0 = putih SDR. Dipasang ulang (thread present dihentikan dulu) setiap kali opsinya berubah.
- **Kurva:** dihitung dari channel terkuat warna terenkode. Di bawah *knee* 0,75 warnanya tetap. Di atasnya, warna linear dikali `1 + (puncak − 1)·t²`, jadi naik halus dari ×1 di knee sampai ×puncak di putih penuh. Puncaknya adalah pilihan pemain, dibatasi headroom layar saat itu (`maximumExtendedDynamicRangeColorComponentValue`, dibaca ulang tiap 30 present, berubah mengikuti slider kecerahan).
- **GUI dan tangan tetap SDR:** dunia ditangkap di titik yang sama dengan interpolasi (awal `render3dHud`). Saat present, frame jadi dibandingkan per piksel dengan dunia itu, dan ekspansi hanya dipakai di piksel yang masih sama (transisi lembut 1,5–6/255). Hotbar putih, teks chat, dan crosshair tidak ikut menyilaukan. Overlay yang menggelapkan seluruh layar (menu pause, vignette di tepi) juga membuat bagian yang ditutupinya tetap SDR.
- **Present:** salinan staging diganti pass "compose" yang menulis frame extended linear ke tekstur `RGBA16Float`. Drawable float memakai pipeline present kedua. Berlaku di jalur VSync nyala dan thread present VSync mati.
- **Dengan interpolasi frame:** history dunia disimpan dalam float yang sudah diekspansi, dan frame compose menjadi tekstur UI-nya. Semua input dan output MetalFX pun ada di ruang extended linear yang sama, dan di bagian tanpa GUI frame compose sama persis dengan dunia yang diekspansi.
- **Screenshot (F2)** tetap SDR karena dibaca dari render target game, bukan dari drawable.

Hasil gametest (Apple M3, macOS 27, puncak 300%, headroom layar diabaikan lewat `-Dmine2metal.hdr.ignoreDisplayHeadroom=true` supaya hasilnya tidak bergantung pada kecerahan):
- Frame compose dibaca balik. Dunia paling terang 1,72× putih SDR (kabut terang di cakrawala, ~5,5% piksel di atas 1,0), **hotbar tetap 1,0**. Hasilnya sama dengan temporal 50% + interpolasi.
- Lolos tanpa error di bawah validation, Metal 4 dan Metal 3. Interpolasi dan temporal tidak berubah (31,4 / 30,6 dB).
- Belum dilihat di layar sungguhan. MacBook Air hanya punya headroom kalau slider kecerahan di bawah maksimum.
- Keterbatasan: yang dinaikkan semua yang sangat terang, termasuk salju dan awan di siang hari, bukan hanya sumber cahaya. Minecraft tidak memberi informasi blok mana yang memancarkan cahaya.

## Roadmap lanjutan (disusun 2026-10-28)

Sudah selesai: backend inti (M0–M8), layar pengaturan (M10), upscaling MetalFX (M11), interpolasi frame MetalFX (M12), Metal 4 (M13), HDR/EDR (M14), satu jalur present, dan spike FSR 3 (F0, lihat [fsr-frame-gen.md](fsr-frame-gen.md)). Uji manual Metal 4, interpolasi, HDR, dan VSync nyala di layar sungguhan masih menunggu ([uji-manual.md](uji-manual.md) nomor 9, 17, 22–31).

Urutan di bawah adalah prioritas. Instalasi (M9) tetap terakhir, sesuai kesepakatan.

| # | Pekerjaan | Isi | Gerbang selesai |
|---|---|---|---|
| R1 ✅ | **FSR 3.1 frame gen di game** (F1 + F2; hasil di [fsr-frame-gen.md](fsr-frame-gen.md#hasil-r1-fsr-31-frame-generation-di-game-2026-10-28)) | Backend FFX masuk dylib (kode AMD C++ + `ffx_metal.mm`, MSL di jar, dikompilasi di thread latar + cache). Jalur Metal 4: encoder compute MTL4, argument table, barrier antar pass, residency. Input: W tanpa HUD, F, depth, motion vector (skala dan tanda dikalibrasi dengan uji PSNR yaw), kamera; HDR lewat transfer scRGB. Output ke slot thread present. Menu: Frame gen Mati / MetalFX / FSR 3.1 (satu saja) | ≥ 31 dB di uji yaw; lebih cepat dari MetalFX di uji jalan-ke-hutan; validation bersih di Metal 3 dan 4 |
| R2 ✅ | **Pacing dengan `CAMetalDisplayLink`** (hasil di bagian "R2" di bawah) | Thread present memakai display link (macOS 14+). Frame buatan diletakkan di tengah dua frame asli menurut `targetPresentationTimestamp`, dan latensi diatur lewat `preferredFrameLatency`. Sekalian ukur VSync nyala di layar sungguhan | Jarak antar frame tampil rata (p95 ≈ 1 refresh) dengan frame gen nyala |
| R3 ✅ | **Lonjakan CPU saat chunk dimuat** (hasil di bagian "R3" di bawah: bukan dari backend kita) | Tes jalan-ke-hutan yang sama di backend OpenGL/Vulkan bawaan sebagai pembanding (sekarang frame bisa tertahan 200–600 ms). Kalau dari backend kita, telusuri (upload buffer, kompilasi pipeline, sinkronisasi) | Penyebab diketahui; kalau dari kita, lonjakan ≤ backend bawaan |
| R4 ✅ | **Waktu GPU per tahap + pengaman frame gen** (hasil di bagian "R4" di bawah) | Timestamp di batas encoder (counter sample buffer) untuk dunia, upscaling, frame gen, HDR, present. Dari situ frame gen mati sendiri (dengan pesan) kalau biayanya melebihi anggaran | Statistik per tahap di `-Dmine2metal.stats`; frame gen tidak pernah menurunkan FPS di bawah separuh tanpa memberi tahu |
| R5 ✅ | **Reactive mask untuk upscaling temporal** (hasil di bagian "R5" di bawah) | `reactiveMaskTexture` MetalFX (macOS 14.4+): tandai piksel mob, partikel, dan air yang bergerak sendiri, supaya tidak meninggalkan jejak | Jejak samar di uji mob bergerak berkurang (diukur dengan screenshot) |
| R6 | **Optimasi shader FSR** (F3) | FP16 manual untuk pass interpolasi utama dan optical flow, disocclusion di render penuh, dispatch concurrent untuk pass kecil | ≤ 6 ms di 1080p dengan render 50% (sekarang 8,7 ms) |
| R7 | **Upscaler FSR sebagai pilihan** | FSR 1 dan FSR 3.1 di menu Upscaling di samping MetalFX. Sudah terbukti jalan, tapi 2–2,5× lebih berat; nilainya sebagai pilihan dan perbandingan kualitas | Kualitas dibandingkan dengan MetalFX di uji temporal; lolos validation |
| R8 | **Rapikan arsitektur** | Satu objek alur "frame dunia" (tangkap/proyeksi/depth/jitter) menggantikan state statis di `WorldUpscaler`, `FrameInterpolation`, `HdrOutput`; paket di luar `upscale`; barrier Metal 4 dipersempit | Gametest penuh tetap lolos; tidak ada perubahan perilaku |
| R10 | **Upgrade ke FSR 3.1.6** (SDK v2.3.0, MIT) | Kode FSR 3 di SDK 2.x ternyata MIT (lihat ralat di fsr-frame-gen.md), dengan frame interpolation 3.1.6 dan upscaler 3.1.5. Shadernya hanya HLSL, jadi terjemahan beralih ke `dxc` → SPIR-V → spirv-cross; host code dan backend `ffx_metal` tetap | Kualitas uji yaw ≥ v1.1.4; validation bersih |
| R9 | **Instalasi (M9)** | Seperti di atas: rilis Modrinth/CurseForge, `.mrpack`, pesan jelas di Mac yang tidak didukung | — |

## Fitur khas Metal (setelah M5)

| Fitur | API Apple | macOS | Input yang dibutuhkan | Kesulitan |
|---|---|---|---|---|
| Overlay performa ✅ | `CAMetalLayer.developerHUDProperties` | 13 | — | Rendah |
| Frame pacing / ProMotion | `present(afterMinimumDuration:)`, `displaySyncEnabled` | 10.15.4 | — | Rendah |
| **Upscaling spasial** | `MTLFXSpatialScaler` | 13 | Warna dunia saja (GUI dirender terpisah di resolusi native) | Sedang |
| **Upscaling temporal** ✅ | `MTLFXTemporalScaler` | 13 | Warna + depth + **motion vector** + jitter kamera | Tinggi |
| Upscaling temporal + denoise | `MTLFXTemporalDenoisedScaler` | 26 | Seperti temporal, plus buffer untuk ray tracing | Di luar lingkup |
| **Frame interpolation** ✅ | `MTLFXFrameInterpolator` | 26 | Warna, depth, motion, **tekstur UI terpisah**, frame sebelumnya | Sangat tinggi |
| HDR/EDR ✅ (ekspansi highlight) | `CAMetalLayer.wantsExtendedDynamicRangeContent` | 10.11 | Shader yang menghasilkan nilai > 1.0 | Tinggi (butuh shader sendiri) |

Catatan:
- Minecraft tidak menghasilkan motion vector. Untuk geometri statis, motion vector bisa dihitung dari depth + matriks kamera frame sebelumnya. Entitas, partikel, dan tekstur animasi butuh penanganan khusus, atau akan ghosting.
- `MTLFXFrameInterpolator` meng-encode ke `MTLCommandBuffer` biasa (`encode(commandBuffer:)`); varian `MTL4FXFrameInterpolator` hanya untuk jalur Metal 4. Jadi backend Metal 3 cukup, asalkan macOS 26+ (dicek di dokumentasi Apple, 2026-10-27).
- Frame interpolation menambah latensi input, yang terasa di game dengan gerakan kamera cepat seperti Minecraft. Fitur ini sebaiknya opsional.
