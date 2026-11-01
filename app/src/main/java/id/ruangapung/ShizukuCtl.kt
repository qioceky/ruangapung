package id.ruangapung

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pengatur integrasi Shizuku: status, minta izin, dan bind ke
 * PrivilegedService (user-service, UID shell).
 *
 * Semua panggilan binder berjalan di thread pool sendiri — JANGAN panggil
 * dari main thread. Callback [ensureBound] juga tiba di thread binder,
 * jadi caller wajib post ke UI thread bila menyentuh tampilan.
 */
object ShizukuCtl {

    enum class State {
        NOT_INSTALLED, // aplikasi Shizuku belum terpasang
        NOT_RUNNING,   // terpasang tapi service-nya belum jalan
        DENIED,        // izin Shizuku untuk aplikasi ini belum diberikan
        NOT_BOUND,     // izin ada, tapi user-service belum terhubung
        READY,         // siap dipakai
    }

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private val binderThreads = Executors.newCachedThreadPool()

    @Volatile
    private var service: IPrivilegedService? = null

    @Volatile
    private var bound = false

    fun isShizukuAppInstalled(context: Context): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        }.getOrDefault(false)

    fun state(context: Context): State {
        if (!isShizukuAppInstalled(context)) return State.NOT_INSTALLED
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return State.NOT_RUNNING
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.DENIED
        if (!bound || service == null) return State.NOT_BOUND
        return State.READY
    }

    fun isReady(): Boolean = bound && service != null

    fun requestPermission(activity: Activity, requestCode: Int) {
        activity.runOnUiThread { Shizuku.requestPermission(requestCode) }
    }

    fun openInStore(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * Pastikan user-service ter-bind dan init() sukses.
     * Callback dipanggil di thread binder: [ok]=true bila siap dipakai.
     */
    fun ensureBound(context: Context, cb: (ok: Boolean, err: String?) -> Unit) {
        binderThreads.execute {
            try {
                if (!Shizuku.pingBinder()) {
                    cb(false, context.getString(R.string.shizuku_card_not_running))
                    return@execute
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    cb(false, context.getString(R.string.shizuku_card_denied))
                    return@execute
                }
                if (bound && service != null) {
                    cb(true, null)
                    return@execute
                }
                val latch = CountDownLatch(1)
                val ref = AtomicReference<IBinder?>()
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        ref.set(binder)
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                        bound = false
                    }
                }
                val args = UserServiceArgs(
                    ComponentName(context.packageName, PrivilegedService::class.java.name),
                )
                    .version(BuildConfig.VERSION_CODE)
                    .processNameSuffix("priv")
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                Shizuku.bindUserService(args, conn)
                if (!latch.await(20, TimeUnit.SECONDS)) {
                    cb(false, "Timeout menghubungkan service Shizuku")
                    return@execute
                }
                val binder = ref.get()
                if (binder == null) {
                    cb(false, "Binder Shizuku null")
                    return@execute
                }
                val svc = IPrivilegedService.Stub.asInterface(binder)
                    ?: run { cb(false, "Binder Shizuku null"); return@execute }
                val ok = runCatching { svc.init() }.getOrDefault(false)
                if (!ok) {
                    cb(false, "Gagal init InputManager di service Shizuku")
                    return@execute
                }
                service = svc
                bound = true
                cb(true, null)
            } catch (e: Exception) {
                cb(false, e.message ?: e.toString())
            }
        }
    }

    /** Jalankan blok dengan service di thread binder. Diam bila belum siap. */
    fun exec(block: (IPrivilegedService) -> Unit) {
        binderThreads.execute {
            val s = service ?: return@execute
            try {
                block(s)
            } catch (_: Exception) {
            }
        }
    }

    fun injectTouch(action: Int, xs: FloatArray, ys: FloatArray, displayId: Int) {
        exec { s -> runCatching { s.injectTouch(action, xs, ys, displayId) } }
    }

    fun injectKey(keyCode: Int, displayId: Int) {
        exec { s -> runCatching { s.injectKey(keyCode, displayId) } }
    }
}
