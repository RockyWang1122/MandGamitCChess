package com.mandgamit.chess.battle.ui

import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mandgamit.chess.BuildConfig
import com.mandgamit.chess.ChessApp
import com.mandgamit.chess.GameActivity
import com.mandgamit.chess.MainActivity
import com.mandgamit.chess.R
import com.mandgamit.chess.battle.BattleBackgroundGuide
import com.mandgamit.chess.battle.BattleKeepAliveService
import com.mandgamit.chess.battle.BattleSessionManager
import com.mandgamit.chess.battle.net.BattleMessage
import com.mandgamit.chess.battle.net.LoopbackTransport
import com.mandgamit.chess.gamelogic.Game
import com.mandgamit.chess.gamelogic.Move
import com.mandgamit.chess.gamelogic.Piece
import com.mandgamit.chess.gamelogic.Position
import com.mandgamit.chess.gamelogic.RulesetProvider

/**
 * 好友对战房间弹窗（BottomSheetDialog，与引擎模式弹窗同风格）。
 *
 * 入口二选一：
 * - 创建房间：建房者执红先行，显示 6 位房间号（可长按复制），等待好友加入后自动开局；
 * - 加入房间：输入好友的 6 位房间号，加入者执黑，双方就位后自动开局。
 *
 * 会话由 ChessApp.getBattleSession() 持有；本弹窗只负责配对阶段交互，
 * onGameStart 后进入 GameActivity（EXTRA_BATTLE_MODE）。
 *
 * DEBUG 包连点标题 5 次：本机回环自测（LoopbackTransport 双端互联，本机陪练自动应着）。
 */
class BattleRoomDialog(private val activity: MainActivity) {

    private var dialog: BottomSheetDialog? = null
    private var enteredGame = false

