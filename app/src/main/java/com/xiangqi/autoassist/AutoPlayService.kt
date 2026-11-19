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
 * 也承担「提取模板」的一次性任务（mode=extract）。
 */
class AutoPlayService : Service() {

    companion object {
        const val MODE_PLAY = "play"
        const val MODE_EXTRACT = "extract"
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
        Handler(t.looper).post {
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
            if (mode == MODE_EXTRACT) {
                extractFlow()
            } else {
                playFlow(myTurnInit)
            }
        } catch (e: Exception) {
            setMsg("出错: ${e.message}")
            notify("出错: ${e.message}")
        } finally {
            running = false
            stopAll()
        }
    }

    private fun extractFlow() {
        for (i in 10 downTo 1) {
            notify("提取模板：${i}s 后识别，请切到棋盘新对局界面")
            sleep(1000)
            if (!running) return
        }
        val g = prefs.grid
        if (g == null) {
            notify("先做「校准棋盘」")
            setMsg("缺少校准")
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
            notify("截图失败，请重试")
            setMsg("截图失败")
            return
        }
        val result = TemplateStore.extractFromScreenshot(this, bmp, g, prefs)
        bmp.recycle()
        notify(result.second)
        setMsg(result.second)
    }

    private fun playFlow(myTurnInit: Boolean) {
        val bottomIsRed = prefs.bottomIsRed
        val mySide = if (bottomIsRed) "w" else "b"
        notify("等待棋盘…请切换到象棋 App 的棋盘界面（音量上键停止）")
        setMsg("等待棋盘…")
        // 等待出现真实棋盘（双方都有将帅且棋子数足够）
        var first = tryRecognize()
        var waited = 0
        while (running && waited < 120_000) {
            val b = tryRecognize()
            if (b != null) {
                val chk = ChessLogic.countPieces(b)
                if (chk.hasK && chk.hask && chk.n >= 6) {
                    first = b
                    break
                }
            }
            sleep(if (prefs.pollMs < 300) 300 else prefs.pollMs)
            waited += if (prefs.pollMs < 300) 300 else prefs.pollMs
        }
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
            val assetSize = assets.openFd("pikafish.nnue").use { it.length }
            if (!dst.exists() || dst.length() != assetSize) {
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
        try { capturer?.close() } catch (e: Exception) {}
        capturer = null
        try { engine?.close() } catch (e: Exception) {}
        engine = null
        try { worker?.quitSafely() } catch (e: Exception) {}
        worker = null
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
