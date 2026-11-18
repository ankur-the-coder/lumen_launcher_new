package com.example.tvlauncher

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Minimal file browser so USB drives and storage can be opened straight from the launcher. Videos play in Lumen's player. */
class FilesActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private lateinit var adapter: EpAdapter
    private var cur: File? = null
    private var entries: List<Episode> = emptyList()
    private val VIDEO = setOf("mkv", "mp4", "avi", "ts", "m4v", "mov", "webm", "wmv", "flv", "mpg", "mpeg", "m2ts", "3gp", "mts")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(resources.getColor(R.color.bg))
        root.setPadding(dp(64), dp(40), dp(64), dp(24))
        title = TextView(this)
        title.textSize = 26f
        title.setTextColor(0xFFFFFFFF.toInt())
        title.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        title.setPadding(dp(8), 0, 0, dp(14))
        root.addView(title)
        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.clipToPadding = false
        adapter = EpAdapter({ i -> open(entries[i]) }, { })
        adapter.hideStatus = true
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showRoots()
    }

    private fun showRoots() {
        cur = null
        title.text = "Files and USB"
        val vols = Storage.volumes(this)
        show(vols.map { Episode(if (it.removable) "USB / SD  -  " + it.root.name else "Internal storage", it.root.path) })
        if (vols.isEmpty()) Toast.makeText(this, "No storage found. Check Settings > Storage access.", Toast.LENGTH_LONG).show()
    }

    private fun show(items: List<Episode>) {
        entries = items
        adapter.items = items
        adapter.notifyDataSetChanged()
        list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    private fun open(e: Episode) {
        val f = File(e.path)
        if (f.isDirectory) {
            cur = f
            title.text = f.path
            val kids = (f.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") && (it.isDirectory || it.extension.lowercase() in VIDEO) }
                .sortedWith { a, b -> if (a.isDirectory != b.isDirectory) (if (a.isDirectory) -1 else 1) else U.naturalCompare(a.name, b.name) }
            show(kids.map { Episode(if (it.isDirectory) it.name + "  /" else it.name, it.path) })
        } else {
            val i = Intent(this, PlayerActivity::class.java)
            i.putExtra("dir", f.parent)
            i.putExtra("path", f.path)
            startActivity(i)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val c = cur
        if (c == null) { super.onBackPressed(); return }
        val roots = Storage.volumes(this).map { it.root.path }
        if (c.path in roots || c.parentFile == null) showRoots() else open(Episode("", c.parentFile!!.path))
    }
}
