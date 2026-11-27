# Zero Team Android

Proyek ini membuat aplikasi Android khusus untuk menjalankan arsip ROM Zero Team USA melalui LibretroDroid dan core FBNeo.

## Menyiapkan game

1. Bangun APK dari workflow **Build Zero Team APK** di tab Actions.
2. Pasang APK hasil build pada perangkat Android.
3. Buka aplikasi, lalu pilih arsip `zeroteam.zip` dari penyimpanan perangkat.
4. Aplikasi memeriksa 13 checksum ROM sebelum membuka game.

ROM tidak disimpan di repositori dan tidak disertakan dalam APK. Pilih arsip ROM yang memang sudah kamu miliki. Aplikasi menyalin arsip ke penyimpanan privatnya agar dapat dijalankan tanpa koneksi internet setelah APK dan ROM tersedia.

## Build

Workflow mengunduh core FBNeo Android resmi untuk ARM64 dan ARM 32-bit dari Libretro buildbot, kemudian membangun APK Debug dengan Gradle.

Hasil build tersedia sebagai artifact bernama **ZeroTeam-Android-APK** pada halaman run GitHub Actions.

## Catatan

- Minimum Android mengikuti konfigurasi proyek (API 21).
- Kontrol sentuh mencakup arah, tombol A/B/X/Y, COIN, dan START.
- Status game belum diuji pada perangkat Android fisik dari CI; lakukan tes boot, input, audio, dan transisi permainan setelah memasang APK.
