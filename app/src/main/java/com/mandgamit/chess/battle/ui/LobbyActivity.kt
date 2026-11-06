package com.mandgamit.chess.battle.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mandgamit.chess.ChessApp
import com.mandgamit.chess.GameActivity
import com.mandgamit.chess.R
import com.mandgamit.chess.battle.BattleKeepAliveService
import com.mandgamit.chess.battle.BattleSessionManager
import com.mandgamit.chess.battle.net.BattleMessage
import com.mandgamit.chess.battle.net.TableInfo

/**
 * 好友对战大厅：共用棋桌列表，入座自动开局，支持观战。
 *
 * - 首次进入提示输入昵称并本地保存，以后不再提示；
 * - 右上角显示昵称（点击修改）；
 * - 空桌全局唯一（服务端保证），点新建若已有空桌仅提示不创建；
 * - 他人入座/离座/开局/销桌经服务端 table_update 广播实时同步。
 */
class LobbyActivity : AppCompatActivity() {

    private lateinit var session: BattleSessionManager
    private lateinit var adapter: TableAdapter

    private val allTables = ArrayList<TableInfo>()

    private val listener = object : BattleSessionManager.BattleListener {
        override fun onRoomCreated(room: String) {}
        override fun onRoomJoined(color: String) {}
        override fun onGameStart() {
            // 坐满自动开局：进入对局页
            if (session.seatedTable != 0) {
                startActivity(Intent(this@LobbyActivity, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_BATTLE_MODE, true)
                })
            }
        }
        override fun onRemoteMove(ucci: String, seq: Int) {}
        override fun onOpponentOffline(ttlSeconds: Int) {}
        override fun onOpponentOnline() {}
        override fun onSyncState(fen: String, moves: List<String>, turn: String) {}
        override fun onSyncRequested() {}
        override fun onOpponentResign() {}
        override fun onOpponentLeave(reason: String?) {}

        override fun onError(msg: String) {
            Toast.makeText(this@LobbyActivity, msg, Toast.LENGTH_SHORT).show()
        }

        override fun onStateChanged(state: BattleSessionManager.State) {
            adapter.mySeatedTable = session.seatedTable
        }

        override fun onLobbyState(tables: List<TableInfo>) {
            allTables.clear()
            allTables.addAll(tables)
            adapter.submitAll(allTables)
        }

        override fun onTableUpdate(info: TableInfo) {
            val idx = allTables.indexOfFirst { it.no == info.no }
            if (info.status == BattleMessage.TABLE_CLOSED) {
                if (idx >= 0) allTables.removeAt(idx)
            } else if (idx >= 0) {
                allTables[idx] = info
            } else {
                allTables.add(info)
            }
            adapter.submitAll(allTables)
        }

        override fun onSitOk(table: Int, color: String) {
            adapter.mySeatedTable = table
        }

        override fun onWatchOk(
            table: Int, red: String, black: String, moves: List<String>, turn: String,
        ) {
            startActivity(Intent(this@LobbyActivity, GameActivity::class.java).apply {
                putExtra(GameActivity.EXTRA_BATTLE_MODE, true)
                putExtra(GameActivity.EXTRA_WATCH_MODE, true)
            })
        }

        override fun onWatchEnd(table: Int, msg: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)

        session = ChessApp.getBattleSession()

        adapter = TableAdapter(
            onSit = { info -> session.sit(info.no) },
            onWatch = { info -> session.watch(info.no) },
            onStand = { session.stand() },
        )
        val recycler = findViewById<RecyclerView>(R.id.recycler_tables)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<View>(R.id.button_back).setOnClickListener { exitLobby() }
        // 点用户图标或昵称都弹出修改昵称对话框
        findViewById<View>(R.id.nick_container).setOnClickListener { showNickDialog(editOnly = true) }

