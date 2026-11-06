package com.mandgamit.chess

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mandgamit.chess.assist.ui.AssistActivity
import com.mandgamit.chess.battle.BattleKeepAliveService
import com.mandgamit.chess.battle.BattleSessionManager
import com.mandgamit.chess.battle.BattleStateStore
import com.mandgamit.chess.battle.ui.BattleRoomDialog
import com.mandgamit.chess.recognize.RecognitionActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "MandGamit"
        private const val KEY_DISCLAIMER_AGREED = "disclaimer_agreed_v1"

        /** 免责声明（首启弹窗 / About / 连线页共用同一口径） */
        const val DISCLAIMER =
            "免责声明：本应用（橘中弈象棋）仅供个人学习、研究与文化交流使用，" +
            "严禁用于任何违反法律法规、游戏平台规则的情形；" +
            "本应用不含自动对弈、点击注入、无障碍服务、Root/Xposed 等功能，" +
            "对局中的所有操作均由玩家本人完成；" +
            "因使用本应用产生的一切后果由使用者自行承担。"

        /** 鸣谢（品牌说明中唯一保留"象棋鱼"字眼处） */
        const val CREDITS =
            "鸣谢：\n" +
            "· 本项目基于开源项目象棋迪克(https://github.com/Silva6c/chess-dike)而开发\n" +
            "· 本项目源码:https://github.com/RockyWang1122/MandGamitCChess\n" +
            "· 象棋迪克申明其基于开源项目\"象棋鱼\"（作者 zfdang，GitHub: zfdang/chinese-chess-android）二次开发；\n" +
            "· YOLO 棋子检测权重文件来自 VinXiangQi 项目；\n" +
            "· 引擎与对弈框架基座：Pikafish 团队与 DroidFish（Peter Österlund），GPL-3.0 开源授权。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 进程被系统回收后冷启动：若好友对战已开局，ChessApp 已按落盘房间自动重连，
        // 这里直接把用户送回对局页（MainActivity 保留在返回栈底，退出对局仍回到主页）
        maybeRedirectToActiveBattle()

        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        maybeShowDisclaimer()

        // Bind buttons
        val buttonPlay: View = findViewById(R.id.button_play)
        val buttonLink: View = findViewById(R.id.button_link)
        val buttonEdit: View = findViewById(R.id.button_edit)
        val buttonRecognize: View = findViewById(R.id.button_recognize)
        val buttonManuals: View = findViewById(R.id.button_manuals)
        val buttonTrain: View = findViewById(R.id.button_train)
        val buttonEndgames: View = findViewById(R.id.button_endgames)
        val buttonBlind: View = findViewById(R.id.button_blind)
        val buttonInvite: View = findViewById(R.id.button_invite)

        // Set click listeners
        buttonPlay.setOnClickListener {
            // 人机大战
            val intent = Intent(this, GameActivity::class.java)
            startActivity(intent)
        }

        buttonLink.setOnClickListener {
            // 棋软连线：屏幕识别 + 悬浮窗走法指导
            val intent = Intent(this, AssistActivity::class.java)
            startActivity(intent)
        }

        buttonEdit.setOnClickListener {
            // 棋盘编辑：自定义摆棋后进入对弈
            val intent = Intent(this, BoardEditorActivity::class.java)
            startActivity(intent)
        }

        buttonRecognize.setOnClickListener {
            // 图片识别：拍照/选图识别棋盘 FEN
            val intent = Intent(this, RecognitionActivity::class.java)
            startActivity(intent)
        }

        buttonManuals.setOnClickListener {
            // 棋谱：入口页（对局棋谱 / 内置棋谱）
            startActivity(Intent(this, ManualsHubActivity::class.java))
        }

        buttonTrain.setOnClickListener {
            // 进阶（train）列表
            openStudyList(StudyListActivity.CATEGORY_TRAIN, "进阶")
        }

        buttonEndgames.setOnClickListener {
            // 残局（endgames）列表
            openStudyList(StudyListActivity.CATEGORY_ENDGAMES, "残局")
        }

        buttonBlind.setOnClickListener {
            // 盲棋训练：人机对弈，棋盘不显示棋子，引擎走子语音播报
            val intent = Intent(this, GameActivity::class.java)
            intent.putExtra(GameActivity.EXTRA_BLIND_MODE, true)
            startActivity(intent)
        }

        buttonInvite.setOnClickListener {
            // 好友对战：进入大厅（棋桌模式）
            startActivity(Intent(this, com.mandgamit.chess.battle.ui.LobbyActivity::class.java))
        }

        // 左上角关于按钮
        findViewById<View>(R.id.button_about).setOnClickListener { showAboutDialog() }

        // 对局进行中进程被杀：会话已在后台自动重连，直达对局页；
        // 仅持大厅座位/观战（未开局）时直达大厅（保活通知点击在栈被清后也走这里）
        maybeRedirectToActiveBattle()
        maybeRedirectToLobby()
    }

    /**
     * 手势返回/返回键 = 退出 APP：结束任务栈、停掉保活前台服务（通知图标随之消失）、
     * 销毁对战会话（断开连接）。座位/棋局由服务端保留时长兜底，重进大厅可恢复。
     */
    override fun onBackPressed() {
        ChessApp.getBattleSession().destroy()
        stopService(Intent(this, BattleKeepAliveService::class.java))
        finishAffinity()
    }

    /**
     * VIVO 等 ROM 的省电策略：切后台后保留进程（前台服务在跑、连接未断），
     * 却把栈顶的 GameActivity 销毁并移出任务栈。用户切回时 MainActivity 仅走
     * onRestart 恢复（不重建、不走 onCreate），必须在恢复时补一次对局重定向，
     * 否则用户会停在主界面而棋局其实还活着。
     */
    override fun onRestart() {
        super.onRestart()
        // 建房后常跳微信分享房号、在等待好友加入期间切回：立即探测连接，
        // 断线退避中则取消等待立即重连（会话内部按状态自行决定是否动作）
        ChessApp.getBattleSession().onReturnToForeground()
        maybeRedirectToActiveBattle()
        maybeRedirectToLobby()
    }

    /** 若落盘数据表明好友对战仍在进行，直接跳转对局页；否则视为正常主界面展示。 */
    private fun maybeRedirectToActiveBattle() {
        if (!BattleStateStore.hasActiveGame()) return
        Log.i(TAG, "active battle found, redirect to GameActivity")
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_BATTLE_MODE, true)
        })
    }

    /**
     * 大厅补跳：会话仍持有棋桌座位或观战时（ROM 回收了栈顶大厅页 / 用户从对局页退回），
     * 回到主界面后自动送回大厅，避免"已坐下却看不到大厅"。
     * 仅浏览态（IN_LOBBY）不跳：用户可能主动退出大厅，不应被拉回。
     */
    private fun maybeRedirectToLobby() {
        val session = ChessApp.getBattleSession()
        // 用户主动退出过大厅（返回键/退出流程）：尊重选择，不自动拉回，
        // 直到其重新进入大厅（标记清除）或对局开始（走对局页补跳）
        if (session.lobbyManuallyExited) return
        if (BattleStateStore.hasActiveGame()) return // 对局中优先跳对局页
        if (session.seatedTable != 0 || session.watchingTable != 0) {
            Log.i(TAG, "lobby seat/watch active (table=${session.seatedTable}/${session.watchingTable}), redirect to LobbyActivity")
            startActivity(
                Intent(this, com.mandgamit.chess.battle.ui.LobbyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    /** 直接进入学习资料某一类别的列表页 */
    private fun openStudyList(category: String, title: String) {
        val intent = Intent(this, StudyListActivity::class.java)
        intent.putExtra(StudyListActivity.EXTRA_CATEGORY, category)
        intent.putExtra(StudyListActivity.EXTRA_TITLE, title)
        startActivity(intent)
    }

    /** 首次启动弹免责声明：同意后记忆，不同意即退出 */
    private fun maybeShowDisclaimer() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_AGREED, false)) return
        AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(DISCLAIMER)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { d, _ ->
                prefs.edit().putBoolean(KEY_DISCLAIMER_AGREED, true).apply()
                d.dismiss()
            }
            .setNegativeButton("不同意并退出") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    /** 关于：本地对话框（特色功能 + 免责声明 + 鸣谢），不依赖外部站点 */
    private fun showAboutDialog() {
        val text = "橘中弈象棋 MandGamit v${BuildConfig.VERSION_NAME}\n\n" +
            "特色功能：\n" +
            "· 完整对弈(Pikafish 引擎)\n" +
            "· AI 连线识别" +
            DISCLAIMER + "\n\n" +
            CREDITS
        AlertDialog.Builder(this)
            .setTitle("关于 · 橘中弈象棋")
            .setMessage(text)
            .setPositiveButton("我知道了", null)
            .show()
    }
}
