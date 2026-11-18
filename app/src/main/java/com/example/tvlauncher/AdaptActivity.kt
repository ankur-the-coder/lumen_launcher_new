package com.example.tvlauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Shown when a picked picture is not 16:9. Everything is live on the full-screen preview:
 * choose Fill (move + zoom), Fit + blur, Fit + colour or Stretch, then apply.
 */
class AdaptActivity : AppCompatActivity() {
    private lateinit var adaptView: AdaptView
    private lateinit var panel: View
    private lateinit var hintPill: TextView
    private lateinit var modeDesc: TextView
    private lateinit var zoomBox: View
    private lateinit var busy: View
    private lateinit var modeChips: List<TextView>
    private var path = ""
    private var ready = false
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, now -> onFocusChanged(now) }

    private val descriptions = arrayOf(
        "Fill:  covers the whole screen. Whatever does not fit is cropped, so move and zoom the picture to choose what stays.",
        "Fit + blur:  shows the whole picture and fills the sides with a soft, blurred copy of it.",
        "Fit + colour:  shows the whole picture on a background glowing in its main colour.",
        "Stretch:  squeezes the picture to 16:9. Nothing is cropped, but it will look distorted."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adapt)
        path = intent.getStringExtra("path") ?: ""
        adaptView = findViewById(R.id.adaptView)
        panel = findViewById(R.id.panel)
        hintPill = findViewById(R.id.hintPill)
        modeDesc = findViewById(R.id.modeDesc)
        zoomBox = findViewById(R.id.zoomBox)
        busy = findViewById(R.id.busy)
        modeChips = listOf(findViewById(R.id.mFill), findViewById(R.id.mBlur), findViewById(R.id.mColor), findViewById(R.id.mStretch))

        for ((i, c) in modeChips.withIndex()) {
            c.setOnClickListener { setMode(i) }
            c.setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.04f) }
        }
        val actions = listOf<Pair<Int, () -> Unit>>(
            Pair(R.id.zoomOut, { adaptView.zoomBy(-0.25f) }),
            Pair(R.id.zoomIn, { adaptView.zoomBy(0.25f) }),
            Pair(R.id.zoomReset, { adaptView.resetView() }),
            Pair(R.id.btnCancel, { finish() }),
            Pair(R.id.btnApply, { apply() })
        )
        for ((id, act) in actions) {
            val v = findViewById<TextView>(id)
            v.setOnClickListener { act() }
            v.setOnFocusChangeListener { fv, has -> Motion.focus(fv, has, 1.04f) }
        }
        findViewById<TextView>(R.id.btnApply).isSelected = true
        adaptView.onOk = { modeChips[adaptView.mode].requestFocus() }

        // load the picture (sized for the preview) in the background
        Bg.io.execute {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            val bmp = decode(path, 1920, Bitmap.Config.RGB_565)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bmp == null || o.outWidth <= 0) {
                    Toast.makeText(this, "Could not open this picture", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                val ratio = o.outWidth.toFloat() / o.outHeight
                findViewById<TextView>(R.id.aSub).text =
                    "Original " + o.outWidth + " x " + o.outHeight + "   (" + String.format(Locale.US, "%.2f", ratio) + " : 1,  your TV is 1.78 : 1)"
                adaptView.setSource(bmp, Blur.make(bmp, 160), U.dominant(bmp))
                ready = true
                // portrait or square pictures look best whole; wide ones look best filled
                setMode(if (ratio < 1.2f) Composer.FIT_BLUR else Composer.FILL)
                adaptView.alpha = 0f
                adaptView.animate().alpha(1f).setDuration(420).start()
                panel.alpha = 0f
                panel.translationY = 40f
                panel.animate().alpha(1f).translationY(0f).setStartDelay(150).setDuration(480).setInterpolator(DecelerateInterpolator(2f)).start()
                modeChips[adaptView.mode].requestFocus()
            }
        }
        findViewById<View>(android.R.id.content).viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            findViewById<View>(android.R.id.content).viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        } catch (e: Exception) {
        }
    }

    private fun setMode(m: Int) {
        adaptView.setMode(m)
        for ((i, c) in modeChips.withIndex()) c.isSelected = i == m
        modeDesc.text = descriptions[m]
        zoomBox.visibility = if (m == Composer.FILL) View.VISIBLE else View.GONE
        hintPill.text = if (m == Composer.FILL) "Arrow keys move the picture     OK opens the tools" else "Press OK to open the tools"
    }

    /** While you are moving the picture the tools slide away so you can judge the result on the whole screen. */
    private fun onFocusChanged(now: View?) {
        if (!ready) return
        val onPreview = now === adaptView
        panel.animate().cancel()
        hintPill.animate().cancel()
        if (onPreview) {
            panel.animate().alpha(0f).translationY(panel.height * 0.4f).setStartDelay(0).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
            hintPill.animate().alpha(1f).setStartDelay(120).setDuration(260).start()
        } else {
            panel.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
            hintPill.animate().alpha(0f).setStartDelay(0).setDuration(140).start()
        }
    }

    private fun apply() {
        if (!ready) return
        val mode = adaptView.mode
        val zoom = adaptView.zoom
        val px = adaptView.panX
        val py = adaptView.panY
        busy.visibility = View.VISIBLE
        busy.alpha = 0f
        busy.animate().alpha(1f).setDuration(200).start()
        val ctx = applicationContext
        val p = path
        Bg.io.execute {
            try {
                render(p, mode, zoom, px, py, Wall.file(ctx))
                Wall.reload(ctx) {
                    if (!isDestroyed) {
                        Toast.makeText(this, "Wallpaper applied", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (!isDestroyed) {
                        busy.visibility = View.GONE
                        Toast.makeText(this, "Could not create the wallpaper: " + (e.message ?: "out of memory"), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (busy.visibility == View.VISIBLE) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.exit)
    }

    companion object {
        /** Decodes with the long side at most [maxSide]. */
        fun decode(path: String, maxSide: Int, cfg: Bitmap.Config): Bitmap? {
            return try {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, o)
                if (o.outWidth <= 0) return null
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                o2.inPreferredConfig = cfg
                BitmapFactory.decodeFile(path, o2)
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        /** Renders the 16:9 result with exactly the same routine as the preview and saves it as a JPEG. */
        fun render(path: String, mode: Int, zoom: Float, panX: Float, panY: Float, out: File) {
            val src = decode(path, 2560, Bitmap.Config.RGB_565) ?: throw java.io.IOException("Cannot read picture")
            val big = maxOf(src.width, src.height) >= 2400
            val w = if (big) 2560 else 1920
            val h = if (big) 1440 else 1080
            val blur = Blur.make(src, 160)
            val color = U.dominant(src)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            Composer.draw(c, w.toFloat(), h.toFloat(), mode, src, blur, color, zoom, panX, panY, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG), Matrix())
            val tmp = File(out.parentFile, out.name + ".tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw java.io.IOException("Could not save")
            bmp.recycle()
            src.recycle()
        }
    }
}
