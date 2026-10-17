package com.example.standbyclock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 表盘：4 个数字槽位(HH:MM)，每分钟翻页动画，
 * 支持两种样式：0=倾斜俏皮(每槽随机角度/缩放)，1=端正。
 */
class ClockView(context: Context) : View(context) {

    companion object {
        private const val COLON_EXTRA = 0.44f
        private const val COLON_TILT = -6.0f
        private const val PAIR_FACTOR_TILTED = 0.6f
        private const val PAIR_FACTOR_UPRIGHT = 0.74f
        const val STYLE_TILTED = 0
        const val STYLE_UPRIGHT = 1
    }

    /** 单个数字槽位。 */
    private class Slot {
        var cur: Char = '0'
        var next: Char? = null
        var progress = 0f
        var tilt = 0f
        var scale = 1f
    }

    private val slots = Array(4) { Slot() }

    var lightColor: Int = Color.parseColor("#5AA7EE")
        private set
    var darkColor: Int = Color.parseColor("#0A5FB4")
        private set

    /** 0..1，1=完全倾斜，0=端正。 */
    private var tiltFactor = 1f

    var styleIndex = STYLE_TILTED
        private set

    /** 文字尺寸(px)。 */
    private var ts = 0f
    private var maxAdvance = 0f
    private var typeface: Typeface? = null

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint()

    private var colorAnim: ValueAnimator? = null
    private var styleAnim: ValueAnimator? = null
    private var slideAnim: ValueAnimator? = null

    init {
        digitPaint.textAlign = Paint.Align.CENTER
        digitPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        randomizePerSlot()
    }

    private fun randomizePerSlot() {
        for (s in slots) {
            val dir = if (Random.nextBoolean()) 1f else -1f
            s.tilt = (Random.nextFloat() * 2.5f + 4.0f) * dir
            s.scale = Random.nextFloat() * 0.11f + 0.95f
        }
    }

    fun setFont(tf: Typeface) {
        typeface = tf
        digitPaint.typeface = tf
        measurePaint.typeface = tf
        requestLayout()
    }

    private fun applyColors(light: Int, dark: Int) {
        lightColor = light
        darkColor = dark
        invalidate()
    }

    /** 直接切换配色(base=亮色, overlay=暗色)。 */
    fun setColors(base: Int, overlay: Int) {
        applyColors(base, overlay)
    }

