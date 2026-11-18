package com.example.tvlauncher

import android.graphics.Bitmap

/** Cheap CPU blur: shrink the picture, box-blur it three times, let bilinear scaling smooth it back up. */
object Blur {
    fun make(src: Bitmap, w: Int): Bitmap {
        val h = (w * src.height.toFloat() / src.width).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        val tmp = IntArray(px.size)
        for (i in 0 until 3) {
            pass(px, tmp, w, h, 4, true)
            pass(tmp, px, w, h, 4, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val c = i.coerceIn(0, len - 1)
                val p = src[if (horizontal) l * w + c else c * w + l]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (i in 0 until len) {
                dst[if (horizontal) l * w + i else i * w + l] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val ia = (i + r + 1).coerceIn(0, len - 1)
                val isub = (i - r).coerceIn(0, len - 1)
                val pa = src[if (horizontal) l * w + ia else ia * w + l]
                val ps = src[if (horizontal) l * w + isub else isub * w + l]
                sr += ((pa shr 16) and 255) - ((ps shr 16) and 255)
                sg += ((pa shr 8) and 255) - ((ps shr 8) and 255)
                sb += (pa and 255) - (ps and 255)
            }
        }
    }
}
