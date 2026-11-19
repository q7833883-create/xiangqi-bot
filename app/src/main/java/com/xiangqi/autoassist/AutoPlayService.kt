package com.xiangqi.autoassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random
import com.xiangqi.autoassist.engine.ChessLogic
import com.xiangqi.autoassist.engine.Engine
import com.xiangqi.autoassist.engine.KotlinEngine
import com.xiangqi.autoassist.engine.PikafishEngine

/**
 * 自动对弈前台服务：截屏识别 → 引擎算招 → 无障碍落子。
 * 也承担「提取模板」（mode=extract）与「指点模式」（mode=hint，只显示不落子）。
 */
class AutoPlayService : Service() {

    companion object {
        const val MODE_PLAY = "play"
        const val MODE_EXTRACT = "extract"
        const val MODE_HINT = "hint"
        const val MODE_DETECT = "detect"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT = "result"
        const val EXTRA_MY_TURN = "myTurn"
        const val ACTION_STOP = "com.xiangqi.autoassist.STOP"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastMessage: String = "待机"
            private set

        fun requestStop() {
            running = false
        }

        private fun setMsg(s: String) {
            lastMessage = s
        }
    }

    private lateinit var prefs: Prefs
    private var capturer: ScreenCapturer? = null
    private var engine: Engine? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var hintOverlay: HintOverlay? = null

