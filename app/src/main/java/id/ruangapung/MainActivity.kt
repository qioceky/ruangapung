package id.ruangapung

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
        var favorite: Boolean,
    )

    companion object {
        private const val SHIZUKU_REQ = 1001
        const val PREF_WINDOW_SIZE = "window_size" // 0=kecil, 1=sedang, 2=besar

        /** Fraksi (lebar, tinggi) layar untuk ukuran jendela bawaan. */
        fun windowFractions(context: Context): Pair<Float, Float> {
            return when (
                context.getSharedPreferences("ruangapung", MODE_PRIVATE)
                    .getInt(PREF_WINDOW_SIZE, 1)
            ) {
                0 -> 0.60f to 0.50f
                2 -> 0.90f to 0.75f
                else -> 0.75f to 0.60f
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    private val allApps = mutableListOf<AppEntry>()
    private val shownApps = mutableListOf<AppEntry>()
    private lateinit var adapter: AppAdapter

    private lateinit var pageHome: View
    private lateinit var pageSettings: View
    private lateinit var tabHome: TextView
    private lateinit var tabFav: TextView
    private lateinit var tabSettings: TextView
    private lateinit var statusCard: TextView
    private lateinit var searchBox: EditText
    private lateinit var emptyView: TextView

    private lateinit var setShizukuCard: TextView
    private lateinit var setShizukuButton: Button
    private lateinit var setWindowSizeValue: TextView
    private lateinit var setBubbleToggle: Button
    private lateinit var setCrashLog: Button
    private lateinit var setAbout: TextView

    private var tab = 0 // 0=Beranda, 1=Favorit, 2=Pengaturan
    private var shizukuHintShown = false
    private var bindingShizuku = false

    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindShizuku()
                } else {
                    updateShizukuUi()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ruangapung", MODE_PRIVATE)

        pageHome = findViewById(R.id.pageHome)
        pageSettings = findViewById(R.id.pageSettings)
        tabHome = findViewById(R.id.tabHome)
        tabFav = findViewById(R.id.tabFav)
        tabSettings = findViewById(R.id.tabSettings)
        statusCard = findViewById(R.id.statusCard)
        searchBox = findViewById(R.id.searchBox)
        emptyView = findViewById(R.id.emptyView)
        val list = findViewById<RecyclerView>(R.id.appList)
        list.layoutManager = LinearLayoutManager(this)

        setShizukuCard = findViewById(R.id.setShizukuCard)
        setShizukuButton = findViewById(R.id.setShizukuButton)
        setWindowSizeValue = findViewById(R.id.setWindowSizeValue)
        setBubbleToggle = findViewById(R.id.setBubbleToggle)
        setCrashLog = findViewById(R.id.setCrashLog)
        setAbout = findViewById(R.id.setAbout)

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        adapter = AppAdapter(
            shownApps,
            onTap = { entry -> openApp(entry) },
            onLongPress = { entry ->
                entry.favorite = !entry.favorite
                saveFavorites()
                Toast.makeText(
                    this,
                    if (entry.favorite) getString(R.string.added_favorite, entry.label)
                    else getString(R.string.removed_favorite, entry.label),
                    Toast.LENGTH_SHORT,
                ).show()
                refreshList(searchBox.text.toString())
            },
        )
        list.adapter = adapter

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshList(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        tabHome.setOnClickListener { selectTab(0) }
        tabFav.setOnClickListener { selectTab(1) }
        tabSettings.setOnClickListener { selectTab(2) }

        // Tahan kartu status untuk menyalin log crash terakhir (bila ada).
        statusCard.setOnLongClickListener {
            if (RuangApungApp.copyLastCrash(this)) {
                Toast.makeText(this, getString(R.string.crash_copied), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.no_crash_log), Toast.LENGTH_SHORT).show()
            }
            true
        }

        // --- Pengaturan ---
        findViewById<View>(R.id.setWindowSizeRow).setOnClickListener { showWindowSizeDialog() }
        setBubbleToggle.setOnClickListener {
            if (BubbleService.isRunning) {
                stopService(Intent(this, BubbleService::class.java))
                Toast.makeText(this, getString(R.string.bubble_stopped), Toast.LENGTH_SHORT).show()
            } else {
                ensureOverlayThenStartBubble()
            }
            updateSettingsUi()
        }
        setCrashLog.setOnClickListener {
            if (RuangApungApp.copyLastCrash(this)) {
                Toast.makeText(this, getString(R.string.crash_copied), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.no_crash_log), Toast.LENGTH_SHORT).show()
            }
        }
        setAbout.text = aboutText()

        loadApps()
        selectTab(0)
        refreshList("")
    }

    override fun onResume() {
        super.onResume()
        updateStatusCard()
        updateSettingsUi()
        refreshList(searchBox.text.toString())
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    // ---------- Tab ----------

    private fun selectTab(i: Int) {
        tab = i
        pageHome.visibility = if (i == 2) View.GONE else View.VISIBLE
        pageSettings.visibility = if (i == 2) View.VISIBLE else View.GONE
        styleTab(tabHome, i == 0)
        styleTab(tabFav, i == 1)
        styleTab(tabSettings, i == 2)
        refreshList(searchBox.text.toString())
    }

    private fun styleTab(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) 0xFF3B82F6.toInt() else 0xFF8A93A6.toInt())
        tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ---------- Buka aplikasi ----------

    private fun openApp(entry: AppEntry) {
        if (ShizukuCtl.isReady()) {
            openFloating(entry)
        } else {
            // Fallback lama bila Shizuku belum siap.
            if (!shizukuHintShown) {
                shizukuHintShown = true
                Toast.makeText(this, getString(R.string.hint_shizuku), Toast.LENGTH_LONG).show()
            }
            if (FreeformLauncher.launch(this, entry.packageName)) {
                Toast.makeText(this, getString(R.string.opening_floating, entry.label), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.open_failed, entry.label), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openFloating(entry: AppEntry) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.overlay_permission_needed), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val i = Intent(this, FloatingWindowService::class.java)
            .setAction(FloatingWindowService.ACTION_OPEN)
            .putExtra(FloatingWindowService.EXTRA_PKG, entry.packageName)
            .putExtra(FloatingWindowService.EXTRA_LABEL, entry.label)
        startForegroundService(i)
        Toast.makeText(this, getString(R.string.opening_floating, entry.label), Toast.LENGTH_SHORT).show()
    }

    // ---------- Shizuku ----------

    private fun updateShizukuUi() {
        when (ShizukuCtl.state(this)) {
            ShizukuCtl.State.NOT_INSTALLED -> {
                setShizukuCard.text = getString(R.string.shizuku_card_not_installed)
                setShizukuButton.text = getString(R.string.shizuku_btn_install)
                setShizukuButton.setOnClickListener { ShizukuCtl.openInStore(this) }
            }
            ShizukuCtl.State.NOT_RUNNING -> {
                setShizukuCard.text = getString(R.string.shizuku_card_not_running)
                setShizukuButton.text = getString(R.string.shizuku_btn_guide)
                setShizukuButton.setOnClickListener { showShizukuGuide() }
            }
            ShizukuCtl.State.DENIED -> {
                setShizukuCard.text = getString(R.string.shizuku_card_denied)
                setShizukuButton.text = getString(R.string.shizuku_btn_request)
                setShizukuButton.setOnClickListener {
                    ShizukuCtl.requestPermission(this, SHIZUKU_REQ)
                }
            }
            ShizukuCtl.State.NOT_BOUND -> {
                setShizukuCard.text = getString(R.string.shizuku_card_not_bound)
                setShizukuButton.text = getString(R.string.shizuku_btn_retry)
                setShizukuButton.setOnClickListener { bindShizuku() }
                bindShizuku()
            }
            ShizukuCtl.State.READY -> {
                setShizukuCard.text = getString(R.string.shizuku_card_ready)
                setShizukuButton.text = getString(R.string.shizuku_btn_guide)
                setShizukuButton.setOnClickListener { showShizukuGuide() }
            }
        }
    }

    private fun bindShizuku() {
        if (bindingShizuku) return
        bindingShizuku = true
        ShizukuCtl.ensureBound(this) { ok, err ->
            runOnUiThread {
                bindingShizuku = false
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.shizuku_bound_ok)
                    else getString(R.string.shizuku_bound_fail, err ?: "?"),
                    Toast.LENGTH_LONG,
                ).show()
                updateShizukuUi()
            }
        }
    }

    private fun showShizukuGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.shizuku_guide_title)
            .setMessage(R.string.shizuku_guide)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- Pengaturan ----------

    private fun updateSettingsUi() {
        updateShizukuUi()
        setWindowSizeValue.text = windowSizeLabel()
        setBubbleToggle.text = getString(
            if (BubbleService.isRunning) R.string.bubble_on else R.string.bubble_off,
        )
        val hasCrash = RuangApungApp.lastCrash(this) != null
        setCrashLog.isEnabled = hasCrash
        setCrashLog.alpha = if (hasCrash) 1f else 0.5f
    }

    private fun windowSizeLabel(): String = when (prefs.getInt(PREF_WINDOW_SIZE, 1)) {
        0 -> getString(R.string.window_size_small)
        2 -> getString(R.string.window_size_large)
        else -> getString(R.string.window_size_medium)
    }

    private fun showWindowSizeDialog() {
        val items = arrayOf(
            getString(R.string.window_size_small),
            getString(R.string.window_size_medium),
            getString(R.string.window_size_large),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.set_window_size)
            .setSingleChoiceItems(items, prefs.getInt(PREF_WINDOW_SIZE, 1)) { d, which ->
                prefs.edit().putInt(PREF_WINDOW_SIZE, which).apply()
                setWindowSizeValue.text = windowSizeLabel()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun aboutText(): String {
        val v = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (_: Exception) {
            "?"
        }
        return "RuangApung $v\nid.ruangapung"
    }

    // ---------- Daftar aplikasi ----------

    private fun loadApps() {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        val favs = prefs.getStringSet("favorites", emptySet()).orEmpty()
        allApps.clear()
        val seen = HashSet<String>()
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (!seen.add(pkg) || pkg == packageName) continue
            val label = ri.loadLabel(pm).toString()
            allApps.add(AppEntry(label, pkg, ri.loadIcon(pm), pkg in favs))
        }
        allApps.sortWith(compareByDescending<AppEntry> { it.favorite }.thenBy { it.label.lowercase() })
    }

    private fun saveFavorites() {
        prefs.edit()
            .putStringSet("favorites", allApps.filter { it.favorite }.map { it.packageName }.toSet())
            .apply()
    }

    private fun refreshList(query: String) {
        val favOnly = tab == 1
        val q = query.trim().lowercase()
        shownApps.clear()
        shownApps.addAll(
            allApps.filter {
                (!favOnly || it.favorite) &&
                    (q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q))
            }.sortedWith(compareByDescending<AppEntry> { it.favorite }.thenBy { it.label.lowercase() }),
        )
        adapter.notifyDataSetChanged()
        if (shownApps.isEmpty()) {
            emptyView.text = getString(
                if (favOnly) R.string.empty_favorites else R.string.no_apps,
            )
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
    }

    private fun updateStatusCard() {
        // Engine kita tak butuh freeform sistem bila Shizuku siap: sembunyikan peringatan.
        if (ShizukuCtl.state(this) == ShizukuCtl.State.READY) {
            statusCard.visibility = View.GONE
            return
        }
        statusCard.visibility = View.VISIBLE
        statusCard.text = if (FreeformLauncher.isFreeformSupported(this)) {
            getString(R.string.status_supported)
        } else {
            getString(R.string.status_unsupported)
        }
    }

    private fun ensureOverlayThenStartBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.overlay_permission_needed), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val svc = Intent(this, BubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        Toast.makeText(this, getString(R.string.bubble_started), Toast.LENGTH_SHORT).show()
    }

    private inner class AppAdapter(
        private val items: List<AppEntry>,
        private val onTap: (AppEntry) -> Unit,
        private val onLongPress: (AppEntry) -> Unit,
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rowIcon)
            val name: TextView = v.findViewById(R.id.rowName)
            val sub: TextView = v.findViewById(R.id.rowSub)
            val star: TextView = v.findViewById(R.id.rowStar)
            val floatBtn: TextView = v.findViewById(R.id.rowFloat)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.row_app, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val e = items[pos]
            h.icon.setImageDrawable(e.icon)
            h.name.text = e.label
            h.sub.text = e.packageName
            h.star.visibility = if (e.favorite) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { onTap(e) }
            h.floatBtn.setOnClickListener { onTap(e) }
            h.itemView.setOnLongClickListener { onLongPress(e); true }
        }
    }
}
