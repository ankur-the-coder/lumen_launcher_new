package com.example.tvlauncher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class EpAdapter(
    private val onClick: (Int) -> Unit,
    private val onLong: (Int) -> Unit
) : RecyclerView.Adapter<EpAdapter.VH>() {
    var items: List<Episode> = emptyList()
    var selectMode = false   // used by the player drawer: rows are highlighted by code, not focus
    var current = -1
    var hideStatus = false
    var sel = -1

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.epName)
        val status: TextView = v.findViewById(R.id.epStatus)
        val bar: ProgressBar = v.findViewById(R.id.epBar)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        val h = VH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onClick(p)
        }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onLong(p)
            true
        }
        v.setOnFocusChangeListener { fv, has -> Motion.focus(fv, has, 1.025f) }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.name.text = e.name
        val p = Store.prog(e.path)
        if (selectMode) {
            h.itemView.isFocusable = false
            h.itemView.isSelected = position == sel
            Motion.focus(h.itemView, position == sel, 1.03f)
        }
        if (hideStatus) { h.status.text = ""; h.bar.visibility = View.GONE; return }
        when {
            position == current -> {
                h.status.text = "Playing"
                h.bar.visibility = if (p != null && p.dur > 0 && !p.done) View.VISIBLE else View.GONE
                if (p != null) h.bar.progress = (p.frac * 1000).toInt()
            }
            p == null || (p.pos <= 1000 && !p.done) -> {
                h.status.text = "Not played"
                h.bar.visibility = View.GONE
            }
            p.done -> {
                h.status.text = "Watched"
                h.bar.visibility = View.GONE
            }
            else -> {
                h.status.text = U.fmt(p.pos) + " / " + U.fmt(p.dur)
                h.bar.visibility = View.VISIBLE
                h.bar.progress = (p.frac * 1000).toInt()
            }
        }
    }
}

class EpisodesActivity : AppCompatActivity() {
    private lateinit var ambient: AmbientView
    private lateinit var list: RecyclerView
    private lateinit var adapter: EpAdapter
    private lateinit var poster: ImageView
    private lateinit var info: View
    private var dir = ""
    private var eps: List<Episode> = emptyList()
    private var focusedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episodes)
        dir = intent.getStringExtra("dir") ?: ""
        ambient = findViewById(R.id.ambient)
        ambient.blurIn()
        list = findViewById(R.id.list)
        poster = findViewById(R.id.poster)
        info = findViewById(R.id.info)
        val title = Library.titleByDir(dir)
        findViewById<TextView>(R.id.name).text = title?.name ?: java.io.File(dir).name

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val ph = (h * 0.54f).toInt()
        val ratio = if (title != null) title.pw.toFloat() / title.ph else 0.67f
        val pw = (ph * ratio).toInt().coerceAtMost((w * 0.30f).toInt())
        val pLp = poster.layoutParams as FrameLayout.LayoutParams
        pLp.width = pw
        pLp.height = (pw / ratio).toInt()
        pLp.leftMargin = (w * 0.06f).toInt()
        pLp.topMargin = (h * 0.14f).toInt()
        poster.layoutParams = pLp
        U.round(poster, dp(16).toFloat())
        val iLp = info.layoutParams as FrameLayout.LayoutParams
        iLp.width = maxOf(pw, dp(260))
        iLp.leftMargin = (w * 0.06f).toInt()
        iLp.topMargin = (h * 0.14f).toInt() + pLp.height + dp(18)
        info.layoutParams = iLp

        val lLp = list.layoutParams as FrameLayout.LayoutParams
        lLp.leftMargin = (w * 0.06f).toInt() + maxOf(pw, dp(260)) + (w * 0.05f).toInt()
        lLp.rightMargin = (w * 0.06f).toInt()
        lLp.topMargin = (h * 0.1f).toInt()
        lLp.bottomMargin = (h * 0.07f).toInt()
        list.layoutParams = lLp
        list.setPadding(dp(6), dp(8), dp(6), dp(8))

        if (title != null) {
            Images.load(title.poster, ph) { b ->
                if (b != null) poster.setImageBitmap(b)
                ambient.setTarget(Images.color(title.poster) ?: Color.parseColor("#34447A"))
            }
        }

        adapter = EpAdapter({ pos -> play(pos) }, { pos ->
            val e = eps[pos]
            val p = Store.prog(e.path)
            Store.setDone(e.path, !(p?.done ?: false))
            adapter.notifyItemChanged(pos)
            updateCount()
        })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        Bg.io.execute {
            val found = Library.episodes(dir)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                eps = found
                adapter.items = found
                adapter.notifyDataSetChanged()
                updateCount()
                focusTarget()
                Motion.rise(list, 40, 30f)
            }
        }
    }

    private fun updateCount() {
        val watched = eps.count { Store.prog(it.path)?.done == true }
        findViewById<TextView>(R.id.count).text =
            eps.size.toString() + (if (eps.size == 1) " video" else " videos") + "  -  " + watched + " watched  -  hold OK to mark watched"
    }

    private fun focusTarget() {
        if (eps.isEmpty()) return
        val last = Store.last(dir)
        var idx = if (last != null) eps.indexOfFirst { it.path == last } else 0
        if (idx < 0) idx = 0
        list.scrollToPosition(idx)
        list.post {
            val vh = list.findViewHolderForAdapterPosition(idx)
            if (vh != null) vh.itemView.requestFocus() else list.requestFocus()
            focusedOnce = true
        }
    }

    private fun play(pos: Int) {
        val e = eps.getOrNull(pos) ?: return
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("dir", dir).putExtra("path", e.path))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.enter, R.anim.exit)
    }

    override fun onResume() {
        super.onResume()
        ambient.invalidate()
        if (eps.isNotEmpty()) {
            adapter.notifyDataSetChanged()
            updateCount()
            if (focusedOnce) focusTarget()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.exit)
    }
}
