package com.xiangqi.autoassist

import android.app.Activity
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 校准悬浮窗：全屏透明窗捕获用户在棋盘界面点下的两个对角交叉点。
 * 对应 JS 版 floaty.rawWindow 的校准流程。
 */
object OverlayCalibrator {

    var active: Boolean = false
        private set

    fun run(activity: Activity, onDone: (Grid) -> Unit, onError: (String) -> Unit) {
        if (active) {
            onError("校准已在进行中")
            return
        }
        val wm = activity.getSystemService(WindowManager::class.java)
        val root = FrameLayout(activity)
        root.setBackgroundColor(0x00000000)
        val tip = TextView(activity)
        tip.text = "点第 1 下：左上角交叉点"
        tip.setTextColor(0xFFFF3333.toInt())
        tip.textSize = 16f
        tip.setPadding(48, 96, 48, 48)
        root.addView(
            tip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val taps = ArrayList<Pair<Float, Float>>(2)
        active = true
        root.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                taps.add(Pair(e.rawX, e.rawY))
                if (taps.size == 1) {
                    tip.text = "已记录左上角，再点右下角交叉点"
                } else if (taps.size >= 2) {
                    val g = Prefs.gridOf(taps[0].first, taps[0].second, taps[1].first, taps[1].second)
                    try { wm.removeView(root) } catch (ex: Exception) {}
                    active = false
                    onDone(g)
                    return@setOnTouchListener true
                }
            }
            true
        }
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            active = false
            onError("悬浮窗添加失败: ${e.message}")
        }
    }
}
