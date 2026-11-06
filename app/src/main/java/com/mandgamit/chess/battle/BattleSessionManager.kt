package com.mandgamit.chess.battle

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mandgamit.chess.battle.net.BattleMessage
import com.mandgamit.chess.battle.net.BattleProtocol
import com.mandgamit.chess.battle.net.BattleTransport

/**
 * 好友对战会话管理器：持有连接、房间号、token、我方颜色、断线重连与状态快照对账。
 *
 * 生命周期独立于 Activity（由 ChessApp 持有默认实例），GameActivity 销毁/重建后
 * 连接与对局状态仍可恢复。
 *
 * 状态机：
 * IDLE → CONNECTING → WAITING_GUEST(建房者) / JOINING(加入者) → READY → PLAYING ⇄ DISCONNECTED → CLOSED
 */
class BattleSessionManager(
    private val transportFactory: () -> BattleTransport,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    enum class State {
        IDLE,
        CONNECTING,
        IN_LOBBY,        // 已进入大厅（订阅桌状态广播）
        SEATED_WAITING,  // 大厅已入座，等待对手
        WATCHING,        // 正在观战
        WAITING_GUEST,   // 已建房，等待对方加入
        JOINING,         // 已发 join_room，等待服务器回 room_joined
        READY,           // 双方就位，start_game 已收到
        PLAYING,
        DISCONNECTED,    // 断线，房间保留，尝试重连中
        CLOSED,
    }

    interface BattleListener {
        /** 建房成功，返回房间号。 */
        fun onRoomCreated(room: String)
        /** 加入成功，返回我方颜色（"red"/"black"）。 */
        fun onRoomJoined(color: String)
        /** 双方就位，进入对局（双方都会收到）。 */
        fun onGameStart()
        /**
         * 断线重连恢复：服务端补发的 start_game 与本地已是同一局（代数未变）。
         * 只需把服务端执子色/昵称同步进界面，不得重置棋盘（本地已由快照恢复）。
         */
        fun onGameResumed(color: String) {}
        /** 收到对方走子（UCCI）。 */
        fun onRemoteMove(ucci: String, seq: Int)
        /** 对方断线/重连恢复。ttlSeconds 为服务器房间保留时长（秒），0 表示未知。 */
        fun onOpponentOffline(ttlSeconds: Int)
        fun onOpponentOnline()
        /** 断线后重连成功，收到对方最新局面快照（用于对账恢复）。 */
        fun onSyncState(fen: String, moves: List<String>, turn: String)
        /** 对方请求对账：本地应回推当前局面快照（控制器调 pushLocalSync）。 */
        fun onSyncRequested()
        /** 对方认输。 */
        fun onOpponentResign()
        /** 对方已离开房间（reason 非空为服务器说明，如保留超时关闭）。 */
        fun onOpponentLeave(reason: String? = null)
        /** 对方请求求和。 */
        fun onDrawOffer() {}
        /** 对方同意求和（我方为提议方）。 */
        fun onDrawAccepted() {}
        /** 对方拒绝求和（我方为提议方）。 */
        fun onDrawDeclined() {}
        /** 对方发起新一局。 */
        fun onNewGame() {}
        /** 对方请求悔棋。 */
        fun onUndoRequest() {}
        /** 对方同意悔棋（我方为请求方）。 */
        fun onUndoAccepted() {}
        /** 对方拒绝悔棋（我方为请求方）。 */
        fun onUndoDeclined() {}
        /** 收到对方文字聊天。 */
        fun onChatText(text: String) {}
        /** 收到对方语音聊天（AMR-NB base64 数据与时长，双方不落地存储）。 */
        fun onChatVoice(amrB64: String, durationMs: Int) {}
        /** 连接或协议错误。 */
        fun onError(msg: String)
        /** 连接状态变化（供 UI 更新断线条）。 */
        fun onStateChanged(state: State)

        // —— 大厅（棋桌）模式回调 ——
        /** 大厅全量快照（enter_lobby 应答）。 */
        fun onLobbyState(tables: List<com.mandgamit.chess.battle.net.TableInfo>) {}
        /** 单桌增量更新（status=closed 表示该桌已销毁）。 */
        fun onTableUpdate(info: com.mandgamit.chess.battle.net.TableInfo) {}
        /** 入座成功。 */
        fun onSitOk(table: Int, color: String) {}
        /** 观战成功：携带双方昵称与走子缓存（供本地重放）。 */
        fun onWatchOk(table: Int, red: String, black: String, moves: List<String>, turn: String) {}
        /** 观战的棋桌已销毁。 */
        fun onWatchEnd(table: Int, msg: String) {}
        /** 对手终局后离座：棋桌保留，本人座位不变（界面提示后可回大厅继续等待）。 */
        fun onOpponentLeft() {}
    }

    var listener: BattleListener? = null

    var state = State.IDLE
        private set

    var roomCode: String? = null
        private set
    var myColor: String = BattleMessage.COLOR_RED
        private set
    var isHost: Boolean = false
        private set

    /** 对局是否已开始（收到 start_game 后置 true）。重连时仅在此为 true 时才 request_sync。 */
    var gameStarted: Boolean = false
        private set

    // —— 大厅（棋桌）模式状态 ——
    /** 本人昵称（进入大厅时上报）。 */
    var nick: String = ""
        private set
    /** 已入座的桌号（0 表示未入座）。 */
    var seatedTable: Int = 0
        private set
    /** 正在观战的桌号（0 表示未观战）。 */
    var watchingTable: Int = 0
        private set
    /**
     * 用户主动退出大厅标记（LobbyActivity.finish 时置位，重新进入大厅时清除）。
     * MainActivity 据此区分"用户主动退出"与"ROM 销毁大厅页"：
     * 前者不做座位补跳（否则返回键被拉回大厅死循环），后者仍需补跳恢复界面。
     */
    @Volatile
    var lobbyManuallyExited: Boolean = false
    /** 当前对局双方昵称（大厅 start_game / watch_ok 携带，供对局页展示）。 */
    var redNick: String = ""
        private set
    var blackNick: String = ""
        private set

    /** 最新已同步局面（断线重连时作为基准）。 */
    var lastSyncFen: String = ""
        private set
    var lastSyncMoves: List<String> = emptyList()
        private set
    var lastSyncTurn: String = BattleMessage.COLOR_RED
        private set

    /** 我方已发着法序号（move.seq 单调递增）。 */
    private var mySeq = 0

    /** 对方已发着法最大 seq（用于去重/丢包检测）。 */
    private var opponentMaxSeq = 0

    /** 对局代数：由服务端 start_game 下发（首局=1），用于丢弃跨局迟到的对账消息。 */
    private var gameGen = 0

    private var transport: BattleTransport? = null
    private var myToken: String? = null

    // —— 重连 ——
    private var reconnectAttempts = 0
    private val maxReconnectDelayMs = 30_000L
    /** 当前排队中的退避重连任务，回前台时可取消失效、立即重连。 */
    private var pendingReconnect: Runnable? = null

    /** 是否已有一次重连正在建连（TCP 握手中），防止回前台与退避到期并发建两条连接。 */
    private var reconnectInProgress = false

    /**
     * 重连恢复路径（restoreSession/attemptReconnect 凭 token 重发 join_room）置位：
     * 服务端随后补发的 start_game 若代数未变，只是"已是某局"的标记（本地棋盘
     * 已由落盘快照恢复），不得重置棋盘——但服务端颜色仍须同步（见 onGameResumed）。
     * 若代数不同（变大＝离线期间达成了"再来一局"；变小＝棋桌重建后从 1 重新计数），
     * 则按真正的新局处理、重置棋盘并作废旧快照。消费一次即复位。
     */
    @Volatile
    private var suppressNextStartGame = false

    /**
     * 等待态回前台探测的超时任务：发出应用层 Ping 后限时未收到任何回包即判死断线，
     * 触发重连（服务端会对重连房主补发 start_game，对手可能已抢先入座开局）。
     */
    private var waitingProbe: Runnable? = null

    private val heartbeatIntervalMs = 20_000L
    private val waitingProbeTimeoutMs = 5_000L
    private var lastMessageAt = 0L
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (state == State.PLAYING || state == State.READY) {
                val silentMs = System.currentTimeMillis() - lastMessageAt
                if (silentMs > heartbeatIntervalMs * 2) {
                    Log.w(TAG, "heartbeat timeout, mark disconnected")
                    handleDisconnected("heartbeat_timeout")
                } else {
                    sendRaw(BattleMessage.Ping())
                    mainHandler.postDelayed(this, heartbeatIntervalMs)
                }
            }
        }
    }

    // ==================== 公共 API（主线程调用） ====================

    /** 创建房间。建房者执红。 */
    fun createRoom() {
        resetSession()
        state = State.CONNECTING
        notifyState()
        connectTransport { ok ->
            if (ok) {
                // 基于 Android ID 生成设备固定房号：同一设备每次建房房号一致
                val wantRoom = generateDeviceRoomCode()
                android.util.Log.i("BattleSession", "createRoom: want_room=$wantRoom")
                if (!sendRaw(BattleMessage.CreateRoom(wantRoom))) {
                    notifyError("连接异常，请重试")
                    state = State.IDLE
                    notifyState()
                }
            } else {
                notifyError("无法连接服务器")
                state = State.IDLE
                notifyState()
            }
        }
    }

    /**
     * 生成设备固定 6 位房间号。优先用 ANDROID_ID（API 26+ 稳定），
     * 取不到时组合设备硬件信息作为 fallback，确保同一设备结果一致。
     */
    private fun generateDeviceRoomCode(): String {
        return try {
            val androidId = android.provider.Settings.Secure.getString(
                com.mandgamit.chess.ChessApp.getContext().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            val seed = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                // ANDROID_ID 可用（排除模拟器已知默认值）
                androidId
            } else {
                // fallback：组合设备信息生成稳定 ID
                buildString {
                    append(android.os.Build.MODEL)
                    append(android.os.Build.MANUFACTURER)
                    append(android.os.Build.BRAND)
                    append(android.os.Build.DEVICE)
                }
            }
            var hash = 0L
            for (ch in seed) {
                hash = (hash * 31 + ch.code) and 0x7FFFFFFFL
            }
            val code = String.format("%06d", hash % 1000000)
            android.util.Log.d("BattleSession", "device room code=$code (seed=${seed.take(8)}…)")
            code
        } catch (t: Throwable) {
            // 极端异常：用包名+安装时间，仍保证同一设备一致
            android.util.Log.w("BattleSession", "generateDeviceRoomCode failed", t)
            val ctx = com.mandgamit.chess.ChessApp.getContext()
            val installTime = ctx.packageManager
                .getPackageInfo(ctx.packageName, 0).firstInstallTime
            String.format("%06d", (installTime % 1000000))
        }
    }

    // ==================== 大厅（棋桌）模式 API ====================

    /** 进入大厅：建立连接并订阅桌状态广播。 */
    fun enterLobby(nick: String) {
        this.nick = nick
        if (transport != null && (state == State.IN_LOBBY || state == State.SEATED_WAITING
                || state == State.WATCHING || state == State.PLAYING || state == State.READY)) {
            // 已连接：直接重发 enter_lobby 刷新订阅与昵称
            sendLobbyRaw(BattleMessage.EnterLobby(nick))
            // 大厅阶段维持应用层心跳：空闲连接不被 ROM/代理掐断（幂等）
            startHeartbeat()
            if (state != State.IN_LOBBY && state != State.SEATED_WAITING && state != State.WATCHING) {
                state = State.IN_LOBBY
                notifyState()
            }
            return
        }
        closeTransport()
        state = State.CONNECTING
        notifyState()
        connectTransport { ok ->
            if (ok) {
                if (!sendLobbyRaw(BattleMessage.EnterLobby(nick))) {
                    notifyError("连接异常，请重试")
                    state = State.IDLE
                    notifyState()
                    return@connectTransport
                }
                // 大厅阶段维持应用层心跳：空闲连接不被 ROM/代理掐断
                startHeartbeat()
                // lobby_state 回包到达后置 IN_LOBBY（见 handleMessage）
                // 与 attemptReconnect 一致：建连后恢复座位或观战，
                // 否则 DISCONNECTED 状态下回大厅只订阅了广播，座位还绑在死连接上
                val room = roomCode
                when {
                    room != null && myToken != null -> {
                        sendRaw(BattleMessage.JoinRoom(room, myToken))
                        state = when {
                            gameStarted -> State.PLAYING
                            seatedTable != 0 -> State.SEATED_WAITING
                            else -> State.READY
                        }
                        notifyState()
                        if (gameStarted) requestSync()
                        if (gameStarted) startHeartbeat()
                    }
                    watchingTable != 0 -> sendLobbyRaw(BattleMessage.Watch(watchingTable))
                }
            } else {
                notifyError("无法连接服务器")
                state = State.IDLE
                notifyState()
            }
        }
    }

    /** 离开大厅：退订广播并断开连接（入座/观战请先 stand/unwatch）。 */
    fun leaveLobby() {
        sendLobbyRaw(BattleMessage.LeaveLobby())
        mainHandler.removeCallbacks(heartbeatRunnable)
        closeTransport()
        seatedTable = 0
        watchingTable = 0
        state = State.IDLE
        notifyState()
    }

    /** 入座指定桌。color 传 null 由服务器分配（首坐红、次坐黑）。 */
    fun sit(table: Int, color: String? = null) {
        sendLobbyRaw(BattleMessage.Sit(table, color))
    }

    /** 离座。未开局释放座位；已开局解散整桌（服务器不回包给离座方，本地直接清座位）。 */
    fun stand() {
        val t = seatedTable
        if (t == 0) return
        sendLobbyRaw(BattleMessage.Stand(t))
        clearSeat()
        if (state != State.IN_LOBBY) {
            state = State.IN_LOBBY
            notifyState()
        }
    }

    /**
     * 终局后离座：带 after_game 标记，服务端只释放本人座位、保留棋桌，
     * 对方不受影响（收到 opponent_left 后回大厅等待）。本地同 stand() 清座位。
     */
    fun standAfterGame() {
        val t = seatedTable
        if (t == 0) return
        sendLobbyRaw(BattleMessage.Stand(t, afterGame = true))
        clearSeat()
        if (state != State.IN_LOBBY) {
            state = State.IN_LOBBY
            notifyState()
        }
    }

    /** 观战指定桌（须对局进行中）。 */
    fun watch(table: Int) {
        sendLobbyRaw(BattleMessage.Watch(table))
    }

    /** 结束观战。 */
    fun unwatch() {
        val t = watchingTable
        if (t == 0) return
        sendLobbyRaw(BattleMessage.Unwatch(t))
        watchingTable = 0
        state = State.IN_LOBBY
        notifyState()
    }

    /** 修改昵称（已入座/观战席位由服务器同步广播）。 */
    fun updateNick(newNick: String) {
        nick = newNick
        sendLobbyRaw(BattleMessage.NickUpdate(newNick))
    }

    /** 大厅消息不携带 room/token（尚未入座）。 */
    private fun sendLobbyRaw(msg: BattleMessage): Boolean {
        val text = BattleProtocol.encode(msg)
        Log.i(TAG, "send(lobby): ${msg.type}") // 联调诊断：大厅上行帧
        return transport?.send(text) ?: false
    }

    private fun clearSeat() {
        seatedTable = 0
        roomCode = null
        myToken = null
        gameStarted = false
        // 座位/棋桌已不存在：上一局的快照与内存对账基准一并作废，
        // 避免同一进程内再次开局时被新 GameActivity 当作当前局面恢复。
        clearSyncCache()
        BattleStateStore.clear()
    }

    /** 加入房间。加入者执黑。 */
    fun joinRoom(room: String) {
        resetSession()
        roomCode = room
        state = State.CONNECTING
        notifyState()
        connectTransport { ok ->
            if (ok) {
                state = State.JOINING
                notifyState()
                if (!sendRaw(BattleMessage.JoinRoom(room))) {
                    notifyError("连接异常，请重试")
                    state = State.IDLE
                    notifyState()
                }
            } else {
                notifyError("无法连接服务器")
                state = State.IDLE
                notifyState()
            }
        }
    }

    /** 发送我方走子。 */
    fun sendMove(ucci: String): Boolean {
        if (state != State.PLAYING && state != State.READY) {
            Log.w(TAG, "send move rejected: state=$state")
            return false
        }
        mySeq++
        Log.i(TAG, "send move seq=$mySeq $ucci (color=$myColor)")
        persistProgress()
        return sendRaw(BattleMessage.MoveMade(ucci, mySeq))
    }

    /** 主动请求对账（断线重连后调用）。带当前对局代数，旧局回复会被按代数丢弃。 */
    fun requestSync() {
        sendRaw(BattleMessage.RequestSync(gameGen))
    }

    /** 回复对方 sync 请求（以本地为准推送当前局面）。 */
    fun sendSyncState(fen: String, moves: List<String>, turn: String) {
        lastSyncFen = fen
        lastSyncMoves = moves
        lastSyncTurn = turn
        sendRaw(BattleMessage.SyncState(fen, moves, turn, gameGen))
    }

    /** 重置已同步局面缓存（新一局后旧基准不可再用于恢复）。 */
    fun clearSyncCache() {
        lastSyncFen = ""
        lastSyncMoves = emptyList()
        lastSyncTurn = BattleMessage.COLOR_RED
    }

    /** 代数与走子序号落盘：进程被杀冷启动恢复时保持连续，避免对方判重丢子。 */
    private fun persistProgress() {
        BattleStateStore.saveProgress(gameGen, mySeq, opponentMaxSeq)
    }

    /** 我方认输。 */
    fun sendResign() {
        sendRaw(BattleMessage.Resign())
    }

    /** 我方求和（结果经 onDrawAccepted/onDrawDeclined 返回）。 */
    fun sendDrawOffer() {
        sendRaw(BattleMessage.DrawOffer())
    }

    /** 同意对方的求和请求。 */
    fun sendDrawAccept() {
        sendRaw(BattleMessage.DrawAccept())
    }

    /** 拒绝对方的求和请求。 */
    fun sendDrawDecline() {
        sendRaw(BattleMessage.DrawDecline())
    }

    /** 发起再来一局（对方收到 onNewGame 后同样重置棋局）。 */
    fun sendNewGame() {
        sendRaw(BattleMessage.NewGame())
    }

    /** 我方请求悔棋（结果经 onUndoAccepted/onUndoDeclined 返回）。 */
    fun sendUndoRequest() {
        sendRaw(BattleMessage.UndoRequest())
    }

    /** 同意对方的悔棋请求。 */
    fun sendUndoAccept() {
        sendRaw(BattleMessage.UndoAccept())
    }

    /** 拒绝对方的悔棋请求。 */
    fun sendUndoDecline() {
        sendRaw(BattleMessage.UndoDecline())
    }

    /** 发送文字聊天（对局中/就位后可用）。 */
    fun sendChatText(text: String): Boolean {
        if (state != State.PLAYING && state != State.READY) return false
        return sendRaw(BattleMessage.ChatText(text))
    }

    /** 发送语音聊天（AMR-NB base64，最长 20 秒）。 */
    fun sendChatVoice(amrB64: String, durationMs: Int): Boolean {
        if (state != State.PLAYING && state != State.READY) return false
        return sendRaw(BattleMessage.ChatVoice(amrB64, durationMs))
    }

    /** 主动离开（正常关闭，不自动重连）。 */
    fun leave() {
        clearSyncCache()
        BattleStateStore.clear()
        sendRaw(BattleMessage.LeaveRoom())
        closeTransport()
        state = State.CLOSED
        notifyState()
    }

    /** 完全释放（退出对战入口时调用）。 */
    fun destroy() {
        clearSyncCache()
        BattleStateStore.clear()
        closeTransport()
        state = State.IDLE
        notifyState()
    }

    /**
     * App 回到前台（GameActivity.onResume）时调用，避免半开连接拖到下一轮心跳超时才被发现：
     * - DISCONNECTED：取消正在排队的指数退避，立即重连一次（失败则继续退避流程）；
     * - PLAYING/READY：立即发一次 Ping 探测，且把静默阈值判定提前到此刻；
     * - WAITING_GUEST：建房等待阶段无应用层定时心跳，立即发 Ping 并限时 5 秒
     *   等待回包，超时判死进入断线重连（对手可能已在等待期入座开局）；
     * - CONNECTING/JOINING（握手在途）与 IDLE/CLOSED 不干预。
     */
    fun onReturnToForeground() {
        when (state) {
            State.DISCONNECTED -> {
                pendingReconnect?.let { mainHandler.removeCallbacks(it) }
                pendingReconnect = null
                Log.i(TAG, "foreground: cancel backoff wait, reconnect immediately")
                attemptReconnect()
            }
            State.PLAYING, State.READY -> {
                val silentMs = System.currentTimeMillis() - lastMessageAt
                if (lastMessageAt > 0 && silentMs > heartbeatIntervalMs * 2) {
                    Log.w(TAG, "foreground probe: silent for ${silentMs}ms, treat as dead")
                    handleDisconnected("foreground_probe_timeout")
                } else {
                    sendRaw(BattleMessage.Ping())
                    startHeartbeat()
                }
            }
            State.WAITING_GUEST, State.IN_LOBBY, State.SEATED_WAITING, State.WATCHING -> {
                // 等待态/大厅态不能按 40s 静默判死：服务器 50s 协议层 Ping 不更新 lastMessageAt，
                // 健康连接也可能静默超过阈值；主动发探测包并限时等待回包，
                // 半开连接（写缓冲"成功"但对端早已消失）由超时判死触发重连收敛
                val probeAt = System.currentTimeMillis()
                sendRaw(BattleMessage.Ping())
                waitingProbe?.let { mainHandler.removeCallbacks(it) }
                val probe = Runnable {
                    waitingProbe = null
                    if (state != State.WAITING_GUEST && state != State.IN_LOBBY
                        && state != State.SEATED_WAITING && state != State.WATCHING) return@Runnable
                    if (lastMessageAt > probeAt) return@Runnable // 探测期内收到过消息：连接健康
                    Log.w(TAG, "waiting probe timeout, mark disconnected")
                    handleDisconnected("waiting_probe_timeout")
                }
                waitingProbe = probe
                mainHandler.postDelayed(probe, waitingProbeTimeoutMs)
            }
            else -> Unit
        }
    }

    /**
     * 进程被杀后冷启动恢复：依据 [BattleStateStore] 落盘的房间身份重建会话，
     * 立即重新连接并以原 token 重新 join_room；对局已开始时随后 requestSync 对账。
     *
     * 与 [scheduleReconnect] 的区别：本方法面向"全新进程、无现存连接"的起点，
     * 不能调用 resetSession（会清掉刚从磁盘读出的房间号/token）。
     * 连接失败则落入常规断线重连退避流程（房间信息已就位）。
     */
    fun restoreSession(
        room: String, token: String, color: String,
        host: Boolean, started: Boolean, tableNo: Int = 0,
    ) {
        Log.i(TAG, "restoreSession: room=$room, host=$host, gameStarted=$started, table=$tableNo")
        closeTransport()
        roomCode = room
        myToken = token
        myColor = color
        isHost = host
        gameStarted = started
        // 大厅座位标识还原：冷启动后 seatedTable>0 才能让 UI 显示"我的座位"并触发大厅补跳
        if (tableNo > 0) seatedTable = tableNo
        // 代数与序号沿用落盘值：清零会导致对方把我们恢复后的新走子判重丢弃
        val progress = BattleStateStore.loadProgress()
        gameGen = progress.gen
        mySeq = progress.mySeq
        opponentMaxSeq = progress.oppSeq
        reconnectAttempts = 0
        state = State.CONNECTING
        notifyState()
        connectTransport { ok ->
            if (ok) {
                suppressNextStartGame = true // join_room 后的 start_game 补发只作标记，不重置棋盘
                sendRaw(BattleMessage.JoinRoom(room, token))
                state = when {
                    started -> State.PLAYING
                    seatedTable != 0 -> State.SEATED_WAITING
                    else -> State.READY
                }
                notifyState()
                startHeartbeat()
                // 向对方请求最新局面快照；若对方也恰好离线，服务器会在其回来后转发
                if (started) requestSync()
            } else {
                handleDisconnected("restore_connect_failed")
            }
        }
    }

    /**
     * 回环自测/调试用：跳过建房握手，直接以指定身份进入对局状态。
     * transport 仍由构造时的 transportFactory 提供（如 LoopbackTransport 端点）。
     * 仅供 BattleRoomDialog 的 Loopback 演示与单元测试使用。
     */
    fun injectDebugState(room: String, token: String, color: String, host: Boolean) {
        resetSession()
        this.roomCode = room
        this.myToken = token
        this.myColor = color
        this.isHost = host
        // 直接进入 PLAYING 必须同步 gameStarted，维持"对局中 ⇒ gameStarted"不变量，
        // 否则断线重连不会触发 requestSync 对账
        this.gameStarted = true
        connectTransport { /* 忽略 onOpen 回调 */ }
        this.state = State.PLAYING
        notifyState()
    }

    // ==================== 内部实现 ====================

    private fun resetSession() {
        closeTransport()
        // 新房/重新加入：清掉上一局落盘身份与快照（restoreSession 不走这里）
        BattleStateStore.clear()
        roomCode = null
        myToken = null
        myColor = BattleMessage.COLOR_RED
        isHost = false
        gameStarted = false
        gameGen = 0
        mySeq = 0
        opponentMaxSeq = 0
        lastSyncFen = ""
        lastSyncMoves = emptyList()
        lastSyncTurn = BattleMessage.COLOR_RED
        reconnectAttempts = 0
        reconnectInProgress = false
        mainHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun connectTransport(onResult: (Boolean) -> Unit) {
        val t = transportFactory()
        transport = t
        t.connect(object : BattleTransport.Listener {
            override fun onOpen() {
                mainHandler.post {
                    lastMessageAt = System.currentTimeMillis()
                    onResult(true)
                }
            }

            override fun onMessage(text: String) {
                mainHandler.post { handleMessage(text) }
            }

            override fun onClosed(reason: String) {
                mainHandler.post { handleDisconnected(reason) }
            }

            override fun onError(t: Throwable) {
                mainHandler.post {
                    // 只记日志，不向用户暴露原始 socket 错误（如 "Software caused connection abort"）。
                    // 连接断开会由 onClosed → handleDisconnected 触发自动重连；
                    // 仅重连多次失败后才通过 scheduleReconnect 通知用户。
                    Log.w(TAG, "transport error: ${t.message}")
                }
            }
        })
    }

    private fun closeTransport() {
        transport?.close()
        transport = null
        mainHandler.removeCallbacks(heartbeatRunnable)
        waitingProbe?.let { mainHandler.removeCallbacks(it) }
        waitingProbe = null
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingReconnect = null
    }

    private fun sendRaw(msg: BattleMessage): Boolean {
        val text = BattleProtocol.encode(msg, room = roomCode, token = myToken)
        Log.i(TAG, "send: ${msg.type}") // 联调诊断：上行帧
        return transport?.send(text) ?: false
    }

    private fun handleMessage(text: String) {
        lastMessageAt = System.currentTimeMillis()
        val msg = BattleProtocol.decode(text) ?: run {
            Log.w(TAG, "undecodable message: $text")
            return
        }
        // 真机联调诊断：记录服务端下行帧（含 table 列表摘要），定位重连恢复问题
        if (msg is BattleMessage.LobbyState) {
            Log.i(TAG, "recv lobby_state: ${msg.tables.joinToString { "T${it.no}/${it.status}" }}")
        } else if (msg is BattleMessage.Error) {
            Log.i(TAG, "recv error: code=${msg.code}, msg=${msg.msg}")
        } else {
            Log.i(TAG, "recv: ${msg.type}")
        }
        when (msg) {
            is BattleMessage.RoomCreated -> {
                roomCode = msg.room
                myToken = msg.token
                myColor = msg.color
                isHost = true
                // 大厅棋桌的 token 重连恢复：回到入座等待态而非旧房号的 WAITING_GUEST，
                // 否则 enterLobby 快路径不认这个状态，下次 onResume 会多做一次整条重连
                state = when {
                    gameStarted -> State.PLAYING
                    seatedTable != 0 -> State.SEATED_WAITING
                    else -> State.WAITING_GUEST
                }
                BattleStateStore.saveSession(
                    msg.room, msg.token, msg.color, true, gameStarted, tableNo = seatedTable)
                notifyState()
                if (gameStarted) startHeartbeat()
                android.util.Log.i("BattleSession", "room_created: ${msg.room}")
                listener?.onRoomCreated(msg.room)
            }
            is BattleMessage.RoomJoined -> {
                roomCode = msg.room
                myToken = msg.token
                myColor = msg.color
                // 初次加入者执黑；断线重连时保留原房主身份（服务器也可能给房主回 room_joined）
                if (!gameStarted) isHost = false
                state = when {
                    gameStarted -> State.PLAYING
                    seatedTable != 0 -> State.SEATED_WAITING
                    else -> State.READY
                }
                BattleStateStore.saveSession(
                    msg.room, msg.token, msg.color, isHost, gameStarted, tableNo = seatedTable)
                notifyState()
                if (gameStarted) startHeartbeat()
                listener?.onRoomJoined(msg.color)
            }
            is BattleMessage.StartGame -> {
                // 服务端指定的本人执子色（大厅桌开局/再来一局仲裁都会带）：
                // 颜色唯一权威在服务端，客户端据此更新，不得自行换色
                if (msg.color.isNotEmpty()) {
                    myColor = msg.color
                }
                redNick = msg.red
                blackNick = msg.black
                // 对局代数只认服务端：start_game.gen 是唯一权威。离线期间错过
                // "再来一局"时，重连补投的帧代数更大——必须重置棋盘进入新局；
                // 代数未变的补发帧只是恢复标记，保留快照恢复的棋盘。
                // 代数变小同样视为新局：棋桌被解散后重开的新桌代数会从 1 重新计数，
                // 不能因为"比旧值小"就误判为同一局的补发。
                val resumed = suppressNextStartGame
                suppressNextStartGame = false
                val genChanged = msg.gen > 0 && msg.gen != gameGen
                val resumedSameGame = resumed && !genChanged
                if (msg.gen > 0) gameGen = msg.gen
                if (gameGen == 0) gameGen = 1 // 旧版服务端不带代数：首局兜底
                if (!resumedSameGame) {
                    // 真正的新局（含首局、补投的新局、经过换桌后的新局）：上一局的
                    // 落盘快照与内存对账基准立即作废。对局结束后留下的旧快照若被
                    // 新的 GameActivity 恢复，会倒退成上一局的棋盘而不是重新开局。
                    BattleStateStore.clearSnapshot()
                    clearSyncCache()
                    // 走子序号随对局重新计数：双方都在此刻归零才能对齐，
                    // 否则旧局较大的 opponentMaxSeq 会把对方新局的低序号着法判重丢弃
                    mySeq = 0
                    opponentMaxSeq = 0
                }
                state = State.PLAYING
                gameStarted = true
                BattleStateStore.saveSession(roomCode, myToken, myColor, isHost, true, tableNo = seatedTable)
                persistProgress()
                notifyState()
                startHeartbeat()
                if (resumedSameGame) {
                    // 重连恢复补发：同步服务端颜色即可，棋盘保持快照恢复结果
                    Log.i(TAG, "start_game resumed (gen=$gameGen), sync color only")
                    listener?.onGameResumed(myColor)
                } else {
                    Log.i(TAG, "start_game new game (gen=$gameGen, wasResumed=$resumed)")
                    listener?.onGameStart()
                }
            }
            is BattleMessage.MoveMade -> {
                if (state == State.WATCHING) {
                    // 观战：双方走子混编到达，seq 各自独立，不做去重
                    listener?.onRemoteMove(msg.ucci, msg.seq)
                    return
                }
                if (msg.seq <= opponentMaxSeq) {
                    Log.d(TAG, "duplicate move seq=${msg.seq}, ignore")
                    return
                }
                opponentMaxSeq = msg.seq
                Log.i(TAG, "recv move seq=${msg.seq} ${msg.ucci} (myColor=$myColor)")
                persistProgress()
                listener?.onRemoteMove(msg.ucci, msg.seq)
            }
            is BattleMessage.RequestSync -> {
                // 对方请求对账：由控制器回推本地局面快照
                listener?.onSyncRequested()
            }
            is BattleMessage.SyncState -> {
                if (msg.gen != gameGen) {
                    // 旧局迟到的对账快照：新一局后棋盘已重置，套用旧局面会"复活"已结束的棋局
                    Log.w(TAG, "drop stale sync_state gen=${msg.gen}, current=$gameGen")
                    return
                }
                lastSyncFen = msg.fen
                lastSyncMoves = msg.moves
                lastSyncTurn = msg.turn
                state = State.PLAYING
                notifyState()
                listener?.onSyncState(msg.fen, msg.moves, msg.turn)
            }
            is BattleMessage.OpponentOffline -> listener?.onOpponentOffline(msg.ttlSeconds)
            is BattleMessage.OpponentOnline -> listener?.onOpponentOnline()
            is BattleMessage.Resign -> listener?.onOpponentResign()
            is BattleMessage.LeaveRoom -> {
                if (seatedTable != 0) {
                    // 大厅模式：对方离座/保留超时导致棋桌解散，连接保持，回到大厅
                    clearSeat()
                    state = State.IN_LOBBY
                    notifyState()
                }
                listener?.onOpponentLeave(msg.reason)
            }
            is BattleMessage.DrawOffer -> listener?.onDrawOffer()
            is BattleMessage.DrawAccept -> listener?.onDrawAccepted()
            is BattleMessage.DrawDecline -> listener?.onDrawDeclined()
            is BattleMessage.NewGame -> listener?.onNewGame()
            is BattleMessage.UndoRequest -> listener?.onUndoRequest()
            is BattleMessage.UndoAccept -> listener?.onUndoAccepted()
            is BattleMessage.UndoDecline -> listener?.onUndoDeclined()
            is BattleMessage.ChatText -> {
                if (state == State.WATCHING) return // 旁观者不收私聊（服务端本就不转发，双保险）
                listener?.onChatText(msg.text)
            }
            is BattleMessage.ChatVoice -> {
                if (state == State.WATCHING) return
                listener?.onChatVoice(msg.amrB64, msg.durationMs)
            }
            // —— 大厅（棋桌）模式 ——
            is BattleMessage.LobbyState -> {
                if (state == State.CONNECTING || state == State.IDLE) {
                    state = State.IN_LOBBY
                    notifyState()
                }
                listener?.onLobbyState(msg.tables)
            }
            is BattleMessage.TableUpdate -> listener?.onTableUpdate(msg.info)
            is BattleMessage.SitOk -> {
                // 刚重新入座：随后的 start_game 必为新局开始，不能沿用"重连补发同一局"
                // 的抑制标记（否则同代数的另一张新桌会被误判为恢复而不开对局页）
                suppressNextStartGame = false
                seatedTable = msg.table
                roomCode = msg.room
                myToken = msg.token
                myColor = msg.color
                isHost = msg.color == BattleMessage.COLOR_RED
                // 入座即落盘：等待期间进程被杀也可凭 token 恢复原座位（tableNo 供冷启动还原座位标识）
                BattleStateStore.saveSession(msg.room, msg.token, msg.color, isHost, false, tableNo = msg.table)
                if (!gameStarted) {
                    state = State.SEATED_WAITING
                    notifyState()
                }
                listener?.onSitOk(msg.table, msg.color)
            }
            is BattleMessage.WatchOk -> {
                watchingTable = msg.table
                redNick = msg.red
                blackNick = msg.black
                state = State.WATCHING
                notifyState()
                listener?.onWatchOk(msg.table, msg.red, msg.black, msg.moves, msg.turn)
            }
            is BattleMessage.WatchEnd -> {
                watchingTable = 0
                if (state == State.WATCHING) {
                    state = State.IN_LOBBY
                    notifyState()
                }
                listener?.onWatchEnd(msg.table, msg.msg)
            }
            is BattleMessage.OpponentLeft -> {
                // 对手终局后离座：棋桌保留、本人座位不变（seatedTable 不动）。
                // 本地退出对局态回等待态，并把落盘的 game_started 清掉——
                // MainActivity 补跳据此回大厅而不是对战页。
                // 本局已结束且对手已走：上一局的落盘快照/内存对账基准作废，
                // 否则下一次开局时新页面会把这场已结束的棋局恢复出来。
                if (state == State.PLAYING || state == State.READY) {
                    state = State.SEATED_WAITING
                }
                clearSyncCache()
                BattleStateStore.clearSnapshot()
                BattleStateStore.saveSession(roomCode, myToken, myColor, isHost, false, tableNo = seatedTable)
                notifyState()
                listener?.onOpponentLeft()
            }
            is BattleMessage.Ping -> sendRaw(BattleMessage.Pong(msg.ts))
            is BattleMessage.Pong -> Unit // 心跳回包，仅更新 lastMessageAt
            is BattleMessage.Error -> {
                when {
                    state == State.JOINING -> {
                        // 配对阶段被服务器拒绝（房间不存在/已满/房主离线）：断开回到空闲，允许重试
                        closeTransport()
                        state = State.IDLE
                        notifyState()
                    }
                    // 大厅业务错误（已有空桌/座位被占/棋桌不存在等）：仅提示，不改变状态
                    state == State.IN_LOBBY || state == State.SEATED_WAITING
                        || state == State.WATCHING -> {
                        // 观战目标棋桌已被解散：退出观战回大厅，避免之后每轮重连都watch死桌
                        if (msg.code == ERR_TABLE_NOT_FOUND && watchingTable != 0) {
                            val t = watchingTable
                            watchingTable = 0
                            state = State.IN_LOBBY
                            notifyState()
                            listener?.onWatchEnd(t, msg.msg.ifEmpty { "对局已结束" })
                        }
                    }
                    msg.code == ERR_ROOM_NOT_FOUND || msg.code == ERR_INVALID_TOKEN -> {
                        BattleStateStore.clear()
                        if (seatedTable != 0 || watchingTable != 0 || nick.isNotEmpty()) {
                            // 大厅模式：离线期间棋桌已解散（对方离开/保留超时），
                            // 仅清座位回大厅，保持长连继续接收桌状态广播
                            clearSeat()
                            watchingTable = 0
                            reconnectAttempts = 0
                            state = State.IN_LOBBY
                            notifyState()
                        } else {
                            // 旧版房号模式：房间终态关闭，不再重连
                            closeTransport()
                            state = State.CLOSED
                            notifyState()
                        }
                    }
                }
                notifyError(msg.msg.ifEmpty { msg.code })
            }
            else -> Log.d(TAG, "unhandled message type=${msg.type}")
        }
    }

    private fun handleDisconnected(reason: String) {
        Log.w(TAG, "disconnected: $reason, state=$state")
        if (state == State.CLOSED || state == State.IDLE) return
        // 任何连接结束都意味着在途重连尝试已出结果（成功建连后再断也允许下次重连）
        reconnectInProgress = false
        // 终态关闭：正常关闭 1000 或房间关闭 4001（对方离开/房间超时），不再重连
        if (reason == "closed:1000" || reason == "closed:$CLOSE_ROOM_CLOSED") {
            BattleStateStore.clear()
            state = State.CLOSED
            notifyState()
            return
        }
        state = State.DISCONNECTED
        notifyState()
        mainHandler.removeCallbacks(heartbeatRunnable)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 8) {
            BattleStateStore.clear()
            notifyError("重连失败，请重新建房")
            state = State.CLOSED
            notifyState()
            return
        }
        val delay = minOf(1000L * (1 shl reconnectAttempts), maxReconnectDelayMs)
        reconnectAttempts++
        val r = Runnable {
            pendingReconnect = null
            if (state != State.DISCONNECTED) return@Runnable
            attemptReconnect()
        }
        pendingReconnect = r
        mainHandler.postDelayed(r, delay)
    }

    /**
     * 立即执行一次重连（不退避、不增加尝试计数）：
     * 供指数退避到期与 [onReturnToForeground] 回前台抢通共用；失败则继续排下一轮退避。
     *
     * 大厅模式重连：先 enter_lobby 恢复订阅，再凭 token 重入座位，或重新观战。
     */
    private fun attemptReconnect() {
        if (state != State.DISCONNECTED || reconnectInProgress) return
        reconnectInProgress = true
        connectTransport { ok ->
            reconnectInProgress = false
            if (!ok) {
                scheduleReconnect()
                return@connectTransport
            }
            if (nick.isNotEmpty()) {
                // 大厅模式：恢复订阅与昵称
                sendLobbyRaw(BattleMessage.EnterLobby(nick))
            }
            val room = roomCode
            when {
                room != null && myToken != null -> {
                    // 凭 token 恢复原座位（大厅或旧版房均适用）
                    suppressNextStartGame = true // start_game 补发只作标记，不重置棋盘
                    sendRaw(BattleMessage.JoinRoom(room, myToken))
                    state = when {
                        gameStarted -> State.PLAYING
                        seatedTable != 0 -> State.SEATED_WAITING
                        else -> State.READY
                    }
                    notifyState()
                    if (gameStarted) requestSync()
                    reconnectAttempts = 0
                    startHeartbeat()
                }
                watchingTable != 0 -> {
                    // 重新请求观战（watch_ok 将恢复局面）
                    sendLobbyRaw(BattleMessage.Watch(watchingTable))
                    reconnectAttempts = 0
                }
                else -> {
                    // 仅大厅浏览态
                    if (state != State.IN_LOBBY) {
                        state = State.IN_LOBBY
                        notifyState()
                    }
                    reconnectAttempts = 0
                }
            }
        }
    }

    private fun startHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, heartbeatIntervalMs)
    }

    private fun notifyState() {
        val s = state
        listener?.onStateChanged(s)
    }

    private fun notifyError(msg: String) {
        listener?.onError(msg)
    }

    companion object {
        private const val TAG = "BattleSession"

        /** 服务器自定义关闭码：房间终态关闭（对方离开/保留超时），收到后不重连。 */
        const val CLOSE_ROOM_CLOSED = 4001

        // 与服务端 protocol.go 对齐的业务错误码
        private const val ERR_ROOM_NOT_FOUND = "room_not_found"
        private const val ERR_INVALID_TOKEN = "invalid_token"
        private const val ERR_TABLE_NOT_FOUND = "table_not_found"
    }
}
