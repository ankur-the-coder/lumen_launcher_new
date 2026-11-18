package com.example.tvlauncher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val iconSize: Int,
    private val onFocus: (AppInfo) -> Unit,
    private val onClick: (AppInfo) -> Unit,
    private val onLong: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {
    var items: List<AppInfo> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: FrameLayout = v.findViewById(R.id.box)
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val h = VH(v)
        h.box.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        U.round(h.box, parent.context.dp(14).toFloat())
        h.box.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onClick(items[p])
        }
        h.box.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onLong(items[p])
            true
        }
        h.box.setOnFocusChangeListener { view, has ->
            Motion.focus(view, has, 1.16f, true)
            h.label.setTextColor(if (has) 0xFFFFFFFF.toInt() else 0xFFD8E0EE.toInt())
            if (has) {
                val p = h.bindingAdapterPosition
                if (p >= 0) onFocus(items[p])
            }
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        h.icon.setImageBitmap(a.bmp)
        h.label.text = a.label
    }
}

class DrawerActivity : AppCompatActivity() {
    private lateinit var ambient: AmbientView
    private lateinit var grid: RecyclerView
    private lateinit var adapter: AppAdapter
    private var apps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drawer)
        ambient = findViewById(R.id.ambient)
        ambient.blurIn()
        grid = findViewById(R.id.grid)
        findViewById<TextView>(R.id.hTitle).text = "All apps"
        findViewById<TextView>(R.id.hSub).text = "Hold OK on an app to place it in the dock, move or uninstall it"

        val dm = resources.displayMetrics
        val iconSize = (dm.heightPixels * 0.115f).toInt()
        val cell = iconSize + dp(64)
        val span = ((dm.widthPixels - dp(128)) / cell).coerceIn(4, 9)
        grid.layoutManager = GridLayoutManager(this, span)
        grid.clipChildren = false
        grid.itemAnimator = null
        adapter = AppAdapter(iconSize, { a -> ambient.setTarget(a.color) }, { a ->
            if (!DockOps.launch(this, a)) Toast.makeText(this, "Could not open " + a.label, Toast.LENGTH_SHORT).show()
        }, { a ->
            DockOps.menuFor(this, a, apps) { Toast.makeText(this, "Dock updated", Toast.LENGTH_SHORT).show() }
        })
        grid.adapter = adapter
        Bg.io.execute {
            val list = Apps.load(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = list
                adapter.items = list
                adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.hTitle).text = "All apps  " + list.size
                grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<View>(R.id.box)?.requestFocus() }
                Motion.rise(grid, 40, 28f)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ambient.invalidate()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.exit)
    }
}