    fun show() {
        val session = ChessApp.getBattleSession()
        val view = activity.layoutInflater.inflate(R.layout.sheet_battle_room, null)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        this.dialog = dialog

        val choiceRow: LinearLayout = view.findViewById(R.id.battleChoiceRow)
        val createPanel: LinearLayout = view.findViewById(R.id.battleCreatePanel)
        val joinPanel: LinearLayout = view.findViewById(R.id.battleJoinPanel)
        val roomCodeText: TextView = view.findViewById(R.id.battleRoomCodeText)
        val createHint: TextView = view.findViewById(R.id.battleCreateHint)
        val roomInput: EditText = view.findViewById(R.id.battleRoomInput)
        val btnCreate: Button = view.findViewById(R.id.battleBtnCreate)
        val btnJoin: Button = view.findViewById(R.id.battleBtnJoin)
        val btnCancelCreate: Button = view.findViewById(R.id.battleBtnCancelCreate)
        val btnShare: Button = view.findViewById(R.id.battleBtnShare)
        val btnConfirmJoin: Button = view.findViewById(R.id.battleBtnConfirmJoin)

        fun showPanel(panel: LinearLayout) {
            choiceRow.visibility = if (panel === choiceRow) View.VISIBLE else View.GONE
            createPanel.visibility = if (panel === createPanel) View.VISIBLE else View.GONE
            joinPanel.visibility = if (panel === joinPanel) View.VISIBLE else View.GONE
        }

        val listener = object : BattleSessionManager.BattleListener {
            override fun onRoomCreated(room: String) {
                // 建房成功即启动保活：等待好友加入期间切后台也不能断连
                BattleKeepAliveService.start(activity)
                roomCodeText.text = room.chunked(3).joinToString(" ")
                createHint.text = "把房间号告诉好友，等待加入…"
                showPanel(createPanel)
                // 国产 ROM 后台清理比原生 Doze 更激进：首次成功建房时引导一次白名单/自启动
                BattleBackgroundGuide.showGuideIfNeeded(activity)
            }

            override fun onRoomJoined(color: String) {
                createHint.text = "已加入，等待开局…"
                showPanel(createPanel)
                BattleBackgroundGuide.showGuideIfNeeded(activity)
            }

            override fun onGameStart() {
                enteredGame = true
                dialog.dismiss()
                val intent = Intent(activity, GameActivity::class.java)
                intent.putExtra(GameActivity.EXTRA_BATTLE_MODE, true)
                activity.startActivity(intent)
            }

            override fun onError(msg: String) {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                showPanel(choiceRow)
            }

            override fun onStateChanged(state: BattleSessionManager.State) {
                if (state == BattleSessionManager.State.CONNECTING) {
                    // 建房/加入请求已发出，等待服务器回包
                    roomCodeText.text = "……"
                    createHint.text = "连接服务器中…"
                    showPanel(createPanel)
                }
            }

            override fun onRemoteMove(ucci: String, seq: Int) {}
            override fun onOpponentOffline(ttlSeconds: Int) {}
            override fun onOpponentOnline() {}
            override fun onSyncState(fen: String, moves: List<String>, turn: String) {}
            override fun onSyncRequested() {}
            override fun onOpponentResign() {}
            override fun onOpponentLeave(reason: String?) {
                Toast.makeText(activity, reason ?: "对方已离开房间", Toast.LENGTH_SHORT).show()
                showPanel(choiceRow)
            }
        }
        session.listener = listener

        btnCreate.setOnClickListener {
            // Android 13+：保活通知需要运行时授权（进程内只弹一次，不阻塞建房）
            BattleBackgroundGuide.ensureNotificationPermission(activity)
            session.createRoom()
        }
        btnJoin.setOnClickListener {
            showPanel(joinPanel)
            // 进入加入面板时解析剪贴板：有合规分享消息则自动填入房间号
            fillRoomCodeFromClipboard(roomInput)
        }
        btnShare.setOnClickListener {
            val code = session.roomCode ?: return@setOnClickListener
            val shareText = "橘中弈象棋：房间号 $code，复制本消息，打开APP,自动加入房间开始对弈！"
            // 复制到剪贴板
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("room_code", shareText))
            // 直接拉起微信（不走系统分享面板，避免"返回第三方工具"无法回 APP 的问题）
            val wechatIntent = activity.packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (wechatIntent != null) {
                Toast.makeText(activity, "房间号已复制，在微信中粘贴发送", Toast.LENGTH_SHORT).show()
                activity.startActivity(wechatIntent)
            } else {
                // 未安装微信，走系统分享
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                activity.startActivity(Intent.createChooser(shareIntent, "分享房间号"))
            }
        }
        btnCancelCreate.setOnClickListener {
            // 主动取消：发 leave_room 让服务端立即销毁房间（房号立刻可用），再释放会话
            session.leave()
            session.destroy()
            showPanel(choiceRow)
        }
        btnConfirmJoin.setOnClickListener {
            val code = roomInput.text.toString().trim()
            if (code.length != 6 || !code.all { it.isDigit() }) {
                Toast.makeText(activity, "请输入 6 位数字房间号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 同建房：先确保保活通知权限，再发起加入
            BattleBackgroundGuide.ensureNotificationPermission(activity)
            session.joinRoom(code)
        }

        // DEBUG：连点标题 5 次进入本机回环自测
        if (BuildConfig.DEBUG) {
            val title: TextView = view.findViewById(R.id.battleSheetTitle)
            var clickCount = 0
            var lastClickAt = 0L
            title.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastClickAt > 1500) clickCount = 0
                clickCount++
                lastClickAt = now
                if (clickCount >= 5) {
                    clickCount = 0
                    startLoopbackDemo(listener)
                }
            }
        }

        // 若会话已处于建房等待态（弹窗被关掉后重进），直接恢复面板
        if (session.state == BattleSessionManager.State.WAITING_GUEST && session.roomCode != null) {
            listener.onRoomCreated(session.roomCode!!)
        }

