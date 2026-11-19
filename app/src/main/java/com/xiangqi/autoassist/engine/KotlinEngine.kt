package com.xiangqi.autoassist.engine

import kotlin.math.abs

/**
 * JS 兜底引擎的忠实移植（xiangqi_auto.js「引擎：JS 兜底」整节）：
 * 负极大值 + αβ 剪枝 + 吃子优先排序。
 *
 * 棋盘为标准视角（行0=黑方底线，行9=红方底线）；红大写、黑小写。
 * movetimeMs 参数保留但忽略（本引擎按构造时的 depth 深度搜索）。
 */
class KotlinEngine(private val depth: Int = 3) : Engine {

    /** 内部招法表示，对应 JS 的 [fr, fc, tr, tc] 四元组 */
    data class Move(val fr: Int, val fc: Int, val tr: Int, val tc: Int)

    /** 对应 JS: var VAL = { K: 100000, R: 600, N: 285, C: 300, B: 120, A: 120, P: 60 } */
    private val VAL: Map<String, Int> = mapOf(
        "K" to 100000,
        "R" to 600,
        "N" to 285,
        "C" to 300,
        "B" to 120,
        "A" to 120,
        "P" to 60
    )

    /** JS: function isRed(p) { return p && p == p.toUpperCase(); }（棋子字母均为 ASCII） */
    private fun isRed(p: String?): Boolean {
        if (p == null || p.isEmpty()) return false
        return p[0] in 'A'..'Z'
    }

    /** JS: p.toUpperCase()（单字符字母，ASCII 下与 locale 无关） */
    private fun upper(p: String): String = p.uppercase()

    /** JS: function inPalace(r, c, red)：九宫判定（红 7..9 行，黑 0..2 行，列 3..5） */
    private fun inPalace(r: Int, c: Int, red: Boolean): Boolean =
        c in 3..5 && (if (red) r in 7..9 else r in 0..2)

    /** JS: var tgt = function (tr, tc) { var q = bd[tr][tc]; return !q || isRed(q) != red; } */
    private fun tgt(bd: Board, red: Boolean, tr: Int, tc: Int): Boolean {
        val q = bd[tr][tc]
        return q == null || isRed(q) != red
    }

    /** JS: function genMoves(bd, red)：生成所有伪合法招法（含飞将），不做送将过滤（由搜索惩罚） */
    fun genMoves(bd: Board, red: Boolean): MutableList<Move> {
        val moves = ArrayList<Move>()
        for (r in 0..9) for (c in 0..8) {
            val p = bd[r][c]
            if (p == null || isRed(p) != red) continue
            val up = upper(p)
            when (up) {
                "K" -> {
                    val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
                    for (d in dirs) {
                        val r1 = r + d[0]
                        val c1 = c + d[1]
                        if (r1 in 0..9 && c1 in 0..8 && inPalace(r1, c1, red) && tgt(bd, red, r1, c1)) {
                            moves.add(Move(r, c, r1, c1))
                        }
                    }
                    // 飞将
                    val step = if (red) -1 else 1
                    var rr = r + step
                    while (rr in 0..9) {
                        val q2 = bd[rr][c]
                        if (q2 != null) {
                            if (upper(q2) == "K" && isRed(q2) != red) moves.add(Move(r, c, rr, c))
                            break
                        }
                        rr += step
                    }
                }
                "A" -> {
                    val da = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
                    for (a in da) {
                        val r2 = r + a[0]
                        val c2 = c + a[1]
                        if (r2 in 0..9 && c2 in 0..8 && inPalace(r2, c2, red) && tgt(bd, red, r2, c2)) {
                            moves.add(Move(r, c, r2, c2))
                        }
                    }
                }
                "B" -> {
                    val db = arrayOf(intArrayOf(2, 2), intArrayOf(2, -2), intArrayOf(-2, 2), intArrayOf(-2, -2))
                    for (e in db) {
                        val r3 = r + e[0]
                        val c3 = c + e[1]
                        if (r3 < 0 || r3 > 9 || c3 < 0 || c3 > 8) continue
                        if (if (red) r3 < 5 else r3 > 4) continue                 // 不过河
                        if (bd[r + e[0] / 2][c + e[1] / 2] != null) continue      // 塞象眼
                        if (tgt(bd, red, r3, c3)) moves.add(Move(r, c, r3, c3))
                    }
                }
                "N" -> {
                    val dn = arrayOf(
                        intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(2, -1), intArrayOf(2, 1),
                        intArrayOf(-1, -2), intArrayOf(-1, 2), intArrayOf(1, -2), intArrayOf(1, 2)
                    )
                    for (n in dn) {
                        val r4 = r + n[0]
                        val c4 = c + n[1]
                        if (r4 < 0 || r4 > 9 || c4 < 0 || c4 > 8) continue
                        val legR = r + (if (abs(n[0]) == 2) n[0] / 2 else 0)
                        val legC = c + (if (abs(n[1]) == 2) n[1] / 2 else 0)
                        if (bd[legR][legC] != null) continue                      // 蹩马腿
                        if (tgt(bd, red, r4, c4)) moves.add(Move(r, c, r4, c4))
                    }
                }
                "R", "C" -> {
                    val dl = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
                    for (l in dl) {
                        var r5 = r + l[0]
                        var c5 = c + l[1]
                        var screen = 0
                        while (r5 in 0..9 && c5 in 0..8) {
                            val q3 = bd[r5][c5]
                            if (up == "R") {
                                if (q3 == null) {
                                    moves.add(Move(r, c, r5, c5))
                                } else {
                                    if (isRed(q3) != red) moves.add(Move(r, c, r5, c5))
                                    break
                                }
                            } else {
                                if (screen == 0) {
                                    if (q3 == null) moves.add(Move(r, c, r5, c5)) else screen = 1
                                } else {
                                    if (q3 != null) {
                                        if (isRed(q3) != red) moves.add(Move(r, c, r5, c5))
                                        break
                                    }
                                }
                            }
                            r5 += l[0]
                            c5 += l[1]
                        }
                    }
                }
                "P" -> {
                    val fwd = if (red) -1 else 1
                    val r6 = r + fwd
                    if (r6 in 0..9 && tgt(bd, red, r6, c)) moves.add(Move(r, c, r6, c))
                    val crossed = if (red) r <= 4 else r >= 5
                    if (crossed) {
                        if (c > 0 && tgt(bd, red, r, c - 1)) moves.add(Move(r, c, r, c - 1))
                        if (c < 8 && tgt(bd, red, r, c + 1)) moves.add(Move(r, c, r, c + 1))
                    }
                }
            }
        }
        return moves
    }

