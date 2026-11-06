package com.mandgamit.chess.assist.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.mandgamit.chess.R
import com.mandgamit.chess.gamelogic.Piece
import com.mandgamit.chess.utils.ArrowShape
import com.mandgamit.chess.views.BoardGeometry
import kotlin.math.min

/**
 * 悬浮窗里的迷你棋盘：与主界面完全一致的"微缩原版棋盘"
 * （同一张 chessboard.png 底图 + 同一套棋子位图），并把最佳走法画成箭头。
 *
 * 朝向"随动"：[flipped] 为 true（被识别的 App 屏幕上红方在上）时，
 * 迷你棋盘同样红在上、黑在下，与真实屏幕一致；底部永远是被识别屏幕的下方一侧。
 */
class OverlayChessView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 90 个棋子（内部约定 piece[y][x]，EMPTY=0，canonical：红恒在 y=9），null 表示尚无局面 */
    var pieces: IntArray? = null
        set(value) { field = value; invalidate() }

    /** 推荐走法起止（canonical 内部坐标 x,y），null 则不画箭头 */
    var arrowFrom: Pair<Int, Int>? = null
        set(value) { field = value; invalidate() }
    var arrowTo: Pair<Int, Int>? = null
        set(value) { field = value; invalidate() }

    /** 被识别屏幕是否"红在上"（换边/翻转朝向）：true 时迷你棋盘同样红在上 */
    var flipped: Boolean = false
        set(value) { field = value; invalidate() }

    /** 我方是否红方（用于"我方"色条的位置与颜色） */
    var mySideIsRed: Boolean = true
        set(value) { field = value; invalidate() }

    /** 建议已定着（成熟）：true 绿箭头，false 暂定蓝箭头 */
    var mature: Boolean = false
        set(value) { field = value; invalidate() }

    /** 用户点击棋盘切换候选时回调 */
    var onNextCandidate: (() -> Unit)? = null

    /** 是否绘制顶/底"我方"色条（悬浮窗需要；思考细节推演小棋盘不需要） */
    var showSideBar: Boolean = true
        set(value) { field = value; invalidate() }

    // ==== 原版资源（与 ChessView 共用同一套图）====
    private val boardBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.chessboard)
    }
    private val pieceBitmaps: Array<Bitmap> by lazy {
        arrayOf(
            R.drawable.r_shuai, R.drawable.r_shi, R.drawable.r_xiang, R.drawable.r_ma,
            R.drawable.r_ju, R.drawable.r_pao, R.drawable.r_bing,
            R.drawable.b_jiang, R.drawable.b_shi, R.drawable.b_xiang, R.drawable.b_ma,
            R.drawable.b_ju, R.drawable.b_pao, R.drawable.b_zu,
        ).map { BitmapFactory.decodeResource(resources, it) }.toTypedArray()
    }

    // ==== 与 ChessView.java 一致的棋盘几何（统一来源：BoardGeometry.kt）====
    private val boardW = BoardGeometry.BOARD_WIDTH.toFloat()
    private val boardH = BoardGeometry.BOARD_HEIGHT.toFloat()
    private val pieceSize = BoardGeometry.PIECE_SIZE.toFloat()
    private val xOffset = BoardGeometry.X_OFFSET.toFloat()
    private val yOffset = BoardGeometry.Y_OFFSET.toFloat()
    private val interval = BoardGeometry.GRID_INTERVAL.toFloat()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val barPaint = Paint()
    private val arrowPath = Path()
    private val boardDst = RectF()
    private val pieceDst = RectF()

    /** 缩放与棋盘原点（onDraw 中更新） */
    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    /** 按棋盘图宽高比自适应：高度固定时宽度 = 高*boardW/boardH，反之亦然——棋盘填满视图无空隙 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        when {
            hMode == MeasureSpec.EXACTLY && hSize > 0 ->
                setMeasuredDimension((hSize * boardW / boardH).toInt(), hSize)
            wMode == MeasureSpec.EXACTLY && wSize > 0 ->
                setMeasuredDimension(wSize, (wSize * boardH / boardW).toInt())
            else -> super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** canonical (x,y) -> 画布交点中心（按屏幕朝向映射） */
    private fun center(x: Int, y: Int): Pair<Float, Float> {
        val sx = if (flipped) 8 - x else x
        val sy = if (flipped) 9 - y else y
        return Pair(
            originX + (xOffset + sx * interval + pieceSize / 2) * scale,
            originY + (yOffset + sy * interval + pieceSize / 2) * scale,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        scale = min(width / boardW, height / boardH)
        originX = (width - boardW * scale) / 2f
        originY = (height - boardH * scale) / 2f
        boardDst.set(originX, originY, originX + boardW * scale, originY + boardH * scale)
        canvas.drawBitmap(boardBitmap, null, boardDst, bitmapPaint)

        drawPieces(canvas)
        drawArrow(canvas)
        if (showSideBar) drawSideLabel(canvas)
    }

    private fun drawPieces(canvas: Canvas) {
        val arr = pieces ?: return
        val size = pieceSize * scale
        for (y in 0..9) for (x in 0..8) {
            val p = arr[y * 9 + x]
            if (p !in 1..14) continue
            val (cx, cy) = center(x, y)
            pieceDst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            canvas.drawBitmap(pieceBitmaps[p - 1], null, pieceDst, bitmapPaint)
        }
    }

    private fun drawArrow(canvas: Canvas) {
        val f = arrowFrom ?: return
        val t = arrowTo ?: return
        val (fx, fy) = center(f.first, f.second)
        val (tx, ty) = center(t.first, t.second)
        // 我方着法箭头：暂定=蓝，定着（成熟）=绿
        val strokeColor = if (mature) 0xFF2E7D32.toInt() else 0xFF2B6FFF.toInt()
        val fillColor = if (mature) 0xFFA5D6A7.toInt() else 0xFF8AB6FF.toInt()
        // ArrowShape 默认参数基于 1240x1340 基准坐标系，按本视图缩放
        val arrow = ArrowShape(60f, 120f, 0f, 80f * scale, 26f * scale, 10f * scale)
        arrow.getTransformedPath(arrowPath, fx, fy, tx, ty)
        arrowFill.color = fillColor
        arrowStroke.color = strokeColor
        arrowStroke.strokeWidth = 1f * scale.coerceAtLeast(0.75f)
        canvas.drawPath(arrowPath, arrowFill)
        canvas.drawPath(arrowPath, arrowStroke)
    }

    /** 顶部/底部色条指示"我方"所在侧与颜色（随动：底部=被识别屏幕下方一侧） */
    private fun drawSideLabel(canvas: Canvas) {
        val bottomIsRed = !flipped
        val myAtBottom = mySideIsRed == bottomIsRed
        val barH = 6f * scale
        val barColor = if (mySideIsRed) Color.parseColor("#E53935") else Color.parseColor("#37474F")
        barPaint.color = barColor
        val top = if (myAtBottom) boardDst.bottom - barH else boardDst.top
        canvas.drawRect(boardDst.left, top, boardDst.right, top + barH, barPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onNextCandidate?.invoke()
            return true
        }
        return true
    }
}
