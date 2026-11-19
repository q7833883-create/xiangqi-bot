package com.xiangqi.autoassist

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 棋盘自动定位器：从整屏 ARGB_8888 截图中检测 9×10 中国象棋盘的网格线，
 * 返回左上角交叉点 (x0,y0)（第 0 条竖线 × 第 0 条横线）与右下角交叉点 (x1,y1)
 * （第 8 条竖线 × 第 9 条横线），语义与人工校准完全一致，用于替代手动校准。
 *
 * 算法流程：
 *  1) 降采样：长边 ≤ [MAX_SIDE]，按块做 min/max/mean 灰度池化（min 保暗细线、max 保亮细线）；
 *  2) 局部背景：对块均值做积分图，得到每块 [BG_WIN] 半径窗口的局部背景灰度；
 *  3) 双极性二值化：dark = bg - min > delta（暗线亮底），light = max - bg > delta（亮线暗底）；
 *  4) 行/列投影：统计每行（列）线素占比与最长连续段，超阈值者为候选，相邻 ≤[MERGE_GAP]+1 合并成簇，
 *     簇位置取「峰值裁剪质心」（只用以簇内峰值 [TRIM_FRAC] 以上的行计算，抗楚河汉界文字粘连）；
 *  5) 选线：枚举（首，尾）候选对推出理想间距，向中间逐个容差走查，取间距偏差最小的一组 10 横 × 9 竖；
 *  6) 校验与评分：格宽 sx=(x8-x0)/8 与格高 sy=(y9-y0)/9 之比须在 [RATIO_MIN]~[RATIO_MAX]，
 *     一致性 |sx-sy|/sx ≤ [CONSIST_MAX]，板宽高比合理；score = 覆盖一致性 × 间距一致性，低于
 *     [MIN_SCORE] 判为无棋盘。两种极性都出解时取 score 高者。
 *
 * 纯 Kotlin stdlib 实现，无任何 android.* 依赖，可在纯 JVM 单测中运行。
 */
object BoardDetector {

    data class DetectedGrid(
        val x0: Float,   // 左上角交叉点 x（第 0 条竖线）
        val y0: Float,   // 左上角交叉点 y（第 0 条横线）
        val x1: Float,   // 右下角交叉点 x（第 8 条竖线）
        val y1: Float,   // 右下角交叉点 y（第 9 条横线）
        val score: Float // 置信度 0..1
    )

    // ==================== 可调参数 ====================
    /** 降采样后长边上限（像素），加速且抗噪 */
    private const val MAX_SIDE = 720.0
    /** 线素判定：|灰度 - 局部背景| 需超过该值 */
    private const val CONTRAST_DELTA = 28.0
    /** 局部背景窗口半径（降采样像素），需大于线宽、约一个棋子直径 */
    private const val BG_WIN = 16
    /** 行候选：行内线素占比下限（占降采样宽度的比例） */
    private const val ROW_RATIO = 0.15
    /** 列候选：列内线素占比下限（中间 7 列被河界断开，故低于行阈值） */
    private const val COL_RATIO = 0.13
    /** 行/列候选的备选条件：最长连续段占比 */
    private const val RUN_RATIO = 0.45
    /** 备选条件生效时所需的最低占比（= 主阈值 × 该系数） */
    private const val RUN_FB = 0.5
    /** 候选行/列聚簇：间隔 ≤ MERGE_GAP+1 视为同一条线 */
    private const val MERGE_GAP = 3
    /** 簇质心裁剪：仅使用计数 ≥ 峰值×TRIM_FRAC 的行/列参与质心 */
    private const val TRIM_FRAC = 0.6
    /** 选线走查容差：相对理想间距的最大偏差（>25% 剔除） */
    private const val GAP_TOL = 0.25
    /** 最小格宽（降采样像素），过滤 UI 碎线 */
    private const val CELL_MIN = 6.0
    /** 最大格宽/降采样宽（棋盘 8 格宽，物理上限 1/8 略放宽） */
    private const val CELL_MAX_H = 0.135
    /** 最大格高/降采样高（棋盘 9 格高） */
    private const val CELL_MAX_V = 0.12
    /** sx/sy 合法区间 */
    private const val RATIO_MIN = 0.75
    private const val RATIO_MAX = 1.3334
    /** |sx-sy|/sx 上限（与单元测试断言 0.25 对齐且略严） */
    private const val CONSIST_MAX = 0.22
    /** 棋盘宽高比（8sx/9sy）合法区间 */
    private const val ASPECT_MIN = 0.60
    private const val ASPECT_MAX = 1.30
    /** 最终置信度门槛 */
    private const val MIN_SCORE = 0.10
    // ==================================================

