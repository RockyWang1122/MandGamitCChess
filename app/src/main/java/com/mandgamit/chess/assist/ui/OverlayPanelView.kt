package com.mandgamit.chess.assist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mandgamit.chess.R
import kotlin.math.abs

/** 悬浮窗显示模型 */
class OverlayModel(
    /** 单行信息：状态/行权 + 建议或预测（服务端拼好） */
    val statusText: String = "识别中…",
    /** canonical 布局 90 子（红恒在 y=9），null 表示尚无局面 */
    val pieces: IntArray? = null,
    /** 推荐走法起止（canonical 内部坐标 x,y） */
    val arrowFrom: Pair<Int, Int>? = null,
    val arrowTo: Pair<Int, Int>? = null,
    /** 被识别屏幕红方在上（迷你棋盘随动：同样红在上显示） */
    val flipped: Boolean = false,
    val mySideIsRed: Boolean = true,
    /** 建议已"定着"（成熟）：箭头用绿色；false 为暂定（蓝箭头） */
    val mature: Boolean = false,
    /** 已停止指导（按钮显示“继续”） */
    val paused: Boolean = false,
    /** 当前轮到我方走（软件自动维护，单选框随动显示；用户点选可纠正） */
    val myTurn: Boolean = true,
)

enum class OverlayAction {
    /** 停止/继续指导 */
    PAUSE,
    /** 关闭连线（停止录取+移除面板） */
    CLOSE,
    /** 切换候选着法（点小棋盘） */
    CYCLE_CANDIDATE,
    /** 更新棋局：按当前识别局面重建/重分析（不改行棋先后） */
    UPDATE_BOARD,
    /** 手动纠正行棋方：轮到我方 / 轮到对方 */
    SET_TURN_MINE,
    SET_TURN_OPP,
}

class OverlayPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val chess: OverlayChessView
    private val status: TextView
    private val btnPause: TextView
    private val btnClose: TextView
    private val radioMine: android.widget.RadioButton
    private val radioOpp: android.widget.RadioButton

    var onAction: ((OverlayAction) -> Unit)? = null
    /** 拖动开始/结束（用于抑制取帧时的面板隐藏动作） */
    var onDragStateChange: ((Boolean) -> Unit)? = null

    // 程序性同步单选框状态（软件自动维护行棋方）时抑制监听回调，只有用户点选才上报
    private var syncingRadios = false

    // 关闭二次确认：误触风险大，首次点按进入待确认态，3 秒内再点才真正关闭
    private var closeArmed = false
    private val closeNormalColor: Int
    private val closeReset = Runnable { resetCloseState() }

    private fun resetCloseState() {
        closeArmed = false
        btnClose.text = "关闭"
        btnClose.setTextColor(closeNormalColor)
    }

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var onMove: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_panel, this, true)
        chess = findViewById(R.id.overlay_chess)
        status = findViewById(R.id.overlay_status)
        btnPause = findViewById(R.id.overlay_btn_pause)
        btnClose = findViewById(R.id.overlay_btn_close)
        closeNormalColor = btnClose.currentTextColor

        findViewById<TextView>(R.id.overlay_btn_pause).setOnClickListener { onAction?.invoke(OverlayAction.PAUSE) }
        findViewById<TextView>(R.id.overlay_btn_turn).setOnClickListener { onAction?.invoke(OverlayAction.UPDATE_BOARD) }
        radioMine = findViewById(R.id.overlay_radio_mine)
        radioOpp = findViewById(R.id.overlay_radio_opp)
        radioMine.setOnCheckedChangeListener { _, checked ->
            if (!syncingRadios && checked) onAction?.invoke(OverlayAction.SET_TURN_MINE)
        }
        radioOpp.setOnCheckedChangeListener { _, checked ->
            if (!syncingRadios && checked) onAction?.invoke(OverlayAction.SET_TURN_OPP)
        }
        btnClose.setOnClickListener {
            if (!closeArmed) {
                closeArmed = true
                btnClose.text = "确认关闭?"
                btnClose.setTextColor(0xFFFF5252.toInt())
                postDelayed(closeReset, 3000)
            } else {
                removeCallbacks(closeReset)
                closeReset.run()
                onAction?.invoke(OverlayAction.CLOSE)
            }
        }
        chess.onNextCandidate = { onAction?.invoke(OverlayAction.CYCLE_CANDIDATE) }
    }

    /**
     * 心跳：MediaProjection 在屏幕静止时不出新帧，识别循环（由 onImageAvailable 驱动）会停摆。
     * 让一个 1dp 微点持续脉冲 alpha（2%~5%，肉眼不可见），强制合成器持续产帧。
     */
    private var heartbeat: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val dot = findViewById<android.view.View>(R.id.overlay_heartbeat) ?: return
        heartbeat = ValueAnimator.ofFloat(0.02f, 0.05f).apply {
            duration = 240
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        heartbeat?.cancel()
        heartbeat = null
        super.onDetachedFromWindow()
    }

    fun render(model: OverlayModel) {
        status.text = model.statusText
        btnPause.text = if (model.paused) "继续" else "停止"
        chess.pieces = model.pieces
        chess.arrowFrom = model.arrowFrom
        chess.arrowTo = model.arrowTo
        chess.flipped = model.flipped
        chess.mySideIsRed = model.mySideIsRed
        chess.mature = model.mature
        // 单选框随动：行棋方由软件自动维护，这里只做显示同步（不触发纠错回调）
        syncingRadios = true
        radioMine.isChecked = model.myTurn
        radioOpp.isChecked = !model.myTurn
        syncingRadios = false
    }

    /**
     * 全区域拖动：按住面板任意位置移动即可拖动（按钮点击不受影响——小于滑动阈值的
     * 点按会正常分发为点击）。[onMove] 在拖动结束时调用（用于持久化位置）。
     */
    fun setDragHandle(params: WindowManager.LayoutParams, wm: WindowManager, onMove: () -> Unit) {
        this.params = params
        this.wm = wm
        this.onMove = onMove
    }

    /** MOVE 超过阈值后拦截事件，交给 onTouchEvent 移动整个窗口 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = params?.x ?: 0
                startY = params?.y ?: 0
                dragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop)) {
                    dragging = true
                    onDragStateChange?.invoke(true)
                    return true
                }
                return dragging
            }
        }
        return false
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val p = params
            if (dragging && p != null) {
                p.x = startX + (ev.rawX - downX).toInt()
                p.y = startY + (ev.rawY - downY).toInt()
                wm?.updateViewLayout(this, p)
                return true
            }
        } else if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                dragging = false
                onDragStateChange?.invoke(false)
                onMove?.invoke()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
