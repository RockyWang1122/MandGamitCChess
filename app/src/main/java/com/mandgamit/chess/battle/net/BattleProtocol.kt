package com.mandgamit.chess.battle.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 好友对战协议（JSON over WebSocket，扁平结构：{type, token?, room?, ...}）。
 *
 * 服务器纯转发：除房间管理（create/join/leave）外，对局消息原样转发给对端。
 * 走法合法性由双方本机各自校验（RulesetProvider）。
 *
 * 一期消息集：建房/加入/开局/走子/断线重连对账/心跳/认输。
 * 预留 timeLeft 字段（一期不限时，不使用）。
 */
sealed class BattleMessage(val type: String) {

    // —— 客户端 → 服务器（房间管理） ——
    class CreateRoom(val wantRoom: String? = null) : BattleMessage(TYPE_CREATE_ROOM)
    data class JoinRoom(val room: String, val token: String? = null) : BattleMessage(TYPE_JOIN_ROOM)
    data class LeaveRoom(val reason: String? = null) : BattleMessage(TYPE_LEAVE_ROOM)

    // —— 服务器 → 客户端（房间管理回包） ——
    data class RoomCreated(val room: String, val token: String, val color: String) : BattleMessage(TYPE_ROOM_CREATED)
    data class RoomJoined(val room: String, val token: String, val color: String) : BattleMessage(TYPE_ROOM_JOINED)
    data class Error(val code: String, val msg: String) : BattleMessage(TYPE_ERROR)

    // —— 对局消息（服务器转发，需带 token + room） ——
    /**
     * 开局。大厅模式携带红/黑双方昵称；旧版房号模式两个字段为空。
     * color：服务端指定本人执子色（"再来一局"仲裁换色后各端不同）；
     * 旁观者为空；旧版房号模式的 start_game 无此字段。
     * gen：服务端权威对局代数（每开一局自增）。重连补发的同一局与离线期间
     * 错过的新局都靠它区分：代数为 0 表示旧版服务端未提供。
     */
    data class StartGame(
        val red: String = "",
        val black: String = "",
        val color: String = "",
        val gen: Int = 0,
    ) : BattleMessage(TYPE_START_GAME)
    data class MoveMade(val ucci: String, val seq: Int) : BattleMessage(TYPE_MOVE)
    data class RequestSync(val gen: Int = 0) : BattleMessage(TYPE_REQUEST_SYNC)
    data class SyncState(
        val fen: String,
        val moves: List<String>,
        val turn: String, // "red" | "black"
        val gen: Int = 0, // 对局代数：新一局自增，旧局迟到的对账快照按代数丢弃
    ) : BattleMessage(TYPE_SYNC_STATE)
    class Resign : BattleMessage(TYPE_RESIGN)

    // —— 对局协商（服务器转发） ——
    class DrawOffer : BattleMessage(TYPE_DRAW_OFFER)      // 我方求和
    class DrawAccept : BattleMessage(TYPE_DRAW_ACCEPT)    // 对方同意求和（我方为提议方）
    class DrawDecline : BattleMessage(TYPE_DRAW_DECLINE)  // 对方拒绝求和（我方为提议方）
    class NewGame : BattleMessage(TYPE_NEW_GAME)          // 再来一局

    // —— 悔棋协商（服务器转发，无负载；同意后双方各自撤回到请求方上一走子前） ——
    class UndoRequest : BattleMessage(TYPE_UNDO_REQUEST)  // 我方请求悔棋
    class UndoAccept : BattleMessage(TYPE_UNDO_ACCEPT)    // 对方同意悔棋
    class UndoDecline : BattleMessage(TYPE_UNDO_DECLINE)  // 对方拒绝悔棋

    // —— 连接维护 ——
    class Ping(val ts: Long = System.currentTimeMillis()) : BattleMessage(TYPE_PING)
    class Pong(val ts: Long = 0L) : BattleMessage(TYPE_PONG)
    /** 对方掉线。ttlSeconds 为服务器房间保留时长（秒），0 表示未知。 */
    data class OpponentOffline(val ttlSeconds: Int) : BattleMessage(TYPE_OPPONENT_OFFLINE)
    class OpponentOnline : BattleMessage(TYPE_OPPONENT_ONLINE)

    // —— 聊天（服务器转发，双方均不落地存储） ——
    /** 文字消息（发送端限制 40 字）。 */
    data class ChatText(val text: String) : BattleMessage(TYPE_CHAT_TEXT)
    /** 语音消息：AMR-NB 12.2kbps 编码的 base64 数据，最长 20 秒（base64 后约 41KB，低于服务器 64KB 上限）。 */
    data class ChatVoice(val amrB64: String, val durationMs: Int) : BattleMessage(TYPE_CHAT_VOICE)

