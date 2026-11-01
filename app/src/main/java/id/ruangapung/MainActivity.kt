package id.ruangapung

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
        var favorite: Boolean,
    )

    private lateinit var prefs: SharedPreferences
    private val allApps = mutableListOf<AppEntry>()
    private val shownApps = mutableListOf<AppEntry>()
    private lateinit var adapter: AppAdapter
    private lateinit var statusCard: TextView
    private lateinit var bubbleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ruangapung", MODE_PRIVATE)

        statusCard = findViewById(R.id.statusCard)
        bubbleButton = findViewById(R.id.bubbleButton)
        val search = findViewById<EditText>(R.id.searchBox)
        val list = findViewById<RecyclerView>(R.id.appList)
        list.layoutManager = LinearLayoutManager(this)

        adapter = AppAdapter(
            shownApps,
            onTap = { entry ->
                if (FreeformLauncher.launch(this, entry.packageName)) {
                    Toast.makeText(this, getString(R.string.opening_floating, entry.label), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.open_failed, entry.label), Toast.LENGTH_LONG).show()
                }
            },
            onLongPress = { entry ->
                entry.favorite = !entry.favorite
                saveFavorites()
                Toast.makeText(
                    this,
                    if (entry.favorite) getString(R.string.added_favorite, entry.label)
                    else getString(R.string.removed_favorite, entry.label),
                    Toast.LENGTH_SHORT,
                ).show()
                refreshList(search.text.toString())
            },
        )
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshList(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        bubbleButton.setOnClickListener { ensureOverlayThenStartBubble() }

        loadApps()
        updateStatusCard()
        refreshList("")
    }

    override fun onResume() {
        super.onResume()
        updateBubbleButton()
        updateStatusCard()
    }

    private fun loadApps() {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
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
        val q = query.trim().lowercase()
        shownApps.clear()
        shownApps.addAll(
            allApps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                .sortedWith(compareByDescending<AppEntry> { it.favorite }.thenBy { it.label.lowercase() })
        )
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.emptyView).visibility =
            if (shownApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateStatusCard() {
        statusCard.text = if (FreeformLauncher.isFreeformSupported(this)) {
            getString(R.string.status_supported)
        } else {
            getString(R.string.status_unsupported)
        }
    }

    private fun updateBubbleButton() {
        bubbleButton.text = getString(R.string.bubble_toggle)
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
            h.itemView.setOnLongClickListener { onLongPress(e); true }
        }
    }
}
