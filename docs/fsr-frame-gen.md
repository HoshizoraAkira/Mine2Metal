# Rancangan: FSR 3 frame generation di atas Metal

Status: FSR 3.1 frame generation ada di game (R1, 2026-10-28); lihat "Hasil R1" di bawah. F0 (spike) dan riset upscaler juga di bawah.

## Kenapa

Interpolasi frame MetalFX berjalan, tapi interpolatornya sendiri mahal di GPU MacBook Air M3: ~10–40 ms per frame di 1080p dan ~50 ms di 2560×1600 (lihat roadmap M12). Di resolusi Retina, biaya itu saja sudah melebihi anggaran 30 FPS asli. Generator frame FSR 3 dari AMD terdiri dari compute shader biasa: optical flow dengan blok kasar, lalu interpolasi dan inpainting. Shader-shader itu dirancang untuk GPU konsol dan PC kelas menengah. Kalau biayanya di M3 jauh di bawah MetalFX, interpolasi jadi benar-benar berguna di Mac ini. **Itu yang harus dibuktikan dulu (fase F0).**

## Sumber kode: FidelityFX SDK v1.1.4 (MIT, dengan shader GLSL)

- **v1.1.4 (Mei 2025): lisensi MIT.** Ada backend Vulkan lengkap dengan shader **GLSL**, kode host C++ untuk optical flow dan frame interpolation, serta antarmuka backend (`FfxInterface`) yang bisa kita implementasikan untuk Metal.
- **v2.x (2.0–2.3, 2025–2026): kode FSR 3 juga MIT.** Ralat 2026-10-28: rancangan awal menyebut v2.x tidak bisa dipakai, karena paragraf pembuka `docs/license.md` di v2.3.0 berupa lisensi "hanya biner, dilarang reverse engineering". Paragraf itu ternyata hanya berlaku untuk file di luar daftar pengecualian. Daftar pengecualiannya berlisensi MIT dan memuat semua file FSR 3 (frame generation 77/77 file, upscaler 72/72, API dan backend), yang sudah dicek satu per satu. Yang memang tidak terbuka hanya DLL bertanda tangan di `signedbin/`, termasuk FSR 4 (berbasis ML, khusus RDNA 4, tanpa source).
- v2.3.0 berisi versi lebih baru (frame interpolation **3.1.6** vs 1.1.3 di v1.1.4; upscaler 3.1.5 vs 3.1.4), tapi shadernya tinggal HLSL dan backend-nya hanya DX12. Untuk memakainya, terjemahan shader perlu `dxc` (HLSL → SPIR-V) sebagai ganti `glslangValidator`. Itu item roadmap R10. Selama itu belum dikerjakan, v1.1.4 tetap dipakai karena GLSL-nya sudah terbukti diterjemahkan otomatis.

Yang diambil dari v1.1.4 (di-vendor apa adanya ke `third_party/fidelityfx-1.1.4/` beserta `LICENSE.txt`):

| Bagian | Isi | Ukuran |
|---|---|---|
| Shader optical flow | 7 pass: prepare luma, luminance pyramid, histogram + divergence (deteksi pergantian adegan), optical flow v5, filter, scale | header GPU + `.glsl` |
| Shader frame interpolation | 11 pass: reconstruct & dilate (= "prepare"), setup, reconstruct previous depth, game motion vector field, optical flow vector field, disocclusion mask, interpolation, inpainting pyramid, inpainting, game vector field inpainting pyramid, debug view | ~5.800 baris header GPU |
| Host C++ | `ffx_frameinterpolation.cpp`, `ffx_opticalflow.cpp`, header host dan antarmuka backend | ~2.300 baris |

Tidak diambil: `FrameInterpolationSwapchain` (pacing dan present punya AMD, karena thread present kita sudah mengerjakannya), upscaler FSR (kita pakai MetalFX), dan Anti-Lag.

FSR 3.1 memisahkan frame generation dari upscaler. `ffxFrameInterpolationPrepare` membuat depth dan motion vector yang di-dilate dari **depth + motion vector game**, jadi generatornya bisa dipakai tanpa upscaler FSR, berdampingan dengan MetalFX temporal.

