package com.example.tvlauncher

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class Wp(
    val id: String,
    val thumb: String,
    val caption: String,
    val w: Int,
    val h: Int,
    val original: String,
    val track: String?
)

class WallAdapter(
    private val cellW: Int,
    private val cacheKey: () -> String,
    private val onFocusPos: (Int) -> Unit,
    private val onClick: (Wp) -> Unit
) : RecyclerView.Adapter<WallAdapter.VH>() {
    var items: List<Wp> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val cap: TextView = v.findViewById(R.id.cap)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_wall, parent, false)
        val lp = RecyclerView.LayoutParams(cellW, cellW * 9 / 16)
        lp.setMargins(parent.context.dp(7), parent.context.dp(7), parent.context.dp(7), parent.context.dp(7))
        v.layoutParams = lp
        U.round(v, parent.context.dp(14).toFloat())
        val h = VH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onClick(items[p])
        }
        v.setOnFocusChangeListener { fv, has ->
            Motion.focus(fv, has, 1.05f)
            if (has) {
                val p = h.bindingAdapterPosition
                if (p >= 0) onFocusPos(p)
            }
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val w = items[position]
        h.cap.text = w.caption
        h.img.setImageDrawable(null)
        val local = w.thumb.startsWith("/")
        val file = if (local) File(w.thumb) else File(h.itemView.context.cacheDir, "wp/" + cacheKey() + "_" + w.id + ".jpg")
        h.itemView.tag = file.path
        val ctx = h.itemView.context.applicationContext
        val view = h.itemView
        val show = {
            Images.load(file.path, cellW * 9 / 16) { b ->
                if (view.tag == file.path && b != null) {
                    h.img.setImageBitmap(b)
                    h.img.alpha = 0f
                    h.img.animate().alpha(1f).setDuration(260).start()
                }
            }
        }
        if (local || (file.exists() && file.length() >= 500)) {
            show()
            return
        }
        Bg.net.execute {
            var ok = false
            for (attempt in 0..2) {
                if (view.tag != file.path) return@execute   // scrolled away: skip, it will be requested again when it returns
                try {
                    file.parentFile?.mkdirs()
                    Net.download(ctx, w.thumb, file)
                    ok = file.length() >= 500
                    if (ok) break
                } catch (e: Exception) {
                    try { Thread.sleep(400L * (attempt + 1)) } catch (_: InterruptedException) { }
                }
            }
            if (ok) view.post { if (view.tag == file.path) show() }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.itemView.tag = null
        holder.img.setImageDrawable(null)
    }
}

/**
 * Wallpaper browser.
 *  - "Free catalog": ~1000 Unsplash photos through Picsum, no account needed.
 *  - "Pexels" and "Unsplash": their full catalogs and search through the official APIs (free personal API key, typed once).
 * Every wallpaper can be downloaded in 4K / QHD / Full HD / HD / original size.
 */
