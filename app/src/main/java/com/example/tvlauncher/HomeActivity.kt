package com.example.tvlauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HomeActivity : AppCompatActivity(), PosterCb {

    private lateinit var ambient: AmbientView
    private lateinit var leftBlock: View
    private lateinit var tagView: TextView
    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var rowHeader: TextView
    private lateinit var rv: RecyclerView
    private lateinit var dockPill: LinearLayout
    private lateinit var dockApps: LinearLayout
    private lateinit var dockLabel: TextView
    private lateinit var btnDrawer: FrameLayout
    private lateinit var btnSettings: FrameLayout
    private lateinit var btnFiles: FrameLayout
    private lateinit var btnInputs: FrameLayout
    private lateinit var usbBox: LinearLayout
    private lateinit var wifi: ImageView
    private lateinit var adapter: PosterAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var apps: List<AppInfo> = emptyList()
    private var dock: List<AppInfo> = emptyList()
    private var titles: List<Title> = emptyList()
    private var continueCount = 0
    private var posterH = 0
    private var lastPoster = 0
    private var lastDockView: View? = null
    private var shownTitle = ""
    private var usbShown = -1
    private var scanning = false
    private var entered = false
    private var receivers = false
    private val gap by lazy { dp(16) }
    private val neutral = Color.parseColor("#34447A")

    private val rescan = Runnable { scanLibrary() }
    private val netReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { updateWifi() }
    }
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.removeCallbacks(rescan)
            handler.postDelayed(rescan, 1500)
        }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Apps.invalidate()
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        ambient = findViewById(R.id.ambient)
        leftBlock = findViewById(R.id.leftBlock)
        tagView = findViewById(R.id.tag)
        titleView = findViewById(R.id.title)
        hintView = findViewById(R.id.hint)
        rowHeader = findViewById(R.id.rowHeader)
        rv = findViewById(R.id.rv)
        dockPill = findViewById(R.id.dockPill)
        dockApps = findViewById(R.id.dockApps)
        dockLabel = findViewById(R.id.dockLabel)
        btnDrawer = findViewById(R.id.btnDrawer)
        btnSettings = findViewById(R.id.btnSettings)
        btnFiles = findViewById(R.id.btnFiles)
        btnInputs = findViewById(R.id.btnInputs)
        usbBox = findViewById(R.id.usbBox)
        wifi = findViewById(R.id.wifi)

        layoutAll()
        setupRow()
        setupStaticDockButtons()
        askStoragePermission()
        loadApps()
        updateWifi()
    }

    // ------------------------------------------------------------ layout (all sizes derive from the screen)

    private fun layoutAll() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        posterH = (h * 0.31f).toInt()
        val rowStart = (w * 0.445f).toInt()

        fun fl(v: View, f: (FrameLayout.LayoutParams) -> Unit) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            f(lp)
            v.layoutParams = lp
        }
        fl(findViewById(R.id.logo)) { it.leftMargin = (w * 0.06f).toInt(); it.topMargin = (h * 0.07f).toInt() }
        fl(findViewById(R.id.topRight)) { it.rightMargin = (w * 0.06f).toInt(); it.topMargin = (h * 0.075f).toInt() }
        fl(leftBlock) { it.leftMargin = (w * 0.06f).toInt(); it.topMargin = (h * 0.285f).toInt() }
        leftBlock.layoutParams.width = (w * 0.34f).toInt()
        fl(rowHeader) { it.leftMargin = rowStart; it.topMargin = (h * 0.245f).toInt() }
        fl(rv) {
            it.topMargin = (h * 0.302f).toInt() - dp(22)
            it.height = posterH + dp(44)
        }
        rv.setPadding(rowStart - gap, dp(22), 0, dp(22))
        fl(dockPill) { it.bottomMargin = (h * 0.085f).toInt() }
        fl(dockLabel) { it.bottomMargin = (h * 0.03f).toInt() }
        val pad = dp(10)
        dockPill.setPadding(pad + dp(4), pad, pad + dp(4), pad)
    }

    private fun dockSize() = (resources.displayMetrics.heightPixels * 0.1f).toInt()

    private fun setupStaticDockButtons() {
        val s = dockSize()
        for (b in arrayOf(btnDrawer, btnFiles, btnInputs, btnSettings)) {
            val lp = LinearLayout.LayoutParams((s * 0.86f).toInt(), (s * 0.86f).toInt())
            lp.leftMargin = dp(8)
            lp.rightMargin = dp(8)
            b.layoutParams = lp
            val p = (s * 0.2f).toInt()
            b.setPadding(p, p, p, p)
            U.circle(b)
        }
        val divLp = findViewById<View>(R.id.dockDivider).layoutParams as LinearLayout.LayoutParams
        divLp.leftMargin = dp(10)
        divLp.rightMargin = dp(10)
        divLp.height = (s * 0.62f).toInt()
        wireDock(btnDrawer, { showStatic("All apps", "Press OK to open", neutral, "") }, { startActivity(Intent(this, DrawerActivity::class.java)); fadeOpen() }, null, "All apps")
        wireDock(btnFiles, { showStatic("Files and USB", "Browse the TV storage and USB drives", neutral, "") }, { startActivity(Intent(this, FilesActivity::class.java)); fadeOpen() }, null, "Files / USB")
        wireDock(btnInputs, { showStatic("TV inputs", "HDMI, AV and other sources", neutral, "") }, { showInputs() }, null, "Inputs")
        wireDock(btnSettings, { showStatic("Settings", "Wallpapers, storage, installs and remote", neutral, "") }, { startActivity(Intent(this, SettingsActivity::class.java)); fadeOpen() }, null, "Settings")
    }

    private fun showInputs() {
        try {
            val tm = getSystemService(Context.TV_INPUT_SERVICE) as android.media.tv.TvInputManager
            val list = tm.tvInputList.filter { it.isPassthroughInput }
            if (list.isEmpty()) throw IllegalStateException()
            val names = list.map { it.loadLabel(this).toString() }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Switch input").setItems(names) { _, w ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(list[w].id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open this input - use the SOURCE button on your remote", Toast.LENGTH_LONG).show()
                }
            }.show()
        } catch (e: Exception) {
            Toast.makeText(this, "No inputs found here - use the INPUT / SOURCE button on your remote", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupRow() {
        adapter = PosterAdapter(posterH, this)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.setHasFixedSize(false)
        rv.layoutManager = object : LinearLayoutManager(this, RecyclerView.HORIZONTAL, false) {
            // we scroll ourselves so the focused poster glides to a fixed left slot
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean): Boolean = false
        }
        rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (parent.getChildAdapterPosition(view) == 0) outRect.left = gap
            }
        })
    }

    // ------------------------------------------------------------ apps & dock

    private fun loadApps() {
        Bg.io.execute {
            val list = Apps.load(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = list
                buildDock()
            }
        }
    }

    private fun buildDock() {
        dockApps.removeAllViews()
        dock = DockOps.resolve(apps)
        val s = dockSize()
        val inf = LayoutInflater.from(this)
        for (a in dock) {
            val v = inf.inflate(R.layout.item_dock, dockApps, false) as FrameLayout
            val lp = LinearLayout.LayoutParams(s, s)
            lp.leftMargin = dp(7)
            lp.rightMargin = dp(7)
            v.layoutParams = lp
            v.findViewById<ImageView>(R.id.icon).setImageBitmap(a.bmp)
            U.round(v, dp(14).toFloat())
            wireDock(v, {
                showStatic(a.label, "Press OK to open", a.color, "APP")
            }, {
                if (!DockOps.launch(this, a)) Toast.makeText(this, "Could not open " + a.label, Toast.LENGTH_SHORT).show()
            }, {
                DockOps.menuFor(this, a, apps) { buildDock() }
            }, a.label)
            dockApps.addView(v)
        }
        if (!entered) {
            entered = true
            enter()
        }
        val first = dockApps.getChildAt(0) ?: btnDrawer
        if (currentFocus == null || !hasWindowFocus()) first.requestFocus()
    }

    private fun wireDock(v: View, onFocus: () -> Unit, onClick: () -> Unit, onLong: (() -> Unit)?, label: String) {
        v.setOnFocusChangeListener { view, has ->
            Motion.focus(view, has, 1.16f, true)
            if (has) {
                lastDockView = view
                dockLabel.text = label
                onFocus()
            }
        }
        v.setOnClickListener { onClick() }
        if (onLong != null) v.setOnLongClickListener { onLong(); true }
    }

    private fun showStatic(title: String, hint: String, color: Int, tag: String) {
        ambient.setTarget(color)
        setLeft(tag, title, hint)
        updateHeader(-1)
    }

    private fun setLeft(tag: String, title: String, hint: String) {
        if (shownTitle == title + tag) return
        shownTitle = title + tag
        Motion.swap(leftBlock) {
            tagView.text = tag
            tagView.visibility = if (tag.isEmpty()) View.GONE else View.VISIBLE
            titleView.text = title
            hintView.text = hint
        }
    }

    // ------------------------------------------------------------ posters

    private fun scanLibrary() {
        if (scanning) return
        scanning = true
        Bg.io.execute {
            val vols = Storage.volumes(this)
            val found = Library.scan(vols)
            val usb = vols.count { it.removable }
            runOnUiThread {
                scanning = false
                if (isDestroyed) return@runOnUiThread
                setUsb(usb)
                setTitles(found)
            }
        }
    }

    private fun setTitles(found: List<Title>) {
        val cont = found.filter { Library.inProgress(it) }.sortedByDescending { Store.lastTime(it.dir) }
        val rest = found.filter { !Library.inProgress(it) }
        val list = cont + rest
        continueCount = cont.size
        titles = list
        adapter.items = list
        adapter.notifyDataSetChanged()
        val has = list.isNotEmpty()
        rv.visibility = if (has) View.VISIBLE else View.GONE
        rowHeader.visibility = if (has) View.VISIBLE else View.GONE
        if (has) updateHeader(lastPoster)
    }

    private fun updateHeader(pos: Int) {
        rowHeader.text = if (pos in 0 until continueCount || (pos < 0 && continueCount > 0)) "Continue watching" else "Library"
    }

    override fun onFocus(pos: Int, v: View) {
        val t = titles.getOrNull(pos) ?: return
        lastPoster = pos
        updateHeader(pos)
        rv.smoothScrollBy(v.left - rv.paddingLeft - gap, 0, DecelerateInterpolator(1.5f), 320)
        val last = Store.last(t.dir)
        val p = if (last != null) Store.prog(last) else null
        val resumable = Library.inProgress(t) && p != null
        val hint = if (resumable && p != null) "Resume at " + U.fmt(p.pos) + "  -  press OK" else "Press OK to open"
        setLeft(if (resumable) "CONTINUE" else "LIBRARY", t.name, hint)
        Images.load(t.poster, (posterH * 1.25f).toInt()) {
            if (lastPoster == pos && rv.hasFocus()) ambient.setTarget(Images.color(t.poster) ?: neutral)
        }
    }

    override fun onClick(pos: Int) {
        val t = titles.getOrNull(pos) ?: return
        Bg.io.execute {
            val eps = Library.episodes(t.dir)
            val target = Library.resume(t, eps)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (eps.isEmpty()) {
                    Toast.makeText(this, "No video files found in this folder", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val list = Intent(this, EpisodesActivity::class.java).putExtra("dir", t.dir)
                if (target != null) {
                    val play = Intent(this, PlayerActivity::class.java).putExtra("dir", t.dir).putExtra("path", target.first.path)
                    startActivities(arrayOf(list, play))
                } else {
                    startActivity(list)
                }
                fadeOpen()
            }
        }
    }

    override fun onLong(pos: Int) {
        val t = titles.getOrNull(pos) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(arrayOf("Open episode list", "Clear watch progress")) { _, w ->
                if (w == 0) {
                    startActivity(Intent(this, EpisodesActivity::class.java).putExtra("dir", t.dir))
                    fadeOpen()
                } else {
                    for (e in Library.episodes(t.dir)) Store.setDone(e.path, false)
                    Store.clearTitle(t.dir)
                    scanLibrary()
                }
            }.show()
    }

    // ------------------------------------------------------------ status icons

    private fun updateWifi() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val ni = try { cm.activeNetworkInfo } catch (e: Exception) { null }
        @Suppress("DEPRECATION")
        val on = ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
        wifi.setImageResource(if (on) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
    }

    private fun setUsb(n: Int) {
        if (n == usbShown) return
        usbShown = n
        usbBox.removeAllViews()
        val shown = minOf(n, 4)
        for (i in 0 until shown) {
            val iv = ImageView(this)
            iv.setImageResource(R.drawable.ic_usb)
            val lp = LinearLayout.LayoutParams(dp(22), dp(22))
            lp.rightMargin = dp(10)
            iv.layoutParams = lp
            usbBox.addView(iv)
            iv.alpha = 0f
            iv.scaleX = 0.6f
            iv.scaleY = 0.6f
            iv.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(i * 80L).setDuration(320).start()
        }
        if (n > 4) {
            val tv = TextView(this)
            tv.text = "+" + (n - 4)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 15f
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(10)
            tv.layoutParams = lp
            usbBox.addView(tv)
        }
    }

    // ------------------------------------------------------------ one orchestrated entrance

    private fun enter() {
        Motion.rise(findViewById(R.id.topRight), 0, -18f)
        Motion.rise(leftBlock, 120)
        Motion.rise(rowHeader, 220)
        Motion.rise(rv, 300, 50f)
        Motion.rise(dockPill, 420, 60f)
    }

    // ------------------------------------------------------------ permissions & lifecycle

    private fun askStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23 && !hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) && Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        } else if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !Store.permAsked) {
            Store.permAsked = true
            Toast.makeText(this, "To show posters from USB: Settings > Storage access", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        scanLibrary()
    }

    override fun onStart() {
        super.onStart()
        if (!receivers) {
            receivers = true
            registerReceiver(netReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            val mf = IntentFilter()
            for (a in arrayOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL)) mf.addAction(a)
            mf.addDataScheme("file")
            registerReceiver(mediaReceiver, mf)
            val pf = IntentFilter()
            for (a in arrayOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED)) pf.addAction(a)
            pf.addDataScheme("package")
            registerReceiver(pkgReceiver, pf)
        }
    }

    override fun onStop() {
        super.onStop()
        if (receivers) {
            receivers = false
            try { unregisterReceiver(netReceiver); unregisterReceiver(mediaReceiver); unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        updateWifi()
        ambient.invalidate()
        scanLibrary()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // HOME pressed while we are already open: go back to the first dock app
        if (intent?.action == Intent.ACTION_MAIN) {
            rv.scrollToPosition(0)
            (dockApps.getChildAt(0) ?: btnDrawer).requestFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun fadeOpen() {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.enter, R.anim.exit)
    }

    // ------------------------------------------------------------ focus hops between poster row and dock

    private fun inRow(v: View?): Boolean = v != null && rv.findContainingItemView(v) != null

    private fun focusPoster() {
        if (titles.isEmpty()) return
        val pos = lastPoster.coerceIn(0, titles.size - 1)
        val vh = rv.findViewHolderForAdapterPosition(pos)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else {
            rv.scrollToPosition(pos)
            rv.post { rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val f = currentFocus
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && inRow(f)) {
            (lastDockView ?: dockApps.getChildAt(0) ?: btnDrawer).requestFocus()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && f != null && !inRow(f)) {
            if (titles.isNotEmpty()) focusPoster()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // a launcher never closes; BACK just returns to the dock
        (lastDockView ?: dockApps.getChildAt(0) ?: btnDrawer).requestFocus()
        rv.smoothScrollToPosition(0)
    }
}
