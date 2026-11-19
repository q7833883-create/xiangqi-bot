package com.xiangqi.autoassist

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BoardDetector 单元测试：全部使用程序化生成的合成棋盘（无外部图片）。
 * 生成逻辑与 node 孪生验证脚本 /tmp/twin.js 完全一致（同一种子、同色板、同噪声），
 * 场景覆盖：标准浅色木底 / 多分辨率缩放 / 深色主题 / 无棋盘 / 局部遮挡 / 强噪声 / 非标占比。
 */
class BoardDetectorTest {

    // ==================== 确定性 RNG（与 JS mulberry32 一致） ====================
    private class Rng(seed: Int) {
        private var a: Int = seed
        fun next(): Double {
            a += 0x6D2B79F5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (255 shl 24) or (r shl 16) or (g shl 8) or b

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private class Scene(
        val px: IntArray,
        val w: Int,
        val h: Int,
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double
    )

    // ==================== 合成场景绘制 ====================
    private fun fillRect(px: IntArray, w: Int, x: Double, y: Double, rw: Double, rh: Double, color: Int) {
        val x0 = max(0, x.roundToInt())
        val y0 = max(0, y.roundToInt())
        val x1 = min(w, ceil(x + rw).toInt())
        val y1 = min(px.size / w, ceil(y + rh).toInt())
        for (yy in y0 until y1) {
            var idx = yy * w + x0
            for (xx in x0 until x1) {
                px[idx] = color
                idx++
            }
        }
    }

    private fun drawPiece(px: IntArray, w: Int, cx: Double, cy: Double, r: Double, fill: Int, rim: Int) {
        val rimW = max(2.0, r * 0.12)
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = ceil(cy + r).toInt()
        for (yy in y0..y1) {
            val dy = yy - cy
            val hw = sqrt(max(0.0, r * r - dy * dy))
            val x0 = max(0, floor(cx - hw).toInt())
            val x1 = min(w - 1, ceil(cx + hw).toInt())
            for (xx in x0..x1) {
                val dx = xx - cx
                val d = sqrt(dx * dx + dy * dy)
                px[yy * w + xx] = if (d > r - rimW) rim else fill
            }
        }
    }

    private fun drawGlyph(px: IntArray, w: Int, x: Double, y: Double, gw: Double, gh: Double, color: Int) {
        val t = max(2.0, (gw * 0.14).roundToInt().toDouble())
        fillRect(px, w, x, y, gw, t, color)
        fillRect(px, w, x, y + (gh - t) / 2, gw, t, color)
        fillRect(px, w, x, y + gh - t, gw, t, color)
        fillRect(px, w, x + gw * 0.22, y + t, t, (gh - t) / 2, color)
        fillRect(px, w, x + gw * 0.62, y + (gh - t) / 2, t, (gh - t) / 2, color)
    }

    private fun drawRiverText(px: IntArray, w: Int, left: Double, boardW: Double, ry0: Double, ry1: Double, color: Int) {
        val bandH = ry1 - ry0
        val glyphH = bandH * 0.5
        val glyphW = glyphH
        val gap = boardW * 0.05
        val totalW = 4 * glyphW + 3 * gap
        var x = left + (boardW - totalW) / 2
        val y = ry0 + (bandH - glyphH) / 2
        for (g in 0 until 4) {
            drawGlyph(px, w, x, y, glyphW, glyphH, color)
            x += glyphW + gap
        }
    }

    /** 标准开局 32 子：1=红 0=黑 */
    private fun startingPieces(): List<IntArray> {
        val list = ArrayList<IntArray>()
        for (i in 0..8) list.add(intArrayOf(i, 0, 0))
        list.add(intArrayOf(1, 2, 0))
        list.add(intArrayOf(7, 2, 0))
        for (i in intArrayOf(0, 2, 4, 6, 8)) list.add(intArrayOf(i, 3, 0))
        for (i in intArrayOf(0, 2, 4, 6, 8)) list.add(intArrayOf(i, 6, 1))
        list.add(intArrayOf(1, 7, 1))
        list.add(intArrayOf(7, 7, 1))
        for (i in 0..8) list.add(intArrayOf(i, 9, 1))
        return list
    }

    private fun addNoise(px: IntArray, w: Int, h: Int, rnd: Rng, amount: Double) {
        for (i in px.indices) {
            if (rnd.next() < amount) {
                val d = ((rnd.next() * 2 - 1) * 50).roundToInt()
                val p = px[i]
                val r = clamp255(((p shr 16) and 0xFF) + d)
                val g = clamp255(((p shr 8) and 0xFF) + d)
                val b = clamp255((p and 0xFF) + d)
                px[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * 合成整屏棋盘：铺底色 -> 10 横 9 竖线（中间 7 条竖线在河界断开）-> 楚河汉界文字
     * -> 32 个圆形棋子（遮线）-> 噪声 -> 可选遮挡弹窗。
     */
    private fun makeBoardScene(
        w: Int,
        h: Int,
        dark: Boolean,
        seed: Int,
        noise: Double,
        boardFrac: Double,
        dialog: Boolean
    ): Scene {
        val rnd = Rng(seed)
        val px = IntArray(w * h)
        val bgC = if (dark) argb(44, 48, 58) else argb(232, 206, 168)
        val lineC = if (dark) argb(198, 186, 150) else argb(94, 62, 34)
        val redC = if (dark) argb(150, 60, 50) else argb(168, 58, 46)
        val redRim = if (dark) argb(112, 44, 36) else argb(132, 42, 32)
        val blackC = if (dark) argb(24, 24, 28) else argb(32, 32, 36)
        val blackRim = if (dark) argb(10, 10, 12) else argb(14, 14, 18)
        px.fill(bgC)
        val boardW = w * boardFrac
        val cell = boardW / 8
        val boardH = cell * 9
        val left = (w - boardW) / 2
        val top = (h - boardH) / 2
        // 10 条横线
        val lw = max(2, (cell * 0.022).roundToInt())
        for (j in 0..9) {
            fillRect(px, w, left, top + j * cell - lw / 2.0, boardW, lw.toDouble(), lineC)
        }
        // 9 条竖线；中间 7 条在河界（第 4~5 行之间，约 44%~56% 高度）断开
        for (i in 0..8) {
            if (i == 0 || i == 8) {
                fillRect(px, w, left + i * cell - lw / 2.0, top, lw.toDouble(), boardH, lineC)
            } else {
                fillRect(px, w, left + i * cell - lw / 2.0, top, lw.toDouble(), 4 * cell, lineC)
                fillRect(px, w, left + i * cell - lw / 2.0, top + 5 * cell, lw.toDouble(), 4 * cell, lineC)
            }
        }
        // 楚河汉界文字
        drawRiverText(px, w, left, boardW, top + 4 * cell, top + 5 * cell, lineC)
        // 32 个棋子（遮住部分线）
        for (p in startingPieces()) {
            val cx = left + p[0] * cell
            val cy = top + p[1] * cell
            val r = cell * 0.4
            if (p[2] == 1) drawPiece(px, w, cx, cy, r, redC, redRim) else drawPiece(px, w, cx, cy, r, blackC, blackRim)
        }
        if (noise > 0) addNoise(px, w, h, rnd, noise)
        // 白色弹窗盖住棋盘右上角（约 32% 宽 × 30% 高）
        if (dialog) {
            val dx0 = (w * 0.68).roundToInt().toDouble()
            val dy1 = (h * 0.30).roundToInt().toDouble()
            fillRect(px, w, dx0, 0.0, w - dx0, dy1, argb(245, 245, 248))
        }
        return Scene(px, w, h, left, top, left + boardW, top + boardH)
    }

    private fun makeSolid(w: Int, h: Int): Scene {
        val px = IntArray(w * h)
        px.fill(argb(120, 124, 132))
        return Scene(px, w, h, 0.0, 0.0, 0.0, 0.0)
    }

    private fun makeGradient(w: Int, h: Int): Scene {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val t = y.toDouble() / h
            val c = argb((30 + 180 * t).roundToInt(), (40 + 150 * t).roundToInt(), (60 + 120 * t).roundToInt())
            for (x in 0 until w) px[y * w + x] = c
        }
        return Scene(px, w, h, 0.0, 0.0, 0.0, 0.0)
    }

    private fun makeUI(w: Int, h: Int, seed: Int): Scene {
        val rnd = Rng(seed)
        val px = IntArray(w * h)
        px.fill(argb(40, 44, 52))
        fillRect(px, w, 0.0, 0.0, w.toDouble(), 120.0, argb(55, 60, 72))
        fillRect(px, w, 40.0, 50.0, 360.0, 20.0, argb(200, 205, 215))
        var y = 200.0
        for (i in 0 until 6) {
            fillRect(px, w, 24.0, y, (w - 48).toDouble(), 180.0, argb(54, 58, 68))
            fillRect(px, w, 48.0, y + 30, 300.0, 16.0, argb(150, 155, 165))
            fillRect(px, w, 48.0, y + 70, (w - 120).toDouble(), 16.0, argb(100, 105, 115))
            y += 204
        }
        fillRect(px, w, 0.0, (h - 140).toDouble(), w.toDouble(), 140.0, argb(50, 54, 64))
        for (i in 0 until 4) {
            fillRect(px, w, 60 + i * (w - 120) / 4.0, (h - 100).toDouble(), 80.0, 60.0, argb(120, 125, 135))
        }
        addNoise(px, w, h, rnd, 0.01)
        return Scene(px, w, h, 0.0, 0.0, 0.0, 0.0)
    }

    // ==================== 断言辅助 ====================
    /** 允许误差：屏宽的 1.5% */
    private fun tol(w: Int): Double = w * 0.015

    private fun maxErrOf(g: BoardDetector.DetectedGrid, s: Scene): Double =
        maxOf(abs(g.x0 - s.x0), abs(g.y0 - s.y0), abs(g.x1 - s.x1), abs(g.y1 - s.y1))

    private fun consOf(g: BoardDetector.DetectedGrid): Double {
        val sx = (g.x1 - g.x0) / 8.0
        val sy = (g.y1 - g.y0) / 9.0
        return abs(sx - sy) / sx
    }

    private fun assertGrid(name: String, s: Scene) {
        val r = BoardDetector.detect(s.px, s.w, s.h)
        assertNotNull("$name: 应检出棋盘，实际返回 null", r)
        val g = r!!
        val maxErr = maxErrOf(g, s)
        val cons = consOf(g)
        assertTrue("$name: 误差 $maxErr 超过容差 ${tol(s.w)}（det=(${g.x0},${g.y0},${g.x1},${g.y1}) gt=(${s.x0},${s.y0},${s.x1},${s.y1})）", maxErr <= tol(s.w))
        assertTrue("$name: 格宽格高一致性 $cons 不小于 0.25", cons < 0.25)
        assertTrue("$name: score=${g.score} 超出 0..1", g.score >= 0f && g.score <= 1f)
    }

    // ==================== 测试场景 ====================

    @Test
    fun standardLightBoard_1080x2400() {
        assertGrid("标准浅色 1080x2400", makeBoardScene(1080, 2400, dark = false, seed = 42, noise = 0.01, boardFrac = 0.95, dialog = false))
    }

    @Test
    fun scaledBoards_720x1600_and_1440x3200() {
        assertGrid("缩放 720x1600", makeBoardScene(720, 1600, dark = false, seed = 43, noise = 0.01, boardFrac = 0.95, dialog = false))
        assertGrid("缩放 1440x3200", makeBoardScene(1440, 3200, dark = false, seed = 44, noise = 0.01, boardFrac = 0.95, dialog = false))
    }

    @Test
    fun darkThemeBoard_1080x2400() {
        assertGrid("深色主题 1080x2400", makeBoardScene(1080, 2400, dark = true, seed = 45, noise = 0.01, boardFrac = 0.95, dialog = false))
    }

    @Test
    fun darkThemeBoard_720x1600() {
        assertGrid("深色主题 720x1600", makeBoardScene(720, 1600, dark = true, seed = 49, noise = 0.01, boardFrac = 0.95, dialog = false))
    }

    @Test
    fun noBoard_scenes_returnNull() {
        val solid = makeSolid(1080, 2400)
        assertNull("纯色屏不应检出", BoardDetector.detect(solid.px, solid.w, solid.h))
        val grad = makeGradient(1080, 2400)
        assertNull("渐变屏不应检出", BoardDetector.detect(grad.px, grad.w, grad.h))
        val ui = makeUI(1080, 2400, 77)
        assertNull("仅 UI 元素屏不应检出", BoardDetector.detect(ui.px, ui.w, ui.h))
    }

    @Test
    fun occludedBoard_detectedAndCorrect() {
        // 约 1/3 宽白色弹窗盖住棋盘右上角：要求仍能检出且坐标正确（不许返回错误坐标）
        val s = makeBoardScene(1080, 2400, dark = false, seed = 46, noise = 0.01, boardFrac = 0.95, dialog = true)
        val r = BoardDetector.detect(s.px, s.w, s.h)
        assertNotNull("遮挡场景应仍能检出", r)
        val g = r!!
        assertTrue("遮挡场景坐标错误：maxErr=${maxErrOf(g, s)}", maxErrOf(g, s) <= tol(1080))
        assertTrue("遮挡场景一致性异常：${consOf(g)}", consOf(g) < 0.25)
    }

    @Test
    fun occludedBoard_wrongCoordsNeverReturned_evenIfNull() {
        // 换一批种子：允许返回 null，但只要返回就必须落在真值容差内
        for (seed in intArrayOf(11, 12, 13)) {
            val s = makeBoardScene(1080, 2400, dark = false, seed = seed, noise = 0.015, boardFrac = 0.95, dialog = true)
            val r = BoardDetector.detect(s.px, s.w, s.h)
            if (r != null) {
                assertTrue("seed=$seed 遮挡场景返回了错误坐标", maxErrOf(r, s) <= tol(1080))
                assertTrue("seed=$seed 遮挡场景一致性异常", consOf(r) < 0.25)
            }
        }
    }

    @Test
    fun heavyNoise_twoPercent_stillDetected() {
        assertGrid("2% 噪声", makeBoardScene(1080, 2400, dark = false, seed = 47, noise = 0.02, boardFrac = 0.95, dialog = false))
    }

    @Test
    fun nonStandardBoardFractions_stillDetected() {
        assertGrid("占比 0.70", makeBoardScene(1080, 2400, dark = false, seed = 48, noise = 0.01, boardFrac = 0.70, dialog = false))
        assertGrid("占比 0.80", makeBoardScene(1080, 2400, dark = false, seed = 58, noise = 0.01, boardFrac = 0.80, dialog = false))
        assertGrid("占比 0.90", makeBoardScene(1080, 2400, dark = false, seed = 59, noise = 0.01, boardFrac = 0.90, dialog = false))
    }

    @Test
    fun cellConsistency_belowQuarter_forBothThemes() {
        for (dark in booleanArrayOf(false, true)) {
            val s = makeBoardScene(1080, 2400, dark = dark, seed = 99, noise = 0.01, boardFrac = 0.95, dialog = false)
            val r = BoardDetector.detect(s.px, s.w, s.h)
            assertNotNull("theme=$dark 应检出", r)
            assertTrue("theme=$dark 一致性 ${consOf(r!!)} 应 < 0.25", consOf(r) < 0.25)
        }
    }

    @Test
    fun multipleSeeds_stableDetection() {
        for (seed in intArrayOf(1, 2, 3, 4, 5)) {
            assertGrid("light seed=$seed", makeBoardScene(1080, 2400, dark = false, seed = seed, noise = 0.015, boardFrac = 0.95, dialog = false))
            assertGrid("dark seed=$seed", makeBoardScene(1080, 2400, dark = true, seed = seed + 100, noise = 0.015, boardFrac = 0.95, dialog = false))
        }
    }

    @Test
    fun detect_rejectsBadInput() {
        assertNull("空像素应返回 null", BoardDetector.detect(IntArray(0), 0, 0))
        val tiny = IntArray(100) { 0xFF888888.toInt() }
        assertNull("尺寸不符应返回 null", BoardDetector.detect(tiny, 10, 20))
    }
}