class WallpaperActivity : AppCompatActivity() {
    private lateinit var ambient: AmbientView
    private lateinit var grid: RecyclerView
    private lateinit var adapter: WallAdapter
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var chips: List<TextView>
    private var src = 0
    private var query = ""
    private var page = 0
    private var loading = false
    private var more = true
    private var token = 0
    private val all = ArrayList<Wp>()
    private val cats = arrayOf("Nature", "Mountains", "Ocean", "Forest", "Space", "City night", "Architecture", "Abstract", "Minimal", "Dark", "Cars", "Animals", "Sunset", "Snow")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wall)
        ambient = findViewById(R.id.ambient)
        ambient.blurIn()
        grid = findViewById(R.id.grid)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        findViewById<TextView>(R.id.hTitle).text = "Wallpapers"
        findViewById<TextView>(R.id.hSub).text = "Pick a picture, then choose the resolution to download"
        chips = listOf(findViewById(R.id.srcFree), findViewById(R.id.srcPexels), findViewById(R.id.srcUnsplash), findViewById(R.id.srcFiles))
        for ((i, c) in chips.withIndex()) {
            c.setOnClickListener { pickSource(i) }
            c.setOnLongClickListener { if (i in 1..2) askKey(i, false); true }
            c.setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.04f) }
        }
        val dm = resources.displayMetrics
        val span = 4
        val cellW = (dm.widthPixels - dp(128)) / span - dp(14)
        grid.layoutManager = GridLayoutManager(this, span)
        grid.clipChildren = false
        grid.itemAnimator = null
        adapter = WallAdapter(cellW, { "s" + src }, { pos ->
            if (pos >= all.size - 8 && !loading && more) loadPage()
        }, { wp -> chooseSize(wp) })
        grid.adapter = adapter

        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                query = search.text.toString().trim()
                restart()
                true
            } else false
        }
        findViewById<View>(R.id.btnCats).setOnClickListener {
            if (src == 3) {
                Toast.makeText(this, "Categories are for the online catalogs - type part of a file name to search your files", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this).setTitle("Categories").setItems(cats) { _, w ->
                search.setText(cats[w])
                query = cats[w]
                restart()
            }.show()
        }
        findViewById<View>(R.id.btnCats).setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.04f) }
        val reset = findViewById<View>(R.id.btnReset)
        reset.setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.04f) }
        reset.setOnClickListener {
            Wall.clear(this)
            ambient.invalidate()
            Toast.makeText(this, "Wallpaper removed - using ambient colours", Toast.LENGTH_SHORT).show()
        }
        src = Store.wallSource.coerceIn(0, 3)
        selectChip()
        restart()
    }

    private fun selectChip() {
        for ((i, c) in chips.withIndex()) c.isSelected = i == src
    }

    private fun pickSource(i: Int) {
        if (i == src) return
        src = i
        Store.wallSource = i
        selectChip()
        restart()
    }

    private fun key(): String = if (src == 1) Store.pexelsKey else if (src == 2) Store.unsplashKey else ""

    private fun restart() {
        token++
        all.clear()
        adapter.items = emptyList()
        adapter.notifyDataSetChanged()
        page = 0
        more = true
        loading = false
        if (src == 3) {
            loadLocal()
            return
        }
        if (src > 0 && key().isEmpty()) {
            status.text = (if (src == 1) "Pexels" else "Unsplash") + " needs a free API key (one-time, takes a minute)."
            askKey(src, true)
            return
        }
                loadPage()
    }

    private fun askKey(which: Int, thenLoad: Boolean) {
        val name = if (which == 1) "Pexels" else "Unsplash"
        val site = if (which == 1) "pexels.com/api" else "unsplash.com/developers  (create an app, copy its Access Key)"
        val et = EditText(this)
        et.setSingleLine(true)
        et.setText(if (which == 1) Store.pexelsKey else Store.unsplashKey)
        et.setTextColor(0xFFFFFFFF.toInt())
        et.setPadding(dp(24), dp(12), dp(24), dp(12))
        AlertDialog.Builder(this)
            .setTitle("$name API key")
            .setMessage("Get a free key at $site on any phone or PC, then type it here once. It is stored only on this TV.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                if (which == 1) Store.pexelsKey = et.text.toString() else Store.unsplashKey = et.text.toString()
                if (which == src) restart()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ catalog

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun loadPage() {
        if (loading || !more) return
        loading = true
        val my = token
        val next = page + 1
        val s = src
        val q = query
        val k = key()
        if (all.isEmpty()) status.text = "Loading..."
        Bg.io.execute {
            try {
                val list = fetch(s, q, k, next)
                runOnUiThread {
                    if (isDestroyed || my != token) return@runOnUiThread
                    loading = false
                    page = next
                    if (list.isEmpty()) more = false
                    val start = all.size
                    all.addAll(list)
                    adapter.items = ArrayList(all)
                    adapter.notifyItemRangeInserted(start, list.size)
                    status.text = when {
                        all.isEmpty() -> "No results"
                        s == 0 && q.isEmpty() -> all.size.toString() + " photos from the free catalog"
                        q.isEmpty() -> all.size.toString() + " photos  -  popular right now"
                        else -> all.size.toString() + " photos for \"" + q + "\""
                    }
                    if (start == 0 && all.isNotEmpty()) grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed || my != token) return@runOnUiThread
                    loading = false
                    val m = e.message ?: e.javaClass.simpleName
                    status.text = "Could not load: $m.  " +
                        (if (m.contains("401") || m.contains("403")) "The API key looks wrong - hold OK on the source button to change it."
                        else "Check Wi-Fi. If this is an SSL error, turn on Relaxed SSL in Settings.")
                }
            }
        }
    }

    private fun fetch(s: Int, q: String, k: String, p: Int): List<Wp> {
        val out = ArrayList<Wp>()
        when (s) {
            0 -> if (q.isNotEmpty()) {
                val u = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=30&gsroffset=" + ((p - 1) * 30) +
                    "&gsrsearch=" + enc(q + " filetype:bitmap filew:>1900 fileh:>1000") + "&prop=imageinfo&iiprop=url|size&iiurlwidth=480"
                val pages = JSONObject(Net.getString(this, u)).optJSONObject("query")?.optJSONObject("pages")
                if (pages != null) {
                    val keys = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }
                    for (pg in keys) {
                        val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                        val w = ii.optInt("width"); val h = ii.optInt("height")
                        if (w < h) continue
                        out.add(Wp("c" + pg.optLong("pageid"), ii.optString("thumburl"), pg.optString("title").removePrefix("File:").substringBeforeLast('.'), w, h, ii.optString("url"), null))
                    }
                }
            } else {
                val arr = JSONArray(Net.getString(this, "https://picsum.photos/v2/list?page=$p&limit=30"))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    out.add(Wp(id, "https://picsum.photos/id/$id/480/270.jpg", o.optString("author"), o.getInt("width"), o.getInt("height"), "", null))
                }
                if (p >= 34) return emptyList()
            }
            1 -> {
                val url = if (q.isEmpty()) "https://api.pexels.com/v1/curated?per_page=30&page=$p"
                else "https://api.pexels.com/v1/search?query=${enc(q)}&orientation=landscape&per_page=30&page=$p"
                val o = JSONObject(Net.getString(this, url, mapOf("Authorization" to k)))
                val arr = o.optJSONArray("photos") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val ph = arr.getJSONObject(i)
                    val src = ph.getJSONObject("src")
                    out.add(Wp(ph.getLong("id").toString(), src.getString("medium"), ph.optString("photographer"), ph.getInt("width"), ph.getInt("height"), src.getString("original"), null))
                }
            }
            else -> {
                val hdr = mapOf("Authorization" to "Client-ID $k")
                val arr: JSONArray = if (q.isEmpty()) {
                    JSONArray(Net.getString(this, "https://api.unsplash.com/photos?order_by=popular&per_page=30&page=$p", hdr))
                } else {
                    JSONObject(Net.getString(this, "https://api.unsplash.com/search/photos?query=${enc(q)}&orientation=landscape&per_page=30&page=$p", hdr)).optJSONArray("results") ?: JSONArray()
                }
                for (i in 0 until arr.length()) {
                    val ph = arr.getJSONObject(i)
                    val urls = ph.getJSONObject("urls")
                    val user = ph.optJSONObject("user")?.optString("name") ?: ""
                    val track = ph.optJSONObject("links")?.optString("download_location")
                    out.add(Wp(ph.getString("id"), urls.getString("small"), user, ph.getInt("width"), ph.getInt("height"), urls.getString("raw"), track))
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ resolutions & download

    private class Opt(val label: String, val url: String)

    private fun optionsFor(wp: Wp): List<Opt> {
        if (src == 0 && wp.original.isNotEmpty()) return listOf(Opt("Original   " + wp.w + " x " + wp.h, wp.original))
        val list = ArrayList<Opt>()
        val ratio = wp.h.toDouble() / wp.w
        val widths = intArrayOf(3840, 2560, 1920, 1280)
        val names = arrayOf("4K Ultra HD", "QHD", "Full HD", "HD")
        for ((i, w) in widths.withIndex()) {
            if (w > wp.w && i != widths.size - 1) continue
            val h = Math.round(w * ratio).toInt()
            val url = when (src) {
                0 -> if (wp.original.isNotEmpty()) wp.original else "https://picsum.photos/id/${wp.id}/$w/$h.jpg"
                1 -> wp.original + "?auto=compress&cs=tinysrgb&w=$w"
                else -> wp.original + (if (wp.original.contains("?")) "&" else "?") + "w=$w&q=85&fm=jpg&fit=max"
            }
            list.add(Opt(names[i] + "   " + w + " x " + h, url))
        }
        val orig = when (src) {
            0 -> if (wp.original.isNotEmpty()) wp.original else "https://picsum.photos/id/${wp.id}/${wp.w}/${wp.h}.jpg"
            1 -> wp.original
            else -> wp.original + (if (wp.original.contains("?")) "&" else "?") + "q=90&fm=jpg"
        }
        list.add(Opt("Original   " + wp.w + " x " + wp.h, orig))
        return list
    }

    private fun chooseSize(wp: Wp) {
        if (src == 3) {
            afterPick(File(wp.original))
            return
        }
        val opts = optionsFor(wp)
        AlertDialog.Builder(this)
            .setTitle("Download resolution  -  " + wp.caption)
            .setItems(opts.map { it.label }.toTypedArray()) { _, w -> download(wp, opts[w]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun download(wp: Wp, opt: Opt) {
        status.text = "Downloading " + opt.label + " ..."
        val tmp = File(cacheDir, "wallpaper_dl.jpg")
        val ctx = applicationContext
        val k = key()
        Bg.io.execute {
            try {
                Net.download(ctx, opt.url, tmp, emptyMap()) { done, total ->
                    runOnUiThread {
                        if (!isDestroyed) status.text = "Downloading ${opt.label}  -  " + (done / 1024 / 1024) + " MB" + (if (total > 0) " of " + (total / 1024 / 1024) + " MB" else "")
                    }
                }
                // Unsplash asks apps to report downloads
                if (src == 2 && wp.track != null && k.isNotEmpty()) {
                    try { Net.getString(ctx, wp.track, mapOf("Authorization" to "Client-ID $k")) } catch (e: Exception) { }
                }
                runOnUiThread { if (!isDestroyed) afterPick(tmp) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed) status.text = "Download failed: " + (e.message ?: "error") + ".  Try a smaller resolution."
                }
            }
        }
    }

    // ------------------------------------------------------------ my files

    private val imgExt = setOf("jpg", "jpeg", "png", "webp", "bmp")

    private fun loadLocal() {
        loading = true
        more = false
        val my = token
        val q = query.lowercase()
        status.text = "Looking for pictures on this TV and attached drives..."
        Bg.io.execute {
            val found = ArrayList<File>()
            for (v in Storage.volumes(this)) walkImages(v.root, 0, found)
            found.sortByDescending { it.lastModified() }
            val list = ArrayList<Wp>()
            for (f in found) {
                if (q.isNotEmpty() && !f.name.lowercase().contains(q)) continue
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                try { BitmapFactory.decodeFile(f.path, o) } catch (e: Exception) { continue }
                if (maxOf(o.outWidth, o.outHeight) < 1200) continue
                list.add(Wp(f.path.hashCode().toString(), f.path, f.nameWithoutExtension, o.outWidth, o.outHeight, f.path, null))
                if (list.size >= 500) break
            }
            runOnUiThread {
                if (isDestroyed || my != token) return@runOnUiThread
                loading = false
                all.clear()
                all.addAll(list)
                adapter.items = ArrayList(all)
                adapter.notifyDataSetChanged()
                status.text = if (all.isEmpty()) "No large pictures found. Copy wallpapers to a USB drive (any folder) and plug it in."
                else all.size.toString() + " pictures on this TV and attached drives  -  newest first"
                if (all.isNotEmpty()) grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    private fun walkImages(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 4 || out.size >= 1500) return
        val kids = dir.listFiles() ?: return
        for (k in kids) {
            if (k.isDirectory) {
                val n = k.name.lowercase()
                if (!n.startsWith(".") && n != "android" && n != "lost.dir") walkImages(k, depth + 1, out)
            } else if (!k.name.startsWith(".") && imgExt.contains(k.extension.lowercase()) && k.length() > 120 * 1024) {
                out.add(k)
            }
        }
    }

    /** 16:9 pictures are applied straight away; anything else opens the live adapt screen. */
    private fun afterPick(file: File) {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        try { BitmapFactory.decodeFile(file.path, o) } catch (e: Exception) { }
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            status.text = "That file is not a readable picture"
            return
        }
        val ratio = o.outWidth.toFloat() / o.outHeight
        if (Math.abs(ratio - 16f / 9f) <= 0.03f) {
            status.text = "Applying wallpaper..."
            val ctx = applicationContext
            Bg.io.execute {
                try {
                    file.copyTo(Wall.file(ctx), true)
                    Wall.reload(ctx) {
                        if (!isDestroyed) {
                            ambient.invalidate()
                            status.text = "Wallpaper applied  -  " + o.outWidth + " x " + o.outHeight
                            Toast.makeText(this, "Wallpaper applied", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { if (!isDestroyed) status.text = "Could not apply: " + (e.message ?: "error") }
                }
            }
        } else {
            startActivity(android.content.Intent(this, AdaptActivity::class.java).putExtra("path", file.path))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.enter, R.anim.exit)
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
