package com.xiangqi.autoassist

import android.app.Service
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 指点模式的悬浮窗：实时同步的棋盘 + 「算一招」「停止」按钮。
 * - 不抢焦点（FLAG_NOT_FOCUSABLE），不影响底层 App 渲染；
 * - 整窗可拖动：按住棋盘/边框区域拖动，自动夹在屏幕范围内；
 * - 所有视图操作都切到主线程执行。
 */
class HintOverlay(
    private val service: Service,
    private val onSuggest: () -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private var rootView: LinearLayout? = null
    private var boardView: OverlayBoardView? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false

    fun show() {
        main.post {
            if (added) return@post
            val ctx = service
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(10, 10, 10, 10)
            root.background = GradientDrawable().apply {
                setColor(0xE6113319.toInt())
                cornerRadius = 14f
            }

            val board = OverlayBoardView(ctx)
            boardView = board
            root.addView(
                board,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 8, 0, 0)
            row.gravity = Gravity.CENTER
            row.addView(
                mkButton(ctx, "算一招") { onSuggest() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                mkButton(ctx, "停止") { onStop() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            root.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.END
            p.x = 12
            p.y = 120
            params = p

            // ===== 拖动：按住非按钮区域即可移动整窗 =====
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            root.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = p.x
                        startY = p.y
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            dragging = true
                        }
                        if (dragging) {
                            val screenW = v.resources.displayMetrics.widthPixels
                            val screenH = v.resources.displayMetrics.heightPixels
                            // gravity=END：x 是距右边缘的距离，手指右移 → x 减小
                            val maxX = max(0, screenW - v.width)
                            val maxY = max(0, screenH - v.height)
                            p.x = (startX - dx.roundToInt()).coerceIn(0, maxX)
                            p.y = (startY + dy.roundToInt()).coerceIn(0, maxY)
                            try {
                                wm.updateViewLayout(v, p)
                            } catch (e2: Exception) {
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        true
                    }
                    else -> false
                }
            }

            try {
                wm.addView(root, p)
                added = true
                rootView = root
            } catch (e: Exception) {
                added = false
            }
        }
    }

    private fun mkButton(ctx: android.content.Context, text: String, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.setAllCaps(false)
        b.setOnClickListener { onClick() }
        return b
    }

    fun hide() {
        main.post {
            val r = rootView ?: return@post
            try { wm.removeView(r) } catch (e: Exception) {}
            added = false
            rootView = null
            boardView = null
            params = null
        }
    }

    /** 同步识别到的棋盘（清空建议高亮）；board 允许为 null（尚未识别到） */
    fun updateBoard(board: Array<Array<String?>>?, status: String) {
        main.post {
            boardView?.setData(board, null, null, status)
        }
    }

    /** 显示建议：高亮起终点 + 状态文字 */
    fun showSuggestion(
        board: Array<Array<String?>>?,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        text: String
    ) {
        main.post {
            boardView?.setData(board, from, to, text)
        }
    }
}
