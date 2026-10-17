package com.example.standbyclock

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 底部弹出的配色面板（作为 Dialog 的内容视图），
 * 内含 10 组预置配色，选中后通过 Listener 回调。
 */
class ColorSheet(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onClose()
        fun onColorSelected(index: Int)
    }

    companion object {
        /** light(亮) to dark(暗) 成对配色。 */
        val PALETTES: List<Pair<Int, Int>> = listOf(
            Color.parseColor("#5AA7EE") to Color.parseColor("#0A5FB4"),
            Color.parseColor("#A78BFA") to Color.parseColor("#6D28D9"),
            Color.parseColor("#F272B6") to Color.parseColor("#B0368C"),
            Color.parseColor("#F87171") to Color.parseColor("#C81E1E"),
            Color.parseColor("#FBBF24") to Color.parseColor("#EA7A0C"),
            Color.parseColor("#6EE7A0") to Color.parseColor("#16A34A"),
            Color.parseColor("#2DD4BF") to Color.parseColor("#0D9488"),
            Color.parseColor("#818CF8") to Color.parseColor("#4338CA"),
            Color.parseColor("#57D97A") to Color.parseColor("#3B82F6"),
            Color.parseColor("#F59E0B") to Color.parseColor("#3B82F6")
        )
    }

    var listener: Listener? = null

    private val density = context.resources.displayMetrics.density
    private val rings = mutableListOf<View>()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(-1288950738)
            cornerRadii = floatArrayOf(
                dp(22f).toFloat(), dp(22f).toFloat(), dp(22f).toFloat(), dp(22f).toFloat(), 0f, 0f, 0f, 0f
            )
        }
        setPadding(0, dp(10f), 0, dp(14f))
        elevation = dp(14f).toFloat()

        // 头部：标题 + 关闭按钮
        val header = FrameLayout(context)
        val title = TextView(context).apply {
            text = "颜色"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        header.addView(
            title,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val close = TextView(context).apply {
            text = "✕"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(872415231)
            }
            setOnClickListener { listener?.onClose() }
        }
        val closeLp = FrameLayout.LayoutParams(dp(30f), dp(30f))
        closeLp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        closeLp.marginEnd = dp(14f)
        header.addView(close, closeLp)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f)))

        // 一排色块
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        PALETTES.forEachIndexed { index, pal ->
            val cell = FrameLayout(context)
            val swatch = SwatchView(context, pal.first, pal.second)
            val swatchLp = FrameLayout.LayoutParams(dp(40f), dp(40f))
            swatchLp.gravity = Gravity.CENTER
            cell.addView(swatch, swatchLp)

            val ring = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2.5f), Color.WHITE)
                }
                visibility = INVISIBLE
            }
            val ringLp = FrameLayout.LayoutParams(dp(50f), dp(50f))
            ringLp.gravity = Gravity.CENTER
            cell.addView(ring, ringLp)
            rings.add(ring)

            cell.layoutParams = LayoutParams(dp(54f), dp(54f))
            cell.isClickable = true
            cell.setOnClickListener { listener?.onColorSelected(index) }
            row.addView(cell)
        }
        val rowLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        rowLp.topMargin = dp(10f)
        addView(row, rowLp)
    }

    fun setSelection(index: Int) {
        rings.forEachIndexed { i, ring ->
            ring.visibility = if (i == index) VISIBLE else INVISIBLE
        }
    }

    private fun dp(v: Float): Int = (density * v).toInt()
}
