package com.example.tvlauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.aomedia.avif.android.AvifDecoder
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Small memory-safe image loader. Never crops; remembers an ambient colour per image. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(26 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val colors = ConcurrentHashMap<String, Int>()
    private val main = Handler(Looper.getMainLooper())

    fun color(path: String): Int? = colors[path]

    fun load(path: String, maxH: Int, cb: (Bitmap?) -> Unit) {
        val key = "$path@$maxH"
        val hit = cache.get(key)
        if (hit != null) {
            cb(hit)
            return
        }
        Bg.io.execute {
            val b = decode(path, 0, maxH, Bitmap.Config.ARGB_8888)
            if (b != null) {
                cache.put(key, b)
                if (!colors.containsKey(path)) colors[path] = U.dominant(b)
            }
            main.post { cb(b) }
        }
    }

    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()
    private fun isAvif(path: String) = ext(path).let { it == "avif" || it == "avifs" }

    private fun avifBuf(path: String): ByteBuffer? = try {
        val bytes = File(path).readBytes()
        val b = ByteBuffer.allocateDirect(bytes.size)
        b.put(bytes); b.rewind(); b
    } catch (e: Throwable) { null }

    /** Image size without decoding pixels. Unknown formats get a 2:3 poster shape so the title is still listed. */
    fun bounds(path: String): Pair<Int, Int> {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth > 0 && o.outHeight > 0) return Pair(o.outWidth, o.outHeight)
        } catch (e: Throwable) { }
        try {
            val buf = avifBuf(path)
            if (buf != null) {
                val info = AvifDecoder.Info()
                if (AvifDecoder.getInfo(buf, buf.remaining(), info) && info.width > 0) return Pair(info.width, info.height)
            }
        } catch (e: Throwable) { }
        return Pair(2000, 3000)
    }

    private fun decodeAvif(path: String, maxW: Int, maxH: Int): Bitmap? {
        try {
            val buf = avifBuf(path) ?: return null
            val info = AvifDecoder.Info()
            if (!AvifDecoder.getInfo(buf, buf.remaining(), info) || info.width <= 0) return null
            val full = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
            if (!AvifDecoder.decode(buf, buf.remaining(), full)) return null
            var s = 1.0
            if (maxH > 0 && info.height > maxH) s = maxH.toDouble() / info.height
            if (maxW > 0 && info.width * s > maxW) s = maxW.toDouble() / info.width
            if (s >= 1.0) return full
            val out = Bitmap.createScaledBitmap(full, (info.width * s).toInt().coerceAtLeast(1), (info.height * s).toInt().coerceAtLeast(1), true)
            if (out !== full) full.recycle()
            return out
        } catch (e: Throwable) {
            return null
        }
    }

    /** Neutral card used when a format cannot be decoded on this TV (e.g. HEIC on Android 5), so the title still shows. */
    private fun placeholder(path: String, maxH: Int): Bitmap {
        val h = if (maxH > 0) maxH.coerceAtMost(900) else 600
        val w = h * 2 / 3
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2B3552.toInt(), 0xFF0F1424.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0x99FFFFFF.toInt()
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.textSize = h / 9f
        c.drawText(ext(path).uppercase(), w / 2f, h / 2f, p)
        return b
    }

    fun decode(path: String, maxW: Int, maxH: Int, cfg: Bitmap.Config): Bitmap? {
        try {
            if (!isAvif(path)) {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, o)
                if (o.outWidth > 0 && o.outHeight > 0) {
                    var s = 1
                    while ((maxH <= 0 || o.outHeight / (s * 2) >= maxH) && (maxW <= 0 || o.outWidth / (s * 2) >= maxW) && (maxH > 0 || maxW > 0)) s *= 2
                    val o2 = BitmapFactory.Options()
                    o2.inSampleSize = s
                    o2.inPreferredConfig = cfg
                    val b = BitmapFactory.decodeFile(path, o2)
                    if (b != null) return b
                }
            }
            decodeAvif(path, maxW, maxH)?.let { return it }
            // only posters get a placeholder; wallpapers just fail
            return if (cfg == Bitmap.Config.ARGB_8888 && maxH > 0) placeholder(path, maxH) else null
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            return null
        } catch (e: Exception) {
            return null
        }
    }
}
