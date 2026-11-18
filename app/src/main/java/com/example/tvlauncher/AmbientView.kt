package com.example.tvlauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** The launcher background: near-black, a soft colour glow that glides to whatever is focused, optional wallpaper underneath. */
class AmbientView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val base = Color.parseColor("#06080F")
    private var color = Color.parseColor("#2C3C70")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private var anim: ValueAnimator? = null
    private var fitFor: Bitmap? = null
    private var fitW = 0
    private var fitH = 0
    private var mix = 0f
    private var mixAnim: ValueAnimator? = null
    private val mBlur = Matrix()
    private var blurFor: Bitmap? = null
    private var blurW = 0
    private var blurH = 0

    fun setTarget(c: Int, instant: Boolean = false) {
        anim?.cancel()
        if (instant) {
            color = c
            invalidate()
            return
        }
        val a = ValueAnimator.ofObject(ArgbEvaluator(), color, c)
        a.duration = 750
        a.interpolator = DecelerateInterpolator(1.4f)
        a.addUpdateListener {
            color = it.animatedValue as Int
            invalidate()
        }
        anim = a
        a.start()
    }

    /** Screens other than home call this: the wallpaper melts from sharp into a soft blur as the screen opens. */
    fun blurIn() {
        if (!Store.wallBlur) return
        mixAnim?.cancel()
        mix = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 900
        a.startDelay = 120
        a.interpolator = DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            mix = it.animatedValue as Float
            invalidate()
        }
        mixAnim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        mixAnim?.cancel()
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bmp = Wall.bmp
        val hasWall = bmp != null && !bmp.isRecycled
        canvas.drawColor(base)
        if (hasWall && bmp != null) {
            if (bmp !== fitFor || fitW != width || fitH != height) {
                val s = maxOf(w / bmp.width, h / bmp.height)
                m.setScale(s, s)
                m.postTranslate((w - bmp.width * s) / 2f, (h - bmp.height * s) / 2f)
                fitFor = bmp
                fitW = width
                fitH = height
            }
            canvas.drawBitmap(bmp, m, bmpPaint)
            val bl = Wall.blurred
            if (mix > 0f && bl != null && !bl.isRecycled) {
                if (bl !== blurFor || blurW != width || blurH != height) {
                    val sb = maxOf(w / bl.width, h / bl.height)
                    mBlur.setScale(sb, sb)
                    mBlur.postTranslate((w - bl.width * sb) / 2f, (h - bl.height * sb) / 2f)
                    blurFor = bl
                    blurW = width
                    blurH = height
                }
                bmpPaint.alpha = (mix * 255).toInt()
                canvas.drawBitmap(bl, mBlur, bmpPaint)
                bmpPaint.alpha = 255
            }
            canvas.drawColor(Color.argb(165 + (mix * 35).toInt(), 4, 6, 12))
        }
        val a = if (hasWall) 120 else 160
        val c0 = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        val c1 = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        paint.shader = RadialGradient(w * 0.62f, h * 0.30f, w * 0.78f, c0, c1, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, h * 0.55f, 0f, h, Color.argb(0, 0, 0, 0), Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }
}
