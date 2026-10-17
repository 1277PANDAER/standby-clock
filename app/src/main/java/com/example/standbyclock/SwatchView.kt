package com.example.standbyclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** 配色面板里的圆形色块：左半 base 色、右半 overlay 色。 */
class SwatchView(context: Context, private val base: Int, private val overlay: Int) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        paint.color = base
        canvas.drawCircle(r, r, r, paint)
        if (overlay != base) {
            paint.color = overlay
            rect.set(r, 0f, width.toFloat(), height.toFloat())
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawCircle(r, r, r, paint)
            canvas.restore()
        }
    }
}