    private class Cand(val pos: Double, val cnt: Double)

    private class Sel(
        val pos: DoubleArray,
        val cnt: DoubleArray,
        val dev: Double,
        val quality: Double,
        val weight: Double
    )

    private class Res(val rows: DoubleArray, val cols: DoubleArray, val score: Double)

    /**
     * @param px 整屏 ARGB_8888 像素（px[y*w+x]，高 8 位起依次为 A,R,G,B）
     * @param w  图像宽
     * @param h  图像高
     * @return 检测成功返回交叉点坐标与置信度；失败返回 null
     */
    fun detect(px: IntArray, w: Int, h: Int): DetectedGrid? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null

        // ---- 1) 降采样（块池化：mean/min/max 灰度） ----
        val ds = max(1, ceil(max(w, h) / MAX_SIDE).toInt())
        val dw = ceil(w.toDouble() / ds).toInt()
        val dh = ceil(h.toDouble() / ds).toInt()
        val n = dw * dh
        val sum = DoubleArray(n)
        val cnt = IntArray(n)
        val mn = DoubleArray(n) { 255.0 }
        val mx = DoubleArray(n)
        for (by in 0 until dh) {
            val y0 = by * ds
            val y1 = min(h, y0 + ds)
            for (bx in 0 until dw) {
                val x0 = bx * ds
                val x1 = min(w, x0 + ds)
                var s = 0.0
                var c = 0
                var mnV = 255.0
                var mxV = 0.0
                for (y in y0 until y1) {
                    var idx = y * w + x0
                    for (x in x0 until x1) {
                        val p = px[idx]
                        idx++
                        val g = 0.299 * ((p shr 16) and 0xFF) +
                                0.587 * ((p shr 8) and 0xFF) +
                                0.114 * (p and 0xFF)
                        s += g
                        c++
                        if (g < mnV) mnV = g
                        if (g > mxV) mxV = g
                    }
                }
                val o = by * dw + bx
                sum[o] = s
                cnt[o] = c
                mn[o] = mnV
                mx[o] = mxV
            }
        }
        val mean = DoubleArray(n)
        for (i in 0 until n) mean[i] = sum[i] / cnt[i]

        // ---- 2) 积分图求局部背景 ----
        val iw = dw + 1
        val integ = DoubleArray(iw * (dh + 1))
        for (y in 0 until dh) {
            var rs = 0.0
            for (x in 0 until dw) {
                rs += mean[y * dw + x]
                integ[(y + 1) * iw + (x + 1)] = integ[y * iw + (x + 1)] + rs
            }
        }
        val bg = DoubleArray(n)
        for (y in 0 until dh) {
            val ya = max(0, y - BG_WIN)
            val yb = min(dh - 1, y + BG_WIN)
            for (x in 0 until dw) {
                val xa = max(0, x - BG_WIN)
                val xb = min(dw - 1, x + BG_WIN)
                val area = integ[(yb + 1) * iw + (xb + 1)] - integ[ya * iw + (xb + 1)] -
                        integ[(yb + 1) * iw + xa] + integ[ya * iw + xa]
                bg[y * dw + x] = area / ((yb - ya + 1.0) * (xb - xa + 1.0))
            }
        }

        // ---- 3) 双极性二值化 ----
        val dark = BooleanArray(n)
        val light = BooleanArray(n)
        for (i in 0 until n) {
            dark[i] = bg[i] - mn[i] > CONTRAST_DELTA
            light[i] = mx[i] - bg[i] > CONTRAST_DELTA
        }

