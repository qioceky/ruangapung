package id.ruangapung

import android.os.IBinder
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Implementasi IPrivilegedService. Dijalankan sebagai Shizuku user-service:
 * proses terpisah dengan UID shell, sehingga bisa memanggil `am`,
 * dan menginjeksi input via InputManager (butuh INJECT_EVENTS milik shell).
 *
 * Arsitektur meniru ControlService milik Mi-Freeform (com.sunshine.freeform).
 */
class PrivilegedService : IPrivilegedService.Stub() {

    @Volatile
    private var inputManager: Any? = null

    @Volatile
    private var injectMethod: Method? = null

    /**
     * Ambil IInputManager via ServiceManager (refleksi, seperti
     * ControlService.init() di referensi).
     */
    override fun init(): Boolean {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder ?: return false
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val im = asInterface.invoke(null, binder) ?: return false
            // IInputManager.aidl: boolean injectInputEvent(in InputEvent ev, int displayId)
            injectMethod = im.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            inputManager = im
            true
        } catch (_: Exception) {
            inputManager = null
            injectMethod = null
            false
        }
    }

    override fun execShell(cmd: String): Int {
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            // Konsumsi output agar pipe tidak deadlock; `am` output-nya kecil.
            runCatching { proc.inputStream.readBytes() }
            proc.waitFor()
        } catch (_: Exception) {
            -999
        }
    }

    override fun injectTouch(action: Int, xs: FloatArray, ys: FloatArray, displayId: Int) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        if (xs.isEmpty() || xs.size != ys.size) return
        try {
            val n = xs.size
            val props = Array(n) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(n) { i ->
                MotionEvent.PointerCoords().apply {
                    x = xs[i]
                    y = ys[i]
                    pressure = 1f
                    size = 1f
                }
            }
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(
                now, now, action, n, props, coords,
                0, 0, 1f, 1f, -1, 0,
                4098, // InputDevice.SOURCE_TOUCHSCREEN
                0,
            )
            setDisplayId(ev, displayId)
            inject.invoke(im, ev, displayId)
            ev.recycle()
        } catch (_: Exception) {
            // Fire-and-forget: jangan crash service karena satu sentuhan gagal.
        }
    }

    override fun injectKey(keyCode: Int, displayId: Int) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return
        try {
            val now = SystemClock.uptimeMillis()
            for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val ev = KeyEvent(now, now, a, keyCode, 0)
                setDisplayId(ev, displayId)
                inject.invoke(im, ev, displayId)
                // KeyEvent.recycle() tidak ada di SDK publik — biarkan GC yang urus.
            }
        } catch (_: Exception) {
        }
    }

    private fun setDisplayId(ev: InputEvent, displayId: Int) {
        try {
            InputEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(ev, displayId)
        } catch (_: Exception) {
        }
    }
}
