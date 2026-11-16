# Rilis

## Membuat file rilis

File rilis hanya dibuat oleh GitHub Actions (`.github/workflows/build.yml`), tidak pernah dari build lokal, supaya siapa pun bisa memeriksa bahwa yang ada di GitHub dan Modrinth dibangun dari kode sumber repo ini. Setiap push dan pull request menjalankan build yang sama; sebuah tag menerbitkannya:

1. Atur `version` di `gradle.properties`, commit, lalu push.
2. `git tag v<versi> && git push origin v<versi>`. Workflow gagal kalau tag dan `version` berbeda.
3. Workflow membangun di runner `macos-26` (Apple silicon, Xcode 26.6 yang dipatok di workflow, glslang dan spirv-cross dari Homebrew), memeriksa bahwa dylib di dalam jar adalah yang baru saja dikompilasinya, membuat atestasi asal build (build provenance) untuk jar, .mrpack, dan dylib, lalu membuat GitHub Release beserta `SHA256SUMS.txt` dan `BUILD-INFO.txt` (checksum, commit, tautan run, dan versi toolchain).
4. Kalau variabel repo `MODRINTH_PROJECT_ID` dan secret `MODRINTH_TOKEN` (personal access token Modrinth dengan scope "Create versions") sudah diatur, jar yang sama diunggah ke Modrinth sebagai versi baru. Tanpa keduanya, unggah jar dari GitHub Release secara manual, tanpa diubah.

Memeriksa file yang diunduh: `gh attestation verify mine2metal-<versi>.jar --repo HoshizoraAkira/Mine2Metal`.

Library native dibangun sama persis, byte demi byte, dari kode sumber dan toolchain yang sama, di mana pun checkout-nya: tidak ada path absolut atau timestamp yang masuk. Membangun ulang dengan versi Xcode, glslang, dan spirv-cross di `BUILD-INFO.txt` menghasilkan SHA-256 `libmine2metal.dylib` yang sama.

Untuk build lokal (untuk diuji):

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
- GitHub Release `v0.0.1` berisi `mine2metal-0.0.1.jar` dan `mine2metal-0.0.1.mrpack` beserta checksum SHA-256. Rilis ini dibangun lokal, sebelum ada workflow rilis; rilis berikutnya dibuat oleh GitHub Actions.
- Modrinth meminta kode sumber dan otomasi build yang transparan untuk library native (2026-11-16); workflow rilis adalah jawabannya.
- Halaman Modrinth sedang dibuat pemilik dengan teks di atas; CurseForge belum. Keduanya butuh akun pemilik.
