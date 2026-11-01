package id.ruangapung

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay

/**
 * Mesin jendela mengambang "beneran": VirtualDisplay + `am start --display`
 * via Shizuku user-service. Meniru arsitektur Mi-Freeform.
 */
object FloatingEngine {

    /**
     * Buat virtual display untuk me-render aplikasi target.
     * Flag PRESENTATION tidak butuh izin khusus.
     */
    fun createDisplay(context: Context, w: Int, h: Int, dpi: Int): VirtualDisplay? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return try {
            dm.createVirtualDisplay(
                "RuangApung",
                w, h, dpi,
                null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Luncurkan aplikasi ke display virtual via shell (`am start --display`).
     * Callback dipanggil di thread binder Shizuku — JANGAN sentuh UI langsung.
     */
    fun launchOnDisplay(
        context: Context,
        packageName: String,
        displayId: Int,
        cb: (ok: Boolean) -> Unit,
    ) {
        ShizukuCtl.exec { svc ->
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                val cn = intent?.component
                if (cn == null) {
                    cb(false)
                    return@exec
                }
                // Nama komponen dikutip karena bisa mengandung '$' (inner class).
                val cmd = "am start -n '${cn.flattenToString()}' --display $displayId"
                val rc = svc.execShell(cmd)
                cb(rc == 0)
            } catch (_: Exception) {
                cb(false)
            }
        }
    }
}
