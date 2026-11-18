package com.example.tvlauncher

import android.graphics.BitmapFactory
import java.io.File

class Title(val name: String, val dir: String, val poster: String, val pw: Int, val ph: Int)
class Episode(val name: String, val path: String)

/** Scans storage for folders that contain a poster image (jpg, png, webp, avif, heic, bmp, gif ...). Folders named "undefined" are skipped. */
object Library {
    private val VIDEO = setOf("mkv", "mp4", "avi", "ts", "m4v", "mov", "webm", "wmv", "flv", "mpg", "mpeg", "m2ts", "3gp", "mts")
    private val IMG = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "avif", "avifs", "heic", "heif", "hif", "bmp", "gif", "tif", "tiff", "jp2")
    private val SYSTEM = setOf("android", "lost.dir", "system volume information", "\$recycle.bin", "dcim", "data")
    private val POSTER_NAMES = setOf("poster", "folder", "cover", "thumb", "default", "front")

    @Volatile var titles: List<Title> = emptyList()
    private val epCache = HashMap<String, List<Episode>>()

    fun titleByDir(dir: String): Title? = titles.firstOrNull { it.dir == dir }

    private fun skip(name: String): Boolean {
        val n = name.lowercase()
        return n == "undefined" || n.startsWith(".") || n in SYSTEM
    }

    fun scan(vols: List<Storage.Vol>): List<Title> {
        val out = ArrayList<Title>()
        for (v in vols) walk(v.root, 0, out)
        val sorted = out.sortedWith { a, b -> U.naturalCompare(a.name, b.name) }
        synchronized(epCache) { epCache.clear() }
        titles = sorted
        return sorted
    }

    private fun walk(dir: File, depth: Int, out: MutableList<Title>) {
        if (depth > 3) return
        val kids = dir.listFiles() ?: return
        if (depth > 0) {
            val t = poster(dir, kids)
            if (t != null) {
                out.add(t)
                return
            }
        }
        for (k in kids) if (k.isDirectory && !skip(k.name)) walk(k, depth + 1, out)
    }

    private fun poster(dir: File, kids: Array<File>): Title? {
        val imgs = kids.filter {
            it.isFile && !it.name.startsWith(".") && IMG.contains(it.extension.lowercase())
        }
        if (imgs.isEmpty()) return null
        val ordered = imgs.sortedBy { if (POSTER_NAMES.contains(it.nameWithoutExtension.lowercase())) 0 else 1 }
        val f = ordered.first()
        val (w, h) = Images.bounds(f.path)
        return Title(dir.name, dir.path, f.path, w, h)
    }

    fun episodes(dir: String): List<Episode> {
        synchronized(epCache) { epCache[dir]?.let { return it } }
        val files = ArrayList<File>()
        collect(File(dir), files, 0)
        val base = File(dir).path
        val list = files
            .sortedWith { a, b -> U.naturalCompare(a.path.removePrefix(base), b.path.removePrefix(base)) }
            .map { Episode(U.cleanName(it.name), it.path) }
        synchronized(epCache) { epCache[dir] = list }
        return list
    }

    private fun collect(dir: File, out: MutableList<File>, depth: Int) {
        if (depth > 4) return
        val kids = dir.listFiles() ?: return
        for (k in kids) {
            if (k.isDirectory) {
                if (!skip(k.name)) collect(k, out, depth + 1)
            } else if (!k.name.startsWith(".") && VIDEO.contains(k.extension.lowercase())) {
                out.add(k)
            }
        }
    }

    /** Where "OK" on a poster should go: the episode to resume (with position), or null = open the folder list. */
    fun resume(t: Title, eps: List<Episode>): Pair<Episode, Long>? {
        if (eps.isEmpty()) return null
        val last = Store.last(t.dir) ?: return null
        val i = eps.indexOfFirst { it.path == last }
        if (i < 0) return null
        val p = Store.prog(last) ?: return null
        if (p.done) return if (i + 1 < eps.size) Pair(eps[i + 1], 0L) else null
        return if (p.pos > 3000) Pair(eps[i], p.pos) else null
    }

    fun inProgress(t: Title): Boolean {
        if (Store.lastTime(t.dir) <= 0L) return false
        val last = Store.last(t.dir) ?: return false
        return Store.prog(last)?.done != true
    }
}
