# RuangApung

Buka aplikasi apa pun dalam **jendela mengambang (freeform)** — tanpa root, tanpa Xposed/LSPosed, tanpa Shizuku.

RuangApung adalah launcher sederhana: pilih aplikasi dari daftar (atau dari gelembung tepi yang selalu tampil), dan aplikasi itu dibuka sebagai jendela mengambang yang bisa digeser dan diubah ukurannya oleh sistem.

## Fitur (v0.1.0)

- **Daftar aplikasi** yang bisa diluncurkan + kolom cari (Bahasa Indonesia).
- **Favorit**: tahan aplikasi di daftar untuk menandai favorit.
- **Gelembung tepi**: gelembung kecil yang bisa digeser; ketuk untuk membuka panel mini berisi favorit dari layar mana pun.
- **Kartu status kompatibilitas**: memberi tahu apakah perangkat mendukung mode freeform.
- Tanpa iklan, tanpa tracker, tanpa izin INTERNET.

## Cara pakai

1. Instal APK, buka RuangApung.
2. Ketuk aplikasi → terbuka sebagai jendela mengambang.
3. (Opsional) Ketuk "Aktifkan gelembung tepi", setujui izin "Tampilkan di atas aplikasi lain".

## Batasan (jujur)

- Hanya berfungsi di perangkat yang mendukung **freeform window management**. Aplikasi menampilkan statusnya di kartu atas.
- **Tidak bisa memaksa semua aplikasi**: aplikasi yang menolak windowing (mis. `resizeableActivity=false`) tetap terbuka normal. Ini batasan API publik Android — modul Xposed seperti Mi-Freeform bisa memaksa, RuangApung tidak.
- Ukuran/posisi jendela awal: 80% × 72% layar, di tengah. Ukuran & posisi bebas setelahnya diatur lewat bingkai jendela sistem.

## Cara kerja

Memakai API publik Android saja: `ActivityOptions.setLaunchBounds(Rect)` + `setLaunchWindowingMode(WINDOWING_MODE_FREEFORM)`, lalu `startActivity(intent, opts.toBundle())`. Detail di [RANCANGAN.md](RANCANGAN.md).

## Build

Build hanya lewat GitHub Actions (workflow `build.yml`): `./gradlew :app:assembleRelease`.
Keystore rilis di-pin via secret repo (`RUANGAPUNG_KEYSTORE_BASE64`, …) — lihat skill `android_ci_apk_signing`.

## Lisensi

Apache-2.0 — lihat [LICENSE](LICENSE).