        dialog.setOnDismissListener {
            if (session.listener === listener) session.listener = null
            // 未进入对局且会话不在对局态：发 leave_room 通知服务端销毁房间，再释放会话。
            // CONNECTING（冷启动自动重连中）/DISCONNECTED（重连退避中）不在此清理：
            // 连接尚在途，销毁会把正在恢复的房间一起杀掉；重进弹窗可继续承接。
            if (!enteredGame
                && session.state != BattleSessionManager.State.PLAYING
                && session.state != BattleSessionManager.State.READY
                && session.state != BattleSessionManager.State.CONNECTING
                && session.state != BattleSessionManager.State.DISCONNECTED
            ) {
                if (session.state == BattleSessionManager.State.WAITING_GUEST) {
                    session.leave()
                }
                session.destroy()
                BattleKeepAliveService.stop(activity)
            }
        }
        dialog.show()
    }

    /**
     * 解析剪贴板中的分享消息（"橘中弈象棋：房间号 123456，…"），
     * 有合规 6 位房号则自动填入输入框。
     */
    private fun fillRoomCodeFromClipboard(roomInput: EditText) {
        try {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return
            if (!clipboard.hasPrimaryClip()) return
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString() ?: return
            val roomCode = Regex("房间号\\s*(\\d{6})").find(text)?.groupValues?.get(1) ?: return
            roomInput.setText(roomCode)
        } catch (_: Throwable) {
            // 剪贴板访问失败静默忽略
        }
    }

    /**
     * 本机回环自测：同进程双会话经 LoopbackTransport 互联。
     * 建房方为本机玩家（执红），guest 侧由内置简易陪练（随机合法着）应着。
     */
    private fun startLoopbackDemo(
        hostUiListener: BattleSessionManager.BattleListener,
    ) {
        val (hostEnd, guestEnd) = LoopbackTransport.createPair()
        val host = BattleSessionManager({ hostEnd })
        val guest = BattleSessionManager({ guestEnd })
        host.injectDebugState("888888", "hostToken", BattleMessage.COLOR_RED, host = true)
        guest.injectDebugState("888888", "guestToken", BattleMessage.COLOR_BLACK, host = false)
        host.listener = hostUiListener
        // 全局会话替换为回环建房方（GameActivity 从 ChessApp 取会话）
        ChessApp.setBattleSessionForDebug(host)

        // 本机陪练：镜像一盘棋，收到红方着法后随机回一手黑方合法着
        val mirror = Game(true)
        val mainHandler = Handler(Looper.getMainLooper())
        guest.listener = object : BattleSessionManager.BattleListener {
            override fun onRemoteMove(ucci: String, seq: Int) {
                val board = mirror.currentBoard
                val incoming = Move(Position(0, 0), Position(0, 0))
                if (incoming.fromUCCIString(ucci)) {
                    board.doMove(incoming)
                }
                mainHandler.postDelayed({
                    val reply = randomLegalMove(mirror)
                    if (reply != null) {
                        board.doMove(reply)
                        guest.sendMove(reply.getUCCIString())
                    }
                }, 600)
            }

            override fun onRoomCreated(room: String) {}
            override fun onRoomJoined(color: String) {}
            override fun onGameStart() {}
            override fun onOpponentOffline(ttlSeconds: Int) {}
            override fun onOpponentOnline() {}
            override fun onSyncState(fen: String, moves: List<String>, turn: String) {}
            override fun onSyncRequested() {}
            override fun onOpponentResign() {}
            override fun onOpponentLeave(reason: String?) {}
            override fun onError(msg: String) {}
            override fun onStateChanged(state: BattleSessionManager.State) {}
        }

        Toast.makeText(activity, "回环自测：本机陪练执黑", Toast.LENGTH_SHORT).show()
        hostUiListener.onGameStart()
    }

    /** 为当前轮走方生成一手随机合法着（扫描全盘 90×90 候选）。 */
    private fun randomLegalMove(game: Game): Move? {
        val board = game.currentBoard
        val red = board.bRedGo
        val candidates = ArrayList<Move>()
        for (y in 0 until 10) {
            for (x in 0 until 9) {
                val piece = board.getPieceByPosition(x, y)
                if (piece == Piece.EMPTY || Piece.isRed(piece) != red) continue
                for (ty in 0 until 10) {
                    for (tx in 0 until 9) {
                        val m = Move(Position(x, y), Position(tx, ty))
                        if (RulesetProvider.get().isMoveLegal(m, board)) {
                            candidates.add(m)
                        }
                    }
                }
            }
        }
        return candidates.randomOrNull()
    }
}
