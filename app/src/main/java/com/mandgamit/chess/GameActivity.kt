package com.mandgamit.chess

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.XAxis
import com.mandgamit.chess.adapters.HistoryAndTrendAdapter
import com.mandgamit.chess.assist.ui.OverlayChessView
import com.mandgamit.chess.battle.BattleBackgroundGuide
import com.mandgamit.chess.battle.BattleKeepAliveService
import com.mandgamit.chess.battle.BattleSessionManager
import com.mandgamit.chess.battle.BattleStateStore
import com.mandgamit.chess.battle.BattleVoice
import com.mandgamit.chess.battle.net.BattleMessage
import com.mandgamit.chess.cloudbook.CloudBookClient
import com.mandgamit.chess.cloudbook.CloudBookResult
import com.mandgamit.chess.cloudbook.CloudQueryState
import com.mandgamit.chess.controllers.GameController
import com.mandgamit.chess.controllers.ControllerListener
import com.mandgamit.chess.controllers.NetworkGameController
import com.mandgamit.chess.databinding.ActivityGameBinding
import com.mandgamit.chess.gamelogic.Board
import com.mandgamit.chess.gamelogic.Game
import com.mandgamit.chess.gamelogic.GameStatus
import com.mandgamit.chess.gamelogic.Move
import com.mandgamit.chess.gamelogic.Piece
import com.mandgamit.chess.gamelogic.Position
import com.mandgamit.chess.gamelogic.RulesetProvider
import com.mandgamit.chess.utils.GameRecordExporter
import com.mandgamit.chess.views.ChessView


