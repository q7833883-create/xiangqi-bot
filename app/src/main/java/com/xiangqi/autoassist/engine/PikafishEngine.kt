package com.xiangqi.autoassist.engine

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pikafish UCI 子进程引擎（移植自 engine_server.py 的桥接逻辑，去掉 HTTP 层）。
 * 二进制来自 APK 内置的 jniLibs（nativeLibraryDir），NNUE 权重已拷贝到应用私有目录。
 */
class PikafishEngine(
    private val binPath: String,
    private val nnuePath: String?,
    private val threads: Int = 2,
    private val hashMb: Int = 128
) : Engine {

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var lines: MutableList<String> = mutableListOf()
    private val lock = ReentrantLock()

    private fun alive(): Boolean {
        val p = proc ?: return false
        return p.isAlive
    }

    private fun ensureStarted() {
        if (!alive()) start()
    }

    private fun start() {
        val f = File(binPath)
        if (!f.exists()) throw RuntimeException("找不到引擎二进制: $binPath")
        val pb = ProcessBuilder(binPath)
        pb.redirectErrorStream(true)
        val p = pb.start()
        proc = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        val newLines: MutableList<String> = mutableListOf()
        lines = newLines
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        val t = Thread {
            try {
                var line: String? = reader.readLine()
                while (line != null) {
                    synchronized(newLines) { newLines.add(line!!) }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                // 进程退出或 IO 中断，静默结束读线程
            }
        }
        t.isDaemon = true
        t.start()
        send("uci")
        waitFor("uciok", 8000)
        if (nnuePath != null && File(nnuePath).exists()) {
            send("setoption name EvalFile value $nnuePath")
        }
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("isready")
        waitFor("readyok", 8000)
    }

    private fun send(cmd: String) {
        val w = writer ?: throw RuntimeException("引擎未启动")
        w.write(cmd)
        w.write("\n")
        w.flush()
    }

    private fun waitFor(token: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(lines) {
                for (i in lines.indices) {
                    if (lines[i].contains(token)) {
                        lines.subList(0, i + 1).clear()
                        return
                    }
                }
            }
            if (!alive() && proc != null) throw RuntimeException("引擎进程退出")
            Thread.sleep(50)
        }
        throw RuntimeException("引擎等待 $token 超时")
    }

    override fun bestMove(fen: String, movetimeMs: Int): String? {
        lock.withLock {
            ensureStarted()
            send("position fen $fen")
            send("go movetime $movetimeMs")
            val deadline = System.currentTimeMillis() + movetimeMs + 20000L
            while (System.currentTimeMillis() < deadline) {
                synchronized(lines) {
                    for (i in lines.indices) {
                        val ln = lines[i]
                        if (ln.startsWith("bestmove")) {
                            lines.subList(0, i + 1).clear()
                            val parts = ln.trim().split(" ")
                            return if (parts.size > 1) parts[1] else null
                        }
                    }
                }
                if (!alive() && proc != null) throw RuntimeException("引擎进程退出")
                Thread.sleep(60)
            }
            throw RuntimeException("引擎无 bestmove")
        }
    }

    override fun close() {
        try {
            send("quit")
        } catch (e: Exception) {
        }
        try {
            proc?.destroy()
        } catch (e: Exception) {
        }
        proc = null
        writer = null
    }
}
