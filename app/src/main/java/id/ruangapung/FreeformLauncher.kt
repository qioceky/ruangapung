package id.ruangapung

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/**
 * Membuka aplikasi lain dalam jendela mengambang (freeform).
 * Tanpa root, tanpa Xposed, tanpa Shizuku.
 *
 * `setLaunchBounds` adalah API publik; `setLaunchWindowingMode` adalah API
 * tersembunyi AOSP sehingga dipanggil via refleksi (diabaikan bila gagal —
 * jendela tetap dibuka dengan bounds yang diminta).
 *
 * Batasan jujur: hanya berfungsi bila perangkat mendukung mode freeform
 * (PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) dan aplikasi target
 * mengizinkan windowing. Tidak bisa "memaksa" semua aplikasi.
 */
object FreeformLauncher {

    /** Nilai AOSP ActivityOptions.WINDOWING_MODE_FREEFORM (API tersembunyi). */
    private const val WINDOWING_MODE_FREEFORM = 5


    /** Persegi jendela: ~80% lebar, ~72% tinggi, di tengah layar. */
    fun defaultBounds(context: Context): Rect {
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.80).toInt()
        val h = (dm.heightPixels * 0.72).toInt()
        val left = (dm.widthPixels - w) / 2
        val top = ((dm.heightPixels - h) / 2 * 0.9).toInt()
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Luncurkan [packageName] sebagai jendela mengambang.
     * @return true bila intent berhasil dikirim.
     */
    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

        val opts = ActivityOptions.makeBasic()
        opts.launchBounds = defaultBounds(context)
        trySetFreeformWindowingMode(opts)
        return try {
            context.startActivity(intent, opts.toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Coba aktifkan mode freeform via API tersembunyi AOSP.
     * Gagal diam-diam bila perangkat memblokir aksesnya.
     */
    private fun trySetFreeformWindowingMode(opts: ActivityOptions) {
        try {
            val m = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType,
            )
            m.invoke(opts, WINDOWING_MODE_FREEFORM)
        } catch (_: Exception) {
            // Abaikan: launchBounds saja yang dipakai.
        }
    }

    fun isFreeformSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
        )
}