        val nick = loadNick()
        if (nick.isEmpty()) {
            // 首次进入：先填昵称，确认后才进大厅
            showNickDialog(editOnly = false)
        }
        refreshNickView()
    }

    /** 用户主动关闭大厅（返回键/退出流程）都会走到这里：打上标记，MainActivity 不再自动补跳。 */
    override fun finish() {
        session.lobbyManuallyExited = true
        super.finish()
    }

    /** 手势返回/返回键等同主动退出：离座/停观战 → 退订大厅 → 关闭界面。 */
    override fun onBackPressed() {
        exitLobby()
    }

    /**
     * 退出大厅：无论是否就座，先 stand（服务端收桌并广播）与 unwatch，
     * 再 leaveLobby（退订广播 + 断开连接 + 停心跳），最后关闭界面。
     * 服务端桌面连接随 leave_lobby 关闭，下次进入大厅重新建连。
     */
    private fun exitLobby() {
        if (session.seatedTable != 0) session.stand()
        if (session.watchingTable != 0) session.unwatch()
        session.leaveLobby()
        finish()
    }

    override fun onResume() {
        super.onResume()
        // 重新进入大厅：清除"主动退出"标记，恢复 ROM 销毁大厅页时的自动补跳能力
        session.lobbyManuallyExited = false
        session.listener = listener
        adapter.mySeatedTable = session.seatedTable
        adapter.myNick = loadNick()
        // 回到大厅（从对局页返回/观战结束）：重发 enter_lobby 拉取最新全量
        val nick = loadNick()
        if (nick.isNotEmpty()) {
            session.enterLobby(nick)
        }
        session.onReturnToForeground()
        // 前台保活：大厅阶段切后台也要保住进程，否则国产 ROM 回收后回来会掉回主界面
        BattleKeepAliveService.start(this)
    }

    override fun onPause() {
        super.onPause()
        // 切出大厅且已不在任何座位/观战时停止保活，避免空跑通知
        if (isFinishing && session.seatedTable == 0 && session.watchingTable == 0) {
            BattleKeepAliveService.stop(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (session.listener === listener) {
            session.listener = null
        }
        if (isFinishing && session.seatedTable == 0 && session.watchingTable == 0) {
            BattleKeepAliveService.stop(this)
        }
    }

    // ---------------- 昵称 ----------------

    private fun loadNick(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NICK, "") ?: ""

    private fun saveNick(nick: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_NICK, nick).apply()
    }

    private fun refreshNickView() {
        findViewById<TextView>(R.id.text_nick).text = loadNick()
    }

    companion object {
        private const val PREFS = "battle_prefs"
        private const val KEY_NICK = "nick"

        /** 昵称显示宽度上限：中文 2/字、英文 1/字 → 中文最多 4 字，英文最多 8 字符。 */
        private const val NICK_MAX_WIDTH = 8

        /**
         * 按显示宽度截断的输入过滤器：输入法与粘贴均实时生效，
         * 超出宽度的新字符被静默丢弃（保留已有内容不回退）。
         */
        private fun nickWidthFilter() = object : android.text.InputFilter {
            override fun filter(
                source: CharSequence, start: Int, end: Int,
                dest: android.text.Spanned?, dstart: Int, dend: Int,
            ): CharSequence {
                var width = 0
                if (dest != null) {
                    for (i in 0 until dest.length) width += charWidth(dest[i])
                }
                val sb = StringBuilder()
                for (i in start until end) {
                    val c = source[i]
                    val w = charWidth(c)
                    if (width + w > NICK_MAX_WIDTH) break
                    sb.append(c)
                    width += w
                }
                return sb
            }
        }

        /** CJK 及全角字符按 2 计，其余按 1 计。 */
        private fun charWidth(c: Char): Int = if (c.code >= 0x2E80) 2 else 1

        /** 按显示宽度截断字符串（宽度上限 NICK_MAX_WIDTH）。 */
        private fun truncateByWidth(s: String): String {
            var width = 0
            for (i in s.indices) {
                width += charWidth(s[i])
                if (width > NICK_MAX_WIDTH) return s.substring(0, i)
            }
            return s
        }
    }

    /**
     * 昵称对话框。editOnly=false 为首次进入（不可取消，必须填写）；
     * editOnly=true 为修改昵称（可取消）。
     */
    private fun showNickDialog(editOnly: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_nickname, null)
        val edit = view.findViewById<EditText>(R.id.edit_nick)
        edit.setText(loadNick())
        // 显示宽度限制：中文（全角）计 2、英文/数字计 1，总宽 ≤8 → 中文4字/英文8字符
        edit.filters = arrayOf(nickWidthFilter())
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (editOnly) "修改昵称" else "设置昵称")
            .setView(view)
            .setPositiveButton("确定", null)
            .apply {
                if (editOnly) setNegativeButton("取消", null)
            }
            .setCancelable(editOnly)
            .create()
        dialog.setCanceledOnTouchOutside(editOnly)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = edit.text.toString()
                // 兜底：过滤只管新输入，历史存量超宽昵称在此按同口径截断
                val nick = truncateByWidth(raw.trim())
                when {
                    // 空 / 纯空格
                    nick.isEmpty() -> edit.error = "昵称不能为空"
                    // 任何位置含空白字符（半角/全角空格、Tab 等）均不允许
                    raw.any { it.isWhitespace() } -> edit.error = "昵称不能包含空格"
                    else -> {
                        saveNick(nick)
                        refreshNickView()
                        adapter.myNick = nick
                        if (editOnly) {
                            session.updateNick(nick)
                        } else {
                            session.enterLobby(nick)
                        }
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }
}
