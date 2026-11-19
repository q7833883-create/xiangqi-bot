package com.xiangqi.autoassist

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 棋盘几何：x0,y0 = 左上角交叉点；x1,y1 = 右下角交叉点（屏幕像素坐标） */
data class Grid(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val cellW: Float get() = (x1 - x0) / 8f
    val cellH: Float get() = (y1 - y0) / 9f

    fun center(r: Int, c: Int): Pair<Int, Int> =
        Pair((x0 + c * cellW).roundToInt(), (y0 + r * cellH).roundToInt())

    /** 棋盘识别区域（含边距），对应 JS 的 boardRegion */
    fun region(): IntArray {
        val m = max(cellW, cellH) * 0.62f
        val x = max(0f, x0 - m).roundToInt()
        val y = max(0f, y0 - m).roundToInt()
        val w = ((x1 - x0) + 2 * m).roundToInt()
        val h = ((y1 - y0) + 2 * m).roundToInt()
        return intArrayOf(x, y, w, h)
    }
}

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("xiangqi-bot", Context.MODE_PRIVATE)

    var grid: Grid?
        get() {
            if (!sp.contains("gx0")) return null
            return Grid(
                sp.getFloat("gx0", 0f), sp.getFloat("gy0", 0f),
                sp.getFloat("gx1", 0f), sp.getFloat("gy1", 0f)
            )
        }
        set(v) {
            if (v == null) sp.edit().remove("gx0").remove("gy0").remove("gx1").remove("gy1").apply()
            else sp.edit().putFloat("gx0", v.x0).putFloat("gy0", v.y0)
                .putFloat("gx1", v.x1).putFloat("gy1", v.y1).apply()
        }

    /** 底部（玩家侧）是否为红方 */
    var bottomIsRed: Boolean
        get() = sp.getBoolean("bottomIsRed", true)
        set(v) = sp.edit().putBoolean("bottomIsRed", v).apply()

    /** 引擎模式：auto | pikafish | js */
    var engineMode: String
        get() = sp.getString("engineMode", "auto") ?: "auto"
        set(v) = sp.edit().putString("engineMode", v).apply()

    /** 引擎每步思考毫秒 */
    var movetimeMs: Int
        get() = sp.getInt("movetimeMs", 3000)
        set(v) = sp.edit().putInt("movetimeMs", v).apply()

    /** 轮询识别间隔毫秒 */
    var pollMs: Int
        get() = sp.getInt("pollMs", 700)
        set(v) = sp.edit().putInt("pollMs", v).apply()

    /** 模板匹配阈值 */
    var matchThreshold: Float
        get() = sp.getFloat("matchThreshold", 0.72f)
        set(v) = sp.edit().putFloat("matchThreshold", v).apply()

    /** 落子方式：tap | drag */
    var moveMode: String
        get() = sp.getString("moveMode", "tap") ?: "tap"
        set(v) = sp.edit().putString("moveMode", v).apply()

    /** 拟人化点击延迟下限/上限毫秒 */
    var clickDelayMin: Int
        get() = sp.getInt("clickDelayMin", 350)
        set(v) = sp.edit().putInt("clickDelayMin", v).apply()

    var clickDelayMax: Int
        get() = sp.getInt("clickDelayMax", 800)
        set(v) = sp.edit().putInt("clickDelayMax", v).apply()

    companion object {
        fun gridOf(x0: Float, y0: Float, x1: Float, y1: Float): Grid {
            return Grid(
                min(x0, x1), min(y0, y1),
                max(x0, x1), max(y0, y1)
            )
        }
    }
}