## Arsitektur

```
thread render (per frame)
  dunia ─► [MetalFX upscale] ─► tangkap W (tanpa HUD), depth, motion vector   (sudah ada)
       └─► tangan + GUI ─► frame jadi F
  publish ─► encode FSR:
       prepare(depth, motion, jitter, kamera)      → dilated depth/motion, prev depth
       optical flow(W sebelumnya, W sekarang)      → flow + deteksi pergantian adegan
       interpolasi(F, W tanpa HUD, hasil di atas)  → frame buatan (dengan GUI frame F)
  ─► slot thread present: frame buatan + F
thread present (sudah ada): tampilkan frame buatan, lalu F, di refresh berturut-turut
```

Semua input yang dibutuhkan FSR sudah kita buat untuk MetalFX dan HDR: warna dunia tanpa HUD (`currentBackBuffer_HUDLess`), frame jadi (`currentBackBuffer`), depth, motion vector dari pass `m2m_encode_motion`, jitter, near/far/FOV, dan waktu antar frame. FSR memakai HUD-less untuk melacak gerak lalu menempelkan GUI dari frame sekarang, mirip mode UI composited di MetalFX. Jalur present satu-jalur yang baru selesai tinggal diberi tekstur frame buatan dari sumber lain.

Di menu Metal, "Interpolasi Frame" menjadi pilihan **Mati / MetalFX / FSR 3**.

### Shader: GLSL → SPIR-V → MSL → metallib saat build

1. `glslangValidator` (sudah terpasang) mengompilasi `.glsl` dari backend Vulkan dengan `-DFFX_GLSL=1 -DFFX_GPU=1` dan permutasi yang kita pakai:
   - `LOW_RES_MOTION_VECTORS`: 1 saat upscaling, 0 tanpa.
   - `JITTER_MOTION_VECTORS = 0`: motion vector kita tanpa jitter.
   - `INVERTED_DEPTH = 0`.
   - `HDR_COLOR_INPUT`: 0 untuk SDR, 1 untuk EDR.

   Hasilnya sekitar 18 pass × beberapa permutasi.
2. `spirv-cross` (perlu `brew install spirv-cross`) menerjemahkan SPIR-V ke MSL 3.1 dan mengeluarkan refleksi JSON (nama dan slot binding setiap resource).
3. MSL-nya dikompilasi **saat runtime** lewat `newLibraryWithSource`, seperti shader backend lainnya. Ini tidak butuh Metal Toolchain (di Xcode 26+ toolchain itu unduhan terpisah). Kompilasi ke-18 pass memakan ~2,7 detik sekali, lalu masuk cache Metal (~20 ms setelahnya).
4. Generator kecil mengubah refleksi menjadi tabel C dengan bentuk yang sama seperti blob shader AMD. Kode host AMD mencocokkan resource **berdasarkan nama** (`r_…`, `rw_…`, `cb…`), jadi namanya harus persis sama.

Semua ini menjadi task Gradle (`buildFsrShaders`) sebelum `buildNative`. Artefak antara tidak masuk repo. Untuk F0, skripnya `scripts/build-fsr-shaders.sh`.

Fitur GPU yang dipakai shader-nya sudah dicek di sumbernya:
- **Atomic:** hanya `imageAtomicMax/Min/Add` 32-bit pada tekstur `r32ui`. Di Metal ini menjadi texture atomics (Metal 3.1, macOS 14; tekstur perlu `MTLTextureUsageShaderAtomic`).
- **Subgroup:** `subgroupShuffleXor`, ballot, dan quad, menjadi fungsi `simd_*`/`quad_*`.
- **Shared memory:** `groupshared` (SPD 16×16) menjadi `threadgroup`.
- Tidak ada atomic 64-bit di pass yang kita pakai. Ekstensi int64 di `ffx_core_glsl.h` hanya diaktifkan dengan syarat tertentu.

### Host: backend `ffx_metal`

