# Uji manual

Gametest otomatis (`./gradlew runClientGameTest`) sudah memeriksa beberapa hal: layar judul, dunia (spawn, dari atas, malam, render distance 16), improved transparency, fullscreen, resize, pemilihan backend, dan Metal API/shader validation. Hasilnya dibandingkan piksel dengan OpenGL. Daftar di bawah berisi hal yang hanya bisa dinilai dengan bermain langsung.

## Cara menjalankan

Lingkungan dev (Sodium ikut termuat):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home ./gradlew runClient
```

Tambahkan `JAVA_TOOL_OPTIONS="-Dmine2metal.stats=true"` untuk mencatat FPS dan waktu GPU di log setiap 5 detik.

Launcher resmi:

1. Pasang Fabric Loader ≥ 0.20.1 untuk Minecraft 26.3 lewat [installer Fabric](https://fabricmc.net/use/installer/), dan buat profil terpisah.
2. Salin `build/libs/mine2metal-0.0.1.jar` ke folder `mods` profil itu (bawaan: `~/Library/Application Support/minecraft/mods`). Sodium boleh ikut, Fabric API tidak wajib.
3. Jalankan profilnya. Tekan F3: kolom kanan harus menampilkan `Metal 3 (mine2metal)`.

## Daftar periksa

| # | Yang dicek | Hasil yang diharapkan |
|---|---|---|
| 1 | F3 | Backend tertulis `Metal 3 (mine2metal)` |
| 2 | Bergerak, melompat, terbang, menoleh cepat | Tidak ada kedip, robek, atau objek hilang |
| 3 | Menghancurkan dan memasang blok | Chunk diperbarui seketika, retakan blok terlihat |
| 4 | Air, kaca berwarna, es (dari dekat dan dari bawah air) | Transparansi dan fog air benar |
| 5 | Hujan, badai, salju | Partikel cuaca dan petir tampil |
| 6 | Nether dan End | Langit, fog, dan portal benar |
| 7 | Inventory, chat, menu pause, Options | GUI, teks, dan item 3D di slot benar |
| 8 | Video Settings: ubah render distance, GUI scale, Graphics preset, Improved Transparency | Berganti tanpa crash atau gambar rusak |
| 9 | Vsync on/off, Max Framerate | Dengan vsync gerakan halus; tanpa vsync FPS naik dan tidak ada stutter berkala |
| 10 | F11 (fullscreen), keluar fullscreen, ubah ukuran window dengan mouse | Gambar langsung menyesuaikan, tidak gepeng atau buram |
| 11 | Pindah window ke monitor lain (jika ada), minimize lalu restore, Cmd+Tab | Tetap tampil normal setelah kembali |
| 12 | F2 (screenshot) | File di `screenshots/` tidak terbalik dan warnanya benar |
| 13 | Main 15–30 menit | Tidak ada penurunan FPS terus-menerus atau memori yang terus naik |
| 14 | Video Settings → Graphics API → OpenGL, lalu restart | F3 menunjukkan OpenGL (backend Metal mati); kembalikan ke Default untuk Metal lagi |
| 15 | Video Settings → Metal (Sodium) atau Metal Settings... (vanilla): Upscaling Spatial, geser Render Scale 50% ↔ 100% | Dunia jadi lebih lembut di skala rendah tapi tetap tajam di tepi; menu, HUD, dan teks tetap tajam; tidak ada kedip saat menggeser |
| 16 | Upscaling Spatial lalu ubah ukuran window dan F11 | Gambar langsung menyesuaikan, tidak gepeng |
| 17 | Temporal dan Frame Interpolation | Terlihat tapi tidak bisa dipilih, dengan tooltip "menyusul" |

Kalau ada yang salah: catat nomor barisnya, ambil screenshot (F2), dan simpan `logs/latest.log`. Untuk mencari tahu apakah masalahnya dari Metal, jalankan ulang dengan `-Dmine2metal.enabled=false` dan bandingkan.