    /** 350ms 渐变过渡到新配色。 */
    fun animateColors(base: Int, overlay: Int) {
        colorAnim?.cancel()
        val fromLight = lightColor
        val fromDark = darkColor
        val eval = ArgbEvaluator()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350L
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                val light = eval.evaluate(f, fromLight, base) as Int
                val dark = eval.evaluate(f, fromDark, overlay) as Int
                applyColors(light, dark)
            }
            start()
        }
    }

    fun setCurrentStyle(value: Int) {
        val target = if (value == STYLE_TILTED) STYLE_TILTED else STYLE_UPRIGHT
        if (target != styleIndex) animateStyle(target)
    }

    private fun animateStyle(target: Int) {
        styleAnim?.cancel()
        styleIndex = target
        if (target == STYLE_TILTED) randomizePerSlot()
        val from = tiltFactor
        val to = if (target == STYLE_TILTED) 1f else 0f
        styleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            addUpdateListener { anim ->
                tiltFactor = from + (to - from) * anim.animatedFraction
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    /** 更新显示时间；animate=true 时超过整分钟会播放翻页动画。 */
    fun update(hour: Int, minute: Int, animate: Boolean) {
        val chars = charArrayOf(
            (hour / 10 + 48).toChar(),
            (hour % 10 + 48).toChar(),
            (minute / 10 + 48).toChar(),
            (minute % 10 + 48).toChar()
        )
        var changed = false
        for (i in 0 until 4) {
            if (slots[i].cur != chars[i] || slots[i].next != null) {
                if (!animate || width <= 0) {
                    slots[i].cur = chars[i]
                    slots[i].next = null
                    slots[i].progress = 0f
                } else {
                    slots[i].next = chars[i]
                    changed = true
                }
            }
        }
        if (changed) {
            startSlide()
        } else {
            requestLayout()
            invalidate()
        }
    }

    private fun startSlide() {
        slideAnim?.cancel()
        slideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300L
            addUpdateListener { anim ->
                val p = anim.animatedFraction
                for (s in slots) {
                    if (s.next != null) s.progress = p
                }
                requestLayout()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    for (s in slots) {
                        s.next?.let { s.cur = it }
                        s.next = null
                        s.progress = 0f
                    }
                    requestLayout()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun pairFactor(): Float =
        tiltFactor * (PAIR_FACTOR_TILTED - PAIR_FACTOR_UPRIGHT) + PAIR_FACTOR_UPRIGHT

    private fun slotScale(s: Slot): Float = (s.scale - 1f) * tiltFactor + 1f

    private fun slotTilt(s: Slot): Float = s.tilt * tiltFactor

    private fun charWidth(c: Char): Float = measurePaint.measureText(c.toString())

    private fun displayedWidth(s: Slot): Float {
        val next = s.next
        val w = if (next == null) {
            charWidth(s.cur)
        } else {
            charWidth(s.cur) + (charWidth(next) - charWidth(s.cur)) * s.progress
        }
        return slotScale(s) * w
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        ts = h * 0.98f
        measurePaint.typeface = typeface
        measurePaint.textSize = ts
        var ma = 0f
        for (c in "0123456789") ma = max(ma, measurePaint.measureText(c.toString()))
        maxAdvance = ma
        val pf = pairFactor()
        val maxW1 = maxAdvance * 1.06f
        val total = 3f * maxW1 * pf + ts * COLON_EXTRA + maxW1
        val maxAllowed = w * 0.96f
        if (total > maxAllowed) {
            ts *= maxAllowed / total
            measurePaint.textSize = ts
            var ma2 = 0f
            for (c in "0123456789") ma2 = max(ma2, measurePaint.measureText(c.toString()))
            maxAdvance = ma2
        }
        digitPaint.textSize = ts
        setMeasuredDimension(w, h)
    }

    /** 弹簧缓动：略微过冲后落定。 */
    private fun springEase(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return 1f - (exp(COLON_TILT * t) * cos(7.853982f * t))
    }

    override fun onDraw(canvas: Canvas) {
        if (ts <= 0f) return
        val pf = pairFactor()
        val widths = FloatArray(4) { displayedWidth(slots[it]) }
        val centerDist = FloatArray(3)
        for (i in 0 until 3) {
            centerDist[i] = (widths[i] + widths[i + 1]) / 2f * pf
        }
        centerDist[1] += ts * COLON_EXTRA
        val total = widths[0] / 2f + centerDist.sum() + widths[3] / 2f
        val centers = FloatArray(4)
        centers[0] = (width - total) / 2f + widths[0] / 2f
        for (i in 1 until 4) centers[i] = centers[i - 1] + centerDist[i - 1]

        val token = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), layerPaint)
        canvas.drawColor(Color.BLACK)
        val fm = digitPaint.fontMetrics
        val cy = height / 2f
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        val exitShift = ts * 1.5f
        val slideIn = ts * 0.78f

        for (i in 0 until 4) {
            val s = slots[i]
            val cx = centers[i]
            digitPaint.color = if (i % 2 == 0) darkColor else lightColor
            val tilt = slotTilt(s)
            val scale = slotScale(s)
            canvas.save()
            canvas.rotate(tilt, cx, cy)
            canvas.scale(scale, scale, cx, cy)
            val next = s.next
            if (next == null) {
                digitPaint.alpha = 255
                canvas.drawText(s.cur.toString(), cx, baseline, digitPaint)
            } else {
                val p = s.progress
                if (p < 0.999f) {
                    val ep = min(1f, 1.35f * p)
                    digitPaint.alpha = ((1f - ep) * 255f).toInt().coerceIn(0, 255)
                    canvas.drawText(s.cur.toString(), cx, baseline - exitShift * ep, digitPaint)
                }
                val eased = springEase(p)
                digitPaint.alpha = (min(1f, 3f * p) * 255f).toInt().coerceIn(0, 255)
                canvas.drawText(next.toString(), cx, (1f - eased) * slideIn + baseline, digitPaint)
                digitPaint.alpha = 255
            }
            canvas.restore()
        }
        canvas.restoreToCount(token)

        // 冒号：两个带光晕的圆点
        val ccx = (centers[1] + centers[2]) / 2f
        val radius = ts * 0.098f
        val offset = ts * 0.185f
        canvas.save()
        canvas.rotate(tiltFactor * COLON_TILT, ccx, cy)
        colonPaint.alpha = 225
        drawDot(canvas, ccx, cy - offset, radius)
        drawDot(canvas, ccx, cy + offset, radius)
        colonPaint.alpha = 255
        canvas.restore()
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, r: Float) {
        colonPaint.shader = RadialGradient(
            x - r * 0.3f, y - 0.3f * r, r * 2f,
            intArrayOf(-131587, -2565928, -6645094),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, r, colonPaint)
    }
}
