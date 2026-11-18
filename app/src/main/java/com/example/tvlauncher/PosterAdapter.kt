package com.example.tvlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

interface PosterCb {
    fun onFocus(pos: Int, v: View)
    fun onClick(pos: Int)
    fun onLong(pos: Int)
}

/** Posters keep their own aspect ratio: the card is sized to the image, so nothing is ever cropped. */
class PosterAdapter(private val posterH: Int, private val cb: PosterCb) : RecyclerView.Adapter<PosterAdapter.PH>() {
    var items: List<Title> = emptyList()

    class PH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val bar: View = v.findViewById(R.id.bar)
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].dir.hashCode().toLong()
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        U.round(v, parent.context.dp(14).toFloat())
        val h = PH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) cb.onClick(p)
        }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) cb.onLong(p)
            true
        }
        v.setOnFocusChangeListener { fv, has ->
            Motion.focus(fv, has, 1.09f)
            if (has) {
                val p = h.bindingAdapterPosition
                if (p >= 0) cb.onFocus(p, fv)
            }
        }
        return h
    }

    override fun onBindViewHolder(h: PH, position: Int) {
        val t = items[position]
        val ratio = t.pw.toFloat() / t.ph.toFloat()
        val w = (posterH * ratio).toInt().coerceIn((posterH * 0.55f).toInt(), (posterH * 1.9f).toInt())
        val lp = RecyclerView.LayoutParams(w, posterH)
        lp.rightMargin = h.itemView.context.dp(16)
        h.itemView.layoutParams = lp
        h.itemView.tag = t.poster
        h.img.setImageDrawable(null)
        Images.load(t.poster, (posterH * 1.25f).toInt()) { bmp ->
            if (h.itemView.tag == t.poster && bmp != null) h.img.setImageBitmap(bmp)
        }
        val last = Store.last(t.dir)
        val p = if (last != null) Store.prog(last) else null
        if (p != null && !p.done && p.frac > 0.01f) {
            h.bar.visibility = View.VISIBLE
            val bl = h.bar.layoutParams
            bl.width = (w * p.frac).toInt().coerceAtLeast(6)
            h.bar.layoutParams = bl
        } else {
            h.bar.visibility = View.GONE
        }
    }
}