Kode host AMD tidak diubah. Ia memanggil backend lewat `FfxInterface` untuk membuat resource dan pipeline, menjadwalkan job GPU (dispatch, copy, clear), lalu mengeksekusinya. Kita menulis implementasi Metal dalam Objective-C++ (`src/main/native/fsr/ffx_metal.mm`) di atas helper yang sudah ada (`gpu.m`):

| Fungsi FfxInterface | Implementasi Metal |
|---|---|
| CreateBackendContext / GetDeviceCapabilities | Konteks dari `M2MContext`. Kemampuan: wave 32, FP16, texture atomics |
| CreateResource / RegisterResource / GetResourceDescription / DestroyResource | `MTLTexture`/`MTLBuffer` private lewat `m2m_new_private_texture` (dilacak residency, dilepas lewat `m2m_retire`); tekstur game didaftarkan per frame |
| CreatePipeline | `MTLComputePipelineState` dari metallib + tabel refleksi |
| ScheduleGpuJob / ExecuteGpuJobs | Compute encoder Metal 3 atau Metal 4 (argument table, barrier) di command buffer frame; copy/clear lewat encoder yang ada |

Yang perlu ditambahkan di `gpu.m`: helper compute encoder (`m2m_open_compute_encoder`) untuk Metal 3 dan Metal 4. Backend kita saat ini baru punya encoder render dan copy.

Perkiraan kode baru: backend ~1.500 baris, build shader + generator refleksi ~300 baris, integrasi dan setting ~400 baris. Kode AMD yang di-vendor tidak dihitung.

### Konvensi yang harus dikalibrasi

Kalibrasinya memakai metode PSNR yang sudah ada di gametest: kamera berputar 0° → 4°, lalu frame buatan dibandingkan dengan frame asli di 2°.
- **Motion vector:** kita menulis `prev − cur` dalam piksel, dengan baris bawah-ke-atas. FSR mengharapkan vektor dengan `motionVectorScale` dan orientasi UV atas-ke-bawah. Kemungkinan perlu skala `1/renderSize` dan tanda Y dibalik; yang benar ditentukan dari PSNR, seperti tanda jitter dulu.
- **Depth:** zero-to-one, tidak dibalik. `viewSpaceToMetersFactor = 1` (1 blok = 1 m).
- **Warna:** SDR memakai transfer sRGB. EDR memakai input HDR dan transfer linear, dengan `minMaxLuminance` dari headroom.

## Risiko

1. **Performa di M3 belum diketahui.** Ini risiko terbesar, dan karena itulah F0 dikerjakan dulu, sebelum yang lain.
2. **Terjemahan SPIRV-Cross:** tipe half, quad ops, dan image atomics kadang butuh opsi atau tambalan kecil. Kalau ada pass yang tidak bisa diterjemahkan otomatis, pass itu ditulis manual dalam MSL.
3. **Texture atomics di Mac lama** (M1) belum diverifikasi. Kalau tidak didukung, SPIRV-Cross bisa mengemulasinya lewat buffer, dengan biaya tambahan.
4. **Nama dan merek:** fitur diberi nama "FSR 3 Frame Generation (AMD FidelityFX, MIT)" dengan atribusi di README dan lisensi ikut dibundel, tanpa mengesankan dukungan resmi AMD.
5. **Perawatan:** versinya dikunci di v1.1.4. AMD tidak lagi merilis versi MIT, jadi perbaikan dari hulu tidak akan datang lagi.

## Fase

