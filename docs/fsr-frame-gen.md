# Rancangan: FSR 3 frame generation di atas Metal

Status: rancangan, belum ada kode (2026-10-28).

## Kenapa

Interpolasi frame MetalFX berjalan, tapi interpolatornya sendiri mahal di GPU MacBook Air M3: ~10–40 ms per frame di 1080p dan ~50 ms di 2560×1600 (lihat roadmap M12). Di resolusi Retina, biaya itu saja sudah melebihi anggaran 30 FPS asli. Generator frame FSR 3 dari AMD terdiri dari compute shader biasa: optical flow dengan blok kasar, lalu interpolasi dan inpainting. Shader-shader itu dirancang untuk GPU konsol dan PC kelas menengah. Kalau biayanya di M3 jauh di bawah MetalFX, interpolasi jadi benar-benar berguna di Mac ini. **Itu yang harus dibuktikan dulu (fase F0).**

## Sumber kode: FidelityFX SDK v1.1.4, bukan 2.x

- **v1.1.4 (Mei 2025): lisensi MIT.** Ada backend Vulkan lengkap dengan shader **GLSL**, kode host C++ untuk optical flow dan frame interpolation, serta antarmuka backend (`FfxInterface`) yang bisa kita implementasikan untuk Metal.
- **v2.x (2.0–2.3, 2025–2026): tidak bisa dipakai.** Lisensinya hanya untuk distribusi biner dan melarang reverse engineering/dekompilasi, isinya hanya DX12/HLSL, dan sebagian besar komponennya DLL bertanda tangan. FSR 4 (berbasis ML, khusus RDNA 4) juga tidak bisa diport.

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
3. `xcrun metal` / `metallib` membuat satu `.metallib`, disematkan di dylib seperti sekarang.
4. Generator kecil mengubah refleksi menjadi tabel C dengan bentuk yang sama seperti blob shader AMD. Kode host AMD mencocokkan resource **berdasarkan nama** (`r_…`, `rw_…`, `cb…`), jadi namanya harus persis sama.

Semua ini menjadi task Gradle (`buildFsrShaders`) sebelum `buildNative`. Artefak antara tidak masuk repo.

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
