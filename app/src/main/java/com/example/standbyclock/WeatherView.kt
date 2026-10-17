package com.example.standbyclock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 手绘动态天气图标。
 * 全部用表盘主题的两色（亮色 light / 暗色 dark）绘制，随时间循环播放小动画：
 * 晴天=太阳光芒旋转+光晕呼吸；多云=云漂移；雨=雨滴下落；雪=雪花飘落；雷=闪电闪烁；雾=雾带滑动。
 */
class WeatherView(context: Context) : View(context) {

    var kind: WeatherKind = WeatherKind.SUN
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private var light = Color.parseColor("#5AA7EE")
    private var dark = Color.parseColor("#0A5FB4")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private var animator: ValueAnimator? = null
    private var phase = 0f

    fun setColors(light: Int, dark: Int) {
        this.light = light
        this.dark = dark
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnim()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        sync()
    }

    private fun sync() {
        if (isShown) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        when (kind) {
            WeatherKind.SUN -> drawSun(canvas, cx, cy, s)
            WeatherKind.MOON -> drawMoon(canvas, cx, cy, s)
            WeatherKind.PARTLY -> {
                drawSun(canvas, cx - s * 0.15f, cy - s * 0.17f, s * 0.58f)
                drawCloud(canvas, cx + s * 0.05f, cy + s * 0.10f, s * 0.92f)
            }
            WeatherKind.PARTLY_NIGHT -> {
                drawMoon(canvas, cx - s * 0.15f, cy - s * 0.17f, s * 0.58f)
                drawCloud(canvas, cx + s * 0.05f, cy + s * 0.10f, s * 0.92f)
            }
            WeatherKind.CLOUDY -> {
                drawCloud(canvas, cx - s * 0.10f, cy - s * 0.10f, s * 0.66f, drift = 0f)
                drawCloud(canvas, cx + s * 0.12f, cy + s * 0.14f, s * 0.86f, drift = 0.5f)
            }
            WeatherKind.FOG -> {
                drawCloud(canvas, cx, cy - s * 0.17f, s * 0.92f)
                drawFogBars(canvas, cx, cy + s * 0.22f, s)
            }
            WeatherKind.RAIN -> {
                drawCloud(canvas, cx, cy - s * 0.14f, s * 0.92f)
                drawRain(canvas, cx, cy - s * 0.02f, s, 4)
            }
            WeatherKind.SNOW -> drawBigSnowflake(canvas, cx, cy, s)
            WeatherKind.SLEET -> {
                drawCloud(canvas, cx, cy - s * 0.16f, s * 0.92f)
                drawSleet(canvas, cx, cy - s * 0.02f, s)
            }
            WeatherKind.THUNDER -> {
                drawCloud(canvas, cx, cy - s * 0.16f, s * 0.92f)
                drawRain(canvas, cx, cy - s * 0.04f, s, 3, dropAlpha = 0.55f)
                drawBolt(canvas, cx, cy + s * 0.08f, s)
            }
        }
    }