| Fase | Isi | Gerbang lanjut |
|---|---|---|
| **F0 Spike** | Vendor shader + header, kompilasi semua pass ke metallib, host minimal yang menjalankan optical flow + interpolasi pada frame tangkapan gametest, ukur waktu GPU di 1920×1080 dan 2560×1600 | Total ≤ 6 ms di 1080p dan ≤ 12 ms di 2560×1600 (sekitar ⅓ biaya MetalFX). Kalau gagal, berhenti di sini dan catat hasilnya |
| F1 Backend | `ffx_metal` lengkap + kode host AMD, dispatch penuh (prepare, optical flow, interpolasi), validation Metal 3 dan 4 bersih | Frame buatan ≥ 31 dB di uji yaw (setara MetalFX) |
| F2 Integrasi | Pilihan Mati/MetalFX/FSR 3, slot thread present, kombinasi dengan upscaling dan HDR, atribusi lisensi | Gametest penuh lolos; FPS di uji jalan-ke-hutan lebih baik dari MetalFX |
| F3 Penyetelan | Permutasi FP16, queue terpisah supaya interpolasi berjalan paralel dengan frame berikutnya, debug view | — |

## Hasil F0 (2026-10-28)

Yang dibuat:
- `scripts/vendor-fidelityfx.sh`: menyalin 75 file v1.1.4 (MIT) ke `third_party/fidelityfx-1.1.4/`. Satu-satunya ubahan: ukuran konteks default digandakan, karena `wchar_t` di macOS 4 byte, sedangkan AMD menghitungnya 2 byte.
- `scripts/build-fsr-shaders.sh`: 18 pass GLSL → SPIR-V → MSL, semua berhasil tanpa tambalan manual. Atomic tekstur menjadi `atomic_fetch_max` native, dan subgroup menjadi `simd_*`.
- `src/main/native/fsr/ffx_metal.{h,mm}`: backend `FfxInterface` untuk Metal 3, ~600 baris.
  - **Binding:** dibaca dari refleksi pipeline Metal, berdasarkan awalan nama AMD (`r_`/`rw_`/`cb`). Akses tidak bisa dipakai, karena pass yang hanya membaca resource `rw_` menghasilkan MSL read-only.
  - **Debug:** `FFX_METAL_TRACE` dan `FFX_METAL_PROFILE` (waktu GPU per pass).
  - **Kompatibilitas Windows:** `ffx_macos_compat.h` dan pengganti `_countof`/`wcscpy_s`/`swprintf_s`. Kode host AMD tidak diubah.
- `tools/fsrbench`: menjalankan prepare → optical flow → interpolasi dengan kode host AMD asli, di atas dua frame gametest (yaw 0° dan 4°).

Hasil di Apple M3 (MacBook Air), tanpa validation. Di bawah validation tidak ada error.

| Output | Render penuh | Render 50% (seperti upscaling temporal) | MetalFX, render penuh (M12) |
|---|---|---|---|
| 1920×1080 | 11,6–12,8 ms | 8,7 ms | ~10–40 ms |
| 2560×1600 | 23 ms | 17,2 ms | ~47–54 ms |
| 3840×2486 (Retina penuh) | 54 ms | 40 ms | — |

Rincian 1080p, render 50%: pass interpolasi utama 2,1 ms, optical flow ~2,4 ms (7 level), reconstruct previous depth 0,7, game motion vector field 0,6, disocclusion 0,55, piramida inpainting 0,7, sisanya <0,4 per pass. Di render penuh, disocclusion melonjak ke 4,4 ms.

Kualitas: frame buatan sudah berada di antara kedua frame asli (25,6 dB terhadap frame asli di yaw 2°, sedangkan frame asli di kedua ujung 21,8 dB). Ini baru dari optical flow saja, karena benchmark memberi depth datar dan motion vector nol. Motion vector dan depth asli dari game baru masuk di F1. MetalFX dengan input lengkap: 31,3 dB.

**Gerbang F0 tidak tercapai secara angka** (target ≤6 ms di 1080p dan ≤12 ms di 2560×1600; hasilnya 8,7–12 dan 17–23 ms). Tapi:
- FSR 3 **2–3× lebih murah** daripada MetalFX di ukuran yang sama, dan waktunya stabil. MetalFX berubah-ubah antara 10 dan 40 ms di 1080p.
- Di output sekelas 1080p dengan render 50%, 8,7 ms masih muat di anggaran 33 ms per frame asli untuk 30 + 30 FPS. Di Retina penuh (40–54 ms), tidak ada generator frame yang bisa mencapai 30 + 30 di GPU M3 Air.
- Ruang optimasi yang terlihat: pass interpolasi utama dan optical flow (ukuran threadgroup, FP16 manual karena jalur GLSL AMD tidak memakainya), serta disocclusion di render penuh.