    /** JS: function evaluate(bd)：红方视角子力估值（兵过河 +60、中兵 +10） */
    fun evaluate(bd: Board): Int {
        var s = 0
        for (r in 0..9) for (c in 0..8) {
            val p = bd[r][c] ?: continue
            val up = upper(p)
            var v = VAL[up] ?: 0
            if (up == "P") {
                val crossed = if (isRed(p)) r <= 4 else r >= 5
                if (crossed) v += 60
                if (c == 4) v += 10
            }
            s += if (isRed(p)) v else -v
        }
        return s
    }

    /** JS: moves.sort((a, b2) => vb - va)：按吃掉的子力价值降序（稳定排序，空吃为 0） */
    private fun capturedValue(bd: Board, mv: Move): Int {
        val q = bd[mv.tr][mv.tc] ?: return 0
        return VAL[upper(q)] ?: 0
    }

    /**
     * JS: function jsSearch(bd, depth, alpha, beta, red)
     * 用 Double 承载 α/β/分值，与 JS 的 Number（含 ±Infinity）语义完全一致。
     */
    private fun jsSearch(bd: Board, depth: Int, alphaIn: Double, beta: Double, red: Boolean): Double {
        if (depth == 0) return (if (red) 1.0 else -1.0) * evaluate(bd)
        val moves = genMoves(bd, red)
        if (moves.isEmpty()) return -99999.0 + (10 - depth)
        // 吃子优先排序
        moves.sortWith(compareByDescending<Move> { capturedValue(bd, it) })
        var best = Double.NEGATIVE_INFINITY
        var alpha = alphaIn
        for (mv in moves) {
            val saved = bd[mv.tr][mv.tc]
            bd[mv.tr][mv.tc] = bd[mv.fr][mv.fc]
            bd[mv.fr][mv.fc] = null
            val sc = -jsSearch(bd, depth - 1, -beta, -alpha, !red)
            bd[mv.fr][mv.fc] = bd[mv.tr][mv.tc]
            bd[mv.tr][mv.tc] = saved
            if (sc > best) best = sc
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    /**
     * JS: function engineJs(fen)（CONFIG.JS_ENGINE_DEPTH 改为构造参数 depth）。
     * 根节点对每个招法做 -jsSearch(depth-1)，取最高分；无招返回 null。
     */
    override fun bestMove(fen: String, movetimeMs: Int): String? {
        val bd = ChessLogic.fenToBoard(fen)
        val red = fen.split(" ").getOrElse(1) { "" } == "w"
        val moves = genMoves(bd, red)
        var bestScore = Double.NEGATIVE_INFINITY
        var bestMove: Move? = null
        for (mv in moves) {
            val saved = bd[mv.tr][mv.tc]
            bd[mv.tr][mv.tc] = bd[mv.fr][mv.fc]
            bd[mv.fr][mv.fc] = null
            val sc = -jsSearch(bd, depth - 1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, !red)
            bd[mv.fr][mv.fc] = bd[mv.tr][mv.tc]
            bd[mv.tr][mv.tc] = saved
            if (sc > bestScore) {
                bestScore = sc
                bestMove = mv
            }
        }
        val bm = bestMove ?: return null
        fun sq(r: Int, c: Int): String = "abcdefghi"[c].toString() + (9 - r).toString()
        return sq(bm.fr, bm.fc) + sq(bm.tr, bm.tc)
    }
}
