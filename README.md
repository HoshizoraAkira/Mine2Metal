# mine2metal

Mod Fabric yang menambahkan backend Apple Metal untuk lapisan GPU `renderpearl` di Minecraft Java, dan dirancang agar tetap berjalan bersama Sodium.

Status: **M1–M8, M10, upscaling spasial M11** (uji manual belum dilakukan, lihat [docs/uji-manual.md](docs/uji-manual.md)). Layar judul dan dunia singleplayer dirender lewat Metal, dengan maupun tanpa Sodium. Hasilnya nyaris identik piksel dengan OpenGL. Di uji otomatis pada MacBook Air M3, Metal + Sodium mencapai 2000–2700 FPS di skenario ringan dan 660–1500 FPS di 1920×1080 render distance 16 (tergantung suhu), dibanding 200–490 FPS untuk OpenGL + Sodium. Detail ada di [docs/roadmap.md](docs/roadmap.md#hasil-uji-otomatis-2026-10-27). Masih eksperimental: mod yang memanggil OpenGL langsung tidak akan menggambar, dan beberapa kasus langka (triangle fan ber-index, clear sebagian) belum didukung.

![Dunia Minecraft 26.3 dengan Sodium, dirender dengan Metal](docs/screenshots/m6-world-sodium.png)

Progres per milestone ada di [docs/roadmap.md](docs/roadmap.md), latar belakang teknis di [docs/riset-awal.md](docs/riset-awal.md).

## Target

- Minecraft 26.3, Fabric Loader ≥ 0.20.1, Java 25
- macOS ≥ 14 di Apple Silicon (arm64)
- Sodium `mc26.3-0.9.3-beta.1` (dipasang otomatis di runtime dev)

## Struktur

| Path | Isi |
|---|---|
| `src/main/java/dev/mine2metal/metal/MetalBackend.java` | Implementasi `GpuBackend` (window SDL dengan flag Metal, pembuatan device) |
| `src/main/java/dev/mine2metal/metal/MetalDevice.java` | `GpuDeviceBackend`: info device, buffer, tekstur, sampler, pipeline |
| `src/main/java/dev/mine2metal/metal/MetalCommandEncoder.java` | Perekaman perintah, submit, fence, kerja yang menunggu GPU |
| `src/main/java/dev/mine2metal/metal/MetalSurface.java` | `CAMetalLayer` dan present |
| `src/main/java/dev/mine2metal/metal/ShaderTranslator.java` | SPIR-V → MSL dengan SPIRV-Cross |
| `src/main/java/dev/mine2metal/metal/MetalRenderPipeline.java` | Library MSL, layout vertex/blend, pipeline state per format depth |
| `src/main/java/dev/mine2metal/metal/MetalRenderPass.java` | Binding uniform, scissor, dan semua varian draw |
| `src/main/java/dev/mine2metal/metal/MetalNative.java` | Binding FFM ke `libmine2metal.dylib` |
| `src/main/java/dev/mine2metal/mixin/PreferredGraphicsApiMixin.java` | Menaruh Metal di urutan pertama backend yang dicoba |
| `src/main/native/` | Library Objective-C (C ABI `m2m_*` di `mine2metal.h`), dibangun oleh task `buildNative` |

## Build dan jalankan

Butuh JDK 25 atau lebih baru dan Xcode Command Line Tools (`xcrun clang`).

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew build
```

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew runClient
```

Uji otomatis (membuat dunia, mengambil screenshot ke `build/run/clientGameTest/screenshots/`, dan mencatat FPS di log):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew runClientGameTest
```

Tambahkan `-PnoSodium` untuk menguji renderer chunk vanilla. Untuk pembanding OpenGL, jalankan dengan `JAVA_TOOL_OPTIONS="-Dmine2metal.enabled=false"`.

### Pengaturan di game

- Dengan Sodium: Options → Video Settings → di sidebar kiri "mine2metal" → "Metal".
- Tanpa Sodium: Options → Video Settings → tombol "Metal Settings..." di bagian bawah.

Isinya: **Versi Metal** (Otomatis / Metal 4 / Metal 3, berlaku setelah restart), **Upscaling** (Off / Spatial / Temporal), **Render Scale** 50–100%, dan **Interpolasi Frame** (MetalFX, macOS 26+: satu frame buatan di antara setiap pasang frame, hingga dua kali FPS di layar sampai batas refresh rate). Perubahan langsung berlaku dan disimpan di `config/mine2metal.properties`.

### Mematikan Metal

- Di game: Options → Video Settings → Graphics API → OpenGL (atau Vulkan), lalu restart. Metal hanya dipakai saat pilihan ini "Default".
- Di file: `config/mine2metal.properties` (dibuat otomatis) dengan `enabled=false`.
- Lewat JVM: `-Dmine2metal.enabled=false`, yang mengalahkan file config.

### Debug

- `--vulkanValidation` (argumen game) atau variabel lingkungan `MTL_DEBUG_LAYER=1` menyalakan Metal API validation. `MTL_SHADER_VALIDATION=1` menambah pemeriksaan di dalam shader (lambat).
- `--renderDebugLabels` memberi nama render encoder untuk Xcode GPU capture dan Instruments.
- Command buffer yang gagal di GPU dicatat sebagai error `Metal: Command buffer failed ...` di log.

Opsi JVM:

| Properti | Fungsi |
|---|---|
| `-Dmine2metal.enabled=false` | Mematikan backend Metal tanpa melepas mod |
| `-Dmine2metal.dumpFrame=/path/frame.png` | Menyimpan frame yang dipresentasikan ke PNG (tanpa izin screen recording) |
| `-Dmine2metal.dumpFrameIndex=N` | Nomor frame yang disimpan (default 300) |
| `-Dmine2metal.stats=true` | Log tiap 5 detik: FPS, waktu GPU per frame, waktu CPU menunggu GPU, jumlah encoder, dan volume transfer |
| `-Dmine2metal.passTimes=true` | Seperti `stats`, plus waktu GPU per pass (setiap pass di-commit terpisah, jadi lebih lambat) |
| `-Dmine2metal.labels=true` | Memberi nama render encoder sesuai pass, untuk Xcode GPU capture atau Instruments (Metal System Trace) |

Saat memakai `runClient`, properti bisa dioper lewat `JAVA_TOOL_OPTIONS`, misalnya `JAVA_TOOL_OPTIONS="-Dmine2metal.dumpFrame=/tmp/frame.png" ./gradlew runClient`.
