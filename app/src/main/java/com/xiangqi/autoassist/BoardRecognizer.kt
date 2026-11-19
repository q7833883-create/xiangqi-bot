package com.xiangqi.autoassist

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 单枚棋子模板：灰度像素 + 预计算的均值/标准差（用于 Pearson 相关匹配） */
class Template(
    val code: String,
    val w: Int,
    val h: Int,
    val gray: FloatArray,
    val mean: Float,
    val std: Float
)

object Ncc {
    /**
     * 在整幅图中以 (cx,cy) 为中心、允许 maxOff 像素偏移，返回与模板的最佳 Pearson 相关系数。
     * px 为 ARGB_8888 的像素数组（取绿通道做灰度）。无足够方差时返回 0。
     */
    fun bestAt(
        px: IntArray, w: Int, h: Int,
        cx: Int, cy: Int, rad: Int,
        t: Template, maxOff: Int, step: Int
    ): Float {
        var best = 0f
        var oy = -maxOff
        while (oy <= maxOff) {
            var ox = -maxOff
            while (ox <= maxOff) {
                val s = corrAt(px, w, h, cx + ox, cy + oy, rad, t)
                if (s > best) best = s
                ox += step
            }
            oy += step
        }
        return best
    }

    /** 单点相关系数：窗口 [cx-rad, cx+rad) × [cy-rad, cy+rad)，尺寸须与模板一致 */
    fun corrAt(
        px: IntArray, w: Int, h: Int,
        cx: Int, cy: Int, rad: Int,
        t: Template
    ): Float {
        val tw = t.w
        val th = t.h
        val x0 = cx - rad
        val y0 = cy - rad
        if (x0 < 0 || y0 < 0 || x0 + tw > w || y0 + th > h) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var dot = 0.0
        var ty = 0
        var idx = y0 * w + x0
        while (ty < th) {
            var tx = 0
            var i = idx
            val grow = ty * tw
            while (tx < tw) {
                val p = px[i]
                // 绿通道灰度（0..255）
                val g = (p shr 8) and 0xFF
                val gv = g.toDouble()
                sum += gv
                sumSq += gv * gv
                dot += gv * t.gray[grow + tx]
                tx++
                i++
            }
            ty++
            idx += w
        }
        val n = (tw * th).toDouble()
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        if (variance < 1e-6) return 0f
        val std = sqrt(variance)
        // Pearson: (E[XY] - E[X]E[Y]) / (stdX * stdY)；模板的 mean/std 已预计算
        val cov = dot / n - mean * t.mean
        return (cov / (std * t.std)).toFloat()
    }
}

/** 棋盘识别：按校准网格在 90 个交叉点采样，与 14 种模板做相关匹配 */
object BoardRecognizer {

    /**
     * @param bmpFull 全屏截图（ARGB_8888）
     * @param g 校准网格
     * @param templates 模板列表（14 种：K A B N R C P + 小写）
     * @param threshold 匹配阈值（如 0.72）
     * @return board[10][9]，屏幕视角；无子为 null
     */
    fun recognize(
        bmpFull: Bitmap,
        g: Grid,
        templates: List<Template>,
        threshold: Float
    ): Array<Array<String?>> {
        val reg = g.region()
        // 半分辨率处理，加速
        val sx = max(1, reg[2] / 2)
        val sy = max(1, reg[3] / 2)
        val crop = safeCrop(bmpFull, reg[0], reg[1], reg[2], reg[3]) ?: return emptyBoard()
        val scaled = Bitmap.createScaledBitmap(crop, sx, sy, true)
        if (scaled != crop) crop.recycle()
        val bw = scaled.width
        val bh = scaled.height
        val px = IntArray(bw * bh)
        scaled.getPixels(px, 0, bw, 0, 0, bw, bh)
        scaled.recycle()

        val board = emptyBoard()
        val half = 0.5f // 半分辨率缩放
        val rad = max(4, (min(g.cellW, g.cellH) * 0.44f * half).roundToInt())
        val cxScale = 0.5f
        val cyScale = 0.5f
        val maxOff = max(2, rad / 4)
        val step = 2
        val best = HashMap<String, Float>(64)
        for (t in templates) {
            if (t.w > rad * 2 + 2 * maxOff || t.h > rad * 2 + 2 * maxOff) continue
            for (r in 0..9) {
                for (c in 0..8) {
                    val ctr = g.center(r, c)
                    val cx = ((ctr.first - reg[0]) * cxScale).roundToInt()
                    val cy = ((ctr.second - reg[1]) * cyScale).roundToInt()
                    val s = Ncc.bestAt(px, bw, bh, cx, cy, rad, t, maxOff, step)
                    if (s >= threshold) {
                        val key = r * 9 + c
                        val prev = best[key]
                        if (prev == null || s > prev) {
                            best[key] = s
                            board[r][c] = t.code
                        }
                    }
                }
            }
        }
        return board
    }

    /** 中心区域红色程度（红方棋子为正），对应 JS redness() */
    fun redness(px: IntArray, w: Int, h: Int): Float {
        val cx = w / 2
        val cy = h / 2
        val rad = min(w, h) / 5
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
        var y = cy - rad
        while (y <= cy + rad) {
            var x = cx - rad
            while (x <= cx + rad) {
                if (x in 0 until w && y in 0 until h) {
                    val p = px[y * w + x]
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                    n++
                }
                x += 2
            }
            y += 2
        }
        if (n == 0) return 0f
        return ((sr / n) - (sg / n + sb / n) / 2).toFloat()
    }

    /** 从整幅截图中裁出矩形（自动夹紧边界），失败返回 null */
    fun safeCrop(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val sx = max(0, min(x, src.width - 1))
        val sy = max(0, min(y, src.height - 1))
        val sw = max(1, min(w, src.width - sx))
        val sh = max(1, min(h, src.height - sy))
        return try {
            Bitmap.createBitmap(src, sx, sy, sw, sh)
        } catch (e: Exception) {
            null
        }
    }

    /** 以 (cx,cy) 为中心裁出 2rad×2rad 的窗口像素（用于模板提取），越界部分返回 null */
    fun cropWindow(src: Bitmap, cx: Int, cy: Int, rad: Int): Pair<IntArray, Int>? {
        val x0 = cx - rad
        val y0 = cy - rad
        if (x0 < 0 || y0 < 0 || x0 + rad * 2 > src.width || y0 + rad * 2 > src.height) return null
        val px = IntArray(rad * 2 * rad * 2)
        src.getPixels(px, 0, rad * 2, x0, y0, rad * 2, rad * 2)
        return Pair(px, rad * 2)
    }

    fun emptyBoard(): Array<Array<String?>> {
        val b = arrayOfNulls<Array<String?>>(10)
        for (r in 0..9) b[r] = arrayOfNulls(9)
        return b
    }

    /** 模板提取时的采样半径（全分辨率） */
    fun sampleRadius(g: Grid): Int {
        return max(8, (min(g.cellW, g.cellH) * 0.44f).roundToInt())
    }

    fun absDiff(a: Float, b: Float): Float = abs(a - b)
}
