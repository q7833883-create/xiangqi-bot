package com.xiangqi.autoassist

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val REQ_CAPTURE = 1001
        const val REQ_NOTIF = 1002
    }

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var engineSpinner: Spinner
    private lateinit var movetimeEdit: EditText
    private var pendingMode: String = AutoPlayService.MODE_PLAY
    private var pendingMyTurn: Boolean = true

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        logView.text = AutoPlayService.lastMessage
    }

    // ==================== 界面 ====================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ScrollView {
        val ctx = this
        val root = ScrollView(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(28), dp(20), dp(28))
        root.addView(box)

        val title = TextView(ctx)
        title.text = "象棋自动对弈助手"
        title.textSize = 22f
        title.setTextColor(0xFF1E2D24.toInt())
        box.addView(title)

        statusView = TextView(ctx)
        statusView.textSize = 14f
        statusView.setPadding(0, dp(10), 0, dp(10))
        box.addView(statusView)

        logView = TextView(ctx)
        logView.textSize = 13f
        logView.setTextColor(0xFF666666.toInt())
        logView.minLines = 2
        box.addView(logView)

        box.addView(button("开启无障碍服务") { ensureAccessibility() })
        box.addView(button("校准棋盘") { onCalibrate() })
        box.addView(button("提取棋子模板") { onExtract() })
        box.addView(button("开始自动对弈") { onPlay() })
        box.addView(button("指点模式（只提示，我自己走）") { onHint() })
        box.addView(button("停止") { onStopClick() })

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(16), 0, 0)
        row.gravity = Gravity.CENTER_VERTICAL
        engineSpinner = Spinner(ctx)
        engineSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("引擎：自动", "引擎：Pikafish", "引擎：内置JS")
        )
        engineSpinner.setSelection(
            when (prefs.engineMode) {
                "pikafish" -> 1
                "js" -> 2
                else -> 0
            }
        )
        row.addView(
            engineSpinner,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        movetimeEdit = EditText(ctx)
        movetimeEdit.hint = "思考毫秒(默认3000)"
        movetimeEdit.setText(prefs.movetimeMs.toString())
        movetimeEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        row.addView(
            movetimeEdit,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        box.addView(row)
        box.addView(button("保存设置") {
            prefs.engineMode = when (engineSpinner.selectedItemPosition) {
                1 -> "pikafish"
                2 -> "js"
                else -> "auto"
            }
            val mt = movetimeEdit.text.toString().toIntOrNull()
            if (mt != null && mt in 200..60000) prefs.movetimeMs = mt
            toast("设置已保存")
            refreshStatus()
        })

        val help = TextView(ctx)
        help.textSize = 13f
        help.setTextColor(0xFF444444.toInt())
        help.setPadding(0, dp(18), 0, 0)
        help.text = "使用流程：\n" +
                "1. 开启无障碍服务（自动模式落子靠它）\n" +
                "2. 校准棋盘：切到象棋 App 棋盘界面，点左上、右下两个交叉点\n" +
                "3. 提取模板：开一局标准初始对局，点本按钮后 10 秒内切到棋盘\n" +
                "4. 自动模式：确认是否轮到你，然后切到棋盘界面挂机\n" +
                "5. 指点模式：屏幕角落显示实时同步的悬浮棋盘，点「算一招」看建议招法（高亮起终点），由你自己落子\n" +
                "6. 停止：按音量上键，或回本 App 点「停止」\n\n" +
                "仅支持竖屏、正视角、圆形棋子的标准 9×10 棋盘。模板/校准数据存在本应用私有目录，换皮肤或分辨率后重新校准提取。"
        box.addView(help)
        return root
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ==================== 状态 ====================

    private fun refreshStatus() {
        val g = prefs.grid
        val tpl = TemplateStore.count(this)
        val pf = File(applicationInfo.nativeLibraryDir, "libpikafish.so").exists()
        val engineName = when (prefs.engineMode) {
            "pikafish" -> "Pikafish"
            "js" -> "内置JS"
            else -> if (pf) "Pikafish" else "内置JS"
        }
        statusView.text = "无障碍: " + (if (accessibilityEnabled()) "已开启" else "未开启") +
                "    校准: " + (if (g != null) "完成" else "未校准") +
                "    模板: $tpl/14" +
                "    引擎: $engineName" +
                "    思考: ${prefs.movetimeMs}ms"
    }

    private fun accessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        val list = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        for (s in list) {
            if (s.resolveInfo?.serviceInfo?.packageName == packageName) return true
        }
        return false
    }

    private fun ensureAccessibility(): Boolean {
        if (accessibilityEnabled()) return true
        toast("请在本页开启「象棋自动对弈助手」的无障碍服务")
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            toast("无法打开无障碍设置: ${e.message}")
        }
        return false
    }

    // ==================== 各按钮流程 ====================

    private fun onCalibrate() {
        if (AutoPlayService.running) {
            toast("自动对弈运行中，请先停止")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先允许「显示在其他应用上层」权限")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                toast("无法打开悬浮窗设置: ${e.message}")
            }
            return
        }
        logView.text = "校准窗已显示：请切到棋盘界面，点左上、右下两个交叉点"
        OverlayCalibrator.run(
            this,
            onDone = { g ->
                prefs.grid = g
                runOnUiThread {
                    logView.text = "校准完成：格宽 ${g.cellW.toInt()} 格高 ${g.cellH.toInt()}"
                    refreshStatus()
                }
            },
            onError = { err -> runOnUiThread { logView.text = err } }
        )
    }

    private fun onExtract() {
        if (AutoPlayService.running) {
            toast("任务运行中，请先停止")
            return
        }
        if (prefs.grid == null) {
            toast("先做「校准棋盘」")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("提取棋子模板")
            .setMessage("点击后请在 10 秒内切到象棋 App 的新对局（标准初始局面）界面，脚本会自动裁取 14 枚棋子模板。")
            .setPositiveButton("开始") { _, _ -> requestProjection(AutoPlayService.MODE_EXTRACT, false) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onPlay() {
        if (AutoPlayService.running) {
            toast("已在运行中")
            return
        }
        if (prefs.grid == null) {
            toast("先做「校准棋盘」")
            return
        }
        if (TemplateStore.count(this) < 14) {
            toast("先做「提取棋子模板」")
            return
        }
        if (!ensureAccessibility()) return
        AlertDialog.Builder(this)
            .setTitle("开始确认")
            .setMessage("现在轮到你走棋吗？\n（是 = 立即算招；否 = 等对方走完再开始）")
            .setPositiveButton("是") { _, _ ->
                pendingMyTurn = true
                requestProjection(AutoPlayService.MODE_PLAY, true)
            }
            .setNegativeButton("否") { _, _ ->
                pendingMyTurn = false
                requestProjection(AutoPlayService.MODE_PLAY, false)
            }
            .show()
    }

    private fun onHint() {
        if (AutoPlayService.running) {
            toast("任务运行中，请先停止")
            return
        }
        if (prefs.grid == null) {
            toast("先做「校准棋盘」")
            return
        }
        if (TemplateStore.count(this) < 14) {
            toast("先做「提取棋子模板」")
            return
        }
        requestProjection(AutoPlayService.MODE_HINT, false)
    }

    private fun onStopClick() {
        AutoPlayService.requestStop()
        stopService(Intent(this, AutoPlayService::class.java))
        logView.text = "已请求停止"
    }

    // ==================== 截图授权与启动 ====================

    private fun requestProjection(mode: String, myTurn: Boolean) {
        pendingMode = mode
        pendingMyTurn = myTurn
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            toast("发起截图授权失败: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            logView.text = "截图权限被拒绝"
            return
        }
        val svc = Intent(this, AutoPlayService::class.java)
        svc.putExtra(AutoPlayService.EXTRA_MODE, pendingMode)
        svc.putExtra(AutoPlayService.EXTRA_RESULT_CODE, resultCode)
        svc.putExtra(AutoPlayService.EXTRA_RESULT, data)
        svc.putExtra(AutoPlayService.EXTRA_MY_TURN, pendingMyTurn)
        try {
            startForegroundService(svc)
            logView.text = if (pendingMode == AutoPlayService.MODE_EXTRACT) "提取模板：请立即切到棋盘界面" else "已启动，请切到棋盘界面"
        } catch (e: Exception) {
            logView.text = "启动失败: ${e.message}"
        }
    }
}
