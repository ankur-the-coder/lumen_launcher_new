package com.example.tvlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** The four ways to turn any picture into a 16:9 wallpaper. One drawing routine serves the live preview and the final file. */
object Composer {
    const val FILL = 0
    const val FIT_BLUR = 1
    const val FIT_COLOR = 2
    const val STRETCH = 3

    fun draw(c: Canvas, w: Float, h: Float, mode: Int, src: Bitmap, blur: Bitmap, color: Int, zoom: Float, panX: Float, panY: Float, paint: Paint, m: Matrix) {
        val bw = src.width.toFloat()
        val bh = src.height.toFloat()
        when (mode) {
            FILL -> {
                val s = maxOf(w / bw, h / bh) * zoom
                val slackX = maxOf(0f, bw * s - w)
                val slackY = maxOf(0f, bh * s - h)
                m.setScale(s, s)
                m.postTranslate((w - bw * s) / 2f - panX * slackX / 2f, (h - bh * s) / 2f - panY * slackY / 2f)
                c.drawBitmap(src, m, paint)
            }
            FIT_BLUR -> {
                val sb = maxOf(w / blur.width, h / blur.height)
                m.setScale(sb, sb)
                m.postTranslate((w - blur.width * sb) / 2f, (h - blur.height * sb) / 2f)
                c.drawBitmap(blur, m, paint)
                c.drawColor(Color.argb(70, 0, 0, 0))
                fit(c, w, h, src, paint, m)
            }
            FIT_COLOR -> {
                c.drawColor(Color.parseColor("#06080F"))
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val c0 = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
                val c1 = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                p.shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.75f, c0, c1, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
                fit(c, w, h, src, paint, m)
            }
            else -> {
                m.setScale(w / bw, h / bh)
                c.drawBitmap(src, m, paint)
            }
        }
    }

    private fun fit(c: Canvas, w: Float, h: Float, src: Bitmap, paint: Paint, m: Matrix) {
        val s = minOf(w / src.width, h / src.height)
        m.setScale(s, s)
        m.postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f)
        c.drawBitmap(src, m, paint)
    }
}

/** Full-screen live preview. Arrow keys move the picture (Fill), changes glide instead of jumping, mode changes cross-fade. */
class AdaptView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var src: Bitmap? = null
    private var blur: Bitmap? = null
    private var color = Color.parseColor("#34447A")
    var mode = Composer.FILL
        private set
    private var from = Composer.FILL
    private var t = 1f
    private var modeAnim: ValueAnimator? = null

    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set
    private var tz = 1f
    private var tpx = 0f
    private var tpy = 0f
    private var running = false

    var onOk: (() -> Unit)? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()

    init {
        isFocusable = true
    }

    fun setSource(b: Bitmap, bl: Bitmap, c: Int) {
        src = b
        blur = bl
        color = c
        invalidate()
    }

    fun setMode(newMode: Int) {
        if (newMode == mode) return
        from = mode
        mode = newMode
        t = 0f
        modeAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 320
        a.interpolator = DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        modeAnim = a
        a.start()
    }

    fun zoomBy(d: Float) {
        tz = (tz + d).coerceIn(1f, 4f)
        kick()
    }

    fun panBy(dx: Float, dy: Float) {
        tpx = (tpx + dx).coerceIn(-1f, 1f)
        tpy = (tpy + dy).coerceIn(-1f, 1f)
        kick()
    }

    fun resetView() {
        tz = 1f
        tpx = 0f
        tpy = 0f
        kick()
    }

    private fun kick() {
        if (!running) {
            running = true
            postOnAnimation(stepper)
        }
    }

    private val stepper = object : Runnable {
        override fun run() {
            zoom += (tz - zoom) * 0.2f
            panX += (tpx - panX) * 0.2f
            panY += (tpy - panY) * 0.2f
            if (abs(tz - zoom) < 0.002f && abs(tpx - panX) < 0.002f && abs(tpy - panY) < 0.002f) {
                zoom = tz
                panX = tpx
                panY = tpy
                running = false
            } else {
                postOnAnimation(this)
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = src ?: return
        val bl = blur ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (t >= 1f) {
            Composer.draw(canvas, w, h, mode, s, bl, color, zoom, panX, panY, paint, m)
        } else {
            Composer.draw(canvas, w, h, from, s, bl, color, zoom, panX, panY, paint, m)
            canvas.saveLayerAlpha(0f, 0f, w, h, (t * 255).toInt())
            Composer.draw(canvas, w, h, mode, s, bl, color, zoom, panX, panY, paint, m)
            canvas.restore()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            onOk?.invoke()
            return true
        }
        if (mode == Composer.FILL) {
            val st = 0.12f * (1f + minOf(event.repeatCount, 9) / 3f)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { panBy(-st, 0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { panBy(st, 0f); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { panBy(0f, -st); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { panBy(0f, st); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
