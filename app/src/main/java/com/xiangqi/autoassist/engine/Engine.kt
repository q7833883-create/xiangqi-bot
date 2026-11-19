package com.xiangqi.autoassist.engine

interface Engine {
    /** 返回 UCI 式招法 "h2e2"，无招（将死/困毙）返回 null；失败抛异常由调用方处理 */
    fun bestMove(fen: String, movetimeMs: Int): String?
    fun close() {}
}
