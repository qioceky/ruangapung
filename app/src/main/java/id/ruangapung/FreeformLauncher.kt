package id.ruangapung

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build

/**
 * Membuka aplikasi lain dalam jendela mengambang (freeform) memakai API publik Android.
 * Tanpa root, tanpa Xposed, tanpa Shizuku.
 *
 * Batasan jujur: hanya berfungsi bila perangkat mendukung mode freeform
 * (PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) dan aplikasi target
 * mengizinkan windowing. Tidak bisa "memaksa" semua aplikasi.
 */
object FreeformLauncher {

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            opts.setLaunchWindowingMode(ActivityOptions.WINDOWING_MODE_FREEFORM)
        }
        return try {
            context.startActivity(intent, opts.toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isFreeformSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
        )
}