        // ---- 4)~7) 每种极性：候选线 -> 选 10 横 9 竖 -> 校验评分 ----
        var best: Res? = null
        val masks = arrayOf(dark, light)
        for (mi in 0..1) {
            val mask = masks[mi]
            val rows = findLines(mask, dw, dh, true, ROW_RATIO * dw)
            val cols = findLines(mask, dw, dh, false, COL_RATIO * dh)
            if (rows.size < 10 || cols.size < 9) continue
            val rs = selectLines(rows, 10, CELL_MIN, CELL_MAX_H * dw) ?: continue
            val cs = selectLines(cols, 9, CELL_MIN, CELL_MAX_V * dh) ?: continue
            val res = validateAndScore(rs, cs) ?: continue
            if (best == null || res.score > best.score) best = res
        }

        val b = best ?: return null
        return DetectedGrid(
            x0 = ((b.cols[0] + 0.5) * ds).toFloat(),
            y0 = ((b.rows[0] + 0.5) * ds).toFloat(),
            x1 = ((b.cols[8] + 0.5) * ds).toFloat(),
            y1 = ((b.rows[9] + 0.5) * ds).toFloat(),
            score = b.score.toFloat()
        )
    }

    /**
     * 对行（horizontal=true）或列做投影统计，返回线簇中心候选（按位置升序）。
     * 候选条件：线素占比 ≥ thresh，或（最长连续段 ≥ RUN_RATIO×长度 且 占比 ≥ thresh×RUN_FB）。
     * 相邻间隔 ≤ MERGE_GAP+1 的候选合并为一簇；簇位置取峰值裁剪质心，权重取簇内总计数。
     */
    private fun findLines(
        mask: BooleanArray,
        W: Int,
        H: Int,
        horizontal: Boolean,
        thresh: Double
    ): List<Cand> {
        val lines = if (horizontal) H else W
        val count = if (horizontal) W else H
        val cnts = IntArray(lines)
        val runs = IntArray(lines)
        for (l in 0 until lines) {
            var c = 0
            var run = 0
            var bestRun = 0
            if (horizontal) {
                val base = l * W
                for (k in 0 until count) {
                    if (mask[base + k]) {
                        c++
                        run++
                        if (run > bestRun) bestRun = run
                    } else {
                        run = 0
                    }
                }
            } else {
                for (k in 0 until count) {
                    if (mask[k * W + l]) {
                        c++
                        run++
                        if (run > bestRun) bestRun = run
                    } else {
                        run = 0
                    }
                }
            }
            cnts[l] = c
            runs[l] = bestRun
        }
        val runThresh = RUN_RATIO * count
        val fbThresh = thresh * RUN_FB
        fun isCand(l: Int): Boolean =
            cnts[l] >= thresh || (runs[l] >= runThresh && cnts[l] >= fbThresh)

        val cands = ArrayList<Cand>()
        var l = 0
        while (l < lines) {
            if (!isCand(l)) {
                l++
                continue
            }
            val idxs = ArrayList<Int>()
            idxs.add(l)
            var last = l
            var j = l + 1
            while (j < lines) {
                if (isCand(j) && j - last <= MERGE_GAP + 1) {
                    idxs.add(j)
                    last = j
                }
                j++
            }
            // 峰值裁剪质心：剔除被链进簇的弱结构（如楚河汉界文字横杠）
            var peak = 0
            for (k in idxs) if (cnts[k] > peak) peak = cnts[k]
            val trim = peak * TRIM_FRAC
            var sumW = 0.0
            var sumP = 0.0
            var fullW = 0.0
            for (k in idxs) {
                fullW += cnts[k]
                if (cnts[k] >= trim) {
                    sumW += cnts[k]
                    sumP += k.toDouble() * cnts[k]
                }
            }
            cands.add(Cand(if (sumW > 0) sumP / sumW else last.toDouble(), fullW))
            l = last + 1
        }
        return cands
    }

    /**
     * 从候选中选出 [count] 条间距最一致线：枚举（首,尾）对 -> 理想间距 gap0，
     * 逐个向中间目标点容差（GAP_TOL×gap0）走查；在成功组合中取间距偏差最小者，
     * 偏差相同取权重（线强度）更大者。间距偏差 > GAP_TOL 的组合直接淘汰。
     */
    private fun selectLines(cands: List<Cand>, count: Int, minCell: Double, maxCell: Double): Sel? {
        val n = cands.size
        if (n < count) return null
        var best: Sel? = null
        var i = 0
        while (i + count - 1 < n) {
            var j = i + count - 1
            while (j < n) {
                val gap0 = (cands[j].pos - cands[i].pos) / (count - 1)
                if (gap0 >= minCell && gap0 <= maxCell) {
                    val tol = GAP_TOL * gap0
                    val chosen = IntArray(count)
                    chosen[0] = i
                    chosen[count - 1] = j
                    var ok = true
                    var m = i + 1
                    for (k in 1..count - 2) {
                        val target = cands[i].pos + k * gap0
                        var bestM = -1
                        var bestD = tol
                        var mm = m
                        while (mm < j) {
                            if (cands[mm].pos > target + tol) break
                            val d = abs(cands[mm].pos - target)
                            if (d <= bestD) {
                                bestD = d
                                bestM = mm
                            }
                            mm++
                        }
                        if (bestM < 0) {
                            ok = false
                            break
                        }
                        chosen[k] = bestM
                        m = bestM + 1
                    }
                    if (ok) {
                        val mean = (cands[j].pos - cands[i].pos) / (count - 1)
                        var dev = 0.0
                        for (k in 0 until count - 1) {
                            val g = cands[chosen[k + 1]].pos - cands[chosen[k]].pos
                            val d = abs(g - mean) / mean
                            if (d > dev) dev = d
                        }
                        if (dev <= GAP_TOL) {
                            var wgt = 0.0
                            for (k in 0 until count) wgt += cands[chosen[k]].cnt
                            val quality = 1 - dev / GAP_TOL
                            val b = best
                            if (b == null || quality > b.quality + 1e-9 ||
                                (abs(quality - b.quality) <= 1e-9 && wgt > b.weight)
                            ) {
                                val pos = DoubleArray(count)
                                val cnt = DoubleArray(count)
                                for (k in 0 until count) {
                                    pos[k] = cands[chosen[k]].pos
                                    cnt[k] = cands[chosen[k]].cnt
                                }
                                best = Sel(pos, cnt, dev, quality, wgt)
                            }
                        }
                    }
                }
                j++
            }
            i++
        }
        return best
    }

    /**
     * 几何校验 + 评分：sx/sy 之比、|sx-sy|/sx 一致性、板宽高比；
     * score = 覆盖一致性（各线计数/期望长度，截断到 1）× 间距一致性，低于 MIN_SCORE 拒绝。
     */
    private fun validateAndScore(rs: Sel, cs: Sel): Res? {
        val rp = rs.pos
        val cp = cs.pos
        val sx = (cp[8] - cp[0]) / 8
        val sy = (rp[9] - rp[0]) / 9
        if (sx <= 0 || sy <= 0) return null
        val ratio = sx / sy
        if (ratio < RATIO_MIN || ratio > RATIO_MAX) return null
        val cons = abs(sx - sy) / sx
        if (cons > CONSIST_MAX) return null
        val bw = cp[8] - cp[0]
        val bh = rp[9] - rp[0]
        val aspect = bw / bh
        if (aspect < ASPECT_MIN || aspect > ASPECT_MAX) return null
        var sum = 0.0
        for (k in 0..9) sum += min(1.0, rs.cnt[k] / bw)
        for (k in 0..8) {
            // 中间 7 条竖线被楚河汉界断开，期望长度按 8/9 折算
            val expected = if (k == 0 || k == 8) bh else bh * 8 / 9
            sum += min(1.0, cs.cnt[k] / expected)
        }
        val coverage = sum / 19
        val space = max(0.0, 1 - rs.dev / GAP_TOL) * max(0.0, 1 - cs.dev / GAP_TOL)
        val score = coverage * space
        if (score < MIN_SCORE) return null
        return Res(rp, cp, score)
    }
}
