package com.example.tvlauncher

import android.content.Context
import android.content.SharedPreferences

/** Everything the launcher remembers: dock, watch progress, settings and API keys. */
object Store {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    }

    // ---- dock
    fun dockOrNull(): List<String>? {
        val s = sp.getString("dock", null) ?: return null
        return s.split(",").filter { it.isNotBlank() }
    }

    fun setDock(l: List<String>) {
        sp.edit().putString("dock", l.joinToString(",")).apply()
    }

    fun resetDock() {
        sp.edit().remove("dock").apply()
    }

    // ---- watch progress
    class Prog(val pos: Long, val dur: Long, val done: Boolean) {
        val frac: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    fun prog(path: String): Prog? {
        val s = sp.getString("p:$path", null) ?: return null
        val a = s.split("|")
        if (a.size < 3) return null
        return Prog(a[0].toLongOrNull() ?: 0L, a[1].toLongOrNull() ?: 0L, a[2] == "1")
    }

    /** Called by the player: also remembers which episode of the folder was watched last. */
    fun save(dir: String, path: String, pos: Long, dur: Long, done: Boolean) {
        sp.edit()
            .putString("p:$path", "$pos|$dur|${if (done) 1 else 0}")
            .putString("l:$dir", path)
            .putLong("t:$dir", System.currentTimeMillis())
            .apply()
    }

    /** Watched / unwatched toggle from the episode list. */
    fun setDone(path: String, done: Boolean) {
        val p = prog(path)
        val dur = p?.dur ?: 0L
        val pos = if (done) dur else 0L
        sp.edit().putString("p:$path", "$pos|$dur|${if (done) 1 else 0}").apply()
    }

    fun last(dir: String): String? = sp.getString("l:$dir", null)
    fun lastTime(dir: String): Long = sp.getLong("t:$dir", 0L)

    fun clearTitle(dir: String) {
        sp.edit().remove("l:$dir").remove("t:$dir").apply()
    }

    fun clearHistory() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("p:") || k.startsWith("l:") || k.startsWith("t:")) e.remove(k)
        e.apply()
    }

    // ---- settings
    var matchFps: Boolean
        get() = sp.getBoolean("matchFps", false)
        set(v) { sp.edit().putBoolean("matchFps", v).apply() }

    var softAudio: Boolean
        get() = sp.getBoolean("softAudio", true)
        set(v) { sp.edit().putBoolean("softAudio", v).apply() }

    var relaxedSsl: Boolean
        get() = sp.getBoolean("relaxedSsl", false)
        set(v) { sp.edit().putBoolean("relaxedSsl", v).apply() }

    var pexelsKey: String
        get() = sp.getString("pexelsKey", "") ?: ""
        set(v) { sp.edit().putString("pexelsKey", v.trim()).apply() }

    var unsplashKey: String
        get() = sp.getString("unsplashKey", "") ?: ""
        set(v) { sp.edit().putString("unsplashKey", v.trim()).apply() }

    var wallBlur: Boolean
        get() = sp.getBoolean("wallBlur", true)
        set(v) { sp.edit().putBoolean("wallBlur", v).apply() }

    var wallSource: Int
        get() = sp.getInt("wallSource", 0)
        set(v) { sp.edit().putInt("wallSource", v).apply() }

    var permAsked: Boolean
        get() = sp.getBoolean("permAsked", false)
        set(v) { sp.edit().putBoolean("permAsked", v).apply() }
}
