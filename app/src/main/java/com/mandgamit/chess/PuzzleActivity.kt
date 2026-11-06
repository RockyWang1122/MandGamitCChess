package com.mandgamit.chess

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.XAxis
import com.mandgamit.chess.adapters.HistoryAndTrendAdapter
import com.mandgamit.chess.cloudbook.CloudBookClient
import com.mandgamit.chess.cloudbook.CloudBookResult
import com.mandgamit.chess.cloudbook.CloudQueryState
import com.mandgamit.chess.controllers.ControllerListener
import com.mandgamit.chess.controllers.GameController
import com.mandgamit.chess.databinding.ActivityPuzzleBinding
import com.mandgamit.chess.gamelogic.GameStatus
import com.mandgamit.chess.gamelogic.Move
import com.mandgamit.chess.views.ChessView

/**
 * 残局/进阶做题页：
 * 入口由 [StudyListActivity] 传入 assets 相对路径（EXTRA_ASSET_PATH）。
 * - 自动扫描同目录全部 .fen，支持上一题/下一题切换
 * - 重置：恢复该题原始 FEN
 * - 提示：弹出引擎后台分析的首选着法
 * - 自由推演，不做限步判定（"二步杀"等仅为题目分类名）
 */
class PuzzleActivity : AppCompatActivity(), View.OnTouchListener, ControllerListener {

    companion object {
        const val EXTRA_ASSET_PATH = "ASSET_PATH"
        private const val TAG = "PuzzleActivity"
        private const val MIN_CLICK_INTERVAL_MS: Long = 100
        private const val SWITCH_INTERVAL_MS: Long = 300
    }

    private lateinit var binding: ActivityPuzzleBinding
    private lateinit var chessLayout: FrameLayout
    private lateinit var chessView: ChessView
    private lateinit var historyAndTrendAdapter: HistoryAndTrendAdapter
    private lateinit var controller: GameController
    private lateinit var soundPlayer: SoundPlayer

    /** 同目录题目（assets 相对路径，自然排序）与当前题号 */
    private val puzzlePaths = mutableListOf<String>()
    private var puzzleIndex = -1

    /** 当前题目的 FEN 原文（重置用） */
    private var currentFen: String? = null

    private var lastHandledClickTime: Long = 0
    private var cloudFen: String? = null

