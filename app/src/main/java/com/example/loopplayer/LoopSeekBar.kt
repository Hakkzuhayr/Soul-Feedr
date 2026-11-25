package com.example.loopplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/** A SeekBar that highlights the A-B loop section. Values are in the same units as progress. */
class LoopSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    var loopStart: Int = -1
        set(v) { field = v; invalidate() }
    var loopEnd: Int = -1
        set(v) { field = v; invalidate() }

    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FF9800 }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9800.toInt() }
    private val markerWidth = 3f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        if (max > 0 && loopStart >= 0) {
            val trackW = (width - paddingLeft - paddingRight).toFloat()
            fun x(v: Int) = paddingLeft + trackW * v / max
            val top = height * 0.15f
            val bottom = height * 0.85f
            val x1 = x(loopStart)
            if (loopEnd > loopStart) {
                val x2 = x(loopEnd)
                canvas.drawRect(x1, top, x2, bottom, regionPaint)
                canvas.drawRect(x2 - markerWidth / 2, top, x2 + markerWidth / 2, bottom, markerPaint)
            }
            canvas.drawRect(x1 - markerWidth / 2, top, x1 + markerWidth / 2, bottom, markerPaint)
        }
        super.onDraw(canvas)
    }
}