## Riset lanjutan dan optimasi (2026-10-28)

### Optimasi backend frame generation

| Percobaan | Hasil |
|---|---|
| Tekstur 1×1 kosong untuk input opsional yang tidak diberikan (distortion field, HUD-less), seperti null descriptor di backend Vulkan AMD | Dipertahankan: lebih benar (tidak ada tekstur `nil` yang di-sample), performa sama |
| `spirv-opt --loop-unroll -O` sebelum spirv-cross, supaya array kecil jadi register | Loop AMD berisi `if`/`continue` sehingga tidak di-unroll; tidak ada perubahan; dibuang |
| spirv-cross `--msl-force-native-arrays` (tanpa `spvUnsafeArray`) | Waktu sama; dibuang |
| Pass membaca salinan biasa dari tekstur atomic `r32ui` (menguji tata letak memori atomic) | Waktu sama; dibuang |

Kesimpulan: terjemahan GLSL → MSL tidak memperlambat apa pun. Biaya pass interpolasi utama, optical flow, dan disocclusion (render penuh) adalah biaya algoritmanya di GPU M3. Penghematan berikutnya butuh penulisan ulang manual (FP16, karena jalur GLSL AMD hampir tidak memakai `half`).

Perbaikan yang ditemukan sambil jalan (sekarang di `ffx_metal.mm`):
- Constant buffer dipad ke ukuran yang dideklarasikan shader. std140 membulatkan struct ke 16 byte, sedangkan struct C AMD tidak (148 vs 160 byte di upscaler FSR 3.1).
- Clear ke resource opsional yang tidak diberikan dilewati.

### Upscaler: FSR 1 dan FSR 3.1 vs MetalFX

Kedua upscaler AMD ikut di-vendor (v1.1.4, MIT). Ke-12 pass-nya diterjemahkan otomatis, dan `tools/fsrbench/upscale.mm` (`fsrupscale`) mengukurnya berdampingan dengan MetalFX pada input yang sama. Di bawah validation tidak ada error. Waktu GPU per frame di Apple M3:

| Output (render) | FSR 3.1 | MetalFX temporal | FSR 1 (EASU + RCAS) | MetalFX spatial |
|---|---|---|---|---|
| 1920×1080 (50%) | 2,67 ms | **1,09 ms** | 0,83 ms | **0,51 ms** |
| 1920×1080 (75%) | 3,40 | **1,37** | 0,88 | **0,51** |
| 2560×1600 (50%) | 4,87 | **2,14** | 1,60 | **1,01** |
| 3840×2486 (50%) | 10,77 | **4,94** | 3,70 | **2,35** |

MetalFX 2–2,5× lebih murah untuk upscaling, sementara FSR 2–3× lebih murah untuk frame generation. Kombinasi paling efisien di Mac ini: **upscaling MetalFX + frame generation FSR 3.1**. Upscaler FSR bisa disediakan sebagai pilihan, tapi akan selalu lebih berat. Kualitasnya belum dibandingkan, karena itu butuh gerakan nyata dari game.

### Fitur Apple yang relevan (dari header SDK macOS 27)

- `MTLFXTemporalScaler.reactiveMaskTexture` (macOS 14.4+): masker untuk mengurangi jejak samar mob dan partikel di upscaling temporal.
- `CAMetalDisplayLink` (macOS 14+): drawable diberikan tepat waktu, lengkap dengan `targetPresentationTimestamp` dan `preferredFrameLatency`. Pengganti `nextDrawable` di thread present, supaya frame buatan bisa diletakkan di tengah dua frame asli dan latensinya terukur.
- `jitteredMotionVectorsEnabled` / `outputResolutionMotionVectors` untuk temporal scaler (macOS 27).

## Hasil R1: FSR 3.1 frame generation di game (2026-10-28)

