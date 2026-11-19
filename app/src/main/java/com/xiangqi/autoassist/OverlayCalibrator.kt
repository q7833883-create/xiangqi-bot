package com.xiangqi.autoassist

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 校准悬浮窗（防误触版）：
 * 1) 显示说明卡片，用户切到棋盘界面后主动点「开始校准」才进入监听；
 * 2) 监听中依次记录左上、右下两个交叉点（开始前的点击一律忽略）；
 * 3) 记录完成后弹出确认（显示坐标与格宽/格高），确认才保存，可重来/放弃。
 */
object OverlayCalibrator {

    var active: Boolean = false
        private set

    private const val PHASE_IDLE = 0
    private const val PHASE_ARMED = 1
    private const val PHASE_CONFIRM = 2

    fun run(activity: Activity, onDone: (Grid) -> Unit, onError: (String) -> Unit) {
        if (active) {
            onError("校准已在进行中")
            return
        }
        val wm = activity.getSystemService(WindowManager::class.java)
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int): Int = (v * dp).toInt()

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.TRANSPARENT)

        val card = LinearLayout(activity)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(px(14), px(12), px(14), px(12))
        card.background = GradientDrawable().apply {
            setColor(0xEE1E2D24.toInt())
            cornerRadius = 14f
        }

        val title = TextView(activity).apply {
            text = "校准棋盘"
            setTextColor(0xFFFFE9C8.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tip = TextView(activity).apply {
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            setPadding(0, px(6), 0, px(6))
        }
        val status = TextView(activity).apply {
            setTextColor(0xFFFFD54F.toInt())
            textSize = 13f
            setPadding(0, px(4), 0, px(4))
        }
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        card.addView(title)
        card.addView(tip)
        card.addView(status)
        card.addView(
            btnRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(px(10), px(60), px(10), 0) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        var phase = PHASE_IDLE
        val taps = ArrayList<Pair<Float, Float>>(2)
        var grid: Grid? = null

        fun exit(result: Grid?, message: String) {
            try { wm.removeView(root) } catch (e: Exception) {}
            active = false
            if (result != null) onDone(result) else onError(message)
        }

        fun setButtons(vararg defs: Pair<String, () -> Unit>) {
            btnRow.removeAllViews()
            for ((label, action) in defs) {
                val b = Button(activity)
                b.text = label
                b.setAllCaps(false)
                b.textSize = 13f
                b.setOnClickListener { action() }
                btnRow.addView(
                    b,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(px(3), 0, px(3), 0) }
                )
            }
        }

        fun describe(g: Grid): String {
            return "左上(" + g.x0.toInt() + "," + g.y0.toInt() + ")  右下(" +
                    g.x1.toInt() + "," + g.y1.toInt() + ")\n" +
                    "格宽 " + g.cellW.toInt() + "px  格高 " + g.cellH.toInt() + "px"
        }

        fun renderButtons() {
            when (phase) {
                PHASE_IDLE -> setButtons(
                    "开始校准" to {
                        phase = PHASE_ARMED
                        taps.clear()
                        status.text = "已就绪：请点左上角交叉点"
                        renderButtons()
                    },
                    "放弃" to { exit(null, "校准已取消") }
                )
                PHASE_ARMED -> setButtons(
                    "放弃" to { exit(null, "校准已取消") }
                )
                else -> setButtons(
                    "确认保存" to { exit(grid, "") },
                    "重新校准" to {
                        phase = PHASE_ARMED
                        taps.clear()
                        grid = null
                        status.text = "已就绪：请点左上角交叉点"
                        renderButtons()
                    },
                    "放弃" to { exit(null, "校准已取消") }
                )
            }
        }

        fun refreshTexts() {
            when (phase) {
                PHASE_IDLE -> {
                    tip.text = "① 切到象棋 App 的棋盘界面\n② 回这里点「开始校准」\n③ 在棋盘上依次点：左上角交叉点、右下角交叉点\n（开始前，其他点击都会被忽略，不怕误触）"
                    status.text = ""
                }
                PHASE_ARMED -> {
                    tip.text = "在棋盘上依次点：左上角交叉点、右下角交叉点"
                }
                else -> {
                    tip.text = "校准完成，确认保存？\n（确认后回到本 App，再进行「提取模板」）"
                }
            }
        }

        fun enterConfirm() {
            phase = PHASE_CONFIRM
            val g = grid!!
            status.text = describe(g)
            refreshTexts()
            renderButtons()
        }

        root.setOnTouchListener { v, e ->
            if (phase == PHASE_ARMED && e.action == MotionEvent.ACTION_DOWN) {
                taps.add(Pair(e.rawX, e.rawY))
                if (taps.size == 1) {
                    status.text = "已记录左上角 ✓，再点右下角交叉点"
                } else if (taps.size >= 2) {
                    grid = Prefs.gridOf(taps[0].first, taps[0].second, taps[1].first, taps[1].second)
                    enterConfirm()
                }
            }
            true
        }

        // 初始状态
        refreshTexts()
        renderButtons()
        active = true
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            active = false
            onError("悬浮窗添加失败: " + (e.message ?: "unknown"))
        }
    }
}