    // ---------- 各元素 ----------

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.21f
        val t = phase * 2f * PI.toFloat()
        stroke.color = light
        stroke.strokeWidth = size * 0.05f
        stroke.alpha = 255
        val pulse = 1f + 0.10f * sin(t)
        for (i in 0 until 12) {
            val a = phase * PI.toFloat() + i * (2f * PI.toFloat() / 12f)
            val r1 = r * 1.45f
            val r2 = r * 1.85f * pulse
            canvas.drawLine(cx + cos(a) * r1, cy + sin(a) * r1, cx + cos(a) * r2, cy + sin(a) * r2, stroke)
        }
        // 光晕
        fill.shader = RadialGradient(
            cx, cy, r * 2.6f,
            intArrayOf(withAlpha(light, 110), withAlpha(light, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 2.6f, fill)
        fill.shader = null
        fill.color = light
        fill.alpha = 255
        canvas.drawCircle(cx, cy, r, fill)
    }

    private fun cloudPath(cx: Float, cy: Float, size: Float): Path {
        val w = size
        val h = size * 0.60f
        val p = Path()
        var q = Path().apply {
            addCircle(cx - w * 0.235f, cy + h * 0.06f, h * 0.33f, Path.Direction.CW)
        }
        p.op(q, Path.Op.UNION)
        q = Path().apply { addCircle(cx + w * 0.02f, cy - h * 0.12f, h * 0.44f, Path.Direction.CW) }
        p.op(q, Path.Op.UNION)
        q = Path().apply { addCircle(cx + w * 0.245f, cy + h * 0.05f, h * 0.31f, Path.Direction.CW) }
        p.op(q, Path.Op.UNION)
        q = Path().apply {
            addRoundRect(
                RectF(cx - w * 0.40f, cy - h * 0.02f, cx + w * 0.40f, cy + h * 0.36f),
                h * 0.19f, h * 0.19f, Path.Direction.CW
            )
        }
        p.op(q, Path.Op.UNION)
        return p
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, size: Float, drift: Float = -1f) {
        var dx = 0f
        if (drift >= 0f) {
            dx = sin((phase + drift) * 2f * PI.toFloat()) * size * 0.045f
        }
        val p = cloudPath(cx + dx, cy, size)
        fill.color = dark
        fill.alpha = 255
        fill.shader = null
        canvas.drawPath(p, fill)
        stroke.color = light
        stroke.strokeWidth = size * 0.035f
        stroke.alpha = 255
        canvas.drawPath(p, stroke)
    }

    /** 泪滴形雨滴。 */
    private fun drawDrop(canvas: Canvas, x: Float, y: Float, size: Float, alpha: Int) {
        val r = size * 0.030f
        val len = size * 0.080f
        val p = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        val tri = Path().apply {
            moveTo(x, y - len)
            lineTo(x - r * 0.85f, y)
            lineTo(x + r * 0.85f, y)
            close()
        }
        p.op(tri, Path.Op.UNION)
        fill.shader = null
        fill.color = light
        fill.alpha = alpha
        canvas.drawPath(p, fill)
        fill.alpha = 255
    }

    /** 六角雪花（三条交叉线），缓慢自转。 */
    private fun drawFlake(canvas: Canvas, x: Float, y: Float, size: Float, alpha: Int, seed: Int) {
        val r = size * 0.050f
        val rot = (phase * 2f + seed) * PI.toFloat() * 0.5f
        stroke.color = light
        stroke.strokeWidth = size * 0.026f
        stroke.alpha = alpha
        for (k in 0 until 3) {
            val a = rot + k * PI.toFloat() / 3f
            canvas.drawLine(
                x - cos(a) * r, y - sin(a) * r,
                x + cos(a) * r, y + sin(a) * r,
                stroke
            )
        }
        stroke.alpha = 255
    }

    private fun drawRain(canvas: Canvas, cx: Float, top: Float, size: Float, count: Int, dropAlpha: Float = 1f) {
        val travel = size * 0.42f
        for (i in 0 until count) {
            val t = (phase + i * (1f / count)) % 1f
            val x = cx + (-0.28f + 0.185f * i) * size
            val y = top + t * travel
            drawDrop(canvas, x, y, size, (255 * dropAlpha * (1f - t * 0.55f)).toInt().coerceIn(0, 255))
        }
    }

    private fun drawSnow(canvas: Canvas, cx: Float, top: Float, size: Float, count: Int) {
        val travel = size * 0.42f
        for (i in 0 until count) {
            val t = (phase + i * (1f / count)) % 1f
            val sway = sin((phase * 3f + i) * 2f * PI.toFloat()) * size * 0.05f
            val x = cx + (-0.28f + 0.185f * i) * size + sway
            val y = top + t * travel
            drawFlake(canvas, x, y, size, (255 * (1f - t * 0.5f)).toInt().coerceIn(0, 255), i)
        }
    }

    /** 雪的图标：一片大的六角雪花（缓慢自转+光晕）+ 旁边飘落的小雪花。 */
    private fun drawBigSnowflake(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.30f
        val rot = phase * PI.toFloat() * 0.5f
        fill.shader = RadialGradient(
            cx, cy, r * 1.9f,
            intArrayOf(withAlpha(light, 80), withAlpha(light, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.9f, fill)
        fill.shader = null
        stroke.color = light
        stroke.strokeWidth = size * 0.045f
        stroke.alpha = 255
        for (k in 0 until 6) {
            val a = rot + k * PI.toFloat() / 3f
            canvas.drawLine(cx, cy, cx + cos(a) * r, cy + sin(a) * r, stroke)
            val bx = cx + cos(a) * r * 0.60f
            val by = cy + sin(a) * r * 0.60f
            for (s in intArrayOf(-1, 1)) {
                val ba = a + s * PI.toFloat() / 4.5f
                canvas.drawLine(bx, by, bx + cos(ba) * r * 0.32f, by + sin(ba) * r * 0.32f, stroke)
            }
        }
        fill.color = light
        fill.alpha = 255
        canvas.drawCircle(cx, cy, size * 0.045f, fill)
        drawSnow(canvas, cx, cy + size * 0.10f, size * 0.72f, 3)
    }

    /** 雨夹雪：云下面雨滴和雪花交替下落。 */
    private fun drawSleet(canvas: Canvas, cx: Float, top: Float, size: Float) {
        val n = 4
        for (i in 0 until n) {
            val t = (phase + i * (1f / n)) % 1f
            val x = cx + (-0.26f + 0.175f * i) * size
            val y = top + t * size * 0.42f
            val a = (255 * (1f - t * 0.5f)).toInt().coerceIn(0, 255)
            if (i % 2 == 0) {
                drawDrop(canvas, x, y, size, a)
            } else {
                drawFlake(canvas, x, y, size, a, i)
            }
        }
    }

    /** 夜晚晴：月牙 + 两颗闪烁小星星。 */
    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.24f
        val t = phase * 2f * PI.toFloat()
        fill.shader = RadialGradient(
            cx, cy, r * 2.4f,
            intArrayOf(withAlpha(light, 90), withAlpha(light, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 2.4f, fill)
        fill.shader = null
        val outer = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val inner = Path().apply {
            addCircle(cx + r * 0.58f, cy - r * 0.44f, r * 0.90f, Path.Direction.CW)
        }
        val p = Path().apply { op(outer, inner, Path.Op.DIFFERENCE) }
        fill.color = light
        fill.alpha = 255
        canvas.drawPath(p, fill)
        val stars = floatArrayOf(-0.38f, -0.32f, 0.32f, 0.36f)
        var idx = 0
        while (idx < stars.size) {
            val fx = stars[idx]
            val fy = stars[idx + 1]
            val tw = 0.45f + 0.55f * abs(sin(t + idx * 0.65f))
            fill.alpha = (255 * tw).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx + fx * size, cy + fy * size, size * 0.046f, fill)
            idx += 2
        }
        fill.alpha = 255
    }

    private fun drawFogBars(canvas: Canvas, cx: Float, top: Float, size: Float) {
        stroke.color = light
        stroke.strokeWidth = size * 0.045f
        for (i in 0 until 3) {
            val t = (phase + i * 0.33f) % 1f
            val a = t * 2f * PI.toFloat()
            val slide = sin(a) * size * 0.06f
            val y = top + i * size * 0.12f
            stroke.alpha = (150 + 105 * (0.5f + 0.5f * sin(a + PI.toFloat() / 2f))).toInt().coerceIn(0, 255)
            val half = size * (0.34f - i * 0.05f)
            canvas.drawLine(cx - half + slide, y, cx + half + slide, y, stroke)
        }
        stroke.alpha = 255
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val t = phase
        val on = when {
            t < 0.10f -> sin(t / 0.10f * PI.toFloat())
            t > 0.22f && t < 0.30f -> sin((t - 0.22f) / 0.08f * PI.toFloat())
            else -> 0f
        }.coerceIn(0f, 1f)
        val p = Path().apply {
            moveTo(cx - size * 0.02f, cy - size * 0.18f)
            lineTo(cx - size * 0.14f, cy + size * 0.03f)
            lineTo(cx - size * 0.03f, cy + size * 0.03f)
            lineTo(cx - size * 0.10f, cy + size * 0.26f)
            lineTo(cx + size * 0.12f, cy - size * 0.04f)
            lineTo(cx + size * 0.01f, cy - size * 0.04f)
            lineTo(cx + size * 0.09f, cy - size * 0.18f)
            close()
        }
        fill.color = light
        fill.shader = null
        fill.alpha = (60 + 195 * on).toInt().coerceIn(0, 255)
        canvas.drawPath(p, fill)
        fill.alpha = 255
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
