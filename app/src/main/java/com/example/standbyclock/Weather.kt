package com.example.standbyclock

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** 天气动画类型（决定画哪种小动画）。 */
enum class WeatherKind { SUN, MOON, PARTLY, PARTLY_NIGHT, CLOUDY, FOG, RAIN, SNOW, SLEET, THUNDER }

/** 一次天气结果。tempMin/tempMax = 今日最低/最高（拿不到时为 null，界面隐藏这一行）；
 *  extra = 附加信息行（空气质量 + 生活指数，如"空气 良 57 · 穿衣 舒适"），拿不到时为 null。 */
data class WeatherInfo(
    val tempC: Int,
    val tempMin: Int?,
    val tempMax: Int?,
    val code: Int,
    val desc: String,
    val city: String,
    val kind: WeatherKind,
    val isDay: Boolean,
    val ts: Long,
    val extra: String? = null,
    /** 数据来源名称（用于界面署名，如"Open-Meteo"/"和风天气"/"wttr.in"）。 */
    val source: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("t", tempC)
        .put("min", tempMin ?: -999)
        .put("max", tempMax ?: -999)
        .put("c", code)
        .put("d", desc)
        .put("city", city)
        .put("day", if (isDay) 1 else 0)
        .put("k", kind.name)
        .put("x", extra ?: "")
        .put("src", source)
        .put("ts", ts)
        .toString()

    companion object {
        fun fromJson(s: String?): WeatherInfo? = try {
            if (s.isNullOrBlank()) {
                null
            } else {
                val o = JSONObject(s)
                val code = o.optInt("c", -1)
                val isDay = o.optInt("day", 1) == 1
                val (fallbackKind, fallbackDesc) = WeatherService.codeToWeather(code, isDay)
                WeatherInfo(
                    tempC = o.optInt("t"),
                    tempMin = o.optInt("min", -999).takeIf { it != -999 },
                    tempMax = o.optInt("max", -999).takeIf { it != -999 },
                    code = code,
                    desc = o.optString("d").ifBlank { fallbackDesc },
                    city = o.optString("city"),
                    kind = runCatching { WeatherKind.valueOf(o.optString("k")) }.getOrNull()
                        ?: fallbackKind,
                    isDay = isDay,
                    ts = o.optLong("ts"),
                    extra = o.optString("x").ifBlank { null },
                    source = o.optString("src")
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 天气数据源（按优先级）：
 * 1) 和风天气（商业源，国内 CDN，中文现象 + 空气质量 + 生活指数；需要 API Host + KEY）
 * 2) Open-Meteo（免密钥，双模型：best_match + 中国气象局 GRAPES）
 * 3) wttr.in（免密钥，WWO 数据）
 * 中文地区名统一用 api-bdc.io 逆地理。全部是阻塞式 IO，必须在后台线程调用。
 */
object WeatherService {

    // ---------- 和风天气配置 ----------
    // 不写在源码里：由 local.properties 的 qw.host / qw.key 注入 BuildConfig（该文件不进仓库）。
    // 没配置时 QW_ENABLED = false，自动只用免费源 —— 这是开源版的默认行为。
    private val QW_HOST: String = BuildConfig.QW_HOST
    private val QW_KEY: String = BuildConfig.QW_KEY
    val QW_ENABLED: Boolean
        get() = QW_HOST.isNotBlank() && QW_KEY.isNotBlank()
    /** 附加行里显示哪一项生活指数（和风的名称，如"穿衣""紫外线""洗车"）。 */
    private const val QW_INDEX_NAME = "穿衣"
    /** 空气质量/生活指数缓存时长（变化慢，不必每次呼出都请求）。 */
    private const val EXTRA_TTL_MS = 30 * 60_000L

    /** 简单 GET（可带自定义头；自动解 gzip），返回响应文本。阻塞调用，必须在后台线程用。 */
    private fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 5000,
        readMs: Int = 6000
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("User-Agent", "StandbyClock/1.9")
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val gz = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
            val stream = if (gz) java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 和风天气 ----------

    /** 和风天气码 → 动画类型（100 晴 / 101-103 多云 / 104 阴 / 3xx 雨 / 4xx 雪 / 5xx 雾霾）。 */
    fun qweatherCodeToKind(code: Int, isDay: Boolean): WeatherKind = when {
        code == 100 || code == 150 -> if (isDay) WeatherKind.SUN else WeatherKind.MOON
        code in 101..103 || code in 151..153 ->
            if (isDay && code < 150) WeatherKind.PARTLY else WeatherKind.PARTLY_NIGHT
        code == 104 -> WeatherKind.CLOUDY
        code in 302..304 -> WeatherKind.THUNDER
        code in 300..399 -> WeatherKind.RAIN
        code in 400..499 -> when (code) {
            404, 405, 406, 456 -> WeatherKind.SLEET
            else -> WeatherKind.SNOW
        }
        code in 500..515 -> WeatherKind.FOG
        else -> WeatherKind.CLOUDY
    }

    /** 用今天的日出/日落判断昼夜（和风 current 不带 is_day）。入参形如 2026-10-17T06:55+08:00。 */
    private fun isDayNow(sunrise: String?, sunset: String?): Boolean {
        fun hm(s: String?): Int? {
            if (s == null || s.length < 16) return null
            val h = s.substring(11, 13).toIntOrNull() ?: return null
            val m = s.substring(14, 16).toIntOrNull() ?: return null
            return h * 60 + m
        }
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val rise = hm(sunrise) ?: return now in 6 * 60 until 19 * 60
        val set = hm(sunset) ?: return now in 6 * 60 until 19 * 60
        return now in rise until set
    }

    /** 主源：和风天气。cached 用于复用 30 分钟内的空气质量/生活指数，省请求次数。 */
    private fun fetchQWeather(lat: Double, lon: Double, city: String, cached: WeatherInfo?): WeatherInfo? = try {
        val latS = String.format(Locale.US, "%.2f", lat)
        val lonS = String.format(Locale.US, "%.2f", lon)
        fun qw(path: String): String =
            httpGet("https://$QW_HOST$path", mapOf("X-QW-Api-Key" to QW_KEY))
        fun qwJson(path: String): JSONObject = JSONObject(qw(path))

        val cur = qwJson("/weather/v1/current/$latS/$lonS?localTime=true")
        val dayRoot = qwJson("/weather/v1/daily/$latS/$lonS?days=1&localTime=true")
        val day = dayRoot.getJSONArray("days").getJSONObject(0)

        val temp = cur.getJSONObject("temperature").getDouble("value")
        val cond = cur.getJSONObject("condition")
        val desc = cond.optString("text", "")
        val qcode = cond.optString("code", "").toIntOrNull() ?: -1

        val astro = day.optJSONObject("astro")
        val isDay = isDayNow(astro?.optString("sunrise"), astro?.optString("sunset"))

        val tMax = day.optJSONObject("temperatureMax")?.optDouble("value", Double.NaN)
            ?.takeIf { !it.isNaN() }?.roundToInt()
        val tMin = day.optJSONObject("temperatureMin")?.optDouble("value", Double.NaN)
            ?.takeIf { !it.isNaN() }?.roundToInt()

        // 空气质量 + 生活指数：30 分钟内的缓存直接复用，省两次请求
        fun buildExtra(): String? {
            var aqiPart: String? = null
            var idxPart: String? = null
            runCatching {
                val idxs = JSONObject(qw("/airquality/v1/current/$latS/$lonS")).optJSONArray("indexes")
                    ?: return@runCatching
                var pick: JSONObject? = null
                for (i in 0 until idxs.length()) {
                    val o = idxs.getJSONObject(i)
                    when (o.optString("code")) {
                        "cn-mee" -> { pick = o; break }                     // 中国标准优先
                        "qaqi", "us-epa" -> if (pick == null) pick = o
                    }
                }
                pick?.let { aqiPart = "空气 ${it.optString("category")} ${it.optInt("aqi")}" }
            }
            runCatching {
                val arr = JSONObject(qw("/v7/indices/1d?type=0&location=$lonS,$latS"))
                    .optJSONArray("daily") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("name").startsWith(QW_INDEX_NAME)) {
                        idxPart = "${o.optString("name").removeSuffix("指数")} ${o.optString("category")}"
                        break
                    }
                }
            }
            val parts = listOfNotNull(aqiPart, idxPart)
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

        val extra: String? = cached
            ?.takeIf { !it.extra.isNullOrBlank() && System.currentTimeMillis() - it.ts < EXTRA_TTL_MS }
            ?.extra
            ?: buildExtra()

        android.util.Log.i(
            "StandbyClock",
            "qweather: temp=$temp range=$tMin~$tMax code=$qcode($desc) isDay=$isDay extra=$extra"
        )
        WeatherInfo(
            tempC = temp.roundToInt(),
            tempMin = tMin,
            tempMax = tMax,
            code = qcode,
            desc = desc,
            city = city,
            kind = qweatherCodeToKind(qcode, isDay),
            isDay = isDay,
            ts = System.currentTimeMillis(),
            extra = extra,
            source = "和风天气"
        )
    } catch (e: Exception) {
        android.util.Log.w("StandbyClock", "qweather fetch failed", e)
        null
    }

    /** WMO 天气码 → (动画类型, 中文说明)；isDay=false 时晴天/多云换成月亮版图标。 */
    fun codeToWeather(code: Int, isDay: Boolean = true): Pair<WeatherKind, String> = when (code) {
        0 -> if (isDay) WeatherKind.SUN to "晴" else WeatherKind.MOON to "晴"
        1 -> if (isDay) WeatherKind.PARTLY to "晴间多云" else WeatherKind.PARTLY_NIGHT to "多云"
        2 -> if (isDay) WeatherKind.PARTLY to "多云" else WeatherKind.PARTLY_NIGHT to "多云"
        3 -> WeatherKind.CLOUDY to "阴"
        45, 48 -> WeatherKind.FOG to "雾"
        51, 53, 55, 56, 57 -> WeatherKind.RAIN to "毛毛雨"
        61 -> WeatherKind.RAIN to "小雨"
        63 -> WeatherKind.RAIN to "中雨"
        65 -> WeatherKind.RAIN to "大雨"
        66, 67 -> WeatherKind.RAIN to "冻雨"
        71 -> WeatherKind.SNOW to "小雪"
        73 -> WeatherKind.SNOW to "中雪"
        75 -> WeatherKind.SNOW to "大雪"
        77 -> WeatherKind.SNOW to "雪粒"
        80 -> WeatherKind.RAIN to "小阵雨"
        81 -> WeatherKind.RAIN to "阵雨"
        82 -> WeatherKind.RAIN to "强阵雨"
        85, 86 -> WeatherKind.SLEET to "雨夹雪"
        95 -> WeatherKind.THUNDER to "雷阵雨"
        96, 99 -> WeatherKind.THUNDER to "雷阵雨伴冰雹"
        else -> WeatherKind.CLOUDY to "未知"
    }

    /**
     * 取天气：主源和风天气 → Open-Meteo（双模型） → wttr.in，逐级兜底。
     * forceSource 传 "qweather"/"openmeteo"/"wttr" 可强制指定来源（调试用）。
     * cached 传入上次结果，用于复用 30 分钟内的空气质量/生活指数。
     */
    fun fetch(
        lat: Double,
        lon: Double,
        forceSource: String? = null,
        cached: WeatherInfo? = null
    ): WeatherInfo? {
        val city = reverseGeocode(lat, lon)
        if (QW_ENABLED && (forceSource == null || forceSource == "qweather")) {
            val info = fetchQWeather(lat, lon, city, cached)
            if (info != null) return info
            android.util.Log.i("StandbyClock", "qweather unavailable, fallback to open-meteo")
        }
        if (forceSource == null || forceSource == "openmeteo") {
            val info = fetchOpenMeteo(lat, lon, city)
            if (info != null) return info
            if (forceSource == null) android.util.Log.i("StandbyClock", "open-meteo unavailable, fallback to wttr.in")
        }
        if (forceSource == null || forceSource == "wttr") {
            return fetchWttr(lat, lon, city)
        }
        return null
    }

    /** 主源：Open-Meteo。
     *  - 当前温度取「中国气象局 GRAPES」模型（与国内手机自带天气口径最接近）
     *  - 当前现象/昼夜取「全球集合 best_match」（对毛毛雨/雾这类码更贴近官方观测）
     *  - 今日最低/最高取两模型并集
     */
    private fun fetchOpenMeteo(lat: Double, lon: Double, city: String): WeatherInfo? = try {
        val base = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&forecast_days=1&timezone=auto",
            lat, lon
        )
        val root = JSONObject(
            httpGet(
                "$base&current=temperature_2m,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min&models=best_match,cma_grapes_global"
            )
        )
        val cur = root.getJSONObject("current")
        val bestTemp = cur.getDouble("temperature_2m")
        var temp = bestTemp
        val code = cur.getInt("weather_code")
        val codeIsDay = if (cur.has("is_day")) cur.getInt("is_day") == 1 else {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            h in 6 until 19
        }
        // 当前温度：再取一次中国气象局 GRAPES 模型，两模型取平均（单一模型常有 ±1~2° 偏差）
        var cmaTemp: Double? = null
        val cmaRoot = runCatching {
            JSONObject(httpGet("$base&current=temperature_2m&models=cma_grapes_global"))
        }.getOrNull()
        if (cmaRoot != null) {
            cmaRoot.optJSONObject("current")?.optDouble("temperature_2m", Double.NaN)
                ?.takeIf { !it.isNaN() }?.let {
                    cmaTemp = it
                    temp = (bestTemp + it) / 2.0
                }
        }
        val isDay = codeIsDay
        val (kind, desc) = codeToWeather(code, isDay)
        val daily = root.optJSONObject("daily")
        // 今日最低/最高：取 best_match 与 CMA GRAPES 两模型的并集（单一模型对最低温常偏高/偏低 1~2°）
        fun firstOf(name: String): Double? =
            daily?.optJSONArray(name)?.optDouble(0, Double.NaN)?.takeIf { !it.isNaN() }
        val mins = listOfNotNull(
            firstOf("temperature_2m_min_best_match"),
            firstOf("temperature_2m_min_cma_grapes_global"),
            firstOf("temperature_2m_min")
        )
        val maxs = listOfNotNull(
            firstOf("temperature_2m_max_best_match"),
            firstOf("temperature_2m_max_cma_grapes_global"),
            firstOf("temperature_2m_max")
        )
        val tMin = if (mins.isEmpty()) null else mins.min().roundToInt()
        val tMax = if (maxs.isEmpty()) null else maxs.max().roundToInt()
        android.util.Log.i(
            "StandbyClock",
            "open-meteo: temp=$temp (best=$bestTemp, cma=$cmaTemp) code=$code isDay=$isDay " +
                "range=$tMin~$tMax [best=${firstOf("temperature_2m_min_best_match")}~${firstOf("temperature_2m_max_best_match")}, " +
                "cma=${firstOf("temperature_2m_min_cma_grapes_global")}~${firstOf("temperature_2m_max_cma_grapes_global")}]"
        )
        WeatherInfo(
            tempC = temp.roundToInt(),
            tempMin = tMin,
            tempMax = tMax,
            code = code,
            desc = desc,
            city = city,
            kind = kind,
            isDay = isDay,
            ts = System.currentTimeMillis(),
            source = "Open-Meteo"
        )
    } catch (e: Exception) {
        android.util.Log.w("StandbyClock", "open-meteo fetch failed", e)
        null
    }

    /** 备源：wttr.in（WWO 天气码，需要做一次映射）。 */
    private fun fetchWttr(lat: Double, lon: Double, city: String): WeatherInfo? = try {
        val url = URL(String.format(Locale.US, "https://wttr.in/%.4f,%.4f?format=j1", lat, lon))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 6000
            setRequestProperty("User-Agent", "StandbyClock/1.1")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val root = JSONObject(text)
        val cur = root.getJSONArray("current_condition").getJSONObject(0)
        val temp = cur.getString("temp_C").toDouble()
        val wcode = cur.getString("weatherCode").toInt()
        val dayFlag = cur.optString("isday", "")
        val today = root.optJSONArray("weather")?.optJSONObject(0)
        val tMax = today?.optString("maxtempC")?.toDoubleOrNull()?.roundToInt()
        val tMin = today?.optString("mintempC")?.toDoubleOrNull()?.roundToInt()
        val descEn = cur.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value") ?: ""
        val isDay = wttrIsDay(cur, today, dayFlag)
        val (kind, desc) = wttrToWeather(wcode, descEn, isDay)
        WeatherInfo(
            tempC = temp.roundToInt(),
            tempMin = tMin,
            tempMax = tMax,
            code = wcode,
            desc = desc,
            city = city,
            kind = kind,
            isDay = isDay,
            ts = System.currentTimeMillis(),
            source = "wttr.in"
        )
    } catch (e: Exception) {
        android.util.Log.w("StandbyClock", "wttr fetch failed", e)
        null
    }

    /**
     * 昼夜判断：有 isday 字段直接用；否则用「今天的日出/日落 + 设备本地时间」
     * （正常情况手机时区=所在地时区）；再不行退回 6:00-19:00 的粗略判断。
     */
    private fun wttrIsDay(cur: JSONObject, today: JSONObject?, flag: String): Boolean {
        if (flag.isNotBlank()) return flag == "1"
        val astro = today?.optJSONArray("astronomy")?.optJSONObject(0)
        val rise = parseHhmm(astro?.optString("sunrise", "") ?: "")
        val set = parseHhmm(astro?.optString("sunset", "") ?: "")
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (rise != null && set != null) {
            return if (set >= rise) nowMin in rise until set else (nowMin >= rise || nowMin < set)
        }
        val h = now.get(Calendar.HOUR_OF_DAY)
        return h in 6 until 19
    }

    /** "06:56 AM" → 当天分钟数；解析失败返回 null。 */
    private fun parseHhmm(s: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?$").find(s.trim()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        when (m.groupValues[3].uppercase(Locale.US)) {
            "PM" -> if (h != 12) h += 12
            "AM" -> if (h == 12) h = 0
        }
        return (h % 24) * 60 + min
    }

    /**
     * wttr.in 天气 → 动画类型 + 中文说明。
     * 优先按英文描述关键词判断（wttr 偶尔返回不在标准表里的码），再退回 WWO 码表。
     */
    private fun wttrToWeather(code: Int, descEn: String, isDay: Boolean): Pair<WeatherKind, String> {
        val t = descEn.lowercase()
        return when {
            t.contains("thunder") || t.contains("lightning") -> WeatherKind.THUNDER to "雷阵雨"
            t.contains("snow") || t.contains("blizzard") || t.contains("ice pellets") -> WeatherKind.SNOW to "雪"
            t.contains("sleet") || t.contains("freezing") -> WeatherKind.SLEET to "雨夹雪"
            t.contains("drizzle") -> WeatherKind.RAIN to "毛毛雨"
            t.contains("rain") || t.contains("shower") -> WeatherKind.RAIN to "雨"
            t.contains("haze") || t.contains("smoke") || t.contains("dust") -> WeatherKind.FOG to "霾"
            t.contains("mist") || t.contains("fog") -> WeatherKind.FOG to "雾"
            t.contains("overcast") -> WeatherKind.CLOUDY to "阴"
            t.contains("cloud") -> if (isDay) WeatherKind.PARTLY to "多云" else WeatherKind.PARTLY_NIGHT to "多云"
            t.contains("sunny") || t.contains("clear") -> if (isDay) WeatherKind.SUN to "晴" else WeatherKind.MOON to "晴"
            else -> when (code) {
                113 -> if (isDay) WeatherKind.SUN to "晴" else WeatherKind.MOON to "晴"
                116, 119 -> if (isDay) WeatherKind.PARTLY to "多云" else WeatherKind.PARTLY_NIGHT to "多云"
                122 -> WeatherKind.CLOUDY to "阴"
                143, 248, 260 -> WeatherKind.FOG to "雾"
                176, 263, 266, 281, 284, 293, 296, 299, 353 -> WeatherKind.RAIN to "小雨"
                302, 305, 308, 311, 314, 356, 359 -> WeatherKind.RAIN to "中雨"
                179, 182, 185, 227, 230, 320, 323, 326, 329, 332, 335, 338 -> WeatherKind.SNOW to "雪"
                317, 350, 362, 365, 368, 371, 374, 377 -> WeatherKind.SLEET to "雨夹雪"
                200, 386, 389, 392, 395 -> WeatherKind.THUNDER to "雷阵雨"
                else -> WeatherKind.CLOUDY to "多云"
            }
        }
    }

    /** 逆地理编码：返回定位所在地的中文地区名；失败返回空串（界面会隐藏这一行）。 */
    private fun reverseGeocode(lat: Double, lon: Double): String = try {
        val url = URL(
            String.format(
                Locale.US,
                "https://api-bdc.io/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=zh",
                lat, lon
            )
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "StandbyClock/1.1")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val o = JSONObject(text)
        o.optString("city").trim()
            .ifBlank { o.optString("locality").trim() }
            .ifBlank { o.optString("principalSubdivision").trim() }
    } catch (e: Exception) {
        android.util.Log.w("StandbyClock", "reverse geocode failed", e)
        ""
    }
}
