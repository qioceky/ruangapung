package id.ruangapung

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.hardware.display.VirtualDisplay

/**
 * Satu jendela mengambang "beneran": overlay milik sendiri yang menampilkan
 * TextureView berisi render VirtualDisplay tempat aplikasi target berjalan
 * (diluncurkan via `am start --display` oleh Shizuku user-service).
 *
 * - Title bar: geser untuk memindah jendela; tombol back & tutup.
 * - Handle pojok kanan bawah: geser untuk resize (VD ikut di-resize).
 * - Sentuhan di TextureView diteruskan (diskala) ke display virtual via
 *   injeksi input oleh PrivilegedService.
 */
class FloatingWindowService : Service() {

    companion object {
        const val ACTION_OPEN = "id.ruangapung.OPEN_FLOATING"
        const val ACTION_CLOSE = "id.ruangapung.CLOSE_FLOATING"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var texture: TextureView? = null
    private var params: WindowManager.LayoutParams? = null
    private var vd: VirtualDisplay? = null

    private var displayId: Int = -1
    private var pkg: String = ""
    private var vdW = 0
    private var vdH = 0
    private var launched = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLOSE -> {
                teardown()
                return START_NOT_STICKY
            }
            else -> {
                val p = intent?.getStringExtra(EXTRA_PKG)
                if (p.isNullOrEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!Settings.canDrawOverlays(this)) {
                    toast(getString(R.string.overlay_permission_needed))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (root == null) {
                    pkg = p
                    showWindow(intent.getStringExtra(EXTRA_LABEL) ?: p)
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "floating"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                getString(R.string.notif_channel_floating),
                NotificationManager.IMPORTANCE_MIN,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text_floating))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(2, notif)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showWindow(label: String) {
        val dpi = resources.displayMetrics.densityDpi
        vd = FloatingEngine.createDisplay(this, 720, 1280, dpi)
        val vdi = vd
        if (vdi == null) {
            toast(getString(R.string.vd_failed))
            stopSelf()
            return
        }
        displayId = vdi.display.displayId

        val v = LayoutInflater.from(this).inflate(R.layout.view_floating, null)
        v.findViewById<TextView>(R.id.winLabel).text = label
        texture = v.findViewById(R.id.winTexture)

        val dm = resources.displayMetrics
        val w = dm.widthPixels * 3 / 4
        val h = dm.heightPixels * 3 / 5
        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - w) / 2
            y = (dm.heightPixels - h) / 3
        }

        wireDrag(v)
        wireResize(v)
        v.findViewById<View>(R.id.btnClose).setOnClickListener { teardown() }
        v.findViewById<View>(R.id.btnBack).setOnClickListener {
            ShizukuCtl.injectKey(KeyEvent.KEYCODE_BACK, displayId)
        }

        val tv = texture ?: run { teardown(); return }
        tv.setOnTouchListener { view, ev ->
            forwardTouch(view, ev)
            true
        }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                vd?.setSurface(Surface(st))
                resizeVdToTexture()
                if (!launched) {
                    launched = true
                    FloatingEngine.launchOnDisplay(this@FloatingWindowService, pkg, displayId) { ok ->
                        ui.post {
                            if (!ok) {
                                toast(getString(R.string.open_failed, label))
                                teardown()
                            }
                        }
                    }
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                resizeVdToTexture()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                teardown()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        try {
            wm.addView(v, params)
        } catch (_: Exception) {
            toast(getString(R.string.bubble_failed))
            teardown()
            return
        }
        root = v
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireDrag(v: View) {
        val title = v.findViewById<View>(R.id.titleBar)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        title.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = p.x
                    startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (ev.rawX - downX).toInt()
                    p.y = startY + (ev.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, p) }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireResize(v: View) {
        val handle = v.findViewById<View>(R.id.resizeHandle)
        var downX = 0f
        var downY = 0f
        var startW = 0
        var startH = 0
        handle.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startW = p.width
                    startH = p.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.width = (startW + (ev.rawX - downX)).toInt().coerceAtLeast(280)
                    p.height = (startH + (ev.rawY - downY)).toInt().coerceAtLeast(380)
                    runCatching { wm.updateViewLayout(v, p) }
                    texture?.post { resizeVdToTexture() }
                    true
                }
                else -> false
            }
        }
    }

    /** Samakan ukuran VD dengan TextureView agar 1:1 (tajam, tanpa distorsi). */
    private fun resizeVdToTexture() {
        val t = texture ?: return
        val w = t.width
        val h = t.height
        if (w > 0 && h > 0) {
            vdW = w
            vdH = h
            runCatching { vd?.resize(w, h, resources.displayMetrics.densityDpi) }
        }
    }

    /** Teruskan sentuhan (diskala ke koordinat VD) ke injeksi input. */
    private fun forwardTouch(tv: View, ev: MotionEvent) {
        val tw = tv.width
        val th = tv.height
        if (tw <= 0 || th <= 0 || vdW <= 0 || vdH <= 0 || displayId < 0) return
        val n = ev.pointerCount
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val sx = vdW / tw.toFloat()
        val sy = vdH / th.toFloat()
        for (i in 0 until n) {
            xs[i] = ev.getX(i) * sx
            ys[i] = ev.getY(i) * sy
        }
        ShizukuCtl.injectTouch(ev.action, xs, ys, displayId)
    }

    private fun teardown() {
        launched = false
        runCatching { vd?.release() }
        vd = null
        displayId = -1
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        texture = null
        params = null
        stopSelf()
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