    @Volatile
    private var stableBoard: Array<Array<String?>>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                "xq_status", getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_PLAY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT)
        val myTurn = intent?.getBooleanExtra(EXTRA_MY_TURN, false) ?: false
        if (resultData == null) {
            setMsg("缺少截图授权")
            stopSelf()
            return START_NOT_STICKY
        }
        val notif = buildNotification(if (mode == MODE_EXTRACT) "提取模板中…" else "启动中…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
        running = true
        val t = HandlerThread("xq-loop")
        worker = t
        t.start()
        workerHandler = Handler(t.looper)
        workerHandler!!.post {
            runLoop(mode, resultCode, resultData, myTurn)
        }
        return START_NOT_STICKY
    }

    // ==================== 主流程 ====================

    private fun runLoop(mode: String, resultCode: Int, resultData: Intent, myTurnInit: Boolean) {
        try {
            val pm = getSystemService(MediaProjectionManager::class.java)
            val projection = pm.getMediaProjection(resultCode, resultData)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    requestStop()
                }
            }, null)
            val metrics = ScreenCapturer.screenMetrics(this)
            capturer = ScreenCapturer(projection, metrics.widthPixels, metrics.heightPixels)
            engine = createEngine()
            if (mode == MODE_DETECT) {
                detectFlow()
            } else if (ensureGrid()) {
                if (mode == MODE_EXTRACT) {
                    extractFlow()
                } else if (mode == MODE_HINT) {
                    hintFlow()
                } else {
                    playFlow(myTurnInit)
                }
            }
        } catch (e: Exception) {
            setMsg("出错: ${e.message}")
            notify("出错: ${e.message}")
        } finally {
            running = false
            stopAll()
        }
    }

    /** 最近一帧的平均亮度（诊断用：≈0 说明截屏是黑屏） */
    private fun frameBrightness(): Int {
        val cap = capturer ?: return -1
        val bmp = cap.latestBitmap() ?: return -1
        var sum = 0L
        var n = 0
        var y = 0
        val stepY = 16
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                sum += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
                n++
                x += 128
            }
            y += stepY
        }
        bmp.recycle()
        return if (n == 0) -1 else (sum / (3L * n)).toInt()
    }

    private fun diag(): String {
        val b = frameBrightness()
        return if (b in 0..8) "；诊断：截屏亮度=$b（接近全黑，说明该应用禁止截屏或截屏失败）"
        else "；诊断：截屏亮度=$b（正常范围，排除黑屏问题）"
    }

    private fun extractFlow() {
        for (i in 30 downTo 1) {
            if (i % 5 == 0 || i <= 3) notify("提取模板：${i}s 后识别，请切到棋盘新对局界面")
            sleep(1000)
            if (!running) return
        }
        val g = prefs.grid
        if (g == null) {
            notify("先做「自动识别棋盘」或手动校准")
            setMsg("缺少棋盘数据")
            return
        }
        var bmp = capturer?.latestBitmap()
        var tries = 0
        while (bmp == null && tries < 15) {
            sleep(200)
            bmp = capturer?.latestBitmap()
            tries++
        }
        if (bmp == null) {
            notify("截图失败，请重试" + diag())
            setMsg("截图失败")
            return
        }
        val result = TemplateStore.extractFromScreenshot(this, bmp, g, prefs)
        bmp.recycle()
        val msg = if (result.first) result.second else result.second + diag()
        notify(msg)
        setMsg(msg)
    }

    /** 等待出现真实棋盘（双方将帅都在且棋子数足够），超时返回 null */
    private fun waitForBoard(): Array<Array<String?>>? {
        var waited = 0
        while (running && waited < 120_000) {
            val b = tryRecognize()
            if (b != null) {
                val chk = ChessLogic.countPieces(b)
                if (chk.hasK && chk.hask && chk.n >= 6) return b
            }
            val p = if (prefs.pollMs < 300) 300 else prefs.pollMs
            sleep(p)
            waited += p
        }
        return null
    }

    private fun playFlow(myTurnInit: Boolean) {
        val bottomIsRed = prefs.bottomIsRed
        val mySide = if (bottomIsRed) "w" else "b"
        notify("等待棋盘…请切换到象棋 App 的棋盘界面（音量上键停止）")
        setMsg("等待棋盘…")
        val first = waitForBoard()
        if (!running) return
        if (first == null) {
            notify("未识别到棋盘，停止")
            setMsg("未识别到棋盘")
            return
        }
        notify("自动对弈启动，我方为" + if (bottomIsRed) "红方" else "黑方" + "。音量上键停止")
        setMsg("自动对弈运行中")
        var myTurn = myTurnInit
        var lastKey = ChessLogic.boardKey(first)
        while (running) {
            if (myTurn) {
                val cur = tryRecognize()
                if (cur == null) {
                    sleep(prefs.pollMs)
                    continue
                }
                val chk = ChessLogic.countPieces(cur)
                if (!chk.hasK || !chk.hask) {
                    notify("有一方的将被吃/识别异常，停止")
                    setMsg("对局结束或识别异常")
                    return
                }
                val fen = ChessLogic.boardToFen(ChessLogic.toCanonical(cur, bottomIsRed), mySide)
                notify("思考中…")
                val mv = try {
                    engine?.bestMove(fen, prefs.movetimeMs)
                } catch (e: Exception) {
                    notify("引擎出错: ${e.message}")
                    setMsg("引擎出错: ${e.message}")
                    return
                }
                if (!running) return
                if (mv == null) {
                    notify("引擎无招（将死/困毙），停止")
                    setMsg("对局结束")
                    return
                }
                doMove(mv)
                notify("已落子 $mv")
                setMsg("已落子 $mv")
                lastKey = ""
                myTurn = false
                sleep(1200)
            } else {
                sleep(prefs.pollMs)
                val b1 = tryRecognize() ?: continue
                val k1 = ChessLogic.boardKey(b1)
                if (k1 == lastKey) continue
                sleep(500)
                val b2 = tryRecognize()
                if (b2 == null || ChessLogic.boardKey(b2) != k1) continue
                lastKey = k1
                myTurn = true
            }
        }
        setMsg("已停止")
    }

    // ==================== 指点模式 ====================

    private fun hintFlow() {
        val bottomIsRed = prefs.bottomIsRed
        val mySide = if (bottomIsRed) "w" else "b"
        notify("等待棋盘…请切换到象棋 App 的棋盘界面")
        setMsg("等待棋盘…")
        val first = waitForBoard()
        if (!running) return
        if (first == null) {
            notify("未识别到棋盘，停止")
            setMsg("未识别到棋盘")
            return
        }
        stableBoard = first
        hintOverlay = HintOverlay(
            this,
            onSuggest = {
                workerHandler?.post { computeSuggestion(bottomIsRed, mySide) }
            },
            onStop = { requestStop() }
        ).also { it.show() }
        hintOverlay?.updateBoard(first, "棋盘已同步")
        notify("指点模式运行中：悬浮棋盘实时同步，点「算一招」获取建议")
        setMsg("指点模式运行中")
        var lastKey = ChessLogic.boardKey(first)
        while (running) {
            sleep(prefs.pollMs)
            val b1 = tryRecognize() ?: continue
            val k1 = ChessLogic.boardKey(b1)
            if (k1 == lastKey) continue
            sleep(500)
            val b2 = tryRecognize() ?: continue
            if (ChessLogic.boardKey(b2) != k1) continue
            lastKey = k1
            stableBoard = b2
            hintOverlay?.updateBoard(b2, "棋盘已同步")
        }
        setMsg("已停止")
    }

    /** 对当前识别到的局面求一步建议，并在悬浮棋盘上高亮 */
    private fun computeSuggestion(bottomIsRed: Boolean, mySide: String) {
        val b = stableBoard
        if (b == null) {
            hintOverlay?.updateBoard(null, "还没识别到棋盘")
            return
        }
        hintOverlay?.updateBoard(b, "思考中…")
        val fen = ChessLogic.boardToFen(ChessLogic.toCanonical(b, bottomIsRed), mySide)
        val mv = try {
            engine?.bestMove(fen, prefs.movetimeMs)
        } catch (e: Exception) {
            hintOverlay?.updateBoard(b, "引擎出错: ${e.message}")
            return
        }
        if (mv == null) {
            hintOverlay?.updateBoard(b, "无招可走（将死/困毙）")
            return
        }
        val s = ChessLogic.engineMoveToScreen(mv, bottomIsRed)
        val piece = b[s.fr][s.fc]
        val name = if (piece != null) OverlayBoardView.pieceCharOf(piece) else "?"
        hintOverlay?.showSuggestion(
            b,
            Pair(s.fr, s.fc), Pair(s.tr, s.tc),
            "建议 $name  $mv（点「算一招」刷新）"
        )
    }

    // ==================== 棋盘自动识别 ====================

    /** 用当前截图自动定位棋盘（一次）；失败返回 null */
    private fun detectOnce(): Grid? {
        val cap = capturer ?: return null
        val bmp = cap.latestBitmap() ?: return null
        val grid = try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val d = BoardDetector.detect(px, bmp.width, bmp.height)
            if (d != null) Grid(d.x0, d.y0, d.x1, d.y1) else null
        } catch (e: Exception) {
            null
        }
        bmp.recycle()
        return grid
    }

    /** 确保已有棋盘几何：优先用已保存的校准/识别结果，否则自动识别（最长 120s） */
    private fun ensureGrid(): Boolean {
        if (prefs.grid != null) return true
        notify("正在自动识别棋盘…请切到棋盘界面")
        setMsg("自动识别棋盘中…")
        var waited = 0
        while (running && waited < 120_000) {
            val g = detectOnce()
            if (g != null) {
                prefs.grid = g
                notify("棋盘识别成功：格宽 ${g.cellW.toInt()} 格高 ${g.cellH.toInt()}")
                setMsg("棋盘识别成功")
                return true
            }
            sleep(1000)
            waited += 1000
        }
        if (running) {
            notify("自动识别失败（${waited / 1000}s 内未找到棋盘）" + diag() + "；也可回 App 做「手动校准」")
            setMsg("自动识别失败")
        }
        return false
    }

    /** 单独的「自动识别棋盘」流程：识别并保存，立即汇报 */
    private fun detectFlow() {
        notify("正在自动识别棋盘…请切到棋盘界面")
        setMsg("自动识别棋盘中…")
        var waited = 0
        var found: Grid? = null
        while (running && waited < 60_000) {
            found = detectOnce()
            if (found != null) break
            sleep(1000)
            waited += 1000
        }
        if (found != null) {
            prefs.grid = found
            notify("识别成功：左上(${found.x0.toInt()},${found.y0.toInt()}) 右下(${found.x1.toInt()},${found.y1.toInt()})")
            setMsg("棋盘识别成功")
        } else {
            notify("识别失败：60s 内未找到棋盘" + diag() + "；可改用「手动校准」")
            setMsg("自动识别失败")
        }
    }

    // ==================== 落子 ====================

    private fun doMove(move: String) {
        val g = prefs.grid ?: return
        val s = ChessLogic.engineMoveToScreen(move, prefs.bottomIsRed)
        val from = g.center(s.fr, s.fc)
        val to = g.center(s.tr, s.tc)
        val lo = prefs.clickDelayMin
        val hi = maxOf(prefs.clickDelayMax, lo + 1)
        sleep(Random.nextInt(lo, hi))
        if (prefs.moveMode == "drag") {
            ChessAccessibilityService.swipe(from.first, from.second, to.first, to.second, 350)
        } else {
            ChessAccessibilityService.click(from.first, from.second)
            sleep(Random.nextInt(220, 420))
            ChessAccessibilityService.click(to.first, to.second)
        }
    }

    // ==================== 识别 ====================

    private fun tryRecognize(): Array<Array<String?>>? {
        val cap = capturer ?: return null
        val g = prefs.grid ?: return null
        val tpls = TemplateStore.load(this)
        if (tpls.size < 14) return null
        val bmp = cap.latestBitmap() ?: return null
        val board = try {
            BoardRecognizer.recognize(bmp, g, tpls, prefs.matchThreshold)
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
        return board
    }

    // ==================== 引擎 ====================

    private fun createEngine(): Engine {
        val pf = File(applicationInfo.nativeLibraryDir, "libpikafish.so")
        val mode = prefs.engineMode
        val usePikafish = when (mode) {
            "pikafish" -> true
            "js" -> false
            else -> pf.exists()
        }
        return if (usePikafish && pf.exists()) {
            val nnue = copyNnueIfNeeded()
            PikafishEngine(pf.absolutePath, nnue, threads = 2, hashMb = 128)
        } else {
            KotlinEngine(3)
        }
    }

    private fun copyNnueIfNeeded(): String? {
        val dst = File(filesDir, "pikafish.nnue")
        return try {
            var expected = -1L
            try {
                assets.openFd("pikafish.nnue").use { expected = it.length }
            } catch (e: Exception) {
                // 资源被压缩时 openFd 不可用，忽略，仍走流复制
            }
            if (!dst.exists() || (expected > 0 && dst.length() != expected)) {
                assets.open("pikafish.nnue").use { input ->
                    FileOutputStream(dst).use { out -> input.copyTo(out, 1 shl 16) }
                }
            }
            dst.absolutePath
        } catch (e: Exception) {
            null // 没有内置权重则跑经典评估
        }
    }

    // ==================== 通知 ====================

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, AutoPlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, "xq_status")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
        b.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.ic_media_play
                ),
                getString(R.string.stop_action), pi
            ).build()
        )
        return b.build()
    }

    private fun notify(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
        } catch (e: Exception) {
        }
    }

    // ==================== 生命周期 ====================

    private fun stopAll() {
        running = false
        try { hintOverlay?.hide() } catch (e: Exception) {}
        hintOverlay = null
        try { capturer?.close() } catch (e: Exception) {}
        capturer = null
        try { engine?.close() } catch (e: Exception) {}
        engine = null
        try { worker?.quitSafely() } catch (e: Exception) {}
        worker = null
        workerHandler = null
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (e: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun sleep(ms: Int) {
        try {
            Thread.sleep(ms.toLong())
        } catch (e: InterruptedException) {
            running = false
        }
    }
}
