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
import android.content.Context
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 常驻悬浮窗：实时同步棋盘 + 模式切换（指点 ⇄ 自动）+ 算一招 + 停止。
 * - 不抢焦点，不影响底层 App 渲染；
 * - 整窗可拖动；
 * - 所有视图操作切主线程。
 */
class OverlayController(
    private val service: Context,
    private val onSuggest: () -> Unit,
    private val onModeSwitch: (String) -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private var rootView: LinearLayout? = null
    private var boardView: OverlayBoardView? = null
    private var modeButton: Button? = null
    private var statusView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false
    private var autoMode = false

    /** 待机模式（主界面进 App 即显示）：只显示按钮面板与提示，不做识别 */
    fun show(startAuto: Boolean) {
        autoMode = startAuto
        main.post {
            ensureViews()
            updateModeButton()
            if (startAuto) {
                statusView?.text = "自动对弈：等待识别…"
            }
        }
    }

    fun showIdle() {
        autoMode = false
        main.post {
            ensureViews()
            updateModeButton()
            statusView?.text = "待机中：识别/对弈启动后自动同步棋盘"
        }
    }

    private fun ensureViews() {
        if (added) return
        val ctx = service
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(10, 10, 10, 10)
            root.background = GradientDrawable().apply {
                setColor(0xE6113319.toInt())
                cornerRadius = 14f
            }

            statusView = TextView(ctx).apply {
                setTextColor(0xFFE0D5C5.toInt())
                textSize = 12f
                setPadding(4, 0, 4, 4)
            }
            root.addView(
                statusView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

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
            modeButton = mkButton(ctx, "") { onModeSwitch(if (autoMode) "hint" else "play") }
            row.addView(
                modeButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
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
            p.x = 24
            p.y = 200
            params = p

            // 整窗拖动
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            root.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = p.x
                        startY = p.y
                        dragging = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (!dragging && abs(dx) > touchSlop && abs(dy) > touchSlop) dragging = true
                        if (dragging) {
                            val dm = v.resources.displayMetrics
                            // gravity=END：x 是距右边缘距离，方向取反
                            p.x = (startX - dx).roundToInt().coerceIn(0, max(0, dm.widthPixels - v.width))
                            p.y = (startY + dy).roundToInt().coerceIn(0, max(0, dm.heightPixels - v.height))
                            try {
                                wm.updateViewLayout(v, p)
                            } catch (e: Exception) {
                            }
                        }
                        dragging
                    }
                    else -> dragging
                }
            }

            try {
                wm.addView(root, p)
                added = true
                rootView = root
                updateModeButton()
            } catch (e: Exception) {
            }
        }

    private fun mkButton(ctx: android.content.Context, text: String, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.textSize = 13f
        b.setPadding(14, 6, 14, 6)
        b.background = GradientDrawable().apply {
            setColor(0xFF2E5339.toInt())
            cornerRadius = 10f
        }
        b.setTextColor(0xFFFFFFFF.toInt())
        b.setOnClickListener { onClick() }
        return b
    }

    private fun updateModeButton() {
        modeButton?.text = if (autoMode) "切到指点" else "切到自动"
    }

    fun isAutoMode(): Boolean = autoMode

    /** 同步识别到的棋盘（自动模式下状态由服务更新） */
    fun updateBoard(board: Array<Array<String?>>?, status: String) {
        main.post {
            boardView?.setData(board, null, null, status)
            statusView?.text = status
        }
    }

    fun showSuggestion(
        board: Array<Array<String?>>,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        status: String
    ) {
        main.post {
            boardView?.setData(board, from, to, status)
            statusView?.text = status
        }
    }

    fun updateStatus(status: String) {
        main.post {
            statusView?.text = status
        }
    }

    fun dismiss() {
        main.post {
            val v = rootView ?: return@post
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
            added = false
            rootView = null
            boardView = null
        }
    }
}
