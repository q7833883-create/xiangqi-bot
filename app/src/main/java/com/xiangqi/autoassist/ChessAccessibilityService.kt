package com.xiangqi.autoassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：负责实际落子（点击/滑动），并监听音量上键作为全局停止开关。
 */
class ChessAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ChessAccessibilityService? = null
            private set

        fun click(x: Int, y: Int): Boolean {
            val svc = instance ?: return false
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            path.lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f)
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return svc.dispatchGesture(gesture, null, null)
        }

        fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val path = Path()
            path.moveTo(x0.toFloat(), y0.toFloat())
            path.lineTo(x1.toFloat(), y1.toFloat())
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return svc.dispatchGesture(gesture, null, null)
        }

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不做控件分析，仅使用手势与按键过滤
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // 仅在自动对弈运行时拦截音量上键，避免影响日常使用
        if (AutoPlayService.running && event != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        ) {
            AutoPlayService.requestStop()
            return true
        }
        return super.onKeyEvent(event)
    }
}
