package id.ruangapung

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Gelembung tepi layar: bisa digeser, ketuk untuk membuka panel mini berisi
 * aplikasi favorit, ketuk favorit untuk dibuka sebagai jendela mengambang.
 * Berjalan sebagai foreground service; butuh izin overlay (SYSTEM_ALERT_WINDOW).
 */
class BubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.overlay_permission_needed), Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        bubble = null
        panel = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val v = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.4).toInt()
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (dx * dx + dy * dy > 100) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(v, params)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.bubble_failed), Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        bubble = v
        bubbleParams = params
    }

    private fun togglePanel() {
        if (panel != null) {
            panel?.let { runCatching { wm.removeView(it) } }
            panel = null
            return
        }
        showPanel()
    }

    /**
     * Buka aplikasi: via jendela mengambang beneran (Shizuku) bila siap,
     * atau fallback lama (ActivityOptions) bila belum.
     */
    private fun openAppFloating(pkg: String, label: String) {
        if (ShizukuCtl.isReady() && Settings.canDrawOverlays(this)) {
            val i = Intent(this, FloatingWindowService::class.java)
                .setAction(FloatingWindowService.ACTION_OPEN)
                .putExtra(FloatingWindowService.EXTRA_PKG, pkg)
                .putExtra(FloatingWindowService.EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            Toast.makeText(this, getString(R.string.opening_floating, label), Toast.LENGTH_SHORT).show()
        } else {
            if (FreeformLauncher.launch(this, pkg)) {
                Toast.makeText(this, getString(R.string.opening_floating, label), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.open_failed, label), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPanel() {
        val prefs = getSharedPreferences("ruangapung", MODE_PRIVATE)
        val favs = prefs.getStringSet("favorites", emptySet()).orEmpty().toList()
        if (favs.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_favorites), Toast.LENGTH_LONG).show()
            return
        }
        val pm = packageManager
        val v = LayoutInflater.from(this).inflate(R.layout.view_panel, null)
        val container = v.findViewById<LinearLayout>(R.id.panelList)
        for (pkg in favs.sorted()) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                ?: pkg
            val row = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(36, 28, 36, 28)
            }
            row.setOnClickListener {
                openAppFloating(pkg, label)
                togglePanel()
            }
            container.addView(row)
        }
        val closeRow = TextView(this).apply {
            text = getString(R.string.close)
            textSize = 13f
            setPadding(36, 28, 36, 28)
            alpha = 0.6f
        }
        closeRow.setOnClickListener { togglePanel() }
        container.addView(closeRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (bubbleParams?.y ?: 300) - 40
        }
        wm.addView(v, params)
        panel = v
        panelParams = params
    }
}
