package com.example.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Shared background threads: scanning, decoding and downloads never touch the UI thread. */
object Bg {
    val io: ExecutorService = Executors.newFixedThreadPool(3)
    val net: ExecutorService = Executors.newFixedThreadPool(4)   // thumbnails download here so they never block decoding
}

object U {
    fun round(v: View, radius: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        v.clipToOutline = true
    }

    fun circle(v: View) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        v.clipToOutline = true
    }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                while (si < a.length && a[si].isDigit()) si++
                var sj = j
                while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = si
                j = sj
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    fun cleanName(file: String): String {
        val dot = file.lastIndexOf('.')
        val base = if (dot > 0) file.substring(0, dot) else file
        return base.replace('.', ' ').replace('_', ' ').trim()
    }

    fun toBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    /** A rich, readable accent colour taken from an image: used for the ambient glow. */
    fun dominant(src: Bitmap): Int {
        return try {
            val w = 24
            val h = 24
            val s = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            s.getPixels(px, 0, w, 0, 0, w, h)
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var wt = 0.0
            val hsv = FloatArray(3)
            for (p in px) {
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                val weight = (hsv[1] * hsv[2]).toDouble() + 0.02
                r += Color.red(p) * weight
                g += Color.green(p) * weight
                b += Color.blue(p) * weight
                wt += weight
            }
            if (wt <= 0.0) return Color.parseColor("#34447A")
            Color.colorToHSV(Color.rgb((r / wt).toInt(), (g / wt).toInt(), (b / wt).toInt()), hsv)
            hsv[1] = hsv[1].coerceIn(0.55f, 1f)
            hsv[2] = hsv[2].coerceIn(0.5f, 0.85f)
            Color.HSVToColor(hsv)
        } catch (e: Exception) {
            Color.parseColor("#34447A")
        }
    }
}

/** One motion language for the whole launcher: quick, decelerating, a little overshoot only on small icons. */
object Motion {
    fun focus(v: View, has: Boolean, scale: Float, overshoot: Boolean = false) {
        val s = if (has) scale else 1f
        v.animate().cancel()
        v.animate().scaleX(s).scaleY(s)
            .setStartDelay(0)
            .setDuration(if (has) 230L else 170L)
            .setInterpolator(if (has && overshoot) OvershootInterpolator(1.5f) else DecelerateInterpolator(1.6f))
            .start()
    }

    fun rise(v: View, delay: Long, dy: Float = 34f) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dy
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(560)
            .setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    /** Fades the old text out and the new text in (used for the big title on the left). */
    fun swap(block: View, change: () -> Unit) {
        block.animate().cancel()
        block.animate().alpha(0f).translationY(10f).setStartDelay(0).setDuration(90)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                change()
                block.translationY = -10f
                block.animate().alpha(1f).translationY(0f).setDuration(260)
                    .setInterpolator(DecelerateInterpolator(1.8f)).start()
            }.start()
    }
}
