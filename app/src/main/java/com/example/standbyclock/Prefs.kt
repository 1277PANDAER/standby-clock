package com.example.standbyclock

import android.content.Context
import android.content.SharedPreferences

/** 简单的偏好存储：配色索引 + 表盘样式索引。 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("clock_prefs", Context.MODE_PRIVATE)

    var paletteIndex: Int
        get() = sp.getInt("palette", 0)   // 默认 0 = 蓝色主题（PALETTES[0]）
        set(v) {
            sp.edit().putInt("palette", v).commit()
        }

    var styleIndex: Int
        get() = sp.getInt("style", 0)
        set(v) {
            sp.edit().putInt("style", v).commit()
        }

    /** 上次天气结果的 JSON 缓存（离线也能显示）。 */
    var weatherCache: String?
        get() = sp.getString("weather", null)
        set(v) {
            sp.edit().putString("weather", v).commit()
        }
}
