package com.xiangqi.autoassist

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 模板落位棋盘定位器：
 * 1. 以参考网格为初值，在 ±maxOff 范围内对 90 个交叉点做模板匹配；
 * 2. 对匹配分 >= 阈值的锚点，做"整体平移一致性"投票（网格刚性平移假设）；
 * 3. 一致票数达标的平移量取中位数细化，得到校正网格。
 */
object BoardLocator {

    fun locate(
        bmpFull: Bitmap,
        templates: List<Template>,
        baseGrid: Grid,
        searchPad: Int = 200,
        threshold: Float = 0.60f
    ): Grid? {
        if (templates.size < 14) return null
        val reg = baseGrid.region()
        val x0 = max(0, reg[0] - searchPad)
        val y0 = max(0, reg[1] - searchPad)
        val x1 = min(bmpFull.width, reg[0] + reg[2] + searchPad)
        val y1 = min(bmpFull.height, reg[1] + reg[3] + searchPad)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return null
        val crop = BoardRecognizer.safeCrop(bmpFull, x0, y0, w, h) ?: return null
        // 半分辨率处理
        val sw = max(1, w / 2)
        val sh = max(1, h / 2)
        val scaled = Bitmap.createScaledBitmap(crop, sw, sh, true)
        if (scaled != crop) crop.recycle()
        val px = IntArray(sw * sh)
        scaled.getPixels(px, 0, sw, 0, 0, sw, sh)
        scaled.recycle()

        val cw = baseGrid.cellW
        val ch = baseGrid.cellH
        // 窗口尺寸必须与模板一致（corrAt 语义）
        val tplW = templates.maxOf { it.w }
        val tplH = templates.maxOf { it.h }
        val radX = tplW / 2
        val radY = tplH / 2
        // 搜索半径（半分辨率）：约 3/4 格，覆盖棋盘整体平移
        val maxOff = max(6, (min(cw, ch) * 0.75f * 0.5f).roundToInt())
        val step = 2

        // 每个格点的最佳匹配分与对应平移量（全分辨率）
        val scores = Array(10) { FloatArray(9) }
        val shiftX = Array(10) { IntArray(9) }
        val shiftY = Array(10) { IntArray(9) }
        for (r in 0..9) {
            for (c in 0..8) {
                val cx = ((baseGrid.x0 + c * cw - reg[0]) * 0.5f).roundToInt()
                val cy = ((baseGrid.y0 + r * ch - reg[1]) * 0.5f).roundToInt()
                var best = 0f
                var bdx = 0
                var bdy = 0
                for (t in templates) {
                    var oy = -maxOff
                    while (oy <= maxOff) {
                        var ox = -maxOff
                        while (ox <= maxOff) {
                            val s = Ncc.corrAt(px, sw, sh, cx + ox, cy + oy, radX, t)
                            if (s > best) {
                                best = s
                                bdx = ox * 2
                                bdy = oy * 2
                            }
                            ox += step
                        }
                        oy += step
                    }
                }
                scores[r][c] = best
                shiftX[r][c] = bdx
                shiftY[r][c] = bdy
            }
        }

        // 平移一致性：统计与 (dx,dy) 一致（±4px）的高分锚点数
        fun agreement(dx: Int, dy: Int, tol: Int = 4): Int {
            var n = 0
            for (r in 0..9) for (c in 0..8) {
                if (scores[r][c] >= threshold &&
                    abs(shiftX[r][c] - dx) <= tol && abs(shiftY[r][c] - dy) <= tol
                ) n++
            }
            return n
        }

        var bestDx = 0
        var bestDy = 0
        var bestN = -1
        val span = maxOff * 2
        var dy = -span
        while (dy <= span) {
            var dx = -span
            while (dx <= span) {
                val n = agreement(dx, dy)
                if (n > bestN || (n == bestN && abs(dx) + abs(dy) < abs(bestDx) + abs(bestDy))) {
                    bestN = n
                    bestDx = dx
                    bestDy = dy
                }
                dx += step * 2
            }
            dy += step * 2
        }
        if (bestN < 4) return null

        // 中位数细化（取一致锚点的平移量）
        val sxs = mutableListOf<Int>()
        val sys = mutableListOf<Int>()
        for (r in 0..9) for (c in 0..8) {
            if (scores[r][c] >= threshold &&
                abs(shiftX[r][c] - bestDx) <= 4 && abs(shiftY[r][c] - bestDy) <= 4
            ) {
                sxs.add(shiftX[r][c])
                sys.add(shiftY[r][c])
            }
        }
        if (sxs.size < 4) return null
        sxs.sort()
        sys.sort()
        val mdx = sxs[sxs.size / 2].toFloat()
        val mdy = sys[sys.size / 2].toFloat()

        val nx0 = baseGrid.x0 + mdx
        val ny0 = baseGrid.y0 + mdy
        // 平移量不得离谱（< 2.5 格）
        if (abs(mdx) > cw * 2.5f || abs(mdy) > ch * 2.5f) return null
        return Grid(nx0, ny0, nx0 + 8 * cw, ny0 + 9 * ch)
    }
}
