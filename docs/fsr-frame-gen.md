# Rancangan: FSR 3 frame generation di atas Metal

Status: F0 (spike) selesai, 2026-10-28. Hasilnya di bagian "Hasil F0" di bawah.

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
