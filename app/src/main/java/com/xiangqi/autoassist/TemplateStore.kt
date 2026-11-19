package com.xiangqi.autoassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xiangqi.autoassist.engine.ChessLogic
import java.io.File
import java.io.FileOutputStream

/**
 * 模板存取：首次「提取棋子模板」时按标准初始布局裁图保存，
 * 之后识别时加载（半分辨率灰度 + 预计算均值/方差）。
 */
object TemplateStore {

    private var cache: List<Template>? = null

    val PIECE_CODES = arrayOf("K", "A", "B", "N", "R", "C", "P", "k", "a", "b", "n", "r", "c", "p")

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "templates")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun count(ctx: Context): Int {
        val d = dir(ctx)
        var n = 0
        for (c in PIECE_CODES) if (File(d, "$c.png").exists()) n++
        return n
    }

    fun clear(ctx: Context) {
        val d = dir(ctx)
        for (c in PIECE_CODES) File(d, "$c.png").delete()
        cache = null
    }

    /** 保存一枚模板（全分辨率裁剪图） */
    fun save(ctx: Context, code: String, bmp: Bitmap): Boolean {
        val f = File(dir(ctx), "$code.png")
        return try {
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 加载全部模板（半分辨率灰度，预计算 mean/std）；不足 14 枚返回空列表 */
    fun load(ctx: Context): List<Template> {
        cache?.let { return it }
        val d = dir(ctx)
        val out = ArrayList<Template>(14)
        for (c in PIECE_CODES) {
            val f = File(d, "$c.png")
            if (!f.exists()) continue
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val t = toTemplate(c, bmp)
            bmp.recycle()
            out.add(t)
        }
        cache = out
        return out
    }

    fun toTemplate(code: String, bmp: Bitmap): Template {
        // 半分辨率
        val w = max(2, bmp.width / 2)
        val h = max(2, bmp.height / 2)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled != bmp) scaled.recycle()
        val gray = FloatArray(w * h)
        var sum = 0.0
        var sumSq = 0.0
        for (i in px.indices) {
            val p = px[i]
            val g = ((p shr 8) and 0xFF).toFloat()
            gray[i] = g
            sum += g
            sumSq += g * g
        }
        val n = (w * h).toDouble()
        val mean = (sum / n).toFloat()
        val variance = (sumSq / n - (sum / n) * (sum / n)).toDouble()
        val std = kotlin.math.sqrt(if (variance < 1e-6) 1e-6 else variance).toFloat()
        return Template(code, w, h, gray, mean, std)
    }

    /**
     * 提取模板：在初始局面的整幅截图上，按标准布局裁出 14 枚棋子，
     * 用双方将/帅的红色程度判断我方颜色。返回是否成功。
     */
    fun extractFromScreenshot(
        ctx: Context,
        bmpFull: Bitmap,
        g: Grid,
        prefs: Prefs
    ): Pair<Boolean, String> {
        val rad = BoardRecognizer.sampleRadius(g)
        val squares = ChessLogic.initialSquares()
        val reg = g.region()
        val boardImg = BoardRecognizer.safeCrop(bmpFull, reg[0], reg[1], reg[2], reg[3])
            ?: return Pair(false, "裁剪棋盘区域失败")
        val crops = HashMap<String, Bitmap>()
        try {
            for (sq in squares) {
                val r = sq.first; val c = sq.second
                val ctr = g.center(r, c)
                val cx = ctr.first - reg[0]
                val cy = ctr.second - reg[1]
                val win = BoardRecognizer.cropWindow(boardImg, cx, cy, rad)
                if (win == null) {
                    return Pair(false, "棋盘区域越界，请重新校准")
                }
                val px = win.first
                val w = win.second
                val bmp = Bitmap.createBitmap(w, rad * 2, Bitmap.Config.ARGB_8888)
                bmp.setPixels(px, 0, w, 0, 0, w, rad * 2)
                crops["$r,$c"] = bmp
            }
            // 用双方将/帅模板的红色程度判定哪边是红方
            val topKing = crops["0,4"]!!
            val botKing = crops["9,4"]!!
            val topRed = rednessOf(topKing)
            val botRed = rednessOf(botKing)
            val topIsRed = topRed > botRed
            var saved = 0
            for (sq in squares) {
                val r = sq.first; val c = sq.second; val code = sq.third
                val isTop = r <= 4
                val isRed = if (isTop) topIsRed else !topIsRed
                val name = if (isRed) code else code.lowercase()
                if (save(ctx, name, crops["$r,$c"]!!)) saved++
            }
            prefs.bottomIsRed = !topIsRed
            cache = null
            return Pair(saved == squares.size, "已保存 $saved 枚模板，我方为" + if (!topIsRed) "红方" else "黑方")
        } finally {
            for (b in crops.values) b.recycle()
            boardImg.recycle()
        }
    }

    private fun rednessOf(bmp: Bitmap): Float {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return BoardRecognizer.redness(px, bmp.width, bmp.height)
    }
}