    // —— 大厅（棋桌）模式：客户端 → 服务器 ——
    /** 进入大厅：订阅桌状态广播，服务器回 lobby_state 全量。 */
    data class EnterLobby(val nick: String) : BattleMessage(TYPE_ENTER_LOBBY)
    /** 离开大厅：退订广播（入座/观战状态不受影响）。 */
    class LeaveLobby : BattleMessage(TYPE_LEAVE_LOBBY)
    /** 新建空桌：已有空桌时服务器拒绝（empty_table_exists）。 */
    class CreateTable : BattleMessage(TYPE_CREATE_TABLE)
    /** 入座：color 为空时服务器默认分配（首坐红、次坐黑）；坐满自动 start_game。 */
    data class Sit(val table: Int, val color: String? = null) : BattleMessage(TYPE_SIT)
    /** 离座：未开局释放座位；已开局等价解散棋桌。afterGame=true 表示对局已终局，服务端保留棋桌。 */
    data class Stand(val table: Int, val afterGame: Boolean = false) : BattleMessage(TYPE_STAND)
    /** 观战指定桌（须对局进行中），服务器回 watch_ok（含走子缓存）。 */
    data class Watch(val table: Int) : BattleMessage(TYPE_WATCH)
    /** 结束观战。 */
    data class Unwatch(val table: Int) : BattleMessage(TYPE_UNWATCH)
    /** 修改昵称：已入座/观战席位同步广播。 */
    data class NickUpdate(val nick: String) : BattleMessage(TYPE_NICK_UPDATE)

    // —— 大厅（棋桌）模式：服务器 → 客户端 ——
    /** 大厅全量快照（enter_lobby 的应答）。 */
    data class LobbyState(val tables: List<TableInfo>) : BattleMessage(TYPE_LOBBY_STATE)
    /** 单桌增量广播（info.status == closed 表示该桌已销毁）。 */
    data class TableUpdate(val info: TableInfo) : BattleMessage(TYPE_TABLE_UPDATE)
    /** 入座成功（携带桌号/颜色/房号/token，供断线重连）。 */
    data class SitOk(val table: Int, val color: String, val room: String, val token: String) :
        BattleMessage(TYPE_SIT_OK)
    /** 观战成功（携带双方昵称与走子缓存，供本地重放）。 */
    data class WatchOk(
        val table: Int,
        val red: String,
        val black: String,
        val moves: List<String>,
        val turn: String,
    ) : BattleMessage(TYPE_WATCH_OK)
    /** 观战的棋桌已销毁。 */
    data class WatchEnd(val table: Int, val msg: String) : BattleMessage(TYPE_WATCH_END)
    /** 终局后对手离座：棋桌保留，本人座位不变（回大厅继续等待新对手）。 */
    data class OpponentLeft(val msg: String) : BattleMessage(TYPE_OPPONENT_LEFT)

    companion object {
        const val TYPE_CREATE_ROOM = "create_room"
        const val TYPE_JOIN_ROOM = "join_room"
        const val TYPE_LEAVE_ROOM = "leave_room"
        const val TYPE_ROOM_CREATED = "room_created"
        const val TYPE_ROOM_JOINED = "room_joined"
        const val TYPE_ERROR = "error"
        const val TYPE_START_GAME = "start_game"
        const val TYPE_MOVE = "move"
        const val TYPE_REQUEST_SYNC = "request_sync"
        const val TYPE_SYNC_STATE = "sync_state"
        const val TYPE_RESIGN = "resign"
        const val TYPE_DRAW_OFFER = "draw_offer"
        const val TYPE_DRAW_ACCEPT = "draw_accept"
        const val TYPE_DRAW_DECLINE = "draw_decline"
        const val TYPE_NEW_GAME = "new_game"
        const val TYPE_UNDO_REQUEST = "undo_request"
        const val TYPE_UNDO_ACCEPT = "undo_accept"
        const val TYPE_UNDO_DECLINE = "undo_decline"
        const val TYPE_PING = "ping"
        const val TYPE_PONG = "pong"
        const val TYPE_OPPONENT_OFFLINE = "opponent_offline"
        const val TYPE_OPPONENT_ONLINE = "opponent_online"
        const val TYPE_CHAT_TEXT = "chat_text"
        const val TYPE_CHAT_VOICE = "chat_voice"

        // —— 大厅（棋桌）模式 ——
        const val TYPE_ENTER_LOBBY = "enter_lobby"
        const val TYPE_LEAVE_LOBBY = "leave_lobby"
        const val TYPE_CREATE_TABLE = "create_table"
        const val TYPE_SIT = "sit"
        const val TYPE_STAND = "stand"
        const val TYPE_WATCH = "watch"
        const val TYPE_UNWATCH = "unwatch"
        const val TYPE_NICK_UPDATE = "nick_update"
        const val TYPE_LOBBY_STATE = "lobby_state"
        const val TYPE_TABLE_UPDATE = "table_update"
        const val TYPE_SIT_OK = "sit_ok"
        const val TYPE_WATCH_OK = "watch_ok"
        const val TYPE_WATCH_END = "watch_end"
        const val TYPE_OPPONENT_LEFT = "opponent_left"

        const val COLOR_RED = "red"
        const val COLOR_BLACK = "black"

        // 棋桌状态（TableInfo.status）
        const val TABLE_EMPTY = "empty"
        const val TABLE_WAITING = "waiting"
        const val TABLE_PLAYING = "playing"
        const val TABLE_CLOSED = "closed"
    }
}

