package com.mandgamit.chess.views

/**
 * 棋盘几何常量（全局唯一来源，类似 C 头文件的 #define）。
 *
 * 所有用到棋盘的界面（对弈 ChessView、编辑 BoardEditorView、连线浮窗 OverlayChessView、
 * 以及以后的棋谱查看界面等）都必须引用这里，禁止各自硬编码。
 *
 * 基准：chessboard.png (1414x1716)，左上棋子中心(93,169)，右下棋子中心(1320,1540)。
 * 换棋盘图时只需改本文件。
 */
object BoardGeometry {
    /** 棋盘图片实际宽度（像素） */
    const val BOARD_WIDTH = 1414

    /** 棋盘图片实际高度（像素） */
    const val BOARD_HEIGHT = 1716

    /** 棋子边长（约为格距的 0.94） */
    const val PIECE_SIZE = 144

    /** 棋盘图内左上角棋子绘制起点 X = 93 - PIECE_SIZE/2 */
    const val X_OFFSET = 21

    /** 棋盘图内左上角棋子绘制起点 Y = 169 - PIECE_SIZE/2 */
    const val Y_OFFSET = 97

    /** 格距 = (1320-93)/8 = 153 */
    const val GRID_INTERVAL = 153

    /** 棋盘第 0 列棋子中心 X（相对棋盘图左边缘） */
    const val GRID_COL0_CENTER = X_OFFSET + PIECE_SIZE / 2 // 93

    /** 棋盘第 0 行棋子中心 Y（相对棋盘图上边缘） */
    const val GRID_ROW0_CENTER = Y_OFFSET + PIECE_SIZE / 2 // 169
}
