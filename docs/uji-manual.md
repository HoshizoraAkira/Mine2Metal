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
| 9 | Vsync on/off, Max Framerate; dengan HUD Performa Metal nyala | Dengan vsync gerakan halus dan FPS menempel di refresh rate (60); tanpa vsync FPS naik dan tidak ada stutter berkala. Input dengan vsync nyala tidak terasa lebih telat dari sebelumnya |
| 10 | F11 (fullscreen), keluar fullscreen, ubah ukuran window dengan mouse | Gambar langsung menyesuaikan, tidak gepeng atau buram |
| 11 | Pindah window ke monitor lain (jika ada), minimize lalu restore, Cmd+Tab | Tetap tampil normal setelah kembali |
| 12 | F2 (screenshot) | File di `screenshots/` tidak terbalik dan warnanya benar |
| 13 | Main 15–30 menit | Tidak ada penurunan FPS terus-menerus atau memori yang terus naik |
| 14 | Video Settings → Graphics API → OpenGL, lalu restart | F3 menunjukkan OpenGL (backend Metal mati); kembalikan ke Default untuk Metal lagi |
| 15 | Video Settings → Metal (Sodium) atau Metal Settings... (vanilla): Upscaling Spatial, geser Render Scale 50% ↔ 100% | Dunia jadi lebih lembut di skala rendah tapi tetap tajam di tepi; menu, HUD, dan teks tetap tajam; tidak ada kedip saat menggeser |
| 16 | Upscaling Spatial lalu ubah ukuran window dan F11 | Gambar langsung menyesuaikan, tidak gepeng |
| 17 | Menu Metal → Interpolasi Frame: nyalakan, lalu matikan | Di macOS 26+ bisa dipilih dan langsung berlaku; F3 menunjukkan FPS render sekitar separuh refresh layar (≈30 di 60 Hz), sedangkan HUD Performa Metal menunjukkan ≈60 frame tampil. Saat dimatikan, FPS kembali seperti sebelumnya |
| 24 | Interpolasi Frame nyala: putar kamera pelan dan cepat, terbang maju, buka inventory dan chat, ganti GUI scale | Gerakan lebih halus daripada 30 FPS tanpa interpolasi; hotbar, crosshair, teks, dan tangan tetap tajam tanpa bayangan ganda; blok tidak berkedip saat kamera berputar. Mob dan air yang bergerak boleh sedikit goyah |
| 26 | Menu Metal → HDR (EDR): nyalakan dengan slider kecerahan macOS di bawah maksimum, siang hari di luar ruangan, lalu dekati lava atau glowstone; geser Kecerahan HDR 125% ↔ 400% | Langit, matahari, dan lava tampak lebih terang dari putih biasa tanpa warna yang pecah; hotbar, teks, chat, dan tangan tetap seterang biasa; geser slider langsung mengubah terangnya. Tooltip HDR menyebut berapa kali putih yang bisa ditampilkan layar |
| 27 | HDR nyala, lalu Interpolasi Frame dan Upscaling Temporal; buka menu pause dan inventory; F2 | Tidak ada kedip terang-gelap antar frame; saat menu terbuka dunia di belakangnya tidak menyilaukan; screenshot F2 tetap SDR biasa |
| 28 | Menu Metal → Pembuatan Frame: Mati, MetalFX, FSR 3.1 bergantian di tempat yang sama (jalan ke arah hutan), dengan HUD Performa Metal nyala | Kedua engine bisa dipilih tanpa crash; pertama kali memilih FSR 3.1 boleh tersendat ~3 detik (kompilasi shader). Gerakan sama halusnya; FSR 3.1 memberi FPS render lebih tinggi daripada MetalFX di resolusi besar. Hotbar, teks, dan tangan tetap tajam di keduanya |
| 25 | Interpolasi Frame + Upscaling Temporal 50%, VSync nyala lalu mati; F11 dan ubah ukuran window | Tetap halus tanpa robek; tidak ada sisa gambar lama setelah ganti ukuran; input terasa tertunda sedikit (sekitar satu frame render) |
| 20 | Upscaling Temporal, Render Scale 50% lalu 75% dan 100%; diam, lalu putar kamera dan terbang maju | Saat diam, dunia hampir setajam resolusi asli (lebih tajam dari Spatial 50%) dan tepi blok halus tanpa gerigi; saat bergerak tidak ada bayangan ganda di blok. Mob yang bergerak cepat boleh meninggalkan jejak samar. Tangan selalu tajam |
| 21 | Upscaling Temporal, lalu /tp jauh, ganti dimensi, F11, ubah ukuran window | Tidak ada sisa gambar lama yang tercampur setelah pindah |
| 22 | Menu Metal → API Grafis → Versi Metal: Otomatis, Metal 4, Metal 3 (restart setelah tiap pilihan) | F3 menunjukkan driver "Metal 4 (mine2metal)" atau "Metal 3 (mine2metal)" sesuai pilihan (Otomatis = Metal 4 di macOS 26+); tooltip menyebut versi yang sedang dipakai; dunia tampil sama di keduanya |
| 23 | Metal 4: main 10 menit dengan VSync nyala lalu mati, upscaling Spatial dan Temporal | Tidak ada crash, layar hitam, atau kedip; FPS di adegan ramai sama atau lebih tinggi dari Metal 3 |
| 18 | Menu Metal → Diagnostik → HUD Performa Metal: nyalakan, lalu matikan | Overlay Apple (FPS, grafik waktu frame, waktu GPU, memori) muncul di pojok window dan hilang lagi; tetap muncul setelah F11 atau restart selama opsinya nyala |
| 19 | Buka inventory dengan kompas dan jam di hotbar, tunggu beberapa detik | Ikon kompas dan jam berganti gambar dengan bersih, tanpa sisa gambar lama di slot |

Kalau ada yang salah: catat nomor barisnya, ambil screenshot (F2), dan simpan `logs/latest.log`. Untuk mencari tahu apakah masalahnya dari Metal, jalankan ulang dengan `-Dmine2metal.enabled=false` dan bandingkan.