/** 大厅单桌快照（与服务端 protocol.go 的 TableInfo 对齐）。 */
data class TableInfo(
    val no: Int,
    val status: String, // empty / waiting / playing / closed
    val red: String = "",
    val black: String = "",
    val watchers: Int = 0,
)

/**
 * 协议编解码。所有出站消息自动附加 room/token（由 SessionManager 填入）。
 */
object BattleProtocol {

    fun encode(msg: BattleMessage, room: String? = null, token: String? = null): String {
        val o = JSONObject()
        o.put("type", msg.type)
        if (room != null) o.put("room", room)
        if (token != null) o.put("token", token)
        when (msg) {
            is BattleMessage.CreateRoom -> {
                if (msg.wantRoom != null) o.put("want_room", msg.wantRoom)
            }
            is BattleMessage.JoinRoom -> {
                o.put("room", msg.room)
                if (msg.token != null) o.put("token", msg.token) // 重连时带原 token
            }
            is BattleMessage.MoveMade -> {
                o.put("ucci", msg.ucci)
                o.put("seq", msg.seq)
            }
            is BattleMessage.RequestSync -> o.put("gen", msg.gen)
            is BattleMessage.SyncState -> {
                o.put("fen", msg.fen)
                o.put("moves", JSONArray(msg.moves))
                o.put("turn", msg.turn)
                o.put("gen", msg.gen)
            }
            is BattleMessage.Ping -> o.put("ts", msg.ts)
            is BattleMessage.Pong -> o.put("ts", msg.ts)
            is BattleMessage.ChatText -> o.put("text", msg.text)
            is BattleMessage.ChatVoice -> {
                o.put("data", msg.amrB64)
                o.put("dur", msg.durationMs)
            }
            is BattleMessage.RoomCreated -> {
                o.put("room", msg.room)
                o.put("token", msg.token)
                o.put("color", msg.color)
            }
            is BattleMessage.RoomJoined -> {
                o.put("room", msg.room)
                o.put("token", msg.token)
                o.put("color", msg.color)
            }
            is BattleMessage.Error -> {
                o.put("code", msg.code)
                o.put("msg", msg.msg)
            }
            // —— 大厅（棋桌）模式 ——
            is BattleMessage.EnterLobby -> o.put("nick", msg.nick)
            is BattleMessage.NickUpdate -> o.put("nick", msg.nick)
            is BattleMessage.Sit -> {
                o.put("table", msg.table)
                if (msg.color != null) o.put("color", msg.color)
            }
            is BattleMessage.Stand -> {
                o.put("table", msg.table)
                if (msg.afterGame) o.put("after_game", true)
            }
            is BattleMessage.Watch -> o.put("table", msg.table)
            is BattleMessage.Unwatch -> o.put("table", msg.table)
            else -> Unit // 无负载类型
        }
        return o.toString()
    }

