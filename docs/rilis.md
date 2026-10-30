# Rilis

## Membuat file rilis

```bash
./gradlew build mrpack
```

- `build/libs/mine2metal-<versi>.jar`: mod, dengan `libmine2metal.dylib` (arm64, macOS 14+) dan lisensi MIT milik mine2metal dan AMD di `META-INF/licenses`.
- `build/distributions/mine2metal-<versi>.mrpack`: modpack untuk Modrinth App dan Prism Launcher.
  - Minecraft, Fabric Loader, dan Sodium diunduh launcher dari sumber resminya. Sodium diambil dari CDN Modrinth (`sodium_mrpack_url` di `gradle.properties`); hash dan ukurannya dihitung dari artefak Sodium yang dipakai build.
  - mine2metal ikut di `overrides/mods` sampai mod ini ada di Modrinth. Setelah itu, jar-nya bisa dirujuk lewat CDN seperti Sodium.
- Versi ada di `version` di `gradle.properties`. Kalau Sodium diganti, `sodium_version` dan `sodium_mrpack_url` harus menunjuk file yang sama.

Uji sebelum upload:
- `./gradlew runProductionClient` menjalankan jar rilis dan Sodium di klien produksi (tanpa classpath dev), di `build/production-run`.
- `JAVA_TOOL_OPTIONS="-Dos.version=13.6 -Dmine2metal.test.fallbackOnly=true" ./gradlew runClientGameTest` memeriksa jalur Mac yang tidak didukung: game memakai OpenGL dan layar judul menjelaskan alasannya.

## Halaman Modrinth / CurseForge

- **Loader:** Fabric. **Versi game:** 26.3. **Lingkungan:** klien saja.
- **Dependensi:** Sodium (opsional/disarankan). Fabric API tidak diperlukan.
- **Lisensi:** MIT. **Ikon:** `src/main/resources/assets/mine2metal/icon.png`.
- **Kategori:** Optimization, Utility.

Deskripsi (bahasa Inggris, untuk halaman proyek):

> **mine2metal** renders Minecraft with Apple's Metal on Macs with Apple silicon instead of OpenGL, which macOS has long stopped improving. It plugs into Minecraft's own graphics layer and works alongside Sodium.
>
> - Higher frame rates than OpenGL, most of all in busy scenes and at long render distances (Metal 4 on macOS 26 or later, Metal 3 otherwise).
> - Upscaling: MetalFX Spatial and Temporal, and AMD FSR 1 and FSR 3.1. Your hand and the HUD stay at full resolution.
> - Frame generation: MetalFX frame interpolation (macOS 26 or later) or AMD FSR 3.1, paced to your display. It pauses itself if it costs more than it gains.
> - HDR (EDR) on displays that support it. The world's highlights go brighter than white while the GUI stays as it is.
> - Settings under Video Settings: in Sodium's sidebar, or a "Metal Settings..." button without Sodium.
>
> **Requirements:** a Mac with Apple silicon (M1 or later), macOS 14 or later, Minecraft 26.3, and Fabric Loader 0.20.1 or later. On any other computer the mod steps aside: the game uses its usual graphics API and the title screen says why.
>
> **Known limits:** mods that call OpenGL directly will not draw. To turn Metal off, set Graphics API to OpenGL in Video Settings.
>
> AMD FidelityFX SDK code is included under its MIT license.

## Keadaan rilis (2026-10-31)

- Repo publik: https://github.com/HoshizoraAkira/Mine2Metal. Tautan homepage, sumber, dan issues di `fabric.mod.json` menunjuk ke sana.
- GitHub Release `v0.0.1` berisi `mine2metal-0.0.1.jar` dan `mine2metal-0.0.1.mrpack` beserta checksum SHA-256.
- Halaman Modrinth sedang dibuat pemilik dengan teks di atas; CurseForge belum. Keduanya butuh akun pemilik.