    /** 翻页/重置最小间隔：避免快速连点导致引擎反复 stop/ready 握手（单搜索槽不会并发，仅减少空转） */
    private var lastSwitchTime: Long = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPuzzleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val assetPath = intent.getStringExtra(EXTRA_ASSET_PATH)
        if (assetPath.isNullOrBlank()) {
            Toast.makeText(this, "题目路径缺失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        controller = GameController(this)

        chessLayout = binding.chesslayout
        chessView = ChessView(this, controller)
        // WRAP_CONTENT + gravity：短屏棋盘等比缩小后在容器内居中
        chessLayout.addView(chessView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        chessView.setOnTouchListener(this)

        binding.backButton.setOnClickListener { finish() }
        binding.prevPuzzleButton.setOnClickListener { switchPuzzle(-1) }
        binding.resetPuzzleButton.setOnClickListener { resetPuzzle() }
        binding.hintButton.setOnClickListener { showHint() }
        binding.nextPuzzleButton.setOnClickListener { switchPuzzle(1) }
        binding.flipButton.setOnClickListener { chessView.flipped = !chessView.flipped }

        binding.tabThinking.setOnClickListener { switchTab(0) }
        binding.tabEval.setOnClickListener { switchTab(1) }
        binding.tabCloud.setOnClickListener { switchTab(2) }
        binding.tabMoves.setOnClickListener { switchTab(3) }

        // 信息卡片折叠/展开：书钉按下(展开态 ic_unfix)→折叠；折叠条点击→恢复
        binding.pinButton.setOnClickListener { setInfoCardCollapsed(true) }
        binding.collapsedBar.setOnClickListener { setInfoCardCollapsed(false) }
        binding.cloudStatus.setOnClickListener {
            cloudFen = null
            refreshCloudBook(forceRefresh = true)
        }

        soundPlayer = SoundPlayer(this, controller)

        historyAndTrendAdapter = HistoryAndTrendAdapter(
            this,
            binding.movesContainer,
            binding.movesScroll,
            binding.thinkingMoves,
            binding.depthText,
            binding.nodesText,
            binding.timeText,
            binding.trendchart,
            controller
        )
        historyAndTrendAdapter.update()
        switchTab(0)

        binding.trendchart.description = Description().apply { text = "" }
        val xAxis = binding.trendchart.xAxis
        xAxis.setGranularityEnabled(true)
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f
        val yAxis = binding.trendchart.axisLeft
        yAxis.setDrawGridLines(false)
        yAxis.setDrawZeroLine(true)
        binding.trendchart.axisRight.isEnabled = false
        binding.trendchart.legend.isEnabled = false

        // 构建同目录题目列表并定位到当前题
        val dir = assetPath.substringBeforeLast('/', "")
        val currentName = assetPath.substringAfterLast('/')
        val names = try {
            assets.list(dir)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        puzzlePaths.addAll(
            names.filter { it.substringAfterLast('.', "").lowercase() == "fen" }
                .sortedWith(naturalFileNameComparator)
                .map { if (dir.isEmpty()) it else "$dir/$it" }
        )
        puzzleIndex = puzzlePaths.indexOf(assetPath)
        if (puzzleIndex < 0) {
            // 兜底：列表异常时至少能打开传入的这一题
            puzzlePaths.add(assetPath)
            puzzleIndex = 0
        }

        // 杀法限步判定已取消："二步杀"等仅作题目分类，引擎应对可能连将，步数不固定

        loadPuzzle(puzzleIndex)
    }

    // ---------------- 题目导航与加载 ----------------

    /** 切换到同目录上一题/下一题；@param delta -1=上一题，+1=下一题 */
    private fun switchPuzzle(delta: Int) {
        if (System.currentTimeMillis() - lastSwitchTime < SWITCH_INTERVAL_MS) return
        val target = puzzleIndex + delta
        if (target < 0) {
            Toast.makeText(this, "已经是第一题", Toast.LENGTH_SHORT).show()
            return
        }
        if (target >= puzzlePaths.size) {
            Toast.makeText(this, "已经是最后一题", Toast.LENGTH_SHORT).show()
            return
        }
        lastSwitchTime = System.currentTimeMillis()
        loadPuzzle(target)
    }

    /** 加载指定题目：读 FEN 并驱动当前回合 */
    private fun loadPuzzle(index: Int) {
        val path = puzzlePaths[index]
        val fen = try {
            assets.open(path).bufferedReader().readText().trim()
        } catch (e: Exception) {
            Log.e(TAG, "加载题目失败: $path", e)
            Toast.makeText(this, "题目加载失败", Toast.LENGTH_SHORT).show()
            return
        }
        puzzleIndex = index
        currentFen = fen
        controller.startFENGame(fen)
        refreshPuzzleTitle()
        historyAndTrendAdapter.update()
        controller.driveCurrentTurn()
    }

    /** 重置当前题为原始 FEN */
    private fun resetPuzzle() {
        if (System.currentTimeMillis() - lastSwitchTime < SWITCH_INTERVAL_MS) return
        lastSwitchTime = System.currentTimeMillis()
        val fen = currentFen ?: return
        controller.startFENGame(fen)
        refreshPuzzleTitle()
        historyAndTrendAdapter.update()
        controller.driveCurrentTurn()
        Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
    }

    /** 标题：题名（去掉扩展名）+ 题号进度 */
    private fun refreshPuzzleTitle() {
        val name = puzzlePaths.getOrNull(puzzleIndex)
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: "残局练习"
        binding.title.text = "$name ${puzzleIndex + 1}/${puzzlePaths.size}"
    }

    // ---------------- 提示 ----------------

    /** 引擎提示：取后台分析 PV 的首个着法（中文着法）弹窗展示 */
    private fun showHint() {
        val game = controller.game
        if (game == null) return
        if (game.isGameOver) {
            Toast.makeText(this, "本题已结束", Toast.LENGTH_SHORT).show()
            return
        }
        if (!controller.isHumanTurn) {
            Toast.makeText(this, "请等引擎走完", Toast.LENGTH_SHORT).show()
            return
        }
        val pv = controller.thinkPvMoves
        if (pv.isEmpty()) {
            Toast.makeText(this, "引擎分析中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val best = pv[0]
        val follow = if (pv.size > 1) pv.drop(1).take(5).joinToString(" ") else ""
        val message = if (follow.isNotEmpty()) "建议着法：$best\n参考续着：$follow" else "建议着法：$best"
        AlertDialog.Builder(this)
            .setTitle("引擎提示")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------- 信息卡片折叠/展开 ----------------

    /**
     * 折叠：头部(tab)与内容页隐藏，仅显示 40dp 折叠条；卡片解除底部约束改 wrap_content，
     * 顶部仍贴 gl_card_top（紧贴棋盘下沿），下方露出山水背景，底栏不动。展开则恢复。
     */
    private fun setInfoCardCollapsed(collapsed: Boolean) {
        binding.expandedHeader.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.infoContent.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.collapsedBar.visibility = if (collapsed) View.VISIBLE else View.GONE

        val lp = binding.infoCard.layoutParams as ConstraintLayout.LayoutParams
        if (collapsed) {
            lp.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
            lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        } else {
            lp.height = 0
            lp.bottomToBottom = R.id.gl_card_bottom
        }
        binding.infoCard.layoutParams = lp
    }

    // ---------------- 信息页切换 ----------------
    private val tabButtons: List<ImageView> by lazy {
        listOf(binding.tabThinking, binding.tabEval, binding.tabCloud, binding.tabMoves)
    }
    private val tabPages: List<View> by lazy {
        listOf(binding.pageThinking, binding.trendchart, binding.pageCloud, binding.movesScroll)
    }
    private val tabActiveBgs = intArrayOf(
        R.drawable.bg_seg_left_active,
        R.drawable.bg_seg_mid_active,
        R.drawable.bg_seg_mid_active,
        R.drawable.bg_seg_right_active
    )
    private val tabInactiveBgs = intArrayOf(
        R.drawable.bg_seg_left_inactive,
        R.drawable.bg_seg_mid_inactive,
        R.drawable.bg_seg_mid_inactive,
        R.drawable.bg_seg_right_inactive
    )

    private fun switchTab(index: Int) {
        tabButtons.forEachIndexed { i, tab ->
            if (i == index) {
                tab.setBackgroundResource(tabActiveBgs[i])
                tab.imageTintList = ColorStateList.valueOf(Color.WHITE)
            } else {
                tab.setBackgroundResource(tabInactiveBgs[i])
                tab.imageTintList = ColorStateList.valueOf(0xFF5C4A3D.toInt())
            }
        }
        tabPages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (index == 2) refreshCloudBook()
        historyAndTrendAdapter.update()
    }

    // ---------------- 云库 ----------------
    private fun refreshCloudBook(forceRefresh: Boolean = false) {
        val game = controller.game
        if (game == null) {
            showCloudStatus("暂无局面")
            return
        }
        val fen = game.currentBoard.toFENString()
        if (!forceRefresh && fen == cloudFen) return
        cloudFen = fen

        binding.cloudMoves.removeAllViews()
        if (game.isGameOver) {
            showCloudStatus("本题已结束")
            return
        }
        showCloudStatus("正在查询云库...")
        CloudBookClient.query(fen, forceRefresh) { result ->
            if (controller.game?.currentBoard?.toFENString() != fen) return@query
            renderCloudResult(result)
        }
    }

    private fun showCloudStatus(text: String) {
        binding.cloudStatus.text = text
        binding.cloudStatus.visibility = View.VISIBLE
        binding.cloudScroll.visibility = View.GONE
    }

    private fun renderCloudResult(result: CloudBookResult) {
        when (result.state) {
            CloudQueryState.LOADED -> {
                val moves = result.moves ?: emptyList()
                binding.cloudMoves.removeAllViews()

                val redTurn = controller.isRedTurn
                val playable = controller.isHumanTurn
                        && controller.game?.isGameOver != true
                        && (controller.isAnalyzing || !controller.isEngineBusy)

                moves.forEach { cm ->
                    val row = layoutInflater.inflate(
                        R.layout.item_cloud_move, binding.cloudMoves, false)
                    row.findViewById<TextView>(R.id.cloudMoveName).text =
                        cloudMoveChinese(cm.ucci)
                    row.findViewById<TextView>(R.id.cloudWinrate).text =
                        "胜率：${if (cm.winrate.isBlank()) "—" else "${cm.winrate}%"}"

                    val displayScore = if (redTurn) cm.score else -cm.score
                    row.findViewById<TextView>(R.id.cloudScore).apply {
                        text = "评分: $displayScore"
                        setTextColor(
                            when {
                                displayScore > 0 -> 0xFF4E4E4C.toInt()
                                displayScore < 0 -> 0xFF538A5C.toInt()
                                else -> 0xFFB09880.toInt()
                            }
                        )
                    }

                    if (playable) {
                        row.setOnClickListener { controller.playerMoveByCloud(cm.ucci) }
                    } else {
                        row.isClickable = false
                        if (!controller.isHumanTurn) row.alpha = 0.45f
                    }
                    binding.cloudMoves.addView(row)
                }

                binding.cloudStatus.visibility = View.GONE
                binding.cloudScroll.visibility = View.VISIBLE
            }
            CloudQueryState.UNKNOWN -> showCloudStatus("云库暂无该局面着法数据，点击重试")
            CloudQueryState.CHECKMATE -> showCloudStatus("走棋方已被将死")
            CloudQueryState.STALEMATE -> showCloudStatus("走棋方困毙，无棋可走")
            CloudQueryState.INVALID -> showCloudStatus("局面代码无效")
            CloudQueryState.ERROR -> showCloudStatus("云库查询失败，点击重试")
        }
    }

    private fun cloudMoveChinese(ucci: String): String {
        val board = controller.game?.currentBoard ?: return ucci
        val m = Move(board)
        return if (m.fromUCCIString(ucci)) m.chsString else ucci
    }

    // ---------------- 棋盘触摸 ----------------
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        event ?: return false
        if (event.action != MotionEvent.ACTION_DOWN) return false

        val now = System.currentTimeMillis()
        if (now - lastHandledClickTime < MIN_CLICK_INTERVAL_MS) return false
        lastHandledClickTime = now

        val pos = chessView.getPosByCoord(event.x, event.y) ?: return false
        controller.touchPosition(pos)
        return false
    }

    // ---------------- 游戏事件 ----------------
    override fun onGameEvent(status: GameStatus?, message: String?) {
        when (status) {
            GameStatus.ILLEGAL -> {
                message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                soundPlayer.illegal()
            }
            GameStatus.MOVE -> soundPlayer.move()
            GameStatus.CAPTURE -> soundPlayer.capture()
            GameStatus.CHECK -> soundPlayer.check()
            GameStatus.CHECKMATE -> soundPlayer.checkmate()
            GameStatus.SELECT -> soundPlayer.select()
            GameStatus.WIN, GameStatus.LOSE, GameStatus.DRAW ->
                message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            GameStatus.ENGINE, GameStatus.UPDATEUI -> { /* 标题保持题名，不覆盖 */ }
            GameStatus.MULTIPV -> soundPlayer.ready()
            null -> { /* 忽略 */ }
        }
        if (binding.pageCloud.visibility == View.VISIBLE) {
            refreshCloudBook()
        }
        historyAndTrendAdapter.update()
    }

    override fun onGameEvent(event: GameStatus?) {
        onGameEvent(event, null)
    }

    override fun runOnUIThread(runnable: Runnable?) {
        runOnUiThread(runnable)
    }

    // ---------------- 生命周期 ----------------
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) controller.shutdown()
        if (::soundPlayer.isInitialized) soundPlayer.release()
    }

    /** 文件名自然排序：连续数字段按数值比较（如"第2局"排在"第10局"前） */
    private val naturalFileNameComparator = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var i2 = i
                var j2 = j
                while (i2 < a.length && a[i2].isDigit()) i2++
                while (j2 < b.length && b[j2].isDigit()) j2++
                val na = a.substring(i, i2).trimStart('0')
                val nb = b.substring(j, j2).trimStart('0')
                val la = na.ifEmpty { "0" }.length
                val lb = nb.ifEmpty { "0" }.length
                if (la != lb) return@Comparator la - lb
                val cmp = na.ifEmpty { "0" }.compareTo(nb.ifEmpty { "0" })
                if (cmp != 0) return@Comparator cmp
                i = i2
                j = j2
            } else {
                val cmp = a[i].compareTo(b[j])
                if (cmp != 0) return@Comparator cmp
                i++
                j++
            }
        }
        a.length - b.length
    }
}
