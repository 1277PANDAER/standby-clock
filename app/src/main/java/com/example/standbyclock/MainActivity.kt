package com.example.standbyclock

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 待机数字时钟：
 *  - 单击屏幕：显示/隐藏顶部信息（日期 + 电量）；天气板块显示时单击=刷新天气
 *  - 长按屏幕：打开配色面板
 *  - 左右滑动：切换表盘样式（倾斜 / 端正）
 *  - 上下滑动：显示/隐藏天气板块（定位地区 + 动态天气图标 + 温度 + 汉字说明）
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_LOCATION = 1001
    }

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var clock: ClockView
    private lateinit var infoBar: FrameLayout
    private lateinit var dateText: TextView
    private lateinit var batteryText: TextView
    private lateinit var bolt: ImageView
    private lateinit var sourceText: TextView
    private lateinit var gestureDetector: GestureDetector

    private lateinit var weatherPanel: LinearLayout
    private lateinit var weatherView: WeatherView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherRange: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherCity: TextView
    private lateinit var weatherExtra: TextView

    private var colorDialog: Dialog? = null
    private var infoShown = false
    private var weatherShown = false
    private var weatherLoading = false
    private var weather: WeatherInfo? = null
    private var demoWeatherCode: Int? = null
    private var debugSource: String? = null

    /** 天气面板显示多久后自动回退为只显示时间（调试可用 --ei autoHideSec 覆盖）。 */
    private var autoHideMs = 3_000L

    /** 右侧"温度+区间"容器（等比缩放时要改它的 LayoutParams）。 */
    private var tempBox: FrameLayout? = null

    /** 天气面板（图标/温度/地区/说明/间距）的**目标**等比缩放系数；1f=原始大小（调试可用 --ef weatherScale 覆盖）。 */
    private var baseWeatherScale = 2.0f

    /** 实际生效的缩放（屏幕高度不够时会被自动压低，内容变少时能回到目标值）。 */
    private var weatherScale = 2.0f
    private var level = -1
    private var charging = false

    private val handler = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val dayFmt = SimpleDateFormat("M月d日", Locale.CHINA)

    private val tick = object : Runnable {
        override fun run() {
            refresh(true)
            schedule()
        }
    }

    private val hideInfoRunnable = Runnable { hideInfo() }

    /** 显示满 autoHideMs 后自动回退为只显示时间（不用手动收面板）。 */
    private val autoHideWeather = Runnable { hideWeather() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            level = intent?.getIntExtra("level", -1) ?: -1
            val scale = intent?.getIntExtra("scale", 100) ?: 100
            val status = intent?.getIntExtra("status", -1) ?: -1
            charging = status == 2 || status == 5
            if (level >= 0 && scale > 0) {
                updateBatteryText()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // 调试用：--ef weatherScale 2.5 实时调天气面板缩放（仅 DEBUG 包；必须在 buildUi 之前读）
        if (BuildConfig.DEBUG && intent?.hasExtra("weatherScale") == true) {
            baseWeatherScale = intent.getFloatExtra("weatherScale", baseWeatherScale).coerceIn(0.5f, 4f)
            weatherScale = baseWeatherScale
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        try {
            val tf = Typeface.createFromAsset(assets, "font/baloo2-700.ttf")
            clock.setFont(tf)
            weatherTemp.typeface = tf
        } catch (_: Exception) {
        }

        val palettes = ColorSheet.PALETTES
        val palette = palettes[prefs.paletteIndex.coerceIn(0, palettes.size - 1)]
        clock.setColors(palette.first, palette.second)
        clock.setCurrentStyle(prefs.styleIndex)
        applyWeatherColors(palette.first, palette.second)
        refresh(false)

        // 上次的天气缓存（离线也能显示）
        weather = WeatherInfo.fromJson(prefs.weatherCache)
        refreshWeatherUi()

        // 调试用：adb 传 --ei demoWeather <WMO码> [--ei demoTemp 25] [--es demoCity 重庆] [--ez demoNight true]
        // 调试用：--es debugSource wttr|openmeteo 强制天气来源
        if (BuildConfig.DEBUG && intent?.hasExtra("debugSource") == true) {
            debugSource = intent.getStringExtra("debugSource")
        }
        if (BuildConfig.DEBUG && intent?.hasExtra("autoHideSec") == true) {
            autoHideMs = intent.getIntExtra("autoHideSec", 3).coerceIn(1, 3600) * 1000L
        }
        Log.i("StandbyClock", "onCreate: debugSource=$debugSource, demoWeather=$demoWeatherCode")
        if (BuildConfig.DEBUG && intent?.hasExtra("demoWeather") == true) {
            val code = intent.getIntExtra("demoWeather", 0)
            val night = intent.getBooleanExtra("demoNight", false)
            val (kind, desc) = WeatherService.codeToWeather(code, !night)
            demoWeatherCode = code
            val demoTemp = intent.getIntExtra("demoTemp", 23)
            weather = WeatherInfo(
                tempC = demoTemp,
                tempMin = demoTemp - 6,
                tempMax = demoTemp + 5,
                code = code,
                desc = desc,
                city = intent.getStringExtra("demoCity") ?: "演示地区",
                kind = kind,
                isDay = !night,
                ts = System.currentTimeMillis()
            )
            refreshWeatherUi()
            root.post { showWeather() }
        }
    }

    private fun dp(v: Float): Int = (resources.displayMetrics.density * v).toInt()

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        clock = ClockView(this)
        root.addView(
            clock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        infoBar = FrameLayout(this)
        dateText = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
        }
        val dateLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        dateLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        dateLp.topMargin = dp(6f)
        infoBar.addView(dateText, dateLp)

        batteryText = TextView(this).apply { textSize = 15f }
        val batteryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bolt = ImageView(this).apply {
            setImageResource(R.drawable.ic_bolt)
            visibility = View.INVISIBLE
        }
        val boltLp = LinearLayout.LayoutParams(dp(15f), dp(15f))
        boltLp.marginEnd = dp(4f)
        batteryRow.addView(bolt, boltLp)
        batteryRow.addView(batteryText)

        val batteryLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        batteryLp.gravity = Gravity.TOP or Gravity.END
        batteryLp.topMargin = dp(10f)
        batteryLp.marginEnd = dp(16f)
        infoBar.addView(batteryRow, batteryLp)

        // 数据来源署名（Open-Meteo 的 CC-BY 4.0 要求注明来源）
        sourceText = TextView(this).apply {
            textSize = 9f
            alpha = 0.45f
            gravity = Gravity.CENTER
        }
        val srcLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        srcLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        srcLp.bottomMargin = dp(2f)
        infoBar.addView(sourceText, srcLp)

        val infoLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(46f))
        infoLp.gravity = Gravity.TOP
        root.addView(infoBar, infoLp)
        infoBar.alpha = 0f
        infoBar.translationY = -dp(48f).toFloat()
        infoBar.visibility = View.INVISIBLE

        buildWeatherPanel()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (colorDialog == null) {
                    if (weatherShown) {
                        // 系统定位没开 → 直接跳到系统定位设置页（比反复"重试"有用）
                        if (!locationServiceEnabled()) {
                            weatherDesc.text = "请打开手机定位"
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            } catch (_: Exception) {
                            }
                        } else {
                            fetchWeather()
                        }
                        // 点按后重新计时，让用户有完整的 3 秒看刷新结果
                        scheduleAutoHide()
                    } else {
                        toggleInfo()
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (colorDialog == null) {
                    showColorDialog()
                }
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // 上下滑 → 天气板块
                if (abs(dy) > abs(dx) && abs(dy) > 120f && abs(velocityY) > 200f) {
                    if (colorDialog == null) {
                        toggleWeather()
                    }
                    return true
                }
                // 左右滑 → 表盘样式
                if (abs(dx) > abs(dy) && abs(dx) > 120f && abs(velocityX) > 200f) {
                    if (colorDialog == null) {
                        toggleStyle()
                    }
                    return true
                }
                return false
            }
        })
        root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        setContentView(root)
    }

    private fun buildWeatherPanel() {
        weatherPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0f
            visibility = View.INVISIBLE
        }
        weatherCity = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0.85f
        }
        weatherPanel.addView(weatherCity)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 左列：动态图标 + 图标下方的汉字天气说明
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        weatherView = WeatherView(this)
        col.addView(weatherView, LinearLayout.LayoutParams(dp(124f), dp(124f)))
        weatherDesc = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        col.addView(weatherDesc)
        row.addView(col)

        // 右侧：当前温度（大数字）+ 今日温度区间（小字，正下方）
        tempBox = FrameLayout(this)
        val box = tempBox!!
        val tempCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        weatherTemp = TextView(this).apply { textSize = 64f }
        tempCol.addView(weatherTemp)
        weatherRange = TextView(this).apply {
            textSize = 18f
            alpha = 0.9f
            gravity = Gravity.CENTER
            setPadding(0, dp(3f), 0, 0)
        }
        tempCol.addView(weatherRange)
        box.addView(
            tempCol,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        val tempBoxLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(124f))
        tempBoxLp.marginStart = dp(18f)
        row.addView(box, tempBoxLp)

        weatherPanel.addView(row)

        // 附加信息行：空气质量 + 生活指数（拿不到时整行隐藏）
        weatherExtra = TextView(this).apply {
            textSize = 13f
            alpha = 0.85f
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, 0)
            visibility = View.GONE
        }
        weatherPanel.addView(weatherExtra)

        val wlp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        wlp.gravity = Gravity.CENTER
        root.addView(weatherPanel, wlp)
        applyWeatherSizes()
        // 不同手机屏幕高度不同：放不下就自动缩到刚好放得下（避免底部文字被裁）
        weatherPanel.post { fitWeatherPanelToScreen() }
    }

    /** 把所有天气元素的字号/尺寸/间距统一乘 weatherScale（图标内部按 View 尺寸相对绘制，自动跟随）。 */
    private fun applyWeatherSizes() {
        val s = weatherScale
        weatherCity.textSize = 14f * s
        weatherDesc.textSize = 16f * s
        weatherTemp.textSize = 64f * s
        weatherRange.textSize = 18f * s
        weatherRange.setPadding(0, dp(3f * s), 0, 0)
        weatherExtra.textSize = 13f * s
        weatherExtra.setPadding(0, dp(6f * s), 0, 0)
        (weatherView.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = dp(124f * s)
            height = dp(124f * s)
            weatherView.layoutParams = this
        }
        (tempBox?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            height = dp(124f * s)
            marginStart = dp(18f * s)
            tempBox?.layoutParams = this
        }
    }

    /** 面板高度超出屏幕可用高度时自动等比缩小；内容变少时也能回到目标倍数。最多校正 3 轮。 */
    private fun fitWeatherPanelToScreen(pass: Int = 0) {
        val avail = (root.height * 0.94f).toInt()
        val h = weatherPanel.height
        if (h <= 0 || avail <= 0) return
        // 面板高度与 weatherScale 成正比：先换算"目标倍数下会有多高"
        val needed = h.toFloat() * (baseWeatherScale / weatherScale)
        val next = if (needed <= avail) baseWeatherScale else baseWeatherScale * avail / needed
        if (kotlin.math.abs(next - weatherScale) > 0.01f) {
            weatherScale = next.coerceAtLeast(0.8f)
            applyWeatherSizes()
            if (pass < 3) weatherPanel.post { fitWeatherPanelToScreen(pass + 1) }
        }
    }

    // ---------- 信息栏 ----------

    private fun toggleInfo() {
        if (infoShown) hideInfo() else showInfo()
    }

    private fun toggleStyle() {
        val next = if (clock.styleIndex == ClockView.STYLE_TILTED) ClockView.STYLE_UPRIGHT else ClockView.STYLE_TILTED
        clock.setCurrentStyle(next)
        prefs.styleIndex = next
    }

    private fun showInfo() {
        infoShown = true
        refreshInfoContent()
        infoBar.visibility = View.VISIBLE
        infoBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(550L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, 4000L)
    }

    private fun hideInfo() {
        if (!infoShown) return
        infoShown = false
        handler.removeCallbacks(hideInfoRunnable)
        infoBar.animate()
            .alpha(0f)
            .translationY(-dp(48f).toFloat())
            .setDuration(420L)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction { infoBar.visibility = View.INVISIBLE }
            .start()
    }

    // ---------- 天气板块 ----------

    private fun toggleWeather() {
        if (weatherShown) hideWeather() else showWeather()
    }

    private fun showWeather() {
        hideInfo()
        weatherShown = true
        weatherPanel.visibility = View.VISIBLE
        weatherPanel.animate()
            .alpha(1f)
            .setDuration(320L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
        // 表盘淡一点，让天气读得清楚
        clock.animate().alpha(0.28f).setDuration(320L).start()
        refreshWeatherUi()
        fetchWeather()   // 每次呼出面板都更新一次天气
        scheduleAutoHide()
    }

    private fun hideWeather() {
        weatherShown = false
        cancelAutoHide()
        weatherPanel.animate()
            .alpha(0f)
            .setDuration(260L)
            .withEndAction { weatherPanel.visibility = View.INVISIBLE }
            .start()
        clock.animate().alpha(1f).setDuration(260L).start()
    }

    /** 面板可见时 autoHideMs 后自动回退；每次呼出/点按/刷新落地都重新计时。 */
    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideWeather)
        if (weatherShown) {
            handler.postDelayed(autoHideWeather, autoHideMs)
        }
    }

    private fun cancelAutoHide() {
        handler.removeCallbacks(autoHideWeather)
    }

    /** 拉取一次天气（呼出面板/点按屏幕时调用）；先把结果写进画面对应字段，再后台取数。 */
    private fun fetchWeather() {
        if (demoWeatherCode != null) return
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        if (weatherLoading) return
        weatherLoading = true
        if (weather == null) weatherDesc.text = "正在获取天气…"
        Thread {
            val t0 = System.currentTimeMillis()
            Log.i("StandbyClock", "weather fetch: start")
            val loc = bestLocation()
            Log.i(
                "StandbyClock",
                "weather fetch: location=" + (loc?.let { "${it.latitude},${it.longitude}" } ?: "null") +
                    " ageMs=" + (loc?.let { System.currentTimeMillis() - it.time }) +
                    " tookMs=" + (System.currentTimeMillis() - t0)
            )
            val info = if (loc != null) WeatherService.fetch(loc.latitude, loc.longitude, debugSource, weather) else null
            Log.i(
                "StandbyClock",
                "weather fetch: result=" + (info?.let { "t=${it.tempC},min=${it.tempMin},max=${it.tempMax},code=${it.code},city=${it.city}" } ?: "null") +
                    " tookMs=" + (System.currentTimeMillis() - t0)
            )
            handler.post {
                weatherLoading = false
                if (info != null) {
                    weather = info
                    prefs.weatherCache = info.toJson()
                    refreshWeatherUi()
                    // 刷新落地后重新计时，确保用户看到的是新数据（面板仍显示时才有意义）
                    scheduleAutoHide()
                } else if (weather == null) {
                    weatherDesc.text = when {
                        !locationServiceEnabled() -> "请打开手机定位"
                        loc == null -> "定位失败，点按重试"
                        else -> "天气获取失败，点按重试"
                    }
                } else {
                    // 有旧数据：短暂提示"刷新失败"再恢复原说明，避免"点了没反应"
                    val keep = weather?.desc ?: ""
                    weatherDesc.text = "刷新失败"
                    handler.postDelayed({
                        if (weatherDesc.text.toString() == "刷新失败") weatherDesc.text = keep
                    }, 2500L)
                }
            }
        }.start()
    }

    /** 系统定位总开关/是否还有可用定位源。关了就没必要重试，直接引导用户去系统设置。 */
    private fun locationServiceEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /** 先取缓存定位，超过 30 分钟就请求一次实时定位（最多等 6 秒）。 */
    private fun bestLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            } catch (_: Exception) {
            }
        }
        if (best != null && System.currentTimeMillis() - best.time < 30 * 60_000L) return best

        // 定位开关被关掉（所有 provider 不可用）→ 直接失败，不白等
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        } ?: return best

        val latch = CountDownLatch(1)
        val fresh = arrayOfNulls<Location>(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                fresh[0] = location
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {
            }

            override fun onProviderDisabled(provider: String) {
            }
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        return fresh[0] ?: best
    }

    private fun refreshWeatherUi() {
        val w = weather
        if (w == null) {
            if (weatherDesc.text.isNullOrBlank()) weatherDesc.text = "天气加载中…"
            return
        }
        weatherView.kind = w.kind
        weatherTemp.text = "${w.tempC}°"
        val min = w.tempMin
        val max = w.tempMax
        if (min != null && max != null) {
            weatherRange.text = "${min}°~${max}°"
            weatherRange.visibility = View.VISIBLE
        } else {
            weatherRange.text = ""
            weatherRange.visibility = View.GONE
        }
        weatherDesc.text = w.desc
        val x = w.extra
        weatherExtra.text = x ?: ""
        weatherExtra.visibility = if (x.isNullOrBlank()) View.GONE else View.VISIBLE
        updateCityText()
        // 附加行出现/消失会改变面板高度 → 重新做一次"放得下"校正
        weatherPanel.post { fitWeatherPanelToScreen() }
        applyWeatherColors(clock.lightColor, clock.darkColor)
    }

    /** 地区名 + 当天日期（如"南岸区 · 10月16日"）；没有地区名时只显示日期。 */
    private fun updateCityText() {
        val dateStr = dayFmt.format(Calendar.getInstance().time)
        val city = weather?.city.orEmpty()
        weatherCity.text = if (city.isBlank()) dateStr else "$city · $dateStr"
        weatherCity.visibility = View.VISIBLE
    }

    private fun applyWeatherColors(light: Int, dark: Int) {
        weatherView.setColors(light, dark)
        weatherTemp.setTextColor(light)
        weatherRange.setTextColor(light)
        weatherDesc.setTextColor(light)
        weatherCity.setTextColor(light)
        weatherExtra.setTextColor(light)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchWeather()
            } else {
                weatherDesc.text = "需要定位权限才能显示天气"
            }
        }
    }

    // ---------- 配色 ----------

    private fun showColorDialog() {
        hideInfo()
        // 避免配色面板与天气面板叠加显示
        if (weatherShown) hideWeather()
        clock.animate()
            .scaleX(0.82f)
            .scaleY(0.82f)
            .translationY(-root.height * 0.05f)
            .setDuration(340L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = ColorSheet(this)
        sheet.listener = object : ColorSheet.Listener {
            override fun onColorSelected(index: Int) {
                prefs.paletteIndex = index
                sheet.setSelection(index)
                val pal = ColorSheet.PALETTES[index]
                clock.animateColors(pal.first, pal.second)
                updateInfoColors(pal.first)
                applyWeatherColors(pal.first, pal.second)
            }

            override fun onClose() {
                d.dismiss()
            }
        }
        sheet.setSelection(prefs.paletteIndex)
        d.setContentView(sheet)

        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0))
            val lp = w.attributes
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            w.attributes = lp
            w.setDimAmount(0.12f)
            if (Build.VERSION.SDK_INT >= 31) {
                w.setBackgroundBlurRadius(dp(36f))
            }
            w.setWindowAnimations(0)
        }
        d.setOnDismissListener {
            colorDialog = null
            clock.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(340L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
        colorDialog = d
        d.show()
        // 从底部滑入
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate()
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    // ---------- 时间 / 电量 ----------

    private fun refresh(animate: Boolean) {
        val now = Calendar.getInstance()
        clock.update(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), animate)
        refreshInfoContent()
        if (weatherShown) updateCityText()   // 面板开着时，跨零点自动更新日期
    }

    private fun refreshInfoContent() {
        dateText.text = dateFmt.format(Calendar.getInstance().time)
        updateBatteryText()
        updateInfoColors(clock.lightColor)
        val src = weather?.source.orEmpty()
        sourceText.text = if (src.isBlank()) "" else "天气数据：$src"
    }

    private fun updateInfoColors(color: Int) {
        dateText.setTextColor(color)
        batteryText.setTextColor(color)
        sourceText.setTextColor(color)
    }

    private fun updateBatteryText() {
        if (level < 0) return
        batteryText.text = "$level%"
        bolt.visibility = if (charging) View.VISIBLE else View.INVISIBLE
    }

    /** 对齐到下一个整分钟(+40ms)刷新。 */
    private fun schedule() {
        handler.removeCallbacks(tick)
        val delay = (60000 - System.currentTimeMillis() % 60000) + 40
        handler.postDelayed(tick, delay)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val dialog = colorDialog
        if (dialog != null) {
            dialog.dismiss()
            return
        }
        if (weatherShown) {
            hideWeather()
            return
        }
        if (infoShown) {
            hideInfo()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        refresh(true)
        schedule()
        enterImmersive()
        if (weatherShown) {
            fetchWeather()   // 回到前台（如刚从系统设置页返回）若面板还开着就补一次
            scheduleAutoHide()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
        handler.removeCallbacks(tick)
        cancelAutoHide()   // 切后台/锁屏后不再计时（省电）
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    /** 全屏沉浸式 + 刘海区域铺满。 */
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 5894
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }
}