class GameActivity() : AppCompatActivity(), View.OnTouchListener, ControllerListener,
    SettingDialogFragment.SettingDialogListener {

    companion object {
        private const val TAG = "GameActivity"
        private const val MIN_CLICK_INTERVAL_MS: Long = 100

        /** 好友对战："对局棋谱"三连击解锁云库页的总时间窗口（毫秒）。 */
        private const val CLOUD_UNLOCK_WINDOW_MS = 1000L

        /** 好友对战模式开关（BattleRoomDialog 在双方就位后携带进入）。 */
        const val EXTRA_BATTLE_MODE = "com.mandgamit.chess.extra.BATTLE_MODE"

        /** 观战模式开关（LobbyActivity 观战进入；只读棋盘，无操作按钮/聊天）。 */
        const val EXTRA_WATCH_MODE = "com.mandgamit.chess.extra.WATCH_MODE"

        /** 盲棋训练模式开关（MainActivity 入口传入）。 */
        const val EXTRA_BLIND_MODE = "com.mandgamit.chess.extra.BLIND_MODE"

        /** 引擎工作模式偏好（与 MainActivity 同一个 prefs 文件）。 */
        private const val PREFS_COMMON = "MandGamit"
        private const val KEY_ENGINE_PLAYS_RED = "engine_plays_red"
        private const val KEY_ENGINE_PLAYS_BLACK = "engine_plays_black"
    }

    // 防止棋盘重复点击
    private var lastHandledClickTime: Long = 0

    private lateinit var binding: ActivityGameBinding
    private lateinit var chessLayout: FrameLayout
    private lateinit var chessView: ChessView
    private lateinit var historyAndTrendAdapter: HistoryAndTrendAdapter
    private lateinit var controller: GameController
    private lateinit var soundPlayer: SoundPlayer

    private var isFromManual = false

    // —— 好友对战 ——
    private var battleMode = false
    private var watchMode = false
    private var networkController: NetworkGameController? = null
    private var battleSession: BattleSessionManager? = null

    // —— 盲棋训练 ——
    private var blindMode = false
    private var tts: TextToSpeech? = null

    /** 观战模式：走子注入锁（防止本地 UI 线程与网络线程并发）。 */
    private val watchLock = Any()
    @Volatile private var watchUndoPending = false

    // 好友对战：云库着法默认隐藏，连续快速点击"对局棋谱"3次后解锁（本局有效）
    private var cloudTabUnlocked = false
    private val movesTabTapTimes = ArrayDeque<Long>()
    private var opponentOnline = true

    /** 对局结束弹窗（新一局/退出）是否已弹过，防止重复触发。 */
    private var battleResultShown = false

    /** 终局对话框引用与待弹任务：对方离开房间时需要关闭/取消，"开始新一局"随即无意义。 */
    private var battleResultDialog: AlertDialog? = null
    /** 对方发来的求和/悔棋请求对话框：对局终结或新局开始时必须撤销，避免旧局协商残留 */
    private var drawOfferDialog: AlertDialog? = null
    private var undoRequestDialog: AlertDialog? = null
    private var pendingResultDialogRunnable: Runnable? = null

    /**
     * 房间终态（对方离开 / 服务器回收 / 重连彻底失败）提示是否已给出。
     * 防止 onOpponentLeave 弹窗后 socket 4001 关闭再叠加一个 CLOSED 对话框。
     */
    private var battleClosedHandled = false

    /** 好友对战本局棋谱是否已自动保存（终局自动保存后，退出不再重复提示/保存）。 */
    private var battleAutoSaved = false

    /** 对方离线重连倒计时（秒），0 表示对方在线。 */
    private var opponentOfflineCountdown = 0
    private val battleCountdownHandler = Handler(Looper.getMainLooper())
    private val battleCountdownRunnable = object : Runnable {
        override fun run() {
            if (opponentOfflineCountdown <= 0) return
            opponentOfflineCountdown--
            updateBattleBanner()
            if (opponentOfflineCountdown > 0) {
                battleCountdownHandler.postDelayed(this, 1000)
            }
        }
    }

    // 防止返回键/退出菜单在异步保存期间重复触发
    private var isExiting = false

    // —— 好友对战聊天（语音/文字，双方均不保留数据）——
    private var battleChatDialog: AlertDialog? = null
    private val voiceRecorder by lazy { BattleVoice.Recorder(this) }
    private val voicePlayer by lazy { BattleVoice.Player(this) }

    /** 按住语音按钮时的全屏录音提示层；null 表示当前未显示。 */
    private var voiceRecordOverlay: View? = null
    /** 当前手势是否处于"上滑取消"状态（松开即丢弃录音）。 */
    private var voiceRecordCancel = false

    /** 录音权限申请：授权后提示用户再次按住麦克风（手指已抬起，不自动补开始）。 */
    private val recordPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "未授予录音权限，无法发送语音", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已授权，请再次按住麦克风说话", Toast.LENGTH_SHORT).show()
            }
        }

    // 云库列表当前已加载的局面FEN（避免同一局面重复查询/重建列表）
    private var cloudFen: String? = null

    /** 好友对战会话事件（SessionManager 已投递主线程）。 */
    private val battleListener = object : BattleSessionManager.BattleListener {
        override fun onRoomCreated(room: String) {}
        override fun onRoomJoined(color: String) {}

        override fun onGameStart() {
            battleAutoSaved = false
            if (battleMode && networkController != null) {
                // 对局中收到 start_game：大厅桌"再来一局"仲裁达成（双方各点一次
                // 新一局后，服务端交换先后手并分帧重发，各带本人颜色），或旧版
                // 房号模式的首次开局。颜色唯一权威在服务端：先更新执子色再重置
                // 棋盘（红先），并同步翻转棋盘视角。
                dismissPendingNegotiationDialogs()
                val redBefore = networkController?.isMyColorRed
                val serverRed = battleSession?.myColor == BattleMessage.COLOR_RED
                networkController?.applyServerStart(if (watchMode) null else serverRed)
                flipBoardIfColorSwapped(redBefore)
                battleResultShown = false
                if (!watchMode) {
                    Toast.makeText(this@GameActivity, "双方已确认新一局，红方先行", Toast.LENGTH_SHORT).show()
                }
                updateBattleActionButtonsEnabled()
            }
            updateBattleStatus()
        }

        override fun onGameResumed(color: String) {
            // 重连恢复补发的 start_game（代数未变）：只同步服务端执子色
            // （离线期间可能已达成"再来一局"换色），棋盘与着法保持快照恢复结果，
            // 绝不能重置（否则旧局 isGameOver=true 会让点击走棋全部无效）。
            if (battleMode && networkController != null) {
                val redBefore = networkController?.isMyColorRed
                networkController?.applyServerColor(color == BattleMessage.COLOR_RED)
                flipBoardIfColorSwapped(redBefore)
                updateBattleActionButtonsEnabled()
            }
            updateBattleStatus()
        }

        override fun onRemoteMove(ucci: String, seq: Int) {
            watchUndoPending = false
            networkController?.injectRemoteMove(ucci)
        }

        override fun onOpponentOffline(ttlSeconds: Int) {
            opponentOnline = false
            updateBattleStatus()
            setTitle("对方离线，等待重连")
            // 启动保留倒计时，到点服务器会关闭房间并通知（leave_room）
            opponentOfflineCountdown = if (ttlSeconds > 0) ttlSeconds else 600
            battleCountdownHandler.removeCallbacks(battleCountdownRunnable)
            battleCountdownHandler.postDelayed(battleCountdownRunnable, 1000)
            updateBattleBanner()
        }

        override fun onOpponentOnline() {
            opponentOnline = true
            opponentOfflineCountdown = 0
            battleCountdownHandler.removeCallbacks(battleCountdownRunnable)
            updateBattleStatus()
            updateBattleBanner()
            networkController?.onConnectionRestored()
        }

        override fun onSyncState(fen: String, moves: List<String>, turn: String) {
            networkController?.applySyncState(fen, moves, turn)
            battleAutoSaved = false
            updateBattleStatus()
        }

        override fun onSyncRequested() {
            networkController?.pushLocalSync()
        }

        override fun onOpponentResign() {
            if (watchMode) {
                // 观战：中立展示终局，等待服务端 watch_end 或本地退出
                networkController?.game?.isGameOver = true
                Toast.makeText(this@GameActivity, "对局结束（一方认输）", Toast.LENGTH_SHORT).show()
                refreshTitle()
                return
            }
            // 对方认输终结本局：撤销其此前发出的求和/悔棋请求对话框
            dismissPendingNegotiationDialogs()
            networkController?.onOpponentResign()
        }

        override fun onOpponentLeave(reason: String?) {
            if (watchMode) return // 旁观者经 watch_end 收敛
            networkController?.game?.isGameOver = true
            opponentOfflineCountdown = 0
            battleCountdownHandler.removeCallbacks(battleCountdownRunnable)
            // 房间已被服务器销毁：关闭"新一局/退出"终局对话框（重开已无意义），只保留退出一个选择
            dismissBattleResultDialog()
            dismissPendingNegotiationDialogs()
            battleClosedHandled = true
            updateBattleStatus()
            updateBattleBanner()
            AlertDialog.Builder(this@GameActivity)
                .setTitle("对方已退出")
                .setMessage(reason ?: "对方已退出对战，房间已关闭。")
                .setPositiveButton("退出对战") { _, _ -> leaveBattleNow() }
                .setCancelable(false)
                .show()
            refreshTitle()
        }

        override fun onOpponentLeft() {
            if (watchMode) return // 观战者经 table_update 收敛
            // 终局后对方离座：棋桌保留、本方座位不变，重开已无意义——
            // 关闭终局对话框，提示后直接返回大厅继续等待新对手（不调 stand，座位已在服务端保留）
            opponentOfflineCountdown = 0
            battleCountdownHandler.removeCallbacks(battleCountdownRunnable)
            dismissBattleResultDialog()
            dismissPendingNegotiationDialogs()
            updateBattleStatus()
            updateBattleBanner()
            AlertDialog.Builder(this@GameActivity)
                .setTitle("对方已退出")
                .setMessage("对方已退出对战，棋桌已保留，你的座位不变。\n可回大厅继续等待新对手。")
                .setPositiveButton("返回大厅") { _, _ ->
                    if (!isFinishing && !isDestroyed) finish()
                }
                .setCancelable(false)
                .show()
            refreshTitle()
        }

        // —— 求和 / 再来一局 ——

        override fun onDrawOffer() {
            if (networkController?.game?.isGameOver == true) return
            drawOfferDialog?.dismiss()
            drawOfferDialog = AlertDialog.Builder(this@GameActivity)
                .setTitle("求和")
                .setMessage("对方请求和棋，是否同意？")
                // 同悔棋：必须二选一回应，禁止点框外/返回键关闭导致对方空等
                .setCancelable(false)
                .setPositiveButton("同意") { _, _ ->
                    battleSession?.sendDrawAccept()
                    // 同意方本地立即重置；提议方经 onDrawAccepted 重置
                    networkController?.onDrawAccepted()
                }
                .setNegativeButton("拒绝") { _, _ ->
                    battleSession?.sendDrawDecline()
                    Toast.makeText(this@GameActivity, "已拒绝对方求和，继续比赛", Toast.LENGTH_SHORT).show()
                }
                .show()
                .apply {
                    setCanceledOnTouchOutside(false)
                    setOnDismissListener { drawOfferDialog = null }
                }
        }

        override fun onDrawAccepted() {
            // 同意方：本地标记和棋成立，弹"新一局/退出"对话框（与认输同流程）
            networkController?.onDrawAccepted()
        }

        override fun onDrawDeclined() {
            Toast.makeText(this@GameActivity, "对方拒绝了求和，继续比赛", Toast.LENGTH_SHORT).show()
        }

        override fun onNewGame() {
            val ctl = networkController ?: return
            // 对方请求新一局：撤销旧局的求和/悔棋请求对话框
            dismissPendingNegotiationDialogs()
            val wasWaiting = ctl.isWaitingRematchAck
            ctl.onRemoteNewGame()
            if (wasWaiting) {
                // 本方此前已点过"开始新一局"在等确认：这条 new_game 就是对方真正点击的确认
                battleResultShown = false
                battleAutoSaved = false
                updateBattleActionButtonsEnabled()
                Toast.makeText(this@GameActivity, "对方已确认新一局，红方先行", Toast.LENGTH_SHORT).show()
            }
            // 否则仅是对方发起新一局请求：棋盘已同步重置，本方保持"本局结束"对话框等待选择
        }

        // —— 悔棋 ——

        override fun onUndoRequest() {
            val ctl = networkController ?: return
            if (watchMode) {
                // 观战：双方互为悔棋（各发 undo_request 无 undo_accept）时服务端已 pop 走子缓存，
                // 旁观端收到第二个 undo_request 即视为悔棋成立，回退两着保持同步
                if (watchUndoPending) {
                    watchUndoPending = false
                    ctl.acceptUndo()
                } else {
                    watchUndoPending = true
                }
                return
            }
            if (ctl.game?.isGameOver == true) return
            // 双方几乎同时请求悔棋：互为同意，直接各自回退
            if (ctl.onUndoRequestMutualIfPending()) {
                Toast.makeText(this@GameActivity, "双方同意悔棋", Toast.LENGTH_SHORT).show()
                return
            }
            undoRequestDialog?.dismiss()
            undoRequestDialog = AlertDialog.Builder(this@GameActivity)
                .setTitle("悔棋")
                .setMessage("对方请求悔棋，是否同意？")
                // 必须二选一回应对方：禁止点击框外或返回键关闭，
                // 否则对方将永远等不到回复而走子被锁，棋局卡死
                .setCancelable(false)
                .setPositiveButton("同意") { _, _ ->
                    battleSession?.sendUndoAccept()
                    ctl.acceptUndo()
                }
                .setNegativeButton("拒绝") { _, _ ->
                    battleSession?.sendUndoDecline()
                    Toast.makeText(this@GameActivity, "已拒绝对方悔棋，继续比赛", Toast.LENGTH_SHORT).show()
                }
                .show()
                .apply {
                    setCanceledOnTouchOutside(false)
                    setOnDismissListener { undoRequestDialog = null }
                }
        }

        override fun onUndoAccepted() {
            if (watchMode) {
                watchUndoPending = false
                networkController?.acceptUndo() // 旁观端同步回退两着
                return
            }
            networkController?.onUndoAccepted()
            Toast.makeText(this@GameActivity, "对方同意悔棋，请走棋", Toast.LENGTH_SHORT).show()
        }

        override fun onUndoDeclined() {
            if (watchMode) {
                watchUndoPending = false
                return
            }
            networkController?.onUndoDeclined()
            Toast.makeText(this@GameActivity, "对方拒绝了悔棋，继续比赛", Toast.LENGTH_SHORT).show()
        }

        override fun onChatText(text: String) {
            if (watchMode) return
            Toast.makeText(this@GameActivity, "对方：$text", Toast.LENGTH_LONG).show()
        }
        override fun onChatVoice(amrB64: String, durationMs: Int) {
            if (watchMode) return
            playBattleVoice(amrB64)
        }
        override fun onError(msg: String) {
            Toast.makeText(this@GameActivity, msg, Toast.LENGTH_SHORT).show()
        }

        override fun onWatchOk(
            table: Int, red: String, black: String, moves: List<String>, turn: String,
        ) {
            if (watchMode) {
                (networkController as? com.mandgamit.chess.controllers.WatchGameController)
                    ?.applyWatchMoves(moves)
                updateBattleStatus()
            }
        }

        override fun onWatchEnd(table: Int, msg: String) {
            if (!isExiting && !isFinishing && !isDestroyed) {
                AlertDialog.Builder(this@GameActivity)
                    .setTitle("观战结束")
                    .setMessage(msg.ifEmpty { "对局已结束" })
                    .setPositiveButton("返回大厅") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }

        override fun onStateChanged(state: BattleSessionManager.State) {
            when (state) {
                BattleSessionManager.State.DISCONNECTED -> {
                    networkController?.onConnectionLost()
                }
                BattleSessionManager.State.PLAYING, BattleSessionManager.State.READY -> {
                    networkController?.onConnectionRestored()
                }
                BattleSessionManager.State.CLOSED -> {
                    // 冷启动恢复时房间已被服务器回收 / 重连 8 次彻底失败：
                    // 无法继续对局，提示后返回主界面，避免把用户留在空棋盘上
                    if (!battleClosedHandled && !isExiting && !isFinishing && !isDestroyed) {
                        battleClosedHandled = true
                        AlertDialog.Builder(this@GameActivity)
                            .setTitle("对战已结束")
                            .setMessage("房间已关闭或网络连接无法恢复，将返回主界面。")
                            .setPositiveButton("返回主界面") { _, _ -> exitBattleOnClosed() }
                            .setCancelable(false)
                            .show()
                    }
                }
                else -> {}
            }
            updateBattleStatus()
            updateBattleBanner()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        battleMode = intent.getBooleanExtra(EXTRA_BATTLE_MODE, false)
        watchMode = intent.getBooleanExtra(EXTRA_WATCH_MODE, false)
        blindMode = intent.getBooleanExtra(EXTRA_BLIND_MODE, false)
        if (battleMode) {
            // 好友对战：会话由 ChessApp 持有（建房对话框已完成配对），
            // 不读本地人机存档，不起引擎
            val session = ChessApp.getBattleSession()
            battleSession = session
            val nc = if (watchMode) {
                // 观战：只读控制器，恒为网络等待态
                com.mandgamit.chess.controllers.WatchGameController(this, session)
            } else {
                // 执子色以服务端为准（建房/入座/重连恢复/再来一局仲裁均由
                // 服务端下发 color），客户端不做任何本地推算
                val myRed = session.myColor == BattleMessage.COLOR_RED
                NetworkGameController(this, session, myRed)
            }
            networkController = nc
            controller = nc
        } else {
            controller = GameController(this)
            // 盲棋训练固定为"玩家执红、引擎执黑"：
            // 1) 不复用人机大战记忆的引擎工作模式（玩家对玩家/引擎对引擎等）
            // 2) 显式覆盖默认值，避免加载人机大战存档后任何隐式路径改变引擎方
            // 3) 盲棋模式无引擎模式选择入口，不会写回偏好，不覆盖人机大战配置
            if (blindMode) {
                controller.enginePlaysRed = false
                controller.enginePlaysBlack = true
                controller.isComputerPlaying = true
                // 盲棋训练每次进入都新开棋局，不恢复上次退出的存档
                controller.startNewGame()
            } else {
                restoreEngineMode()
                controller.loadGameStatus()
            }
        }

        // 初始化棋盘
        chessLayout = binding.chesslayout
        chessView = ChessView(this, controller)
        // 承接来源界面（如棋盘编辑器）的视角翻转状态
        chessView.flipped = intent.getBooleanExtra("Flipped", false)
        // 好友对战：执黑方（加入者）默认翻转视角，黑方在下
        if (battleMode && networkController?.isMyColorRed == false) {
            chessView.flipped = true
        }
        // WRAP_CONTENT + gravity：短屏棋盘等比缩小后在容器内水平/垂直居中
        // （FrameLayout 只认子 View 自身的 layout_gravity，父容器 android:gravity 对其子 View 无效）
        chessLayout.addView(chessView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        chessView.setOnTouchListener(this)

        // P4 底部操作按钮
        binding.backButton.setOnClickListener { saveAndExit() }
        binding.newGameButton.setOnClickListener { showNewGameSheet() }
        binding.undoButton.setOnClickListener {
            if (!isEngineVsEngine) controller.playerUndo()
        }
        binding.flipButton.setOnClickListener {
            chessView.flipped = !chessView.flipped
            // 评估曲线以棋盘下方阵营为正方向，视角翻转时同步刷新
            historyAndTrendAdapter.bottomSideRed = !chessView.flipped
            historyAndTrendAdapter.update()
        }
        binding.engineModeButton.setOnClickListener { showEngineModeSheet() }
        binding.moreButton.setOnClickListener { showMoreOptionsSheet() }

        // P3 四个切换按钮
        binding.tabThinking.setOnClickListener { switchTab(0) }
        binding.tabEval.setOnClickListener { switchTab(1) }
        binding.tabCloud.setOnClickListener { switchTab(2) }
        binding.tabMoves.setOnClickListener {
            switchTab(3)
            maybeUnlockCloudTab()
        }

        // 信息卡片折叠/展开：书钉按下(展开态 ic_unfix)→折叠；折叠条点击→恢复
        binding.pinButton.setOnClickListener { setInfoCardCollapsed(true) }
        binding.collapsedBar.setOnClickListener { setInfoCardCollapsed(false) }

        // 云库状态文字点击重试（查询失败/无数据时）
        binding.cloudStatus.setOnClickListener {
            cloudFen = null
            refreshCloudBook(forceRefresh = true)
        }

        soundPlayer = SoundPlayer(this, controller)

        // 初始化 P3 数据适配器
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
        // 评估曲线初始视角跟随棋盘（黑方在下时以黑方为正）
        historyAndTrendAdapter.bottomSideRed = !chessView.flipped
        // 双击引擎思考着法：弹出思考细节推演小棋盘（无着法时面板无可双击视图）
        historyAndTrendAdapter.onThinkingMoveDoubleClick = { showPvReplayDialog() }
        historyAndTrendAdapter.update()
        switchTab(0)

        // 评估曲线样式
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

        refreshTitle()

        // 从打谱界面传入 FEN 棋局
        val fenString = intent.getStringExtra("FENString")
        if (fenString != null) {
            controller.startFENGame(fenString)
            isFromManual = true
        }

        updateOpponentIcon()
        refreshActionButtons()
        // 按加载的棋局/FEN局面驱动当前回合（引擎自动走或玩家回合后台分析）
        controller.driveCurrentTurn()

        if (battleMode) {
            initBattleMode()
        }
        if (blindMode) {
            initBlindMode()
        }
    }

    // ---------------- 盲棋训练模式 ----------------

    private fun initBlindMode() {
        // 棋盘隐藏棋子
        chessView.blindMode = true

        // 默认折叠信息卡片：盲棋训练尽量不展示信息给玩家（仍可点击折叠条展开）
        setInfoCardCollapsed(true)

        // 初始化 TTS（引擎绑定异步，开场播报放在初始化回调里）
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.CHINESE
                // 引擎就绪后播报开场
                speakBlind("新对局")
            }
        }

        // 底部按钮重映射：新局 / 显示隐藏 / 悔棋 / 输入 / 退出
        fun remapButton(
            container: android.view.ViewGroup,
            iconRes: Int,
            label: String,
            enabled: Boolean = true,
            action: (() -> Unit)? = null
        ) {
            container.isEnabled = enabled
            container.alpha = if (enabled) 1f else 0.4f
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is ImageView) { child.setImageResource(iconRes); break }
            }
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is TextView) { child.text = label; break }
            }
            if (action != null) {
                container.setOnClickListener { action() }
            } else {
                container.setOnClickListener(null)
            }
        }
        // 1.新对局位 → 新局（弹窗选择新对局/继续当前，选完语音播报）
        remapButton(binding.newGameButton, R.drawable.ic_more_alt_move, "新局", true) {
            showBlindNewGameDialog()
        }
        // 2.引擎位 → 显示/隐藏棋子（当前隐藏，按钮显示"显示"）
        remapButton(binding.engineModeButton, R.drawable.ic_show, "显示", true) {
            toggleBlindShowPieces()
        }
        // 3.悔棋位 → 悔棋（悔棋后语音提示轮到谁走棋）
        remapButton(binding.undoButton, R.drawable.ic_btn_undo, "悔棋", true) {
            controller.playerUndo()
            // 悔棋后播报轮到谁走棋
            val turn = if (controller.isRedTurn) "红方走棋" else "黑方走棋"
            speakBlind(turn)
        }
        // 4.翻转位 → 输入着法
        remapButton(binding.flipButton, R.drawable.ic_input, "输入", true) {
            showBlindMoveInputDialog()
        }
        // 5.更多位 → 退出
        remapButton(binding.moreButton, R.drawable.ic_more_exit, "退出", true) {
            saveAndExit()
        }
    }

    /** TTS 播报着法。 */
    private fun speakBlind(text: String) {
        Log.i(TAG, "speakBlind: $text, tts=${tts != null}")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "blind_$text")
    }

    /** 盲棋新局弹窗：复用人机大战的底部面板风格，选完语音播报。 */
    private fun showBlindNewGameDialog() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(0, 0, 0, dpToPx(28))
        }
        // 灰色拖拽条
        sheet.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_sheet_handle)
        }, LinearLayout.LayoutParams(dpToPx(40), dpToPx(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(12)
        })
        // 标题
        sheet.addView(TextView(this).apply {
            text = "新对局"
            textSize = 20f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(14), 0, dpToPx(16))
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 1
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }

        data class BlindNewGameOption(val label: String, val iconRes: Int, val action: () -> Unit)
        val options = listOf(
            BlindNewGameOption("新对局", R.drawable.ic_newgame_plus) {
                maybeSavePgnThenBlindNewGame()
            },
            BlindNewGameOption("继续当前", R.drawable.ic_continue_play) {
                speakBlind("继续当前")
            }
        )

        val dialog = showBottomSheet(sheet)
        options.forEach { option ->
            val cell = createIconCell(option.label, option.iconRes, 48) {
                option.action()
                dialog.dismiss()
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dpToPx(4), 0, dpToPx(4), 0)
            })
        }
        sheet.addView(grid)
    }

    /** 盲棋显示/隐藏棋子切换。 */
    private var piecesVisible = false
    private fun toggleBlindShowPieces() {
        piecesVisible = !piecesVisible
        chessView.blindMode = !piecesVisible
        // 更新按钮图标和文字：隐藏时按钮显示"显示"，显示时按钮显示"隐藏"
        val container = binding.engineModeButton
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is ImageView) {
                child.setImageResource(if (piecesVisible) R.drawable.ic_hide else R.drawable.ic_show)
                break
            }
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) {
                child.text = if (piecesVisible) "隐藏" else "显示"
                break
            }
        }
        speakBlind(if (piecesVisible) "显示棋子" else "隐藏棋子")
    }

    /**
     * 弹出着法输入对话框：按钮点击式（参考"输入着法走棋.html"设计）。
     * 4 个槽位：棋子 / 起始（位置或线号）/ 动作 / 目标数字。槽位填满后自动提交。
     * 与软键盘输入相比，避免拼写错误与一堆语法校验，更稳定快速。
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    private fun showBlindMoveInputDialog() {
        val game = controller.game
        if (game == null || game.isGameOver) {
            Toast.makeText(this, "对局已结束", Toast.LENGTH_SHORT).show()
            return
        }
        if (!controller.isHumanTurn()) {
            Toast.makeText(this, "请轮到你走棋时再输入", Toast.LENGTH_SHORT).show()
            return
        }

        val redToMove = controller.isRedTurn
        // 红方用 帅/相/兵；黑方用 将/象/卒
        val pieceNames = if (redToMove)
            listOf("车", "马", "炮", "相", "士", "帅", "兵")
        else
            listOf("车", "马", "炮", "象", "士", "将", "卒")
        val posNames = listOf("前", "中", "后", "二", "三", "四")
        val numNames = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
        val actNames = listOf("进", "退", "平")

        // 颜色（与 HTML 设计一致：浅米色按钮+红色高亮）
        val normalColors = intArrayOf(0xFFFDF6E6.toInt(), 0xFFECDCBB.toInt())
        val activeColors = intArrayOf(0xFFE3563F.toInt(), 0xFFC2341F.toInt())
        val textDark = 0xFF2B1D0E.toInt()
        val textEmpty = 0xFFD6C6A9.toInt()
        val toolBtnBgColor = 0x33FFFFFF

        val dm = resources.displayMetrics
        fun dpf(dp: Float) = dp * dm.density
        fun gradient(colors: IntArray): GradientDrawable =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)

        fun btnBg(active: Boolean, radiusDp: Float): GradientDrawable =
            gradient(if (active) activeColors else normalColors).apply {
                cornerRadius = radiusDp * dm.density
            }

        fun slotBg(empty: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * dm.density
            if (empty) {
                setColor(0x168B6F4E)
                setStroke(dpf(1.5f).toInt(), 0x598B6F4E)
            } else {
                setColor(Color.WHITE)
                setStroke(dpf(1.5f).toInt(), 0xFFA8875C.toInt())
            }
        }

        // 状态
        var piece: String? = null
        var start: Pair<String, String>? = null  // (value, "pos" / "num")
        var act: String? = null
        var end: String? = null
        var busy = false

        // 创建按钮
        fun makeButton(label: String, radiusDp: Float, fontSize: Float, paddingDp: Int): TextView =
            TextView(this).apply {
                text = label
                textSize = fontSize
                gravity = Gravity.CENTER
                setTextColor(textDark)
                background = btnBg(false, radiusDp)
                setPadding(0, dpToPx(paddingDp), 0, dpToPx(paddingDp))
            }

        // 根容器（覆盖默认 bg_bottom_sheet 的白色背景，用深棕色渐变）
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(28))
            background = gradient(intArrayOf(0xFF5B4632.toInt(), 0xFF241C13.toInt())).apply {
                val r = 20f * dm.density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        // 拖拽条
        sheet.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_sheet_handle)
        }, LinearLayout.LayoutParams(dpToPx(40), dpToPx(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(4)
            bottomMargin = dpToPx(8)
        })

        // 提示文字
        val hint = TextView(this).apply {
            text = "① 点击选择棋子"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF8F7D62.toInt())
            setPadding(0, dpToPx(2), 0, dpToPx(6))
        }
        sheet.addView(hint)

        // 4 个槽位
        val slots = (0..3).map {
            TextView(this).apply {
                text = "·"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(textEmpty)
                setPadding(0, dpToPx(14), 0, dpToPx(14))
                background = slotBg(true)
            }
        }
        val slotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            slots.forEach { slot ->
                addView(slot, LinearLayout.LayoutParams(0, dpToPx(68)).apply {
                    weight = 1f
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                })
            }
        }
        sheet.addView(slotRow)

        // 行标签
        fun addLabel(text: String) {
            sheet.addView(TextView(this).apply {
                this.text = text
                textSize = 11f
                setTextColor(0xFF8F7D62.toInt())
                setPadding(dpToPx(3), dpToPx(6), 0, dpToPx(4))
            })
        }
        // 添加按钮行
        fun addRow(buttons: List<TextView>, itemHeightDp: Int? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                buttons.forEach { btn ->
                    val lp = LinearLayout.LayoutParams(0, itemHeightDp?.let { dpToPx(it) } ?: LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        weight = 1f
                        setMargins(dpToPx(2), 0, dpToPx(2), 0)
                    }
                    addView(btn, lp)
                }
            }
            sheet.addView(row)
        }

        // 棋子按钮（圆形：cornerRadius 远大于半径即可呈现为圆）
        val pieceBtns = pieceNames.map { makeButton(it, 90f, 19f, 12) }
        addLabel("棋子"); addRow(pieceBtns, 46)
        // 位置按钮
        val posBtns = posNames.map { makeButton(it, 9f, 15f, 11) }
        addLabel("位置（同线多子时选）"); addRow(posBtns)
        // 数字按钮
        val numBtns = numNames.map { makeButton(it, 9f, 15f, 11) }
        addLabel("数字（线号）"); addRow(numBtns)
        // 动作按钮
        val actBtns = actNames.map { makeButton(it, 12f, 19f, 13) }
        addLabel("动作"); addRow(actBtns)

        // 工具按钮：撤销 / 清空
        val toolBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dm.density
            setColor(toolBtnBgColor)
        }
        val undoBtn = TextView(this).apply {
            text = "撤销"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(0xFFE8DCC6.toInt()); background = toolBtnBg
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), dpToPx(8))
        }
        val clearBtn = TextView(this).apply {
            text = "清空"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(0xFFE8DCC6.toInt()); background = toolBtnBg
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), dpToPx(8))
        }
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(undoBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(10) })
            addView(clearBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        sheet.addView(toolRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpToPx(12); gravity = Gravity.CENTER
        })

        val dialog = showBottomSheet(sheet)

        // 计算当前 4 个槽位内容（参考 HTML 的 parts() 逻辑）
        fun parts(): Array<String?> {
            val isPos = start != null && start!!.second == "pos"
            return arrayOf(
                if (isPos) start!!.first else piece,
                if (isPos) piece else start?.first,
                act,
                end
            )
        }

        // 渲染槽位、提示、按钮高亮
        fun render() {
            val p = parts()
            slots.forEachIndexed { i, tv ->
                val v = p[i]
                tv.text = v ?: "·"
                tv.setTextColor(if (v == null) textEmpty else textDark)
                tv.background = slotBg(v == null)
            }
            hint.text = when {
                piece == null -> "① 点击选择棋子"
                start == null -> "② 选择「位置」或「起始数字」"
                act == null -> "③ 选择 进 / 退 / 平"
                end == null -> "④ 选择目标数字"
                else -> "即将提交…"
            }
            pieceBtns.forEachIndexed { i, btn ->
                val active = piece == pieceNames[i]
                btn.background = btnBg(active, 90f)
                btn.setTextColor(if (active) Color.WHITE else textDark)
            }
            posBtns.forEachIndexed { i, btn ->
                val active = start != null && start!!.second == "pos" && start!!.first == posNames[i]
                btn.background = btnBg(active, 9f)
                btn.setTextColor(if (active) Color.WHITE else textDark)
                // 已选动作后禁用位置按钮：位置行"二/三/四"与数字行字符相同，
                // 用户在选完动作后容易误点位置行按钮导致 start 被覆盖
                val disabled = act != null && !busy
                btn.isEnabled = !disabled
                btn.alpha = if (disabled) 0.4f else 1f
            }
            numBtns.forEachIndexed { i, btn ->
                val activeStart = start != null && start!!.second == "num" && start!!.first == numNames[i]
                val activeEnd = end == numNames[i]
                btn.background = btnBg(activeStart || activeEnd, 9f)
                btn.setTextColor(if (activeStart || activeEnd) Color.WHITE else textDark)
            }
            actBtns.forEachIndexed { i, btn ->
                val active = act == actNames[i]
                btn.background = btnBg(active, 12f)
                btn.setTextColor(if (active) Color.WHITE else textDark)
            }
        }

        // 提交着法
        fun commit() {
            val text = parts().joinToString("")
            if (parseAndExecuteBlindMove(text)) {
                dialog.dismiss()
            } else {
                Toast.makeText(this@GameActivity, "未识别着法：$text", Toast.LENGTH_SHORT).show()
                piece = null; start = null; act = null; end = null
                busy = false
                render()
            }
        }

        fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        // 绑定事件
        pieceBtns.forEachIndexed { i, btn -> btn.setOnClickListener {
            if (busy) return@setOnClickListener
            piece = pieceNames[i]; render()
        }}
        posBtns.forEachIndexed { i, btn -> btn.setOnClickListener {
            if (busy) return@setOnClickListener
            if (piece == null) { toast("请先选择棋子"); return@setOnClickListener }
            if (act != null) { toast("已选动作，请先撤销再重选位置"); return@setOnClickListener }
            start = posNames[i] to "pos"; render()
        }}
        numBtns.forEachIndexed { i, btn -> btn.setOnClickListener {
            if (busy) return@setOnClickListener
            if (piece == null) { toast("请先选择棋子"); return@setOnClickListener }
            // 还没选动作 → 这个数字填「起始槽」
            if (act == null) {
                start = numNames[i] to "num"; render(); return@setOnClickListener
            }
            // 已经选了动作 → 这个数字填「目标槽」，触发自动提交
            if (end == null) {
                end = numNames[i]
                render()
                busy = true
                Handler(Looper.getMainLooper()).postDelayed({ commit() }, 200)
                return@setOnClickListener
            }
            toast("本步已完成，请点撤销或清空")
        }}
        actBtns.forEachIndexed { i, btn -> btn.setOnClickListener {
            if (busy) return@setOnClickListener
            if (piece == null) { toast("请先选择棋子"); return@setOnClickListener }
            if (start == null) { toast("请先选择位置或起始数字"); return@setOnClickListener }
            act = actNames[i]; render()
        }}
        undoBtn.setOnClickListener {
            if (busy) return@setOnClickListener
            when {
                end != null -> end = null
                act != null -> act = null
                start != null -> start = null
                piece != null -> piece = null
            }
            render()
        }
        clearBtn.setOnClickListener {
            if (busy) return@setOnClickListener
            piece = null; start = null; act = null; end = null
            render()
        }

        render()
    }

    /**
     * 解析文字着法并执行：枚举当前所有合法着法，按中文谱式模糊匹配。
     */
    private fun parseAndExecuteBlindMove(input: String): Boolean {
        val game = controller.game ?: return false
        val board = game.currentBoard
        val redToMove = controller.isRedTurn

        // 归一化输入：去空格、阿拉伯数字→中文数字、繁体→简体
        val normalized = normalizeBlindInput(input)

        // 枚举所有合法着法，生成 chsString → Move 映射
        // 注意：不能用 fromUCCIString(fromPos.toString()+toPos.toString())，
        // 因为 Position.toString() 返回 "(x, y)" 格式，拼接后长度不等于 4，fromUCCIString 必然失败。
        val candidates = mutableListOf<Pair<String, Move>>()
        for (fx in 0 until Board.BOARD_PIECE_WIDTH) {
            for (fy in 0 until Board.BOARD_PIECE_HEIGHT) {
                val fromPos = Position(fx, fy)
                val piece = board.getPieceByPosition(fromPos)
                if (!Piece.isValid(piece)) continue
                if (redToMove && !Piece.isRed(piece)) continue
                if (!redToMove && !Piece.isBlack(piece)) continue

                for (tx in 0 until Board.BOARD_PIECE_WIDTH) {
                    for (ty in 0 until Board.BOARD_PIECE_HEIGHT) {
                        val toPos = Position(tx, ty)
                        val move = Move(fromPos, toPos, board)
                        if (!RulesetProvider.get().isMoveLegal(move, board)) continue
                        val chs = move.chsString
                        if (chs.isEmpty() || chs == "未知动作") continue
                        candidates.add(chs to move)
                        // 兵卒在 4+ 同线时的标准规则变体：
                        // Move.getChsString 对 4+ 兵同线输出"一/二/三/四/五"（红）或"1/2/3/4/5"（黑），
                        // 但标准记谱规则应为"前/二/三/四/后"。生成标准规则变体加入候选，确保用户按
                        // 标准规则输入"前兵平五"等也能匹配。
                        pawnVariantForMultiOnFile(move, board)?.let { variant ->
                            if (variant != chs) candidates.add(variant to move)
                        }
                    }
                }
            }
        }

        // 精确匹配
        var match = candidates.find { it.first == normalized }
        // 模糊匹配：去掉"方"字等后缀差异
        if (match == null) {
            match = candidates.find { normalizeBlindInput(it.first).contains(normalized) }
        }
        // 多条匹配时取第一条（同着法 chsString 唯一）
        if (match != null) {
            val move = match.second
            game.setStartPos(move.fromPosition)
            game.setEndPos(move.toPosition)
            // 复用控制器的走子流程（含合法性校验、音效、引擎推进）
            controller.playerMoveByCloud(move.ucciString)
            return true
        }
        return false
    }

    /**
     * 生成兵卒在 4+ 同线时的标准规则变体 chsString。
     *
     * 标准规则（4+ 兵同线）：红方用"前兵/二兵/三兵/四兵/后兵"，
     * 黑方用"前卒/2卒/3卒/4卒/后卒"（黑方按规则用阿拉伯数字）。
     * 而 Move.getChsString 对 4+ 兵同线输出"一/二/三/四/五"或"1/2/3/4/5"，
     * 与标准规则不符。本函数把 chsString 中开头的位置标记替换为标准规则。
     *
     * 返回 null 表示：不是兵卒、同线兵数 < 4、或无需替换。
     */
    private fun pawnVariantForMultiOnFile(move: Move, board: Board): String? {
        val piece = board.getPieceByPosition(move.fromPosition)
        if (piece != Piece.WBING && piece != Piece.BZU) return null
        val isRed = Piece.isRed(piece)

        // 沿本方前进方向遍历同列棋子，统计同色兵卒数 count 与本子 index
        val ys = if (isRed) (0 until 10) else (9 downTo 0)
        var count = 0
        var index = 0
        for (y in ys) {
            val p = board.getPieceByPosition(move.fromPosition.x, y)
            if (p == piece) {
                count++
                if (move.fromPosition.y == y) index = count
            }
        }
        if (count < 4 || index !in 1..count) return null

        // 标准规则位置标记
        val standardMark = when {
            index == 1 -> "前"
            index == count -> "后"
            isRed -> when (index) {
                2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; else -> return null
            }
            else -> index.toString()  // 黑方用阿拉伯数字：2/3/4/...
        }
        // Move.getChsString 对 4+ 兵输出的位置标记
        val originalMark = if (isRed) when (index) {
            1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; else -> return null
        } else index.toString()

        val chs = move.chsString
        // chsString 格式为 [位置标记][棋子名][动作][数字]，位置标记在开头
        return if (chs.startsWith(originalMark)) chs.replaceFirst(originalMark, standardMark) else null
    }

    /** 归一化盲棋着法输入。 */
    private fun normalizeBlindInput(s: String): String {
        val arabicToChinese = mapOf(
            '0' to '零', '1' to '一', '2' to '二', '3' to '三', '4' to '四',
            '5' to '五', '6' to '六', '7' to '七', '8' to '八', '9' to '九'
        )
        val tradToSimp = mapOf(
            '馬' to '马', '車' to '车', '炮' to '炮', '象' to '象', '士' to '士',
            '將' to '将', '帥' to '帅', '卒' to '卒', '兵' to '兵', '進' to '进'
        )
        return buildString {
            for (ch in s) {
                val mapped = arabicToChinese[ch] ?: tradToSimp[ch] ?: ch
                if (mapped != ' ' && mapped != '\t' && mapped != '　') {
                    append(mapped)
                }
            }
        }
    }

    // ---------------- 好友对战模式 ----------------

    private fun initBattleMode() {
        val session = battleSession ?: return
        // 会话事件挂到本 Activity（会话本身存活于 Application，页面重建后重新 attach）
        session.listener = battleListener

        // 语音播放状态：左下角喇叭三帧动画 + 播放期间静默棋子音效，避免打断语音
        voicePlayer.onPlayStateChanged = { playing ->
            val anim = binding.battleVoiceIndicator.drawable as? AnimationDrawable
            if (playing) {
                soundPlayer.setMuted(true)
                binding.battleVoiceIndicator.visibility = View.VISIBLE
                anim?.start()
            } else {
                anim?.stop()
                binding.battleVoiceIndicator.visibility = View.GONE
                soundPlayer.setMuted(false)
            }
        }

        // 思考页：隐藏引擎指标行与PV，显示对战状态块
        binding.engineMetricRow.visibility = View.GONE
        binding.thinkingMoves.visibility = View.GONE
        binding.battleStatusBlock.visibility = View.VISIBLE
        // 隐藏"评估"tab（无引擎数据）；分段背景天然保持连贯（左/中/右样式不变）
        binding.tabEval.visibility = View.GONE
        binding.trendchart.visibility = View.GONE
        // 云库着法默认不显示，防止双方直接参考云库；连续快点点"对局棋谱"3次解锁
        cloudTabUnlocked = false
        movesTabTapTimes.clear()
        binding.tabCloud.visibility = View.GONE

        // 底部按钮重映射：求和 / 悔棋 / 认输 / 导出FEN / 退出
        fun remapButton(
            container: android.view.ViewGroup,
            iconRes: Int,
            label: String,
            enabled: Boolean,
            action: (() -> Unit)?
        ) {
            container.isEnabled = enabled
            container.alpha = if (enabled) 1f else 0.4f
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is ImageView) { child.setImageResource(iconRes); break }
            }
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is TextView) { child.text = label; break }
            }
            if (action != null) {
                container.setOnClickListener { action() }
            } else {
                container.setOnClickListener(null)
            }
        }
        if (watchMode) {
            // 观战只读：五个操作位全部禁用，无聊天/语音入口
            remapButton(binding.newGameButton, R.drawable.ic_battle_draw, "求和", false, null)
            remapButton(binding.engineModeButton, R.drawable.ic_btn_undo, "悔棋", false, null)
            remapButton(binding.undoButton, R.drawable.ic_voice, "语音", false, null)
            remapButton(binding.flipButton, R.drawable.ic_battle_resign, "认输", false, null)
            remapButton(binding.moreButton, R.drawable.ic_text, "文字", false, null)
        } else {
            // 1.新对局位 → 求和
            remapButton(binding.newGameButton, R.drawable.ic_battle_draw, "求和", true) { offerDrawBattle() }
            // 2.引擎位 → 悔棋（向对方发送悔棋请求）
            remapButton(binding.engineModeButton, R.drawable.ic_btn_undo, "悔棋", true) {
                controller.playerUndo()
            }
            // 3.悔棋位 → 语音（按住直接录音：松开发送，上滑出绿色区域取消）
            remapButton(binding.undoButton, R.drawable.ic_voice, "语音", true, null)
            attachPushToTalk(binding.undoButton)
            // 4.翻转位 → 认输
            remapButton(binding.flipButton, R.drawable.ic_battle_resign, "认输", true) { resignBattle() }
            // 5.更多位 → 文字（打开聊天对话框：文字输入，框内仍可按住说话）
            remapButton(binding.moreButton, R.drawable.ic_text, "文字", true) {
                showBattleChatDialog()
            }
        }

        // 进程被杀 / Activity 被回收后重建：先用本地落盘快照恢复棋盘，
        // 再用会话内已对账快照（同进程重连场景）收敛；后续服务器 sync_state 仍会以着法更长方为准
        // （观战模式不走本地快照：局面以 watch_ok 携带的走子缓存为准）
        val localSnap = BattleStateStore.getSnapshot()
        if (!watchMode && localSnap != null && localSnap.moves.isNotEmpty()) {
            networkController?.restoreLocalSnapshot(localSnap.fen, localSnap.moves)
        }
        if (!watchMode && session.lastSyncFen.isNotEmpty()) {
            networkController?.applySyncState(
                session.lastSyncFen, session.lastSyncMoves, session.lastSyncTurn)
        }
        battleResultShown = false
        battleClosedHandled = false
        opponentOfflineCountdown = 0
        // 重连补投的对局消息可能落在监听重挂的空档（页面被 ROM 回收重建等）被旧监听吞掉，
        // 对局已开始时主动对账一次，以对方回推的权威快照兜底收敛（对端离线时服务器静默丢弃，无副作用）
        if (!watchMode && session.gameStarted) {
            session.requestSync()
        }
        // 按会话当前态统一收敛冻结状态/横幅（含进程重建后立即是 DISCONNECTED/CLOSED 的情况）
        battleListener.onStateChanged(session.state)
        updateBattleStatus()
        updateBattleBanner()

        // 前台保活：对局中切后台不断连
        BattleKeepAliveService.start(this)
    }

    /**
     * 新一局交换先后手后同步棋盘视角：原黑方改执红则翻回红下，原红方改执黑则翻转黑下。
     * watchMode 旁观无颜色概念不翻。随后 UPDATEUI 重绘，触摸映射走 ChessView.flipped 一致。
     */
    private fun flipBoardIfColorSwapped(redBefore: Boolean?) {
        if (!battleMode || watchMode || redBefore == null) return
        val redNow = networkController?.isMyColorRed ?: return
        if (redNow != redBefore) {
            chessView.flipped = redNow != true
        }
    }

    /** 刷新对战状态块（房间号/执子/连接/对手）。 */
    private fun updateBattleStatus() {
        if (!battleMode) return
        val session = battleSession ?: return
        if (watchMode) {
            // 观战：显示桌号与对阵双方昵称，无"我方执子/对手"概念
            binding.battleRoomCode.text = "观战 · 第${session.watchingTable}桌"
            binding.battleMyColor.text =
                "红方 ${session.redNick.ifEmpty { "—" }}  vs  黑方 ${session.blackNick.ifEmpty { "—" }}"
            binding.battleConnState.text = when (session.state) {
                BattleSessionManager.State.DISCONNECTED -> "连接状态：重连中…"
                else -> "连接状态：已连接"
            }
            binding.battleOpponentState.text = "观战中（只读）"
            return
        }
        binding.battleRoomCode.text =
            if (session.seatedTable != 0) "大厅 · 第${session.seatedTable}桌"
            else "房间号 ${session.roomCode ?: "—"}"
        val myRed = networkController?.isMyColorRed == true
        binding.battleMyColor.text =
            if (myRed) "我方执子：红方（先行）" else "我方执子：黑方（后行）"
        binding.battleConnState.text = when (session.state) {
            BattleSessionManager.State.DISCONNECTED -> "连接状态：重连中…"
            BattleSessionManager.State.PLAYING, BattleSessionManager.State.READY,
            BattleSessionManager.State.IN_LOBBY, BattleSessionManager.State.SEATED_WAITING,
            BattleSessionManager.State.WATCHING -> "连接状态：已连接"
            else -> "连接状态：连接中…"
        }
        binding.battleOpponentState.text = if (opponentOnline) "对手：在线" else "对手：断线"
    }

    /** 统一计算对战横幅：我方连接断开优先于对方离线倒计时。 */
    private fun updateBattleBanner() {
        if (!battleMode) return
        val session = battleSession
        when {
            session != null && session.state == BattleSessionManager.State.DISCONNECTED -> {
                binding.battleBanner.text = "连接中断，重连中…"
                binding.battleBanner.visibility = View.VISIBLE
            }
            opponentOfflineCountdown > 0 -> {
                val text = "对方离线，等待重连（%d:%02d 后自动结束）".format(
                    opponentOfflineCountdown / 60, opponentOfflineCountdown % 60)
                binding.battleBanner.text = text
                binding.battleBanner.visibility = View.VISIBLE
            }
            else -> binding.battleBanner.visibility = View.GONE
        }
    }

    // ---------------- 好友对战：聊天（语音/文字） ----------------

    /** 聊天对话框：中间输入框（限 40 字），框内右侧按住说话，框外右侧回车发送。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBattleChatDialog() {
        val session = battleSession ?: return
        val view = layoutInflater.inflate(R.layout.dialog_battle_chat, null)
        val input: EditText = view.findViewById(R.id.chatInput)
        val mic: ImageButton = view.findViewById(R.id.chatMicButton)
        val send: ImageButton = view.findViewById(R.id.chatSendButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle("聊天")
            .setView(view)
            .create()
        battleChatDialog = dialog

        fun sendText() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return
            if (session.sendChatText(text)) {
                dialog.dismiss()
            } else {
                Toast.makeText(this, "当前无法发送，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
        send.setOnClickListener { sendText() }
        input.setOnEditorActionListener { _, _, _ ->
            sendText()
            true
        }

        mic.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (voiceRecorder.start()) {
                        input.setText("")
                        input.isEnabled = false
                        input.hint = "正在录音…松开发送（最长20秒）"
                    } else {
                        Toast.makeText(this, "无法启动录音", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (voiceRecorder.isRecording) stopRecordingAndSend(input)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 手势被中断（非松手）：丢弃本次录音
                    if (voiceRecorder.isRecording) {
                        voiceRecorder.cancel()
                        input.isEnabled = true
                        input.hint = "发送文字（40字内）"
                    }
                    true
                }
                else -> false
            }
        }

        // 到达 20 秒上限自动停止：与松手一致，直接发送
        voiceRecorder.onMaxDurationReached = {
            if (voiceRecorder.isRecording) stopRecordingAndSend(input)
        }

        dialog.setOnDismissListener {
            if (voiceRecorder.isRecording) voiceRecorder.cancel()
            battleChatDialog = null
        }
        dialog.show()
    }

    /** 结束录音（松手/到时长）并直接发送；时间过短则提示并丢弃，数据一律不保留。 */
    private fun stopRecordingAndSend(input: EditText) {
        val result = voiceRecorder.finish()
        input.isEnabled = true
        input.hint = "发送文字（40字内）"
        if (result == null) {
            Toast.makeText(this, "录音时间太短", Toast.LENGTH_SHORT).show()
            return
        }
        val b64 = Base64.encodeToString(result.bytes, Base64.NO_WRAP)
        if (battleSession?.sendChatVoice(b64, result.durationMs) != true) {
            Toast.makeText(this, "当前无法发送，请稍后重试", Toast.LENGTH_SHORT).show()
        } else {
            // 与文字发送一致：发出后关闭聊天对话框
            battleChatDialog?.dismiss()
        }
    }

    // ---------------- 好友对战：按住说话（语音按钮 PTT） ----------------

    /** 手指上滑超过绿色弧区可见高度即进入"松开取消"态，下滑回来恢复。 */
    private var voiceRecordBandH = 0
    /** 弧形区顶部在屏幕中的绝对 Y 坐标（布局后取实际 View 位置，不依赖屏幕高度猜测）。 */
    private var voiceRecordArcTopY = 0f

    /**
     * 绑定按住说话手势到"语音"按钮：
     * 按下立即录音并弹全屏提示；上滑出绿色区域变"松开取消"（下滑回来可恢复）；
     * 松手在正常区则发送、在取消区则丢弃；手势被系统打断（CANCEL）一律丢弃。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPushToTalk(container: android.view.ViewGroup) {
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnTouchListener true
                    }
                    if (!voiceRecorder.start()) {
                        Toast.makeText(this, "无法启动录音", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    voiceRecordCancel = false
                    voiceRecordDownRawY = event.rawY
                    voiceRecorder.onMaxDurationReached = {
                        // 到达 20 秒上限：与松手在正常区一致，直接发送
                        if (voiceRecorder.isRecording) finishPushToTalk(send = true)
                    }
                    showVoiceRecordOverlay()
                    updateVoiceRecordOverlay(false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (voiceRecorder.isRecording && voiceRecordArcTopY > 0f) {
                        val cancel = event.rawY < voiceRecordArcTopY
                        if (cancel != voiceRecordCancel) {
                            voiceRecordCancel = cancel
                            updateVoiceRecordOverlay(cancel)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (voiceRecorder.isRecording) finishPushToTalk(send = !voiceRecordCancel)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 手势被父容器/系统中断（非松手）：丢弃本次录音
                    if (voiceRecorder.isRecording) finishPushToTalk(send = false)
                    true
                }
                else -> false
            }
        }
    }

    /** 按下时的手指纵坐标（rawY），上滑距离以此为基准。 */
    private var voiceRecordDownRawY = 0f

    /** 显示按住说话全屏提示层（透明不抢触摸，触摸序列仍由语音按钮持有）。 */
    private fun showVoiceRecordOverlay() {
        if (isFinishing || isDestroyed) return
        val root = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        if (voiceRecordOverlay?.parent === root) return
        val overlay = layoutInflater.inflate(R.layout.overlay_battle_voice_record, root, false)
        // 录音区几何：直径=2×屏宽的正圆，圆心压到屏幕底部以下，只让圆的顶部 1/4
        // 露出（露出高度=屏宽/2），弧顶浅而平，对齐微信"语音发送.jpg"；
        // 提示文字在弧顶之上，麦克风在可见弧区内。
        val screenW = resources.displayMetrics.widthPixels
        val diameter = screenW * 2
        val bandH = diameter / 6
        voiceRecordBandH = bandH
        val density = resources.displayMetrics.density
        overlay.findViewById<View>(R.id.voiceRecordZone).layoutParams =
            FrameLayout.LayoutParams(diameter, diameter).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = -(diameter - bandH)
            }
        (overlay.findViewById<View>(R.id.voiceRecordMic).layoutParams as FrameLayout.LayoutParams)
            .bottomMargin = (bandH * 0.42f).toInt()
        (overlay.findViewById<View>(R.id.voiceRecordHint).layoutParams as FrameLayout.LayoutParams)
            .bottomMargin = bandH + (12 * density).toInt()
        root.addView(overlay)
        voiceRecordOverlay = overlay
        // 布局完成后取弧形区顶部在屏幕中的实际 Y 坐标，作为取消/恢复的边界
        // （用实际渲染位置而非屏幕高度推算，不受状态栏/导航栏/按钮栏高度影响）
        val zone = overlay.findViewById<View>(R.id.voiceRecordZone)
        zone.post {
            val loc = IntArray(2)
            zone.getLocationOnScreen(loc)
            voiceRecordArcTopY = loc[1].toFloat()
        }
    }

    /**
     * 切换提示层状态：正常态=绿色区域+白麦克风+"上滑取消发送"；
     * 取消态=灰色区域+灰麦克风+红色"松开取消"。
     * 上滑基准点在按下时记录（voiceRecordDownRawY），移动过程不更新。
     */
    private fun updateVoiceRecordOverlay(cancel: Boolean) {
        val overlay = voiceRecordOverlay ?: return
        val zone = overlay.findViewById<View>(R.id.voiceRecordZone)
        val mic = overlay.findViewById<ImageView>(R.id.voiceRecordMic)
        val hint = overlay.findViewById<TextView>(R.id.voiceRecordHint)
        if (cancel) {
            zone.setBackgroundResource(R.drawable.bg_voice_record_gray)
            mic.setColorFilter(0xFF9A9A9A.toInt())
            hint.text = "松开取消"
            hint.setTextColor(0xFFFA5151.toInt())
        } else {
            zone.setBackgroundResource(R.drawable.bg_voice_record_green)
            mic.setColorFilter(Color.WHITE)
            hint.text = "上滑取消发送"
            hint.setTextColor(0xFF2EAD45.toInt())
        }
    }

    /** 结束按住说话：隐藏提示层，按 send 决定发送或丢弃；过短录音提示并丢弃。 */
    private fun finishPushToTalk(send: Boolean) {
        hideVoiceRecordOverlay()
        if (!send) {
            voiceRecorder.cancel()
            return
        }
        val result = voiceRecorder.finish()
        if (result == null) {
            Toast.makeText(this, "录音时间太短", Toast.LENGTH_SHORT).show()
            return
        }
        val b64 = Base64.encodeToString(result.bytes, Base64.NO_WRAP)
        if (battleSession?.sendChatVoice(b64, result.durationMs) != true) {
            Toast.makeText(this, "当前无法发送，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    /** 移除按住说话提示层（录音结束/页面销毁时调用）。 */
    private fun hideVoiceRecordOverlay() {
        val overlay = voiceRecordOverlay ?: return
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        voiceRecordOverlay = null
    }

    /** 播放对方语音：base64 解码后自动播放；喇叭动画与音效互斥由 onPlayStateChanged 驱动。 */
    private fun playBattleVoice(amrB64: String) {
        val bytes = try {
            Base64.decode(amrB64, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
        if (bytes == null || bytes.isEmpty()) return
        voicePlayer.play(bytes)
    }

    /** 求和（more 菜单入口）：向对方发送求和请求，结果经回调返回。 */
    private fun offerDrawBattle() {
        // 等待对方确认新一局：双方都就绪后才能求和
        if (networkController?.isWaitingRematchAck == true) {
            Toast.makeText(this, "等待对方确认新一局，无法求和", Toast.LENGTH_SHORT).show()
            return
        }
        val game = controller.game
        if (game == null || game.isGameOver) {
            Toast.makeText(this, "对局已结束，无法求和", Toast.LENGTH_SHORT).show()
            return
        }
        battleSession?.sendDrawOffer()
        Toast.makeText(this, "求和请求已发送，等待对方答复", Toast.LENGTH_SHORT).show()
    }

    /**
     * 好友对局结束（绝杀/认输/和棋）：双方各弹一次"开始新一局或退出"。
     * 一方选择退出仅释放自己的座位（棋桌保留），另一方经 onOpponentLeft 得到通知，
     * 可回大厅继续等待新对手；对局中的退出仍走 leaveBattleNow 解散棋桌。
     * 对话框设为不可取消：点外部/返回键不会消失，必须做出选择。
     */
    private fun showBattleResultDialog() {
        if (battleResultShown || isFinishing || isDestroyed) return
        val game = controller.game ?: return
        if (!game.isGameOver) return
        battleResultShown = true
        // 本局已终结：撤销对方此前发出、仍挂着的求和/悔棋请求对话框
        dismissPendingNegotiationDialogs()
        val dlg = AlertDialog.Builder(this)
            .setTitle("本局结束")
            .setMessage("可以开始新一局，或退出对战。")
            .setCancelable(false)
            .setPositiveButton("开始新一局") { _, _ ->
                battleResultShown = false
                battleAutoSaved = false
                // 本方发起新一局：重置棋盘并锁定，等服务端仲裁（对方也点一次）
                // 达成后双方会收到带颜色的 start_game，届时统一换色+重置
                networkController?.requestRematch()
                // 立即置灰求和/悔棋/认输：对方确认前新局尚未开始
                updateBattleActionButtonsEnabled()
                if (networkController?.isWaitingRematchAck != true) {
                    // 对方此前已先请求：本方点击即达成一致，直接开局
                    Toast.makeText(this, "双方已确认新一局，红方先行", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("退出对战") { _, _ ->
                // 已自动保存或走棋不足5步：直接退出；否则退出前询问是否保存棋谱
                if (!battleAutoSaved
                    && GameRecordExporter.shouldPromptSave(controller.game?.history ?: emptyList())
                ) {
                    showSavePgnDialog(
                        actionLabel = "退出",
                        exitHint = "退出仅释放你的座位，棋桌保留，对方可继续等待新对手。",
                        onSaveThen = { exitAfterGame() },
                        onSkipSaveThen = { exitAfterGame() }
                    )
                } else {
                    exitAfterGame()
                }
            }
            .show()
        battleResultDialog = dlg
    }

    /** 撤销仍挂着的求和/悔棋请求对话框——对局已终结或新局已开始，旧局协商不再有意义。 */
    private fun dismissPendingNegotiationDialogs() {
        drawOfferDialog?.dismiss()
        drawOfferDialog = null
        undoRequestDialog?.dismiss()
        undoRequestDialog = null
    }

    /** 延迟弹出终局对话框（让音效/终局画面先呈现）；记录任务以便对方离开时取消。 */
    private fun postResultDialogDelayed(delayMs: Long) {
        val r = Runnable { showBattleResultDialog() }
        pendingResultDialogRunnable = r
        battleCountdownHandler.postDelayed(r, delayMs)
    }

    /** 关闭终局对话框并取消待弹任务（对方离开房间时调用：房间已销毁，重开无意义）。 */
    private fun dismissBattleResultDialog() {
        pendingResultDialogRunnable?.let { battleCountdownHandler.removeCallbacks(it) }
        pendingResultDialogRunnable = null
        battleResultDialog?.dismiss()
        battleResultDialog = null
        battleResultShown = true
    }

    /**
     * 好友对战：等待对方确认新一局期间置灰 求和/悔棋/认输 底部按钮——
     * 必须双方都点了"开始新一局"（对方确认到达）才算新局开始，期间不允许发这些消息。
     * 另：悔棋请求已发出、等待对方回复期间，悔棋按钮单独置灰，避免重复发送；
     * 对方同意/拒绝、本方走棋、新局开始时由 UPDATEUI/MOVE 事件触发本方法恢复。
     */
    private fun updateBattleActionButtonsEnabled() {
        // 观战只读：操作按钮恒禁用，不做状态恢复
        if (!battleMode || watchMode) return
        val waiting = networkController?.isWaitingRematchAck == true
        listOf(binding.newGameButton, binding.engineModeButton, binding.undoButton).forEach {
            it.isEnabled = !waiting
            it.alpha = if (waiting) 0.4f else 1f
        }
        // 悔棋请求待回复期间置灰悔棋按钮（engineModeButton 已重映射为"悔棋"）
        if (!waiting && networkController?.isWaitingUndoAck == true) {
            binding.engineModeButton.isEnabled = false
            binding.engineModeButton.alpha = 0.4f
        }
    }

    /** 认输（more 菜单入口）。对局已结束或等待对方确认新一局时不再允许认输。 */
    private fun resignBattle() {
        // 等待对方确认新一局：双方都就绪后才能认输
        if (networkController?.isWaitingRematchAck == true) {
            Toast.makeText(this, "等待对方确认新一局，无法认输", Toast.LENGTH_SHORT).show()
            return
        }
        if (controller.game?.isGameOver == true) {
            Toast.makeText(this, "对局已结束，无法认输", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("认输")
            .setMessage("确定认输吗？本局将判你负。")
            .setPositiveButton("认输") { _, _ -> networkController?.resign() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 离开对战确认（新对局按钮与退出按钮共用）。 */
    private fun showLeaveBattleConfirm() {
        // 观战只读：退出不影响对局，无需确认与棋谱保存询问
        if (watchMode) {
            leaveBattleNow()
            return
        }
        // 已自动保存或走棋不足5步：直接确认退出；否则退出前询问是否保存棋谱
        if (!battleAutoSaved
            && GameRecordExporter.shouldPromptSave(controller.game?.history ?: emptyList())
        ) {
            showSavePgnDialog(
                actionLabel = "退出",
                exitHint = "退出将离开房间并结束本局。",
                onSaveThen = { leaveBattleNow() },
                onSkipSaveThen = { leaveBattleNow() }
            )
            return
        }
        AlertDialog.Builder(this)
            .setTitle("离开对战")
            .setMessage("退出将离开房间并结束本局，确定退出吗？")
            .setPositiveButton("退出") { _, _ -> leaveBattleNow() }
            .setNegativeButton("继续对局", null)
            .show()
    }

    /**
     * 终局后退出：带 after_game 标记离座，服务端仅释放本方座位、保留棋桌，
     * 对方不被踢出（收到 opponent_left 后自行回大厅）。返回后大厅长连保持。
     */
    private fun exitAfterGame() {
        if (isExiting) return
        isExiting = true
        battleSession?.standAfterGame()
        BattleKeepAliveService.stop(this)
        finish()
    }

    /** 离开对战：大厅入座离座 / 观战停止观战 / 房号模式销毁房间，随后关闭页面。 */
    private fun leaveBattleNow() {
        // 先置退出标记：leave() 会同步抛 CLOSED 状态回调，避免终态对话框叠加
        isExiting = true
        val session = battleSession
        when {
            // 观战退出：仅停止观战，保持大厅长连
            watchMode -> {
                session?.unwatch()
                BattleKeepAliveService.stop(this)
                finish()
            }
            // 大厅入座：离座（未开局直接离座；已开局由服务端解散棋桌并通知对方），保持大厅长连
            session != null && session.seatedTable != 0 -> {
                session.stand()
                BattleKeepAliveService.stop(this)
                finish()
            }
            // 大厅棋桌已被服务端解散（对方离开/认输后房间销毁）：座位已清，保持大厅长连直接返回
            session != null && session.state == BattleSessionManager.State.IN_LOBBY -> {
                BattleKeepAliveService.stop(this)
                finish()
            }
            else -> {
                session?.leave()
                session?.destroy()
                finish()
            }
        }
    }

    /** 房间已终态关闭（服务器回收/重连失败）：不再发 leave，仅清理本地并返回主界面。 */
    private fun exitBattleOnClosed() {
        isExiting = true
        battleSession?.destroy()
        BattleKeepAliveService.stop(this)
        finish()
    }

    // ---------------- P3 信息页切换 ----------------

    /**
     * 折叠/展开信息卡片。
     * 折叠：头部(tab)与内容页隐藏，仅显示 40dp 折叠条；卡片解除底部约束改为 wrap_content，
     *      顶部仍贴 gl_card_top（紧贴棋盘下沿），卡片下方露出山水背景，底部按钮栏不动。
     * 展开：恢复底部约束到 gl_card_bottom 与 0dp 撑满高度。
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

    private val tabButtons: List<ImageView> by lazy {
        listOf(binding.tabThinking, binding.tabEval, binding.tabCloud, binding.tabMoves)
    }
    private val tabPages: List<View> by lazy {
        listOf(binding.pageThinking, binding.trendchart, binding.pageCloud, binding.movesScroll)
    }

    // 每个位置的选中/未选中背景：左段(半圆头在左)、中段(方形)、中段、右段(半圆头在右)
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
                // 深褐色图标，与浅色半透明背景形成足够对比（避免与背景糊在一起）
                tab.imageTintList = ColorStateList.valueOf(0xFF5C4A3D.toInt())
            }
        }
        tabPages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (index == 2) {
            refreshCloudBook()
        }
        historyAndTrendAdapter.update()
    }

    /**
     * 好友对战：云库着法页默认隐藏。在 [CLOUD_UNLOCK_WINDOW_MS] 内连续点击
     * "对局棋谱"3 次即解锁，云库分段按钮出现并直接切到云库页（本局有效）。
     */
    private fun maybeUnlockCloudTab() {
        if (!battleMode || cloudTabUnlocked) return
        val now = SystemClock.uptimeMillis()
        while (movesTabTapTimes.isNotEmpty() && now - movesTabTapTimes.first() > CLOUD_UNLOCK_WINDOW_MS) {
            movesTabTapTimes.removeFirst()
        }
        movesTabTapTimes.addLast(now)
        if (movesTabTapTimes.size >= 3) {
            cloudTabUnlocked = true
            movesTabTapTimes.clear()
            binding.tabCloud.visibility = View.VISIBLE
            switchTab(2)
            Toast.makeText(this, "云库参考走法已开启", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- P1 顶部标题 ----------------
    private fun setTitle(text: String) {
        binding.title.text = text
    }

    /** 根据当前评分与轮走方刷新标题，如"红优 30 · 思考中" */
    private fun refreshTitle() {
        val game = controller.game
        if (game == null) {
            // 尚未加载到对局（如首次安装无存档），显示默认标题
            setTitle("橘中弈象棋")
            return
        }
        if (blindMode) {
            val phase = when {
                game.isGameOver -> "对局结束"
                !controller.isHumanTurn() -> "引擎思考中"
                controller.isRedTurn -> "红方走棋"
                else -> "黑方走棋"
            }
            val moveDesc = game.getLastMoveDesc()
            setTitle("${if (moveDesc.isNotEmpty()) "$moveDesc · " else ""}$phase")
            return
        }
        if (battleMode) {
            // 好友对战：无引擎评分，显示轮走方/等待对手
            val nc = networkController
            val phase = when {
                nc != null && nc.isWaitingRematchAck -> "等待对方确认新一局"
                game.isGameOver -> "对局结束"
                nc != null && nc.isWaitingOpponent -> "等待对手走子"
                controller.isRedTurn -> "轮到红方"
                else -> "轮到黑方"
            }
            setTitle("好友对战 · $phase")
            return
        }
        val scoreCp = (game.currentBoard.score * 100).toInt()
        val advantage = when {
            scoreCp >= 5 -> "红优 $scoreCp"
            scoreCp <= -5 -> "黑优 ${-scoreCp}"
            else -> "均势"
        }
        val phase = when {
            controller.isAnalyzing -> "分析中"
            controller.isEngineBusy -> "思考中"
            controller.isRedTurn -> "轮到红方"
            else -> "轮到黑方"
        }
        setTitle("$advantage · $phase")
    }

    // ---------------- 通用 UI 工具 ----------------
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** 显示一个底部弹窗（自动设置透明背景以露出圆角） */
    private fun showBottomSheet(contentView: View): BottomSheetDialog {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(contentView)
        (contentView.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.show()
        return dialog
    }

    /** 创建"图标+文字"的网格单元，用于底部弹窗 */
    private fun createIconCell(
        label: String,
        iconRes: Int,
        iconSizeDp: Int,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(12), 0, dpToPx(12))
            if (enabled) {
                setOnClickListener { onClick() }
            } else {
                alpha = 0.4f
            }
            addView(ImageView(this@GameActivity).apply {
                setImageResource(iconRes)
            }, LinearLayout.LayoutParams(dpToPx(iconSizeDp), dpToPx(iconSizeDp)))
            addView(TextView(this@GameActivity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFF4E4E4C.toInt())
                setPadding(0, dpToPx(8), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------- P3 云库参考着法 ----------------
    /** 刷新云库列表（同一局面不重复查询；手动重试时强制刷新） */
    private fun refreshCloudBook(forceRefresh: Boolean = false) {
        val game = controller.game
        if (game == null) {
            showCloudStatus("暂无对局")
            return
        }
        val fen = game.currentBoard.toFENString()
        if (!forceRefresh && fen == cloudFen) return
        cloudFen = fen

        binding.cloudMoves.removeAllViews()
        if (game.isGameOver) {
            showCloudStatus("对局已结束")
            return
        }
        showCloudStatus("正在查询云库...")
        CloudBookClient.query(fen, forceRefresh) { result ->
            // 网络返回时局面可能已变化，仅当仍停留在该局面时渲染
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
                // 网络对战：仅轮到本地玩家走子时云库着法才可点击（对方回合屏蔽）
                val netPlayable = if (battleMode) {
                    networkController?.isWaitingOpponent != true
                            && networkController?.isWaitingRematchAck != true
                } else true
                // 非网络模式：仅当前轮走方是人类、且处于玩家待走/后台分析状态时，点击列表才能走棋
                val playable = netPlayable
                        && controller.isHumanTurn
                        && controller.game?.isGameOver != true
                        && (controller.isAnalyzing || !controller.isEngineBusy)

                moves.forEach { cm ->
                    val row = layoutInflater.inflate(
                        R.layout.item_cloud_move, binding.cloudMoves, false)
                    row.findViewById<TextView>(R.id.cloudMoveName).text =
                        cloudMoveChinese(cm.ucci)
                    row.findViewById<TextView>(R.id.cloudWinrate).text =
                        "胜率：${if (cm.winrate.isBlank()) "—" else "${cm.winrate}%"}"

                    // 云库score为红方视角：黑方走棋时取反，使评分高=对当前走棋方有利
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
                        // 不可点击时变暗提示（引擎回合/对方回合/对局结束）
                        row.alpha = 0.45f
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

    /** 把UCCI着法串翻译成中文着法（车九进一），失败时显示原始串 */
    private fun cloudMoveChinese(ucci: String): String {
        val board = controller.game?.currentBoard ?: return ucci
        val m = Move(board)
        return if (m.fromUCCIString(ucci)) m.chsString else ucci
    }

    // ---------------- P4 引擎思考细节推演 ----------------
    /**
     * 双击"思考"面板着法后弹出：小棋盘初始局面与大棋盘完全一致，
     * 引擎已出示的主PV着法可在小棋盘上逐着前后推演（不影响大棋盘真实对局）。
     */
    private fun showPvReplayDialog() {
        // 快照打开时刻的 PV（之后引擎继续思考刷新也不影响本次推演）
        val ucci = ArrayList(controller.thinkPvUcci)
        if (ucci.isEmpty()) return
        val chs = ArrayList(controller.thinkPvMoves)
        val baseBoard = Board(controller.game.currentBoard)

        val view = layoutInflater.inflate(R.layout.dialog_pv_replay, null)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)

        val pvBoard = view.findViewById<OverlayChessView>(R.id.pvBoard)
        val stepText = view.findViewById<TextView>(R.id.pvStepText)
        val btnFirst = view.findViewById<ImageButton>(R.id.pvBtnFirst)
        val btnPrev = view.findViewById<ImageButton>(R.id.pvBtnPrev)
        val btnNext = view.findViewById<ImageButton>(R.id.pvBtnNext)
        val btnLast = view.findViewById<ImageButton>(R.id.pvBtnLast)
        pvBoard.showSideBar = false
        pvBoard.flipped = chessView.flipped

        var step = 0 // 已在小棋盘上推演的着法数，0=与大棋盘相同的初始局面

        fun render() {
            // 每次从初始局面重建：最多16着，开销可忽略，且无需处理悔棋
            val b = Board(baseBoard)
            var arrowFrom: Position? = null
            var arrowTo: Position? = null
            for (i in 0 until step) {
                val m = Move(b)
                if (!m.fromUCCIString(ucci[i])) break
                if (i == step - 1) {
                    arrowFrom = m.fromPosition
                    arrowTo = m.toPosition
                }
                b.doMove(m)
            }
            val arr = IntArray(Board.BOARD_PIECE_WIDTH * Board.BOARD_PIECE_HEIGHT) {
                b.getPieceByPosition(it % Board.BOARD_PIECE_WIDTH, it / Board.BOARD_PIECE_WIDTH)
            }
            pvBoard.pieces = arr
            pvBoard.arrowFrom = arrowFrom?.let { it.x to it.y }
            pvBoard.arrowTo = arrowTo?.let { it.x to it.y }
            stepText.text = if (step == 0) {
                "初始局面（0/${ucci.size}）"
            } else {
                "$step/${ucci.size}  ${chs.getOrElse(step - 1) { ucci[step - 1] }}"
            }
            val canBack = step > 0
            val canForward = step < ucci.size
            btnFirst.isEnabled = canBack
            btnPrev.isEnabled = canBack
            btnNext.isEnabled = canForward
            btnLast.isEnabled = canForward
            btnFirst.alpha = if (canBack) 1f else 0.35f
            btnPrev.alpha = if (canBack) 1f else 0.35f
            btnNext.alpha = if (canForward) 1f else 0.35f
            btnLast.alpha = if (canForward) 1f else 0.35f
        }

        btnFirst.setOnClickListener { step = 0; render() }
        btnPrev.setOnClickListener { if (step > 0) { step--; render() } }
        btnNext.setOnClickListener { if (step < ucci.size) { step++; render() } }
        btnLast.setOnClickListener { step = ucci.size; render() }
        view.findViewById<View>(R.id.pvReplayClose).setOnClickListener { dialog.dismiss() }

        render()
        dialog.show()
        // 弹窗宽度随小棋盘收窄（棋盘占内容区，约为原大棋盘的60%），两侧不留大片空白
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.62f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ---------------- P4 引擎工作模式 ----------------
    /** 恢复上次退出时选择的引擎工作模式；从未设置过时沿用 GameController 默认值"引擎执黑"。 */
    private fun restoreEngineMode() {
        val sp = getSharedPreferences(PREFS_COMMON, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_ENGINE_PLAYS_RED)) return
        val engineRed = sp.getBoolean(KEY_ENGINE_PLAYS_RED, false)
        val engineBlack = sp.getBoolean(KEY_ENGINE_PLAYS_BLACK, true)
        // 直接赋值而非 setOpponentMode：此时尚未 loadGameStatus()，避免驱动空棋局
        controller.enginePlaysRed = engineRed
        controller.enginePlaysBlack = engineBlack
        controller.isComputerPlaying = engineRed || engineBlack
    }

    /** 持久化引擎工作模式，下次进入人机大战时沿用。 */
    private fun saveEngineMode(engineRed: Boolean, engineBlack: Boolean) {
        getSharedPreferences(PREFS_COMMON, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENGINE_PLAYS_RED, engineRed)
            .putBoolean(KEY_ENGINE_PLAYS_BLACK, engineBlack)
            .apply()
    }

    private fun showEngineModeSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_engine_mode, null)
        val container = view.findViewById<LinearLayout>(R.id.engine_mode_container)
        val dialog = showBottomSheet(view)

        data class EngineModeItem(val label: String, val engineRed: Boolean, val engineBlack: Boolean)

        val modes = listOf(
            EngineModeItem("引擎执黑", false, true),
            EngineModeItem("引擎执红", true, false),
            EngineModeItem("引擎对引擎", true, true),
            EngineModeItem("玩家对玩家", false, false)
        )

        val selectedIndex = modes.indexOfFirst {
            it.engineRed == controller.enginePlaysRed && it.engineBlack == controller.enginePlaysBlack
        }

        modes.forEachIndexed { index, mode ->
            val isSelected = index == selectedIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(28), dpToPx(18), dpToPx(28), dpToPx(18))
                setOnClickListener {
                    controller.setOpponentMode(mode.engineRed, mode.engineBlack)
                    saveEngineMode(mode.engineRed, mode.engineBlack)
                    updateOpponentIcon()
                    refreshTitle()
                    refreshActionButtons()
                    dialog.dismiss()
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(if (isSelected) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked)
            }, LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)))
            row.addView(TextView(this).apply {
                text = mode.label
                textSize = 18f
                setTextColor(if (isSelected) 0xFF4E4E4C.toInt() else 0xFF444444.toInt())
                setPadding(dpToPx(18), 0, 0, 0)
            })
            container.addView(row)
            if (index < modes.size - 1) {
                container.addView(View(this).apply {
                    setBackgroundResource(R.drawable.bg_sheet_divider)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
    }

    /** 根据对局模式更新"引擎"按钮图标 */
    private fun updateOpponentIcon() {
        if (battleMode) {
            binding.opponentIcon.setImageResource(R.drawable.ic_opponent_player_none)
            return
        }
        val resId = when {
            controller.enginePlaysRed && controller.enginePlaysBlack ->
                R.drawable.ic_opponent_engine_both
            controller.enginePlaysRed ->
                R.drawable.ic_opponent_engine_red
            controller.enginePlaysBlack ->
                R.drawable.ic_opponent_engine_black
            else ->
                R.drawable.ic_opponent_player_none
        }
        binding.opponentIcon.setImageResource(resId)
    }

    /** 是否为引擎对引擎模式（该模式下禁用悔棋、引擎变着） */
    private val isEngineVsEngine: Boolean
        get() = controller.enginePlaysRed && controller.enginePlaysBlack

    /** 刷新底部操作按钮的可用状态 */
    private fun refreshActionButtons() {
        val disabled = isEngineVsEngine
        binding.undoButton.isEnabled = !disabled
        binding.undoButton.alpha = if (disabled) 0.4f else 1f
    }

    // ---------------- P4 更多选项 ----------------
    private fun showMoreOptionsSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_more_options, null)
        val grid = view.findViewById<GridLayout>(R.id.more_grid)
        val dialog = showBottomSheet(view)

        data class MoreOption(
            val label: String,
            val iconRes: Int,
            val enabled: Boolean = true,
            val action: () -> Unit
        )

        // 好友对战：仅保留与引擎无关的项，加求和与认输
        val options = if (battleMode) listOf(
            MoreOption("求和", R.drawable.ic_more_alt_move) { offerDrawBattle() },
            MoreOption("认输", R.drawable.ic_more_exit) { resignBattle() },
            MoreOption("导出FEN", R.drawable.ic_more_export_fen) {
                val game = controller.game
                if (game == null) {
                    Toast.makeText(this, "当前无对局可导出", Toast.LENGTH_SHORT).show()
                    return@MoreOption
                }
                val fen = game.currentBoard.toFENString()
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FEN", fen))
                Toast.makeText(this, "FEN串已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
            MoreOption("退出对局", R.drawable.ic_more_exit) {
                saveAndExit()
            }
        ) else listOf(
            MoreOption("引擎设置", R.drawable.ic_more_engine_settings) {
                val dialog = SettingDialogFragment()
                dialog.setController(controller)
                dialog.listener = this
                dialog.setEngineInfo(controller.engineInfo)
                dialog.show(supportFragmentManager, "CustomDialog")
            },
            MoreOption("引擎变着", R.drawable.ic_more_alt_move, !isEngineVsEngine) {
                controller.computerAskForMultiPV()
            },
            MoreOption("闪电出着", R.drawable.ic_more_quick_move) {
                controller.stopSearchNow()
            },
            MoreOption("导入FEN", R.drawable.ic_more_import_fen) {
                showInputFenDialog()
            },
            MoreOption("导出FEN", R.drawable.ic_more_export_fen) {
                val game = controller.game
                if (game == null) {
                    Toast.makeText(this, "当前无对局可导出", Toast.LENGTH_SHORT).show()
                    return@MoreOption
                }
                val fen = game.currentBoard.toFENString()
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FEN", fen))
                Toast.makeText(this, "FEN串已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
            MoreOption("退出对局", R.drawable.ic_more_exit) {
                saveAndExit()
            }
        )

        options.forEach { option ->
            val cell = createIconCell(option.label, option.iconRes, 36, option.enabled) {
                option.action()
                dialog.dismiss()
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            })
        }
    }

    // ---------------- 棋盘触摸 ----------------
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        event ?: return false
        if (event.action != MotionEvent.ACTION_DOWN) return false

        // 防止重复点击
        val now = System.currentTimeMillis()
        if (now - lastHandledClickTime < MIN_CLICK_INTERVAL_MS) return false
        lastHandledClickTime = now

        val pos = chessView.getPosByCoord(event.x, event.y) ?: return false
        controller.touchPosition(pos)
        Log.d(TAG, "onTouch: x=${event.x}, y=${event.y}, pos=$pos")
        return false
    }

    // ---------------- 新对局 ----------------
    fun showNewGameSheet() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(0, 0, 0, dpToPx(28))
        }
        // 灰色拖拽条
        sheet.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_sheet_handle)
        }, LinearLayout.LayoutParams(dpToPx(40), dpToPx(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(12)
        })
        // 标题
        sheet.addView(TextView(this).apply {
            text = "新对局"
            textSize = 20f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(14), 0, dpToPx(16))
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 1
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }

        data class NewGameOption(val label: String, val iconRes: Int, val action: () -> Unit)
        val options = listOf(
            NewGameOption("新对局", R.drawable.ic_newgame_plus) {
                maybeSavePgnThenAiNewGame()
            },
            NewGameOption("继续当前", R.drawable.ic_continue_play) { /* 仅关闭弹窗 */ }
        )

        val dialog = showBottomSheet(sheet)
        options.forEach { option ->
            val cell = createIconCell(option.label, option.iconRes, 48) {
                option.action()
                dialog.dismiss()
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            })
        }
        sheet.addView(grid)
    }

    // ---------------- 退出 ----------------
    fun saveAndExit() {
        if (isExiting) return
        // 好友对战：不存本地存档，退出即离开房间，需确认（弹窗内含棋谱保存询问）
        if (battleMode) {
            showLeaveBattleConfirm()
            return
        }
        // 盲棋训练：不存档，退出即清空，下次进入开新局
        if (blindMode) {
            if (GameRecordExporter.shouldPromptSave(controller.game?.history ?: emptyList())) {
                showSavePgnDialog(
                    actionLabel = "退出",
                    exitHint = "退出后本局将被清空，下次进入为全新局面。",
                    onSaveThen = { exitBlindNow() },
                    onSkipSaveThen = { exitBlindNow() }
                )
            } else {
                exitBlindNow()
            }
            return
        }
        // 人机大战：任一方走棋超过5步时，退出前询问是否保存棋谱（打谱进入的对局除外）
        if (!isFromManual
            && GameRecordExporter.shouldPromptSave(controller.game?.history ?: emptyList())
        ) {
            showSavePgnDialog(
                actionLabel = "退出",
                exitHint = "",
                onSaveThen = {
                    // 已保存棋谱：清除续局存档，下次进入人机大战从初始棋盘开始
                    proceedAiExit(keepArchive = false)
                },
                onSkipSaveThen = { proceedAiExit(keepArchive = true) }
            )
            return
        }
        proceedAiExit(keepArchive = true)
    }

    /** 盲棋退出：停引擎、清存档并关闭页面。 */
    private fun exitBlindNow() {
        isExiting = true
        controller.player.stopSearch()
        deleteFile("GameSaveFile")
        finish()
    }

    /**
     * 人机模式退出：停引擎，完成后关闭页面。
     * @param keepArchive true=写入续局存档（下次进入恢复本局）；false=清除存档（已保存棋谱，下次从初始棋盘开始）
     */
    private fun proceedAiExit(keepArchive: Boolean) {
        if (isExiting) return
        isExiting = true

        // 立即停止引擎搜索，防止退出过程中继续走棋
        controller.player.stopSearch()

        if (isFromManual) {
            // 从打谱界面进入时无需保存对局
            finish()
            return
        }

        // 在后台线程等待引擎停止并处理存档（文件I/O），避免阻塞主线程造成退出卡顿；
        // saveGameStatus 内部使用 ApplicationContext，即使 Activity 已销毁也可安全执行
        Thread({
            try {
                Thread.sleep(100)
                if (keepArchive) {
                    controller.saveGameStatus()
                } else {
                    // 已保存棋谱：清掉续局存档，下次进入从初始棋盘开始
                    deleteFile("GameSaveFile")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runOnUiThread { finish() }
        }, "save-game-on-exit").start()
    }

    // ---------------- 棋谱保存（退出时提示） ----------------

    /** 当前对局对应的棋谱类型 */
    private fun currentRecordMode(): GameRecordExporter.Mode = when {
        blindMode -> GameRecordExporter.Mode.BLIND
        battleMode -> GameRecordExporter.Mode.FRIEND
        else -> GameRecordExporter.Mode.AI
    }

    /**
     * 保存棋谱确认弹窗（任一方走棋超过5步时，在退出或开新局前触发）。
     * 弹窗期间引擎/对方仍可能走子，进入本方法时即对着法列表做快照。
     *
     * @param actionLabel 后续动作名称："退出" 或 "开新局"
     * @param exitHint 动作补充说明（如"退出将离开房间"），可为空串
     * @param onSaveThen 保存棋谱后继续的动作
     * @param onSkipSaveThen 不保存继续的动作
     */
    private fun showSavePgnDialog(
        actionLabel: String,
        exitHint: String,
        onSaveThen: () -> Unit,
        onSkipSaveThen: () -> Unit
    ) {
        val history = controller.game?.history ?: emptyList()
        val snapshot = ArrayList(history)
        val mode = currentRecordMode()
        val hint = if (exitHint.isEmpty()) "" else "$exitHint\n"
        AlertDialog.Builder(this)
            .setTitle("保存棋谱")
            .setMessage("${hint}是否保存棋谱？\n位置：APP 私有目录/橘中弈象棋/")
            .setPositiveButton("保存并$actionLabel") { _, _ ->
                savePgnThen(mode, snapshot, onSaveThen)
            }
            .setNegativeButton("不保存") { _, _ -> onSkipSaveThen() }
            .setNeutralButton("继续对局", null)
            .setCancelable(true)
            .show()
    }

    /** 构建 PGN（走棋数据在此刻定格）→ 确保存储权限 → 后台写文件 → 继续后续动作。 */
    private fun savePgnThen(
        mode: GameRecordExporter.Mode,
        snapshot: List<Game.HistoryRecord>,
        proceed: () -> Unit
    ) {
        val game = controller.game
        val result = when {
            game == null -> "*"
            // 当前轮走方已被将死 → 对方胜
            game.isGameOver && game.isCheckMate ->
                if (game.currentBoard.bRedGo) "0-1" else "1-0"
            game.isGameOver -> "1/2-1/2"
            else -> "*"
        }
        // 起始局面：FEN 开局时记录在 controller.startFen；黑先标准开局补一版 "b" 手 FEN
        var initialFen = controller.startFen
        if (initialFen == null && snapshot.isNotEmpty() && !snapshot[0].isRedMove) {
            initialFen = Board().apply { bRedGo = false }.toFENString()
        }
        val redName: String
        val blackName: String
        if (mode == GameRecordExporter.Mode.AI) {
            redName = if (controller.enginePlaysRed) "电脑" else "玩家"
            blackName = if (controller.enginePlaysBlack) "电脑" else "玩家"
        } else {
            redName = "红方"
            blackName = "黑方"
        }
        val pgn = GameRecordExporter.buildPgn(mode, snapshot, initialFen, redName, blackName, result)
        Thread({
            val path = try {
                GameRecordExporter.exportPgn(applicationContext, mode, pgn)
            } catch (e: Exception) {
                Log.e(TAG, "exportPgn failed", e)
                null
            }
            runOnUiThread {
                if (path != null) {
                    // applicationContext：finish 后 Toast 仍可显示
                    Toast.makeText(applicationContext, "棋谱已保存：$path", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(applicationContext, "棋谱保存失败", Toast.LENGTH_LONG).show()
                }
                proceed()
            }
        }, "save-pgn").start()
    }

    /** 人机模式"新对局"入口：本局超过5步先询问保存棋谱，再开新局。 */
    private fun maybeSavePgnThenAiNewGame() {
        if (GameRecordExporter.shouldPromptSave(controller.game?.history ?: emptyList())) {
            showSavePgnDialog(
                actionLabel = "开新局",
                exitHint = "",
                onSaveThen = { startAiNewGame() },
                onSkipSaveThen = { startAiNewGame() }
            )
        } else {
            startAiNewGame()
        }
    }

    private fun startAiNewGame() {
        controller.startNewGame()
        historyAndTrendAdapter.update()
        refreshTitle()
        controller.driveCurrentTurn()
    }

    /** 盲棋"新局"入口：本局超过5步先询问保存棋谱，再开新局并语音提示。 */
    private fun maybeSavePgnThenBlindNewGame() {
        if (GameRecordExporter.shouldPromptSave(controller.game?.history ?: emptyList())) {
            showSavePgnDialog(
                actionLabel = "开新局",
                exitHint = "",
                onSaveThen = { startBlindNewGame() },
                onSkipSaveThen = { startBlindNewGame() }
            )
        } else {
            startBlindNewGame()
        }
    }

    private fun startBlindNewGame() {
        controller.startNewGame()
        speakBlind("新对局")
    }

    // ---------------- 好友对战终局自动保存 ----------------

    /**
     * 好友对战终局自动保存棋谱（绝杀/认输/求和成立），无需用户确认。
     * 自动保存后，本局退出不再重复弹出保存询问。
     *
     * @param result PGN 结果标记："1-0" 红胜 / "0-1" 黑胜 / "1/2-1/2" 和棋
     * @param reason 终局原因（用于 Toast 提示，如"绝杀"、"和棋成立"）
     */
    private fun autoSaveBattlePgn(result: String, reason: String) {
        if (battleAutoSaved) return
        val history = controller.game?.history ?: return
        if (history.isEmpty()) return
        battleAutoSaved = true
        val snapshot = ArrayList(history)
        val pgn = GameRecordExporter.buildPgn(
            GameRecordExporter.Mode.FRIEND, snapshot, controller.startFen, "红方", "黑方", result
        )
        Thread({
            val path = try {
                GameRecordExporter.exportPgn(applicationContext, GameRecordExporter.Mode.FRIEND, pgn)
            } catch (e: Exception) {
                Log.e(TAG, "autoSaveBattlePgn failed", e)
                null
            }
            runOnUiThread {
                if (path != null) {
                    Toast.makeText(
                        applicationContext,
                        "$reason，棋谱已自动保存：$path",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // 保存失败（多为未授权存储权限）：回退为退出时询问保存
                    battleAutoSaved = false
                    Toast.makeText(
                        applicationContext,
                        "棋谱自动保存失败，退出本局时会再次询问是否保存",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, "auto-save-battle-pgn").start()
    }

    /** 按终局事件类型判定结果并触发自动保存（认输判负/对方认输判胜/和棋成立）。 */
    private fun autoSaveOnBattleEnd(status: GameStatus) {
        val myRed = networkController?.isMyColorRed ?: return
        when (status) {
            GameStatus.WIN -> autoSaveBattlePgn(if (myRed) "1-0" else "0-1", "对方认输")
            GameStatus.LOSE -> autoSaveBattlePgn(if (myRed) "0-1" else "1-0", "本方认输")
            GameStatus.DRAW -> autoSaveBattlePgn("1/2-1/2", "和棋成立")
            else -> {}
        }
    }

    override fun onBackPressed() {
        // 由 saveAndExit 统一在异步保存完成后 finish，避免 super 立即关闭导致存档未写完
        saveAndExit()
    }

    override fun onResume() {
        super.onResume()
        if (battleMode) {
            // 切后台/锁屏后回来：立即探测连接，半开连接不必再等一轮 40s 心跳超时；
            // 若正处于断线退避等待，取消延迟立即重连一次
            battleSession?.onReturnToForeground()
            // Android 13+ 保活通知权限补申请（进程内最多弹一次系统框）
            BattleBackgroundGuide.ensureNotificationPermission(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (battleMode) {
            battleCountdownHandler.removeCallbacks(battleCountdownRunnable)
            // 聊天资源回收：未完成的录音放弃、语音播放停止、未关闭的对话框关闭
            hideVoiceRecordOverlay()
            if (voiceRecorder.isRecording) voiceRecorder.cancel()
            voicePlayer.stop()
            battleChatDialog?.dismiss()
            // 会话存活于 Application（切后台/旋转不丢局）；仅当已离开房间时回收
            val session = battleSession
            if (session == null || session.state == BattleSessionManager.State.CLOSED
                || session.state == BattleSessionManager.State.IDLE
            ) {
                BattleKeepAliveService.stop(this)
            }
        } else {
            // 关闭 pikafish 引擎子进程，避免退出后引擎进程驻留泄漏
            if (::controller.isInitialized) {
                controller.shutdown()
            }
        }
        // 释放音效 MediaPlayer，避免持有 Activity context 与 native 资源造成泄漏
        if (::soundPlayer.isInitialized) {
            soundPlayer.release()
        }
        // 盲棋 TTS 释放
        tts?.stop()
        tts?.shutdown()
    }

    // ---------------- FEN 输入 ----------------
    fun showInputFenDialog() {
        val input = EditText(this)
        // 预填剪贴板内容
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.let { clip ->
            if (clip.itemCount > 0) {
                input.setText(clip.getItemAt(0).text)
                input.setSelection(input.text.length)
            }
        }
        input.setSelectAllOnFocus(true)

        AlertDialog.Builder(this)
            .setTitle("从FEN串开始新游戏")
            .setMessage("请输入棋局的FEN串：")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                controller.startFENGame(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 多候选着法选择 ----------------
    private fun showMultiPvChoiceDialog() {
        val descs = controller.suggestedMoveDescs
        if (descs.isEmpty()) {
            controller.selectMultiPV(0)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择建议着法")
            .setItems(descs.toTypedArray()) { _, which ->
                controller.selectMultiPV(which)
            }
            .setOnCancelListener {
                if (controller.lastMultiPVForHuman) {
                    // 玩家放弃引擎帮助：不走棋，恢复后台分析
                    controller.cancelMultiPV()
                } else {
                    // 引擎变着弹窗取消：接受第一候选着法
                    controller.selectMultiPV(0)
                }
            }
            .show()
    }

    // ---------------- 游戏事件回调 ----------------
    override fun onGameEvent(status: GameStatus?, message: String?) {
        Log.d(TAG, "onGameEvent: status=$status, message=$message")
        when (status) {
            GameStatus.ILLEGAL -> {
                message?.let { setTitle(it) }
                soundPlayer.illegal()
            }
            GameStatus.MOVE -> {
                soundPlayer.move()
                refreshTitle()
                // 悔棋被同意后的回退、本方走棋均走 MOVE：同步悔棋按钮可用性
                if (battleMode) updateBattleActionButtonsEnabled()
                // 盲棋：播报着法（引擎走完或玩家走完）
                if (blindMode && message != null) {
                    val colorPrefix = if (controller.isRedTurn) "黑方" else "红方"
                    speakBlind("$colorPrefix $message")
                }
            }
            GameStatus.CAPTURE -> {
                message?.let { setTitle(it) }
                soundPlayer.capture()
                if (blindMode) speakBlind("吃子")
            }
            GameStatus.CHECK -> {
                message?.let { setTitle(it) }
                soundPlayer.check()
                if (blindMode) speakBlind("将军")
            }
            GameStatus.CHECKMATE -> {
                message?.let { setTitle(it) }
                soundPlayer.checkmate()
                if (blindMode) speakBlind("绝杀")
                if (battleMode) {
                    // 终局自动保存棋谱：轮走方已被将死 → 对方胜
                    autoSaveBattlePgn(
                        if (controller.game?.currentBoard?.bRedGo == true) "0-1" else "1-0",
                        "绝杀"
                    )
                    // 绝杀：稍作停顿让音效与终局画面先呈现，再弹"新一局/退出"
                    postResultDialogDelayed(1200)
                }
            }
            GameStatus.SELECT -> {
                message?.let { setTitle(it) }
                soundPlayer.select()
            }
            GameStatus.WIN, GameStatus.LOSE, GameStatus.DRAW -> {
                message?.let { setTitle(it) }
                if (blindMode) speakBlind(message ?: "")
                // 好友对战：胜负/和棋已定 → 弹"新一局/退出"对话框
                if (battleMode) {
                    // 终局自动保存棋谱（认输判负/对方认输判胜/和棋成立；绝杀在 CHECKMATE 分支处理）
                    autoSaveOnBattleEnd(status)
                    postResultDialogDelayed(if (status == GameStatus.DRAW) 400 else 600)
                }
            }
            GameStatus.ENGINE -> refreshTitle()
            GameStatus.MULTIPV -> {
                message?.let { setTitle(it) }
                soundPlayer.ready()
                showMultiPvChoiceDialog()
            }
            GameStatus.UPDATEUI -> {
                refreshTitle()
                // 重开确认状态变化（本方点新一局/对方确认到达）：同步求和/悔棋/认输按钮可用性
                updateBattleActionButtonsEnabled()
            }
            null -> { /* 未知状态，忽略 */ }
        }
        // 云库页可见时，按新局面自动刷新参考着法（同局面会直接跳过）
        if (binding.pageCloud.visibility == View.VISIBLE) {
            refreshCloudBook()
        }
        // 刷新信息区
        historyAndTrendAdapter.update()
    }

    override fun onGameEvent(event: GameStatus?) {
        onGameEvent(event, null)
    }

    override fun runOnUIThread(runnable: Runnable?) {
        runOnUiThread(runnable)
    }

    override fun onDialogPositiveClick() {
        Log.d(TAG, "onDialogPositiveClick: ${controller.settings}")
    }

    override fun onDialogNegativeClick() {
        // 不做处理
    }
}
