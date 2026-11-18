package com.example.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

class AppInfo(val pkg: String, val label: String, val launch: Intent, val bmp: Bitmap) {
    val color: Int by lazy { U.dominant(bmp) }
}

object Apps {
    @Volatile var cache: List<AppInfo>? = null

    fun invalidate() {
        cache = null
    }

    /** Blocking: call from a background thread. Includes TV (leanback) and normal launchable apps. */
    fun load(ctx: Context): List<AppInfo> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val map = LinkedHashMap<String, AppInfo>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val list = try { pm.queryIntentActivities(q, 0) } catch (e: Exception) { emptyList() }
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName || map.containsKey(ai.packageName)) continue
                try {
                    val li = Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    val bmp = U.toBitmap(ri.loadIcon(pm), 128)
                    map[ai.packageName] = AppInfo(ai.packageName, ri.loadLabel(pm).toString(), li, bmp)
                } catch (e: Exception) {
                }
            }
        }
        val res = map.values.sortedBy { it.label.lowercase() }
        cache = res
        return res
    }
}

/** Dock editing: order, add, remove, replace. Shared by the home screen and the app drawer. */
object DockOps {
    const val MAX = 8
    private val KNOWN = listOf(
        "com.google.android.youtube.tv", "com.google.android.youtube", "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom", "org.xbmc.kodi", "com.spotify.tv.android", "com.spotify.music",
        "com.disney.disneyplus", "in.startv.hotstar", "com.google.android.apps.youtube.music"
    )

    fun resolve(apps: List<AppInfo>): List<AppInfo> {
        val by = apps.associateBy { it.pkg }
        var saved = Store.dockOrNull()
        if (saved == null) {
            val l = KNOWN.filter { by.containsKey(it) }.toMutableList()
            for (a in apps) {
                if (l.size >= 5) break
                if (!l.contains(a.pkg)) l.add(a.pkg)
            }
            saved = l.take(6)
            Store.setDock(saved)
        }
        return saved.mapNotNull { by[it] }.take(MAX)
    }

    fun menuFor(ctx: Context, app: AppInfo, apps: List<AppInfo>, onChange: () -> Unit) {
        val dock = resolve(apps).map { it.pkg }.toMutableList()
        val idx = dock.indexOf(app.pkg)
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        val save = {
            Store.setDock(dock)
            onChange()
        }
        if (idx >= 0) {
            if (idx > 0) {
                labels.add("Move left")
                acts.add { dock.removeAt(idx); dock.add(idx - 1, app.pkg); save() }
            }
            if (idx < dock.size - 1) {
                labels.add("Move right")
                acts.add { dock.removeAt(idx); dock.add(idx + 1, app.pkg); save() }
            }
            labels.add("Remove from dock")
            acts.add { dock.removeAt(idx); save() }
        } else {
            labels.add(if (dock.size < MAX) "Add to dock..." else "Replace a dock app...")
            acts.add { pickPosition(ctx, app, dock, apps, save) }
        }
        labels.add("App info")
        acts.add {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))
            try { ctx.startActivity(i) } catch (e: Exception) { }
        }
        labels.add("Uninstall")
        acts.add {
            try { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg))) } catch (e: Exception) { }
        }
        AlertDialog.Builder(ctx)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .show()
    }

    private fun pickPosition(ctx: Context, app: AppInfo, dock: MutableList<String>, apps: List<AppInfo>, save: () -> Unit) {
        val names = apps.associate { it.pkg to it.label }
        val full = dock.size >= MAX
        val opts = ArrayList<String>()
        for (i in 0 until dock.size) {
            val n = names[dock[i]] ?: dock[i]
            opts.add(if (full) "Replace $n" else "Position ${i + 1}   (before $n)")
        }
        if (!full) opts.add("Last position")
        AlertDialog.Builder(ctx)
            .setTitle("Place " + app.label)
            .setItems(opts.toTypedArray()) { _, w ->
                if (full) dock[w] = app.pkg else dock.add(w, app.pkg)
                save()
            }
            .show()
    }

    fun launch(ctx: Context, app: AppInfo): Boolean {
        return try {
            ctx.startActivity(app.launch)
            true
        } catch (e: Exception) {
            false
        }
    }
}

fun hasPermission(ctx: Context, perm: String): Boolean =
    ctx.checkCallingOrSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
