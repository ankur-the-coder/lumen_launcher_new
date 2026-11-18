package com.example.tvlauncher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var ambient: AmbientView
    private val valueViews = ArrayList<Pair<TextView, () -> String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        ambient = AmbientView(this)
        root.addView(ambient, FrameLayout.LayoutParams(-1, -1))
        ambient.blurIn()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(64), dp(36), dp(64), dp(12))
        val header = LayoutInflater.from(this).inflate(R.layout.screen_header, page, false)
        header.findViewById<TextView>(R.id.hTitle).text = "Settings"
        header.findViewById<TextView>(R.id.hSub).text = "Wallpaper, apps, storage and playback"
        page.addView(header)
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.setPadding(0, dp(10), 0, dp(24))
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        scroll.addView(container, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        build()
        container.post { (container.getChildAt(1) ?: container).requestFocus() }
    }

    private fun section(name: String) {
        val t = TextView(this)
        t.text = name
        t.textSize = 14f
        t.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        t.setTextColor(0xFF9AA4B8.toInt())
        t.setPadding(dp(6), dp(20), 0, dp(6))
        container.addView(t)
    }

    private fun row(title: String, value: () -> String, onClick: () -> Unit) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = android.view.Gravity.CENTER_VERTICAL
        r.setBackgroundResource(R.drawable.bg_row)
        r.isFocusable = true
        r.isClickable = true
        r.setPadding(dp(22), dp(15), dp(22), dp(15))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        r.layoutParams = lp
        val t = TextView(this)
        t.text = title
        t.textSize = 18f
        t.setTextColor(0xFFFFFFFF.toInt())
        t.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(this)
        v.text = value()
        v.textSize = 15f
        v.setTextColor(0xFF8EC9FF.toInt())
        v.setPadding(dp(16), 0, 0, 0)
        r.addView(v)
        valueViews.add(Pair(v, value))
        r.setOnFocusChangeListener { view, has -> Motion.focus(view, has, 1.02f) }
        r.setOnClickListener { onClick(); refresh() }
        container.addView(r)
    }

    private fun refresh() {
        for ((tv, f) in valueViews) tv.text = f()
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun build() {
        section("Appearance")
        row("Wallpaper", { if (Wall.file(this).exists()) "Custom" else "Ambient colours" }) {
            startActivity(Intent(this, WallpaperActivity::class.java)); openAnim()
        }

        row("Blur wallpaper outside the home screen", { onOff(Store.wallBlur) }) { Store.wallBlur = !Store.wallBlur }

        section("Apps")
        row("Install an app from USB / storage (APK)", { "" }) { apkPicker() }
        row("Allow installs from unknown sources", { "" }) { unknownSources() }
        row("Reset dock to default apps", { "" }) {
            Store.resetDock()
            Toast.makeText(this, "Dock reset", Toast.LENGTH_SHORT).show()
        }
        row("Make Lumen the default launcher", { "" }) { makeDefault() }

        section("Storage and library")
        row("Storage access", { if (hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)) "Granted" else "Needed" }) { storageAccess() }
        row("Refresh posters", { "" }) {
            Toast.makeText(this, "Posters refresh when you go back to the home screen", Toast.LENGTH_LONG).show()
        }
        row("Clear watch history", { "" }) {
            AlertDialog.Builder(this).setTitle("Clear watch history?")
                .setMessage("Resume positions and watched marks are removed.")
                .setPositiveButton("Clear") { _, _ -> Store.clearHistory(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }

        section("Playback")
        row("Software audio fallback", { onOff(Store.softAudio) }) { Store.softAudio = !Store.softAudio }
        row("Match TV refresh rate to video", { onOff(Store.matchFps) }) { Store.matchFps = !Store.matchFps }

        section("Network")
        row("Wi-Fi settings", { "" }) { open(Settings.ACTION_WIFI_SETTINGS) }
        row("Android system settings", { "" }) { open(Settings.ACTION_SETTINGS) }
        row("Relaxed SSL for wallpapers", { onOff(Store.relaxedSsl) }) { Store.relaxedSsl = !Store.relaxedSsl }

        section("About")
        row("Lumen 1.0", { "" }) {
            AlertDialog.Builder(this).setTitle("Lumen")
                .setMessage("Video: always the TV's hardware decoder (4K, HEVC, HDR).\nAudio: hardware / HDMI passthrough first; software FFmpeg only for tracks the TV cannot decode.\nVolume uses the system volume.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun open(action: String) {
        try { startActivity(Intent(action)) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) {
                Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAnim() {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.enter, R.anim.exit)
    }

    private fun storageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                open(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 9)
        } else {
            Toast.makeText(this, "Storage access was granted when the app was installed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun unknownSources() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        } catch (e: Exception) {
            open(Settings.ACTION_SETTINGS)
        }
    }

    // ------------------------------------------------------------ APK installer

    private fun apkPicker() {
        Toast.makeText(this, "Looking for APK files...", Toast.LENGTH_SHORT).show()
        Bg.io.execute {
            val found = ArrayList<File>()
            for (v in Storage.volumes(this)) findApks(v.root, 0, found)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (found.isEmpty()) {
                    Toast.makeText(this, "No .apk files found on the TV or attached drives", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = found.map { it.name + "   (" + (it.length() / 1024 / 1024) + " MB)" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Install which app?")
                    .setItems(labels) { _, w -> install(this, found[w]) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun findApks(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 3 || out.size >= 150) return
        val kids = dir.listFiles() ?: return
        for (k in kids) {
            if (k.isDirectory) {
                if (!k.name.startsWith(".") && !k.name.equals("android", true)) findApks(k, depth + 1, out)
            } else if (k.name.endsWith(".apk", true)) {
                out.add(k)
            }
        }
    }

    private fun install(ctx: Context, f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            val mime = "application/vnd.android.package-archive"
            if (Build.VERSION.SDK_INT >= 24) {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f)
                i.setDataAndType(uri, mime)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                i.setDataAndType(Uri.fromFile(f), mime)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not start the installer: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ambient.invalidate()
        refresh()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.exit)
    }

    /** Re-arms Android's "Always / Just once" question by briefly enabling a twin HOME entry, then asks for HOME. */
    private fun makeDefault() {
        val cn = android.content.ComponentName(this, "com.example.tvlauncher.FakeHome")
        val pm = packageManager
        try { pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP) } catch (e: Exception) { }
        Toast.makeText(this, "Choose Lumen, then tap Always", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Press the HOME button on the remote and choose Lumen > Always", Toast.LENGTH_LONG).show()
        }
        window.decorView.postDelayed({
            try { pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP) } catch (e: Exception) { }
        }, 4000)
    }
}
