package com.xiangqi.autoassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 悬浮窗棋盘：把识别到的局面实时画出来（与真实屏幕同方向），
 * 并可高亮引擎建议的起/终点。
 */
class OverlayBoardView(context: Context) : View(context) {

    private var board: Array<Array<String?>>? = null
    private var sugFrom: Pair<Int, Int>? = null
    private var sugTo: Pair<Int, Int>? = null
    private var status: String = ""

    private val dp = resources.displayMetrics.density
    private val pad = 8 * dp
    private val cell = 30 * dp
    private val statusH = 24 * dp
    private val sideW = pad * 2 + cell * 8
    private val sideH = pad * 2 + cell * 9 + statusH

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5D4037.toInt()
        strokeWidth = 1.4f * dp
        style = Paint.Style.STROKE
    }
    private val boldLine = Paint(linePaint).apply { strokeWidth = 2.6f * dp }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD84315.toInt()
        strokeWidth = 2.2f * dp
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * dp, 5 * dp))
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3E2723.toInt()
        textSize = 12 * dp
        textAlign = Paint.Align.CENTER
    }

    fun setData(
        board: Array<Array<String?>>?,
        from: Pair<Int, Int>?,
        to: Pair<Int, Int>?,
        status: String
    ) {
        this.board = board
        this.sugFrom = from
        this.sugTo = to
        this.status = status
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sideW.toInt(), sideH.toInt())
    }

    private fun ox(): Float = pad
    private fun oy(): Float = pad

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 木质底
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF0D9A8.toInt()
        canvas.drawRoundRect(0f, 0f, sideW, sideH - statusH, 10 * dp, 10 * dp, paint)

        val ox = ox()
        val oy = oy()
        val w = cell * 8
        val h = cell * 9
        // 横线
        for (r in 0..9) {
            val y = oy + r * cell
            canvas.drawLine(ox, y, ox + w, y, if (r == 0 || r == 9) boldLine else linePaint)
        }
        // 竖线（中间列在楚河汉界断开）
        for (c in 0..8) {
            val x = ox + c * cell
            if (c == 0 || c == 8) {
                canvas.drawLine(x, oy, x, oy + h, boldLine)
            } else {
                canvas.drawLine(x, oy, x, oy + 4 * cell, linePaint)
                canvas.drawLine(x, oy + 5 * cell, x, oy + h, linePaint)
            }
        }
        // 九宫斜线
        canvas.drawLine(ox + 3 * cell, oy, ox + 5 * cell, oy + 2 * cell, linePaint)
        canvas.drawLine(ox + 5 * cell, oy, ox + 3 * cell, oy + 2 * cell, linePaint)
        canvas.drawLine(ox + 3 * cell, oy + 7 * cell, ox + 5 * cell, oy + 9 * cell, linePaint)
        canvas.drawLine(ox + 5 * cell, oy + 7 * cell, ox + 3 * cell, oy + 9 * cell, linePaint)
        // 楚河汉界
        textPaint.color = 0xFF8D6E63.toInt()
        textPaint.textSize = 11 * dp
        canvas.drawText("楚 河", ox + w * 0.30f, oy + 4.5f * cell + 4 * dp, textPaint)
        canvas.drawText("汉 界", ox + w * 0.70f, oy + 4.5f * cell + 4 * dp, textPaint)

        val b = board
        if (b != null) {
            val rad = cell * 0.42f
            for (r in 0..9) for (c in 0..8) {
                val p = b[r][c] ?: continue
                val cx = ox + c * cell
                val cy = oy + r * cell
                val red = p[0] in 'A'..'Z'
                paint.style = Paint.Style.FILL
                paint.color = 0xFFF6E9CA.toInt()
                canvas.drawCircle(cx, cy, rad, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * dp
                paint.color = if (red) 0xFFB71C1C.toInt() else 0xFF212121.toInt()
                canvas.drawCircle(cx, cy, rad, paint)
                textPaint.color = paint.color
                textPaint.textSize = cell * 0.52f
                val fm = textPaint.fontMetrics
                canvas.drawText(
                    pieceCharOf(p), cx, cy - (fm.ascent + fm.descent) / 2f, textPaint
                )
            }
        }

        // 建议高亮：起点绿环、终点橙环 + 虚线箭头
        val f = sugFrom
        val t = sugTo
        if (f != null && t != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.4f * dp
            paint.color = 0xFF2E7D32.toInt()
            canvas.drawCircle(ox + f.second * cell, oy + f.first * cell, cell * 0.46f, paint)
            paint.color = 0xFFD84315.toInt()
            canvas.drawCircle(ox + t.second * cell, oy + t.first * cell, cell * 0.46f, paint)
            val path = Path()
            path.moveTo(ox + f.second * cell, oy + f.first * cell)
            path.lineTo(ox + t.second * cell, oy + t.first * cell)
            canvas.drawPath(path, dashPaint)
        }

        // 状态行
        if (status.isNotEmpty()) {
            statusPaint.textSize = 12 * dp
            canvas.drawText(status, sideW / 2f, sideH - 8 * dp, statusPaint)
        }
    }

    companion object {
        fun px(dpValue: Float, context: Context): Float = dpValue * context.resources.displayMetrics.density

        fun pieceCharOf(code: String): String {
            val red = code[0] in 'A'..'Z'
            return when (code.uppercase()) {
                "K" -> if (red) "帅" else "将"
                "A" -> if (red) "仕" else "士"
                "B" -> if (red) "相" else "象"
                "N" -> "马"
                "R" -> "车"
                "C" -> "炮"
                "P" -> if (red) "兵" else "卒"
                else -> "?"
            }
        }
    }
}
