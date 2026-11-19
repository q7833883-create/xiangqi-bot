package com.xiangqi.autoassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xiangqi.autoassist.engine.ChessLogic
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** 棋子模板存取：内置 assets（免提取），也支持用户自提取覆盖 */
object TemplateStore {

    private const val DIR = "templates"
    private const val BUNDLED_DIR = "templates" // assets 下目录

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    /** 内置模板（JJ 象棋）是否可用 */
    fun bundledAvailable(ctx: Context): Boolean {
        return try {
            val names = ctx.assets.list(BUNDLED_DIR) ?: return false
            names.count { it.endsWith(".png") } >= 14 && names.contains("meta.json")
        } catch (e: Exception) {
            false
        }
    }

    /** 加载模板：优先用户提取的（更贴合当前应用），否则用内置的 */
    fun load(ctx: Context): List<Template> {
        val user = loadFrom(ctx, dir(ctx))
        if (user.size >= 14) return user
        val bundled = loadBundled(ctx)
        return if (bundled.size >= 14) bundled else user
    }

    private fun loadBundled(ctx: Context): List<Template> {
        return try {
            val names = ctx.assets.list(BUNDLED_DIR)?.filter { it.endsWith(".png") } ?: return emptyList()
            val out = mutableListOf<Template>()
            for (name in names.sorted()) {
                val code = name.removeSuffix(".png")
                val bmp = BitmapFactory.decodeStream(ctx.assets.open("$BUNDLED_DIR/$name")) ?: continue
                out.add(toTemplate(code, bmp))
                bmp.recycle()
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadFrom(ctx: Context, d: File): List<Template> {
        if (!d.isDirectory) return emptyList()
        val out = mutableListOf<Template>()
        val files = d.listFiles()?.filter { it.name.endsWith(".png") } ?: return emptyList()
        for (f in files.sortedBy { it.name }) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            out.add(toTemplate(f.name.removeSuffix(".png"), bmp))
            bmp.recycle()
        }
        return out
    }

    private fun toTemplate(code: String, bmp: Bitmap): Template {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until w * h) {
            val g = ((px[i] shr 8) and 0xFF).toDouble()
            gray[i] = g.toFloat()
            sum += g
            sumSq += g * g
        }
        val n = (w * h).toDouble()
        val mean = sum / n
        val std = kotlin.math.sqrt(max(1e-6, sumSq / n - mean * mean))
        return Template(code, w, h, gray, mean.toFloat(), std.toFloat())
    }

    fun count(ctx: Context): Int = load(ctx).size

    fun save(ctx: Context, code: String, bmp: Bitmap): Boolean {
        return try {
            val d = dir(ctx)
            d.mkdirs()
            val f = File(d, "$code.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clear(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    /**
     * 从初始局面截图提取 14 枚模板（自动模式：网格+布局已知，直接采样）。
     * 返回 (成功, 消息)。
     */
    fun extractFromScreenshot(
        ctx: Context,
        bmpFull: Bitmap,
        g: Grid,
        prefs: Prefs
    ): Pair<Boolean, String> {
        if (!prefs.bottomIsRed) {
            return Pair(false, "暂只支持红方在下的布局（当前设置黑方在下）")
        }
        val back = listOf("r", "n", "b", "a", "k", "a", "b", "n", "r")
        val squares = mutableListOf<Triple<Int, Int, String>>() // (row, col, code)
        for (c in 0..8) {
            squares.add(Triple(0, c, back[c]))          // 顶部 = 黑方（小写）
            squares.add(Triple(9, c, back[c].uppercase())) // 底部 = 红方（大写）
        }
        squares.add(Triple(2, 1, "c")); squares.add(Triple(2, 7, "c"))
        squares.add(Triple(7, 1, "C")); squares.add(Triple(7, 7, "C"))
        for (c in listOf(0, 2, 4, 6, 8)) {
            squares.add(Triple(3, c, "p"))
            squares.add(Triple(6, c, "P"))
        }
        clear(ctx)
        val rad = BoardRecognizer.sampleRadius(g)
        var ok = 0
        var lastErr = ""
        for ((r, c, code) in squares) {
            val ctr = g.center(r, c)
            val crop = BoardRecognizer.cropWindow(bmpFull, ctr.first, ctr.second, rad)
            if (crop == null) {
                lastErr = "采样越界 ($r,$c)"
                continue
            }
            val bmp = Bitmap.createBitmap(rad * 2, rad * 2, Bitmap.Config.ARGB_8888)
            bmp.setPixels(crop.first, 0, rad * 2, 0, 0, rad * 2, rad * 2)
            if (save(ctx, code, bmp)) ok++
            bmp.recycle()
        }
        return if (ok == 14) Pair(true, "模板提取完成（14/14）")
        else Pair(false, "模板提取不完整（$ok/14）$lastErr")
    }

    /** 从截图按网格+模板识别棋盘（屏幕视角），空位为 null */
    fun recognizeBoard(bmpFull: Bitmap, g: Grid, templates: List<Template>, threshold: Float): Array<Array<String?>> {
        return BoardRecognizer.recognize(bmpFull, g, templates, threshold)
    }

    /** 屏幕视角棋盘 → FEN（红下黑上） */
    fun boardToFen(board: Array<Array<String?>>, bottomIsRed: Boolean): String {
        return ChessLogic.boardToFen(board, bottomIsRed)
    }
}
