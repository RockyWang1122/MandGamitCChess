package com.mandgamit.chess.battle

import android.content.Context
import android.util.Log
import com.mandgamit.chess.ChessApp
import com.mandgamit.chess.battle.net.BattleMessage
import org.json.JSONArray

/**
 * 好友对战状态持久化：房间身份（房间号/token/执子颜色）与对局快照（FEN+全部着法）。
 *
 * 唯一需要落盘的理由：国产 ROM / 系统低内存会在 APP 切后台后杀死进程（即使有前台服务），
 * 进程重启后 ChessApp 静态持有的 BattleSessionManager 与内存棋局全部消失，
 * 用户切回 APP 只能看到主界面。冷启动时读取本存储 → 自动重新加入房间 + 对账恢复棋局。
 *
 * 离开房间（leave/destroy）或房间被服务器终态回收时必须 [clear]。
 */
object BattleStateStore {

    private const val TAG = "BattleStateStore"
    private const val PREFS = "battle_session"

    private const val KEY_ROOM = "room"
    private const val KEY_TOKEN = "token"
    private const val KEY_COLOR = "color"
    private const val KEY_IS_HOST = "is_host"
    private const val KEY_GAME_STARTED = "game_started"
    private const val KEY_TABLE_NO = "table_no"
    private const val KEY_SNAP_FEN = "snap_fen"
    private const val KEY_SNAP_MOVES = "snap_moves"
    private const val KEY_SNAP_TURN = "snap_turn"
    private const val KEY_GEN = "game_gen"
    private const val KEY_MY_SEQ = "my_seq"
    private const val KEY_OPP_SEQ = "opp_seq"

    /** 跨进程恢复所需的对局进度：代数与双方走子序号。 */
    data class Progress(val gen: Int = 0, val mySeq: Int = 0, val oppSeq: Int = 0)

    /** 可恢复的房间身份信息。 */
    data class SessionInfo(
        val room: String,
        val token: String,
        val color: String,
        val isHost: Boolean,
        val gameStarted: Boolean,
        val tableNo: Int = 0, // 大厅棋桌号；0=旧版房号模式（非大厅座位）
    )

    /** 本地持久化的对局快照。 */
    data class Snapshot(
        val fen: String,
        val moves: List<String>,
        val turn: String,
    )

    private fun prefs(): android.content.SharedPreferences? = try {
        val ctx: Context? = ChessApp.getContext()
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        // 单测（无 Application）等极端场景：静默降级为无持久化
        Log.w(TAG, "prefs unavailable", t)
        null
    }

    /** 保存/更新房间身份。room/token 为空时忽略；tableNo>0 表示大厅棋桌座位。 */
    @JvmStatic
    fun saveSession(
        room: String?, token: String?, color: String,
        isHost: Boolean, gameStarted: Boolean, tableNo: Int = 0,
    ) {
        if (room.isNullOrEmpty() || token.isNullOrEmpty()) return
        try {
            prefs()?.edit()
                ?.putString(KEY_ROOM, room)
                ?.putString(KEY_TOKEN, token)
                ?.putString(KEY_COLOR, color)
                ?.putBoolean(KEY_IS_HOST, isHost)
                ?.putBoolean(KEY_GAME_STARTED, gameStarted)
                ?.putInt(KEY_TABLE_NO, tableNo)
                ?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "saveSession failed", t)
        }
    }

    /** 读取可恢复房间；无记录返回 null。 */
    @JvmStatic
    fun loadSession(): SessionInfo? {
        return try {
            val sp = prefs() ?: return null
            val room = sp.getString(KEY_ROOM, null)
            val token = sp.getString(KEY_TOKEN, null)
            if (room.isNullOrEmpty() || token.isNullOrEmpty()) return null
            SessionInfo(
                room = room,
                token = token,
                color = sp.getString(KEY_COLOR, BattleMessage.COLOR_RED)
                    ?: BattleMessage.COLOR_RED,
                isHost = sp.getBoolean(KEY_IS_HOST, false),
                gameStarted = sp.getBoolean(KEY_GAME_STARTED, false),
                tableNo = sp.getInt(KEY_TABLE_NO, 0),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "loadSession failed", t)
            null
        }
    }

    /** 是否存在已开局、冷启动应直接回到对局页的会话。 */
    @JvmStatic
    fun hasActiveGame(): Boolean = loadSession()?.gameStarted == true

    /** 覆盖保存当前对局快照（每次本地/对方走子、悔棋、新局、对账后调用）。 */
    @JvmStatic
    fun saveSnapshot(fen: String, moves: List<String>, turn: String) {
        try {
            val arr = JSONArray()
            moves.forEach { arr.put(it) }
            prefs()?.edit()
                ?.putString(KEY_SNAP_FEN, fen)
                ?.putString(KEY_SNAP_MOVES, arr.toString())
                ?.putString(KEY_SNAP_TURN, turn)
                ?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "saveSnapshot failed", t)
        }
    }

    /**
     * 只清除对局快照，保留房间身份（房间号/token/座位）。
     * 对局结束转回等待态、或新一局开始时调用：此时上一局棋盘已不能也不应再被恢复，
     * 否则随后打开的 GameActivity 会把已结束的棋局当成当前局"复活"。
     */
    @JvmStatic
    fun clearSnapshot() {
        try {
            prefs()?.edit()
                ?.remove(KEY_SNAP_FEN)
                ?.remove(KEY_SNAP_MOVES)
                ?.remove(KEY_SNAP_TURN)
                ?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "clearSnapshot failed", t)
        }
    }

    /** 读取最近一次对局快照；无记录返回 null。 */
    @JvmStatic
    fun getSnapshot(): Snapshot? {
        return try {
            val sp = prefs() ?: return null
            val fen = sp.getString(KEY_SNAP_FEN, null)
            val raw = sp.getString(KEY_SNAP_MOVES, null)
            if (fen.isNullOrEmpty() || raw.isNullOrEmpty()) return null
            val arr = JSONArray(raw)
            val moves = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) moves.add(arr.getString(i))
            Snapshot(
                fen = fen,
                moves = moves,
                turn = sp.getString(KEY_SNAP_TURN, BattleMessage.COLOR_RED)
                    ?: BattleMessage.COLOR_RED,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "getSnapshot failed", t)
            null
        }
    }

    /** 保存对局代数与走子序号（sendMove/收子/新一局时调用，冷启动恢复用）。 */
    @JvmStatic
    fun saveProgress(gen: Int, mySeq: Int, oppSeq: Int) {
        try {
            prefs()?.edit()
                ?.putInt(KEY_GEN, gen)
                ?.putInt(KEY_MY_SEQ, mySeq)
                ?.putInt(KEY_OPP_SEQ, oppSeq)
                ?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "saveProgress failed", t)
        }
    }

    /** 读取对局进度；无记录返回全 0。 */
    @JvmStatic
    fun loadProgress(): Progress {
        return try {
            val sp = prefs() ?: return Progress()
            Progress(
                gen = sp.getInt(KEY_GEN, 0),
                mySeq = sp.getInt(KEY_MY_SEQ, 0),
                oppSeq = sp.getInt(KEY_OPP_SEQ, 0),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "loadProgress failed", t)
            Progress()
        }
    }

    /** 离开房间 / 房间终态关闭：清除全部持久化数据。 */
    @JvmStatic
    fun clear() {
        try {
            prefs()?.edit()?.clear()?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "clear failed", t)
        }
    }
}
