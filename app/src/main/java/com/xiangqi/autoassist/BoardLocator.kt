package com.xiangqi.autoassist

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 模板落位棋盘定位器：
 * 1. 在搜索区域内对 90 个候选格点做模板匹配（取各点最高分）
 * 2. 高分点作为"棋子锚点"
 * 3. 对锚点按初始网格的格子尺寸投票 → 得到网格左上角偏移
 * 4. 返回校正后的网格
 *
 * 适用场景：应用切换导致棋盘整体平移/轻微滚动（JJ 象棋实测常见）。
 */
object BoardLocator {

    /**
     * @param bmpFull   全屏截图
     * @param templates 模板（>=14）
     * @param baseGrid  上次已知网格（作为格子尺寸来源与初值）
     * @param searchPad 搜索区域在棋盘四周外扩的像素（全分辨率）
     * @return 校正后的网格；失败返回 null
     */
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
        val rad = max(4, (min(cw, ch) * 0.44f * 0.5f).roundToInt())
        val maxOff = max(3, rad / 3) // 半分辨率下的搜索半径
        val step = 2

        // 90 个候选格点（以初始网格为参考，允许整体平移由偏移搜索覆盖）
        val votesX = HashMap<Int, Int>()
        val votesY = HashMap<Int, Int>()
        val anchorOff = HashMap<Long, Pair<Int, Int>>() // (r*9+c) -> (ox, oy) 最佳偏移
        var anchors = 0
        for (r in 0..9) {
            for (c in 0..8) {
                val cx = ((baseGrid.x0 + c * cw + 0.5f * cw - reg[0]) * 0.5f).roundToInt()
                val cy = ((baseGrid.y0 + r * ch + 0.5f * ch - reg[1]) * 0.5f).roundToInt()
                var best = 0f
                var bestOx = 0
                var bestOy = 0
                for (t in templates) {
                    // 逐偏移取最优（bestAt 不返回偏移，这里手动展开一层）
                    var oy = -maxOff
                    while (oy <= maxOff) {
                        var ox = -maxOff
                        while (ox <= maxOff) {
                            val s = Ncc.corrAt(px, sw, sh, cx + ox, cy + oy, rad, t)
                            if (s > best) {
                                best = s; bestOx = ox; bestOy = oy
                            }
                            ox += step
                        }
                        oy += step
                    }
                }
                if (best >= threshold) {
                    anchors++
                    anchorOff[(r * 9L + c)] = Pair(bestOx, bestOy)
                    // 锚点像素坐标（全分辨率，相对截图原点）
                    val axFull = (cx + bestOx) * 2 + reg[0]
                    val ayFull = (cy + bestOy) * 2 + reg[1]
                    for (i in 0..8) {
                        val v = ((axFull - i * cw) / 4).roundToInt() * 4
                        votesX[v] = (votesX[v] ?: 0) + 1
                    }
                    for (j in 0..9) {
                        val v = ((ayFull - j * ch) / 4).roundToInt() * 4
                        votesY[v] = (votesY[v] ?: 0) + 1
                    }
                }
            }
        }
        if (anchors < 4) return null
        val bx = votesX.maxByOrNull { it.value } ?: return null
        val by = votesY.maxByOrNull { it.value } ?: return null

        // 细化：取峰桶附近的锚点均值（用各点实际最佳偏移）
        fun refine(peak: Int, cell: Float, isX: Boolean): Float? {
            val sel = mutableListOf<Float>()
            for (r in 0..9) {
                for (c in 0..8) {
                    if (anchorOff[(r * 9L + c)] == null) continue
                    val (ox, oy) = anchorOff[(r * 9L + c)]!!
                    val cx = ((baseGrid.x0 + c * cw + 0.5f * cw - reg[0]) * 0.5f).roundToInt()
                    val cy = ((baseGrid.y0 + r * ch + 0.5f * ch - reg[1]) * 0.5f).roundToInt()
                    val axFull = (cx + ox) * 2 + reg[0]
                    val ayFull = (cy + oy) * 2 + reg[1]
                    val p = if (isX) axFull else ayFull
                    val i = if (isX) c else r
                    if (kotlin.math.abs((p - i * cell) - peak) < 8) {
                        sel.add(p - i * cell)
                    }
                }
            }
            return if (sel.size >= 3) sel.sorted()[sel.size / 2] else null
        }
        val fx0 = refine(bx.key, cw, true) ?: return null
        val fy0 = refine(by.key, ch, false) ?: return null
        // 校验：与初始网格偏移不能太离谱（< 2 格）
        if (kotlin.math.abs(fx0 - baseGrid.x0) > cw * 2.5f) return null
        if (kotlin.math.abs(fy0 - baseGrid.y0) > ch * 2.5f) return null
        return Grid(fx0, fy0, fx0 + 8 * cw, fy0 + 9 * ch)
    }
}
