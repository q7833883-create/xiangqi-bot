package com.xiangqi.autoassist.engine

/**
 * 中国象棋纯逻辑（自 AutoX.js 脚本 xiangqi_auto.js 逐行忠实移植）。
 *
 * 棋盘统一为“屏幕视角”：board[10][9]，行 0 = 屏幕顶部，列 0..8；
 * 元素为 FEN 字母（大写=红，小写=黑）或 null（空位）。
 * 引擎内部则为“标准视角”：行 0 = 黑方底线，行 9 = 红方底线。
 *
 * 纯 Kotlin stdlib 实现，不依赖 android.*。
 */
/** 棋盘类型：board[10][9]，元素为 FEN 字母（大写=红，小写=黑）或 null */
typealias Board = Array<Array<String?>>

object ChessLogic {

    const val START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

    data class PieceCount(val n: Int, val hasK: Boolean, val hask: Boolean)

    data class MoveCells(val fr: Int, val fc: Int, val tr: Int, val tc: Int)

    /**
     * 标准初始布局（屏幕视角，行0=顶部）：(行, 列, 棋子代号)。
     * 棋子代号不分红黑用大写占位（与 JS 原版一致）。
     * 对应 JS: function initialSquares()
     */
    fun initialSquares(): List<Triple<Int, Int, String>> {
        val sq = ArrayList<Triple<Int, Int, String>>(32)
        val back = arrayOf("R", "N", "B", "A", "K", "A", "B", "N", "R")
        for (c in 0..8) {
            sq.add(Triple(0, c, back[c]))   // 顶部底线
            sq.add(Triple(9, c, back[c]))   // 底部底线
        }
        sq.add(Triple(2, 1, "C")); sq.add(Triple(2, 7, "C"))   // 顶部炮
        sq.add(Triple(7, 1, "C")); sq.add(Triple(7, 7, "C"))   // 底部炮
        val pc = intArrayOf(0, 2, 4, 6, 8)
        for (i in pc.indices) {
            sq.add(Triple(3, pc[i], "P"))   // 顶部兵/卒
            sq.add(Triple(6, pc[i], "P"))   // 底部兵/卒
        }
        return sq
    }

    /**
     * 屏幕视角 → 标准局面（红方在下视角）。底部是黑方时需旋转 180°。
     * 对应 JS: function toCanonical(screenBoard)（STORE.bottomIsRed 改为显式参数）
     */
    fun toCanonical(screenBoard: Board, bottomIsRed: Boolean): Board {
        if (bottomIsRed) return screenBoard
        val out: Board = Array(10) { arrayOfNulls<String?>(9) }
        for (r in 0..9) {
            for (c in 0..8) out[r][c] = screenBoard[9 - r][8 - c]
        }
        return out
    }

    /**
     * 空位用数字压缩；sideToMove: "w"/"b"。结果形如 "<棋盘> w - - 0 1"。
     * 对应 JS: function boardToFen(board, sideToMove)
     */
    fun boardToFen(board: Board, sideToMove: String): String {
        val rows = ArrayList<String>(10)
        for (r in 0..9) {
            val s = StringBuilder()
            var empty = 0
            for (c in 0..8) {
                val p = board[r][c]
                if (p == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        s.append(empty)
                        empty = 0
                    }
                    s.append(p)
                }
            }
            if (empty > 0) s.append(empty)
            rows.add(s.toString())
        }
        return rows.joinToString("/") + " " + sideToMove + " - - 0 1"
    }

    /** 对应 JS: function boardKey(board)：逐格拼接，空位记 "."，用于局面变化检测 */
    fun boardKey(board: Board): String {
        val s = StringBuilder(90)
        for (r in 0..9) for (c in 0..8) s.append(board[r][c] ?: ".")
        return s.toString()
    }

    /** 对应 JS: function countPieces(board)：数子并判断双方将/帅是否仍在 */
    fun countPieces(board: Board): PieceCount {
        var n = 0
        var hasK = false
        var hask = false
        for (r in 0..9) for (c in 0..8) {
            val p = board[r][c]
            if (p != null) {
                n++
                if (p == "K") hasK = true
                if (p == "k") hask = true
            }
        }
        return PieceCount(n, hasK, hask)
    }

    /**
     * 引擎招法（如 "h2e2"：file a..i = 红方视角列 0..8，rank 0..9，0 = 红方底线）
     * → 屏幕视角行列。bottomIsRed=false 时整体旋转 180°（(9-fr, 8-fc)）。
     * 对应 JS: function engineMoveToScreen(move)（STORE.bottomIsRed 改为显式参数）
     */
    fun engineMoveToScreen(move: String, bottomIsRed: Boolean): MoveCells {
        val fc = move[0].code - 97          // move.charCodeAt(0) - 97
        val fr = 9 - (move[1] - '0')        // 9 - parseInt(move.charAt(1))
        val tc = move[2].code - 97
        val tr = 9 - (move[3] - '0')
        return if (bottomIsRed) MoveCells(fr, fc, tr, tc)
        else MoveCells(9 - fr, 8 - fc, 9 - tr, 8 - tc)
    }

    /**
     * 从 FEN 棋盘部分解析出 10×9 棋盘（engineJs 内部解析逻辑抽出，供测试与内置引擎复用）。
     * 仅取 FEN 第一段（棋盘部分），空位数字展开为 null。
     */
    fun fenToBoard(fen: String): Board {
        val parts = fen.split(" ")[0].split("/")
        val bd: Board = Array(10) { arrayOfNulls<String?>(9) }
        for (r in 0..9) {
            var c = 0
            for (ch in parts[r]) {
                if (ch.isDigit()) {
                    var e = ch - '0'                    // JS: parseInt(ch)
                    while (e > 0 && c < 9) {
                        bd[r][c] = null
                        c++
                        e--
                    }
                } else {
                    if (c < 9) {
                        bd[r][c] = ch.toString()
                        c++
                    }
                }
            }
        }
        return bd
    }
}
