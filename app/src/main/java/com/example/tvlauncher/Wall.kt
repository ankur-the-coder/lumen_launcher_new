package com.example.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.File

/** The chosen wallpaper (downloaded file + a screen-sized bitmap shared by every screen). */
object Wall {
    @Volatile var bmp: Bitmap? = null
    @Volatile var blurred: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())

    fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    fun reload(ctx: Context, done: () -> Unit = {}) {
        val f = file(ctx)
        val dm = ctx.resources.displayMetrics
        Bg.io.execute {
            val b = if (f.exists()) Images.decode(f.path, minOf(dm.widthPixels, 1920), minOf(dm.heightPixels, 1080), Bitmap.Config.RGB_565) else null
            bmp = b
            blurred = if (b != null) try { Blur.make(b, 192) } catch (e: Throwable) { null } else null
            main.post { done() }
        }
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        bmp = null
        blurred = null
    }
}