Pilihan menu baru **Pembuatan Frame: Mati / MetalFX / FSR 3.1** (satu saja). `frameInterpolation=true` dari versi lama dibaca sebagai MetalFX.

Cara kerjanya:
- **Build.** `scripts/build-fsr-embedded.sh` (task Gradle `buildFsrShaders`) menerjemahkan pass optical flow dan frame interpolation (dua varian: motion vector resolusi render dan resolusi penuh), lalu menyematkan MSL-nya sebagai tabel C. `scripts/build-native.sh` mengompilasi Objective-C backend, `ffx_metal.mm`, `fsr_framegen.mm`, kode host AMD (C++, dengan `ffx_macos_compat.h`), dan tabel shader itu menjadi satu `libmine2metal.dylib`. MSL dikompilasi saat runtime (pertama kali ~3 detik, sesudahnya dari cache Metal).
- **Backend.** `ffx_metal.mm` menyimpan resource dan pipeline; perekamannya lewat protokol `FfxMetalHost`.
  - **Game:** `M2MFfxHost` merekam ke command buffer frame lewat helper baru di `gpu.m` (`m2m_compute_begin`/`m2m_compute_dispatch`, bind ke encoder compute). Di Metal 4 ada argument table bersama dan barrier dispatch → dispatch, karena pass FSR saling bergantung; resource masuk residency set dan dilepas lewat `m2m_retire`.
  - **Alat benchmark:** `FfxMetalCommandBufferHost` (Metal 3 biasa).
- **Input.** Sama dengan MetalFX:
  - dunia tanpa HUD (tangkapan sebelum tangan dan GUI) sebagai `currentBackBuffer_HUDLess`;
  - frame jadi sebagai `currentBackBuffer`;
  - depth Depth32Float dan motion vector dari pass `m2m_encode_motion` (dalam piksel, `motionVectorScale` = 1: kode AMD sudah membagi dengan ukuran sendiri);
  - near/far/FOV (radian), jitter, waktu antar frame (ms).
  - Dengan HDR, warnanya extended linear (transfer scRGB, luminans maksimum = puncak EDR).
- **Riwayat frame.** FSR menyimpan frame sebelumnya sendiri, jadi setiap frame yang ditangkap dikirim ke FSR. Setelah reset, frame buatannya tidak ditampilkan.
- **Presentasi.** Tetap lewat thread present yang sama: frame buatan lalu frame asli.

Hasil gametest (Apple M3, 854×480; uji yaw 0° → 4°, dibandingkan dengan frame asli di 2°):

| Engine | Tanpa upscaling | Temporal 50% |
|---|---|---|
| MetalFX | 31,30 dB | 29,81 dB |
| **FSR 3.1** (Metal 4) | **31,31 dB** | **29,78 dB** |
| FSR 3.1 (Metal 3) | 31,31 dB | 29,81 dB |

- Kalibrasi tanda motion vector: (+1, +1) yang benar (31,2 dB); tanda lain 22–25 dB. Bug pertama yang ketemu: motion vector dibagi ukuran dua kali (oleh kita dan oleh AMD) sehingga nyaris nol, dan FSR hanya memakai optical flow (24,3 dB).
- HDR + FSR dan HDR + MetalFX: hotbar tetap 1,0× putih SDR, highlight dunia 1,72×. Lolos di Metal 4 dan Metal 3.
- Gametest penuh lolos di bawah Metal API validation (Metal 4), tanpa error.
- **Belum terukur di layar sungguhan:** FPS MetalFX vs FSR di game. Angka FPS gametest berubah-ubah menurut status jendela dan suhu (contoh di 2560×1600: MetalFX 29 lalu 53, FSR 30 lalu 48 dalam satu run). Perbandingan yang andal tetap waktu GPU di benchmark: FSR 2–3× lebih murah. Masuk uji manual nomor 28.

## Hasil R6: optimasi FSR frame gen (2026-10-28)

Ukuran di `tools/fsrbench`, Apple M3, frame gametest yaw 0° → 4°. Kualitas diukur terhadap frame asli di yaw 2°.

