package com.mandgamit.chess.adapters

import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.mandgamit.chess.R
import com.mandgamit.chess.controllers.GameController

/**
 * 对弈界面 P3 信息区的数据填充：
 * - 棋谱页（红方着法红色、黑方着法黑色）
 * - 评估曲线页（评分单位换算为厘，如0.30显示为30）
 * - 引擎思考页（深度/节点/时间 + 主PV着法链）
 */
class HistoryAndTrendAdapter(
    private val context: Context,
    private val movesContainer: LinearLayout,
    private val movesScrollView: ScrollView,
    private val thinkingMovesContainer: LinearLayout,
    private val depthText: TextView,
    private val nodesText: TextView,
    private val timeText: TextView,
    private val chart: LineChart,
    private val controller: GameController
) {

    private val redColor = 0xFFC23B33.toInt()
    private val blackColor = 0xFF222222.toInt()

    /**
     * 曲线视角：棋盘下方是否为红方。
     * true（默认）：红优为正、黑优为负；false（翻转视角、黑方在下）：黑优为正。
     */
    var bottomSideRed: Boolean = true

    /**
     * 双击"思考"面板引擎着法时回调（弹出思考细节推演）。
     * 无 PV 着法时面板上没有任何着法视图，天然不会触发。
     */
    var onThinkingMoveDoubleClick: (() -> Unit)? = null

    fun update() {
        updateMoveHistory()
        updateEvaluationChart()
        updateThinkingInfo()
    }

    // ---------------- 棋谱页 ----------------
    private fun updateMoveHistory() {
        movesContainer.removeAllViews()
        val history = controller.game.history

        var rowNo = 1
        var i = 0
        while (i < history.size) {
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_move_row, movesContainer, false)
            val indexView = row.findViewById<TextView>(R.id.move_index)
            val redView = row.findViewById<TextView>(R.id.move_red)
            val blackView = row.findViewById<TextView>(R.id.move_black)
            indexView.text = "$rowNo."

            if (!history[i].isRedMove) {
                // 黑方先行的情况：红方位置留空
                blackView.text = history[i].chsString
                i++
            } else {
                redView.text = history[i].chsString
                i++
                if (i < history.size && !history[i].isRedMove) {
                    blackView.text = history[i].chsString
                    i++
                }
            }
            movesContainer.addView(row)
            rowNo++
        }

        // 始终滚动到最新一着
        movesScrollView.post { movesScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------- 评估曲线页 ----------------
    private fun updateEvaluationChart() {
        // 引擎评分以红方为正；翻转视角后改以棋盘下方阵营为正（下方为黑时取反）
        val perspectiveSign = if (bottomSideRed) 1f else -1f
        val entries = ArrayList<Entry>()
        for (i in 0 until controller.game.history.size) {
            // 引擎eval单位为"个兵"，乘以100换算成厘（与"红优30"的口径一致）
            val score = controller.game.history[i].move.board.score * 100 * perspectiveSign
            entries.add(Entry(i.toFloat(), score))
        }
        if (controller.game.currentBoard.score != 0.toFloat()) {
            entries.add(
                Entry(
                    controller.game.history.size.toFloat(),
                    controller.game.currentBoard.score * 100 * perspectiveSign
                )
            )
        }

        val label = if (bottomSideRed) "红方局势评估" else "黑方局势评估"
        val dataSet = LineDataSet(entries, label)
        dataSet.color = Color.RED
        dataSet.setCircleColors(Color.RED)
        dataSet.setDrawCircles(false)
        dataSet.setDrawFilled(true)
        dataSet.fillColor = Color.RED
        dataSet.fillAlpha = 20
        dataSet.lineWidth = 1.6f

        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    // ---------------- 引擎思考页 ----------------
    private fun updateThinkingInfo() {
        depthText.text = controller.thinkDepth.toString()
        nodesText.text = formatNodes(controller.thinkNodes)
        timeText.text = formatTime(controller.thinkTime)

        thinkingMovesContainer.removeAllViews()
        val moves = controller.thinkPvMoves
        if (moves.isEmpty()) {
            // 引擎尚未输出PV时保持空白，不显示占位提示语
            return
        }

        // 固定每行4个着法：红黑红黑各占等宽一列，上下行颜色严格对齐
        moves.chunked(4).forEachIndexed { rowIndex, rowMoves ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0 until 4) {
                val moveView = TextView(context).apply {
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                    textSize = 16f
                    setPadding(dpToPx(2), dpToPx(5), dpToPx(2), dpToPx(5))
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    if (col < rowMoves.size) {
                        val globalIndex = rowIndex * 4 + col
                        val isRed = controller.thinkPvIsRed.getOrElse(globalIndex) {
                            globalIndex % 2 == 0
                        }
                        text = rowMoves[col]
                        setTextColor(if (isRed) redColor else blackColor)
                        onThinkingMoveDoubleClick?.let { action ->
                            // 双击引擎着法 → 弹出思考细节推演小棋盘。
                            // 所有模式（人机大战/盲棋训练等）均生效，无模式限制。
                            // onDown 返回 true 保证 GestureDetector 在 ScrollView 内也能稳定识别双击
                            val detector = GestureDetector(context,
                                object : GestureDetector.SimpleOnGestureListener() {
                                    override fun onDown(e: MotionEvent): Boolean = true
                                    override fun onDoubleTap(e: MotionEvent): Boolean {
                                        action()
                                        return true
                                    }
                                })
                            setOnTouchListener { _, event ->
                                detector.onTouchEvent(event)
                                true
                            }
                        }
                    }
                }
                row.addView(moveView)
            }
            thinkingMovesContainer.addView(row)
        }
    }

    private fun formatNodes(nodes: Long): String =
        when {
            nodes >= 1_000_000 -> "${nodes / 1_000_000}M"
            nodes >= 1_000 -> "${nodes / 1_000}K"
            else -> nodes.toString()
        }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000.0
        return if (seconds >= 100) {
            // 超过100秒只保留整数秒，避免"100.1秒"换行
            "${seconds.toInt()}秒"
        } else {
            String.format("%.1f秒", seconds)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
