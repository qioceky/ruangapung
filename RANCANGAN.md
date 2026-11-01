# RANCANGAN — RuangApung v0.1.0

## Tujuan

Launcher jendela mengambang/freeform **tanpa** root, Xposed/LSPosed, atau Shizuku.
Target: HP Android apa pun (termasuk non-Xiaomi), minSdk 26, targetSdk/compileSdk 35.

## Arsitektur

```
MainActivity ──tap──▶ FreeformLauncher.launch(pkg)
     │                      │ ActivityOptions: launchBounds + WINDOWING_MODE_FREEFORM
     │                      ▼
     └─▶ BubbleService (foreground + overlay)
              ├─ gelembung geser (TYPE_APPLICATION_OVERLAY)
              └─ panel mini favorit ──tap──▶ FreeformLauncher.launch(pkg)
```

### Komponen

| Komponen | Peran |
|---|---|
| `MainActivity` | Daftar aplikasi launcher, cari, favorit (SharedPreferences, long-press), kartu status kompatibilitas, tombol aktifkan bubble + alur izin overlay |
| `FreeformLauncher` | Objek tunggal: `defaultBounds()` (±80%×72% di tengah), `launch()` via `ActivityOptions`, `isFreeformSupported()` |
| `BubbleService` | Foreground service (`specialUse`), bubble overlay bisa digeser, panel mini favorit |

### API Android yang dipakai

- `ActivityOptions.makeBasic()` → `launchBounds: Rect` (API 24+) → `setLaunchWindowingMode(WINDOWING_MODE_FREEFORM)` (API 28+, dipagari `SDK_INT >= P`)
- `Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_LAUNCH_ADJACENT`
- `PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT` untuk kartu status
- `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` + deklarasi `<queries>` di manifest — **tanpa `QUERY_ALL_PACKAGES`**
- Overlay: `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` + `Settings.canDrawOverlays()` / `ACTION_MANAGE_OVERLAY_PERMISSION`
- Foreground service type `specialUse` + `<property android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE>` (wajib di targetSdk 34+)

### Izin

`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`. Tidak ada `INTERNET`, tidak ada `QUERY_ALL_PACKAGES`, tidak ada izin berbahaya lain.

### Batasan desain

- Tidak memaksa aplikasi yang menolak windowing (batas API publik; jujur di README).
- v0.1.0: ukuran jendela awal tetap (80%×72%); kustomisasi ukuran/posisi menyusul.
- Tema: Material3 terang bersih (liquid glass penuh menyusul setelah fungsi stabil).

## Toolchain & rilis

- Gradle wrapper 8.14.5, AGP 8.13.2, JDK 21, Kotlin 1.9.23, AndroidX.
- Build **hanya** di GitHub Actions (`build.yml`): setup-java Temurin 21, sdkmanager `platforms;android-35` + `build-tools;35.0.0`, restore keystore JKS dari secret, `./gradlew :app:assembleRelease`.
- Verifikasi CI: `apksigner verify --print-certs` → fingerprint SHA-256 **harus sama** dengan yang di-pin; `aapt2 dump badging` → paket `id.ruangapung`, versionCode 1.
- Rilis via GitHub Release (`v0.1.0`) berisi APK + SHA-256.
