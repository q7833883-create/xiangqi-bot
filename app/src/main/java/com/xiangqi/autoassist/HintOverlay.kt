package com.xiangqi.autoassist

import android.app.Service
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout

/**
 * 指点模式的悬浮窗：实时同步的棋盘 + 「算一招」「停止」按钮。
 * 所有视图操作都切到主线程执行。
 */
class HintOverlay(
    private val service: Service,
    private val onSuggest: () -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var rootView: LinearLayout? = null
    private var boardView: OverlayBoardView? = null
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

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 12
            params.y = 120
            try {
                wm.addView(root, params)
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