    /** 解析失败返回 null（调用方记录日志并忽略）。 */
    fun decode(text: String): BattleMessage? {
        return try {
            val o = JSONObject(text)
            when (o.getString("type")) {
                BattleMessage.TYPE_CREATE_ROOM -> BattleMessage.CreateRoom(
                    wantRoom = o.optString("want_room").ifEmpty { null },
                )
                BattleMessage.TYPE_JOIN_ROOM -> BattleMessage.JoinRoom(
                    room = o.getString("room"),
                    token = o.optString("token").ifEmpty { null },
                )
                BattleMessage.TYPE_LEAVE_ROOM -> BattleMessage.LeaveRoom(
                    // 服务器方向可携带关闭原因（如保留超时）；客户端发出时无负载
                    reason = o.optString("msg").ifEmpty { null },
                )
                BattleMessage.TYPE_ROOM_CREATED -> BattleMessage.RoomCreated(
                    room = o.getString("room"),
                    token = o.getString("token"),
                    color = o.optString("color", BattleMessage.COLOR_RED),
                )
                BattleMessage.TYPE_ROOM_JOINED -> BattleMessage.RoomJoined(
                    room = o.getString("room"),
                    token = o.getString("token"),
                    color = o.optString("color", BattleMessage.COLOR_BLACK),
                )
                BattleMessage.TYPE_ERROR -> BattleMessage.Error(
                    code = o.optString("code", "unknown"),
                    msg = o.optString("msg", ""),
                )
                BattleMessage.TYPE_START_GAME -> BattleMessage.StartGame(
                    red = o.optString("red", ""),
                    black = o.optString("black", ""),
                    color = o.optString("color", ""),
                    gen = o.optInt("gen"),
                )
                BattleMessage.TYPE_MOVE -> BattleMessage.MoveMade(
                    ucci = o.getString("ucci"),
                    seq = o.optInt("seq", 0),
                )
                BattleMessage.TYPE_REQUEST_SYNC -> BattleMessage.RequestSync(gen = o.optInt("gen"))
                BattleMessage.TYPE_SYNC_STATE -> {
                    val arr = o.optJSONArray("moves") ?: JSONArray()
                    val moves = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) moves.add(arr.getString(i))
                    BattleMessage.SyncState(
                        fen = o.getString("fen"),
                        moves = moves,
                        turn = o.optString("turn", BattleMessage.COLOR_RED),
                        gen = o.optInt("gen"),
                    )
                }
                BattleMessage.TYPE_RESIGN -> BattleMessage.Resign()
                BattleMessage.TYPE_DRAW_OFFER -> BattleMessage.DrawOffer()
                BattleMessage.TYPE_DRAW_ACCEPT -> BattleMessage.DrawAccept()
                BattleMessage.TYPE_DRAW_DECLINE -> BattleMessage.DrawDecline()
                BattleMessage.TYPE_NEW_GAME -> BattleMessage.NewGame()
                BattleMessage.TYPE_UNDO_REQUEST -> BattleMessage.UndoRequest()
                BattleMessage.TYPE_UNDO_ACCEPT -> BattleMessage.UndoAccept()
                BattleMessage.TYPE_UNDO_DECLINE -> BattleMessage.UndoDecline()
                BattleMessage.TYPE_PING -> BattleMessage.Ping(o.optLong("ts", 0L))
                BattleMessage.TYPE_PONG -> BattleMessage.Pong(o.optLong("ts", 0L))
                BattleMessage.TYPE_OPPONENT_OFFLINE ->
                    BattleMessage.OpponentOffline(o.optInt("ttl", 0))
                BattleMessage.TYPE_OPPONENT_ONLINE -> BattleMessage.OpponentOnline()
                BattleMessage.TYPE_OPPONENT_LEFT ->
                    BattleMessage.OpponentLeft(o.optString("msg", ""))
                BattleMessage.TYPE_CHAT_TEXT -> BattleMessage.ChatText(o.getString("text"))
                BattleMessage.TYPE_CHAT_VOICE -> BattleMessage.ChatVoice(
                    amrB64 = o.getString("data"),
                    durationMs = o.optInt("dur", 0),
                )
                // —— 大厅（棋桌）模式 ——
                BattleMessage.TYPE_LOBBY_STATE -> {
                    val arr = o.optJSONArray("tables") ?: JSONArray()
                    val tables = ArrayList<TableInfo>(arr.length())
                    for (i in 0 until arr.length()) tables.add(decodeTableInfo(arr.getJSONObject(i)))
                    BattleMessage.LobbyState(tables)
                }
                BattleMessage.TYPE_TABLE_UPDATE -> BattleMessage.TableUpdate(
                    info = decodeTableInfo(o.getJSONObject("info")),
                )
                BattleMessage.TYPE_SIT_OK -> BattleMessage.SitOk(
                    table = o.getInt("table"),
                    color = o.optString("color", BattleMessage.COLOR_RED),
                    room = o.optString("room", ""),
                    token = o.optString("token", ""),
                )
                BattleMessage.TYPE_WATCH_OK -> {
                    val arr = o.optJSONArray("moves") ?: JSONArray()
                    val moves = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) moves.add(arr.getString(i))
                    BattleMessage.WatchOk(
                        table = o.getInt("table"),
                        red = o.optString("red", ""),
                        black = o.optString("black", ""),
                        moves = moves,
                        turn = o.optString("turn", BattleMessage.COLOR_RED),
                    )
                }
                BattleMessage.TYPE_WATCH_END -> BattleMessage.WatchEnd(
                    table = o.getInt("table"),
                    msg = o.optString("msg", ""),
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeTableInfo(o: JSONObject) = TableInfo(
        no = o.getInt("no"),
        status = o.optString("status", BattleMessage.TABLE_EMPTY),
        red = o.optString("red", ""),
        black = o.optString("black", ""),
        watchers = o.optInt("watchers", 0),
    )
}