| Output (render) | Sebelum R6 | Sesudah R6 |
|---|---|---|
| 1920×1080 (50%) | 8,7 ms | **3,7 ms** |
| 1920×1080 (100%) | 11,0 ms | **6,5 ms** |
| 2560×1600 (50%) | 16,9 ms | **7,0 ms** |
| 3840×2486 (50%) | 40 ms | **16,2 ms** |

Profil per ukuran dispatch (`FFX_METAL_PROFILE=1` sekarang mencatat ukuran grid) menunjukkan sumber biaya yang sebenarnya:
- Level paling halus optical flow, di resolusi layar penuh, memakan 1,4–2,0 ms.
- Pass interpolasi utama membaca 16 texel warna per piksel secara manual.

Perubahan, semuanya di `src/main/native/fsr/glsl`. File AMD di `third_party` tidak disentuh; `build-fsr-shaders.sh` memakai pass dari sana kalau namanya sama.

1. **Optical flow di setengah resolusi.**
   - HUD-less world diperkecil 2× (rata-rata 2×2) oleh kernel kecil di `fsr_framegen.mm`. Resolusi konteks optical flow dan `opticalFlowScale` mengikuti ukuran itu.
   - Parameter itu memang disediakan AMD. Hanya pass `optical_flow_vector_field` yang perlu pengecekan batas, karena host AMD menghitung dispatch-nya dari ukuran layar.
   - Field vektor yang ¼ ukuran juga membuat pass yang membacanya lebih murah: pass interpolasi utama turun dari ~1,1 ke ~0,55 ms.
   - Kualitas tidak turun. Di bench (hanya optical flow) 25,63 dB vs 25,40 dB sebelumnya. Di game, FSR 31,23 dB dan FSR + temporal 29,61 dB, sama seperti sebelumnya.
2. **Bilinear hardware di pass interpolasi utama.**
   - AMD membaca 4 texel lalu menormalisasi ulang bobot yang ada di dalam rect. Untuk rect seukuran tekstur, satu sample bilinear dengan clamp-to-edge memberi hasil yang sama, karena tap yang di-clamp mengulang texel tepi.
   - Hasilnya 61,6 dB terhadap versi AMD (beda presisi filter saja), dan 2,8 → 2,2 ms sebelum langkah 1.
   - Temuan sampingan: `s_LinearClamp` milik frame interpolation mendapat sampler pertama pipeline, yaitu sampler **point**, juga di backend DirectX/Vulkan AMD (register s0). Backend kita sudah setia pada perilaku itu. Sampler linear kita adalah binding kedua.
3. **Tanpa distortion field.**
   - Game tidak pernah memberi distortion field, jadi tiga pass men-sample tekstur nol 1×1 di setiap tap (8× per piksel di disocclusion).
   - Override pass-nya tidak memasang binding itu dan mengembalikan nol. Hasilnya identik bit-per-bit, dan lebih cepat 0,1–0,2 ms.

Yang dicoba dan tidak membantu:
- `FFX_HALF=1`: jalur GLSL AMD tidak memakai tipe 16-bit, sehingga MSL-nya identik.
- Menghitung SAD optical flow dari byte yang diekstrak sekali, karena GPU Apple tidak punya `msad4`. Hasilnya identik bit-per-bit tapi tidak lebih cepat; compiler Metal sudah menangani versi AMD.

Belum dikerjakan: FP16 manual dan dispatch concurrent. Bench memakai depth datar dan motion vector nol, sehingga cabang berat disocclusion jarang jalan. Optimasi selanjutnya perlu profil dengan data game, misalnya dengan menangkap input FSR dari gametest.

Di game (jendela gametest tertutup, 2560×1600, FSR saja), biaya yang dicatat pengaman R4 turun dari 22,5 ke 15,3 ms. Angka dari jendela tertutup tidak stabil (lihat R4), jadi bench tetap jadi acuan. Gametest penuh lolos di bawah validation di Metal 4, begitu juga interpolasi dan HDR di Metal 3.
