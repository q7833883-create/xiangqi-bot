package com.xiangqi.autoassist.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * ChessLogic + KotlinEngine 单元测试（JUnit4）。
 * 所有断言数值均已用两套独立参照核对：原始 JS 源码（node 运行）与 Python 逐行移植版。
 */
class LogicAndEngineTest {

    private fun startBoard(): ChessLogic.Board = ChessLogic.fenToBoard(ChessLogic.START_FEN + " w")

    // 1) START_FEN 解析 → boardToFen 往返一致
    @Test
    fun startFen_parse_then_boardToFen_roundTrip() {
        val bd = startBoard()
        assertEquals(ChessLogic.START_FEN + " w - - 0 1", ChessLogic.boardToFen(bd, "w"))
        assertEquals(ChessLogic.START_FEN + " b - - 0 1", ChessLogic.boardToFen(bd, "b"))
    }

    // initialSquares：32 枚子；按“顶部=黑（小写）、底部=红（大写）”落子后应还原出 START_FEN
    @Test
    fun initialSquares_buildsStartFen() {
        val squares = ChessLogic.initialSquares()
        assertEquals(32, squares.size)
        val bd: ChessLogic.Board = Array(10) { arrayOfNulls<String?>(9) }
        for ((r, c, code) in squares) {
            bd[r][c] = if (r <= 4) code.lowercase() else code   // 底部为红方视角
        }
        assertEquals(ChessLogic.START_FEN + " w - - 0 1", ChessLogic.boardToFen(bd, "w"))
        val cnt = ChessLogic.countPieces(bd)
        assertEquals(32, cnt.n)
        assertTrue(cnt.hasK)
        assertTrue(cnt.hask)
    }

    // 2) 初始局面红/黑招法数 = 44（原 JS 与 Python 参照实现均输出 44：
    //    兵5 + 炮24 + 车4 + 马4 + 相4 + 仕2 + 帅1）
    @Test
    fun initialPosition_moveCount_is44() {
        val engine = KotlinEngine()
        val bd = startBoard()
        assertEquals(44, engine.genMoves(bd, red = true).size)
        assertEquals(44, engine.genMoves(bd, red = false).size)
    }

    // 3) toCanonical：旋转180°后 (0,0)<->(9,8) 互换，旋转两次回到自身
    @Test
    fun toCanonical_rotateTwice_returnsOriginal() {
        val bd = startBoard()
        val rot = ChessLogic.toCanonical(bd, bottomIsRed = false)
        assertEquals(bd[9][8], rot[0][0])
        assertEquals(bd[0][0], rot[9][8])
        val rot2 = ChessLogic.toCanonical(rot, bottomIsRed = false)
        assertEquals(ChessLogic.boardKey(bd), ChessLogic.boardKey(rot2))
        // bottomIsRed=true 时原样返回（同一引用，与 JS return screenBoard 一致）
        assertSame(bd, ChessLogic.toCanonical(bd, bottomIsRed = true))
    }

    // 4) engineMoveToScreen 两个方向的已知映射
    //    "h2e2"：红视角 fc=7,fr=7,tc=4,tr=7；黑视角（旋转180°）(2,1)→(2,4)
    @Test
    fun engineMoveToScreen_bothDirections() {
        val red = ChessLogic.engineMoveToScreen("h2e2", bottomIsRed = true)
        assertEquals(7, red.fr); assertEquals(7, red.fc)
        assertEquals(7, red.tr); assertEquals(4, red.tc)
        val black = ChessLogic.engineMoveToScreen("h2e2", bottomIsRed = false)
        assertEquals(2, black.fr); assertEquals(1, black.fc)
        assertEquals(2, black.tr); assertEquals(4, black.tc)
        // "a0a9"（红方底线 a 路车 → 黑方底线）黑方视角旋转结果
        val a = ChessLogic.engineMoveToScreen("a0a9", bottomIsRed = false)
        assertEquals(0, a.fr); assertEquals(8, a.fc)
        assertEquals(9, a.tr); assertEquals(8, a.tc)
    }

    // 5) KotlinEngine 在初始局面（w）能返回长度为 4、格式合法的招法
    @Test
    fun kotlinEngine_startPosition_moveFormat() {
        val mv = KotlinEngine().bestMove(ChessLogic.START_FEN + " w", 1000)
        assertNotNull(mv)
        assertEquals(4, mv!!.length)
        assertTrue(mv[0] in 'a'..'i')
        assertTrue(mv[1] in '0'..'9')
        assertTrue(mv[2] in 'a'..'i')
        assertTrue(mv[3] in '0'..'9')
    }

    // 6) 深度 2 在“红车必吃黑车”局面会选吃子（JS 原引擎同局面返回 a1a2）
    @Test
    fun kotlinEngine_takesFreeRook() {
        val fen = "4k4/9/9/9/4P4/9/9/r8/R8/4K4 w - - 0 1"
        assertEquals("a1a2", KotlinEngine(depth = 2).bestMove(fen, 1000))
    }

    // 补充：黑将被自家 8 个士围死（无任何招法）→ 返回 null（将死/困毙语义）
    @Test
    fun kotlinEngine_noLegalMove_returnsNull() {
        val fen = "3aaa3/3aka3/3aaa3/9/9/9/9/9/9/4K4 b - - 0 1"
        assertNull(KotlinEngine(depth = 2).bestMove(fen, 1000))
    }

    // 补充：fenToBoard 对空位数字压缩的展开
    @Test
    fun fenToBoard_expandsDigits() {
        val bd = ChessLogic.fenToBoard("9/9/9/9/9/9/9/9/9/4K4 w - - 0 1")
        for (r in 0..8) for (c in 0..8) assertNull(bd[r][c])
        assertNull(bd[9][3])
        assertNull(bd[9][5])
        assertEquals("K", bd[9][4])
    }
}
