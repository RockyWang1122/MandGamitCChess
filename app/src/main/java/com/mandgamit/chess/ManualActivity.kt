package com.mandgamit.chess

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mandgamit.chess.adapters.HistoryAndTrendAdapter
import com.mandgamit.chess.utils.PathUtil
import com.mandgamit.chess.controllers.ControllerListener
import com.mandgamit.chess.controllers.ManualController
import com.mandgamit.chess.databinding.ActivityManualBinding
import com.mandgamit.chess.gamelogic.GameStatus
import com.mandgamit.chess.views.ChessView
import java.io.File


class ManualActivity() : AppCompatActivity(), ControllerListener,
    View.OnClickListener {

    private var last_selected_path = ""

    // 当前已加载棋谱的位置与来源（用于"上一局/下一局"切换）：
    // manualFromAssets=true 时 currentManualPath 为 assets 相对路径，否则为文件系统绝对路径
    private var currentManualPath: String? = null
    private var manualFromAssets: Boolean = false

    // 防止重复点击
    private val MIN_CLICK_DELAY_TIME: Int = 100
    private var curClickTime: Long = 0
    private var lastClickTime: Long = 0

    private lateinit var binding: ActivityManualBinding
    private lateinit var chessLayout: FrameLayout

    // 棋盘
    private lateinit var chessView: ChessView
    private lateinit var historyAndTrendAdapter: HistoryAndTrendAdapter

    // controller, player, game
    private lateinit var controller: ManualController

    // mediaplayer
    private lateinit var soundPlayer: SoundPlayer

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable screen saver
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityManualBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // new game
        controller = ManualController(this)

        // 初始化棋盘
        chessLayout = binding.chesslayout
        chessView = ChessView(this, controller)
        chessLayout.addView(chessView)

        // Bind all imagebuttons here, and set their onClickListener
        val imageButtons = listOf(
            binding.firstbt,
            binding.backbt,
            binding.forwardbt,
            binding.gamebt,
            binding.prevGamebt,
            binding.nextGamebt,
            binding.backButton,
            binding.choice1bt,
            binding.choice2bt,
            binding.choice3bt,
            binding.choice4bt,
            binding.choice5bt,
        )
        for (button in imageButtons) {
            button.setOnClickListener(this)
        }

        // init audio files
        soundPlayer = SoundPlayer(this, controller)

        // init status text
        setStatusText("未加载棋谱")

        val assetPath = intent.getStringExtra("ASSET_PATH")
        if (assetPath != null) {
            // 学习模式：直接从 assets 加载内建棋谱
            loadManualFromAssets(assetPath)
        }

        val filePath = intent.getStringExtra("FILE_PATH")
        if (filePath != null) {
            // 对局棋谱：从本地文件加载（APP 私有目录 /橘中弈象棋/*.pgn）
            loadManualFromFile(filePath)
        }

        last_selected_path = PathUtil.getInternalAppFilesDir(this, "")
    }

    /** 学习模式：从 assets 加载内建 XQF/PGN 棋谱 */
    private fun loadManualFromAssets(assetPath: String) {
        try {
            val bytes = assets.open(assetPath).readBytes()
            val displayName = assetPath.substringAfterLast('/').substringBeforeLast('.')
            if (controller.loadManualFromBytes(bytes, displayName)) {
                manualFromAssets = true
                currentManualPath = assetPath
                onManualLoaded()
            } else {
                Toast.makeText(this, "棋谱解析失败：$displayName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ManualActivity", "加载 assets 棋谱失败: $assetPath", e)
            Toast.makeText(this, "棋谱加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    // create function to set status text
    fun setStatusText(text: String) {
        binding.statustv.text = text
    }

    fun saveThenExit() {
        finish()
    }


    override fun onClick(v: View?) {
        // handle events for all imagebuttons in activity_player.xml
        when(v) {
            binding.forwardbt -> {
                controller.manualForward()
            }
            binding.backbt -> {
                controller.manualBack()
            }
            binding.firstbt -> {
                controller.manualFirst()
            }
            binding.gamebt -> {
                // start game activity here
                val intent = Intent(this, GameActivity::class.java)
                intent.putExtra("FENString", controller.game.currentBoard.toFENString())
                startActivity(intent)
            }
            binding.prevGamebt -> {
                // 加载同目录上一局 XQF/PGN
                switchManual(-1)
            }
            binding.nextGamebt -> {
                // 加载同目录下一局 XQF/PGN
                switchManual(1)
            }
            binding.backButton -> {
                saveThenExit();
            }
            binding.choice1bt -> {
                setStatusText("选择分支1")
                hideAllChoiceBts()
                controller.selectBranch(0)
            }
            binding.choice2bt -> {
                setStatusText("选择分支2")
                hideAllChoiceBts()
                controller.selectBranch(1)
            }
            binding.choice3bt -> {
                setStatusText("选择分支3")
                hideAllChoiceBts()
                controller.selectBranch(2)
            }
            binding.choice4bt -> {
                setStatusText("选择分支4")
                hideAllChoiceBts()
                controller.selectBranch(3)
            }
            binding.choice5bt -> {
                setStatusText("选择分支5")
                hideAllChoiceBts()
                controller.selectBranch(4)
            }
        }
    }

    private fun hideAllChoiceBts() {
        binding.choice1bt.visibility = View.GONE;
        binding.choice2bt.visibility = View.GONE;
        binding.choice3bt.visibility = View.GONE;
        binding.choice4bt.visibility = View.GONE;
        binding.choice5bt.visibility = View.GONE;
    }


    fun loadManualFromFile(file: String) {
        val result = controller.loadManualFromFile(file)
        if (result) {
            manualFromAssets = false
            currentManualPath = file
            onManualLoaded()
        } else {
            binding.statustv.text = "棋谱加载失败"
            Toast.makeText(this, "棋谱加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 切换到同目录下上一局/下一局 XQF/PGN 棋谱。
     * @param delta -1=上一局，+1=下一局
     */
    private fun switchManual(delta: Int) {
        val current = currentManualPath
        if (current == null) {
            Toast.makeText(this, "请先打开一局棋谱", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = current.substringBeforeLast('/', "")
        val currentName = current.substringAfterLast('/')

        val names: List<String> = if (manualFromAssets) {
            try { assets.list(dir)?.toList() ?: emptyList() }
            catch (e: Exception) { emptyList() }
        } else {
            File(dir).list()?.toList() ?: emptyList()
        }
        val manuals = names.filter { isManualFileName(it) }.sortedWith(naturalFileNameComparator)

        val index = manuals.indexOf(currentName)
        if (index < 0) {
            Toast.makeText(this, "未找到当前棋谱所在目录中的位置", Toast.LENGTH_SHORT).show()
            return
        }
        val target = index + delta
        if (target < 0) {
            Toast.makeText(this, "已经是第一局", Toast.LENGTH_SHORT).show()
            return
        }
        if (target >= manuals.size) {
            Toast.makeText(this, "已经是最后一局", Toast.LENGTH_SHORT).show()
            return
        }

        val targetName = manuals[target]
        if (manualFromAssets) {
            loadManualFromAssets(if (dir.isEmpty()) targetName else "$dir/$targetName")
        } else {
            val targetPath = if (dir.isEmpty()) targetName else "$dir/$targetName"
            last_selected_path = "$dir/"
            loadManualFromFile(targetPath)
        }
    }

    /** 是否为可浏览的棋谱文件（XQF/PGN，不区分大小写） */
    private fun isManualFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "xqf" || ext == "pgn"
    }

    /**
     * 文件名自然排序：连续数字段按数值比较（如"第2局"排在"第10局"前），
     * 其余字符按 Unicode 逐字符比较。
     */
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

    /** 棋谱加载成功后的 UI 更新（文件加载 / assets 加载 共用） */
    private fun onManualLoaded() {
        if(controller.manual.title == null || controller.manual.title.isEmpty()) {
            binding.textViewTitle.text = controller.manual.filename
        } else {
            binding.textViewTitle.text = controller.manual.title
        }

        // if controller.manual.red is empty, then hide textViewRed
        if(controller.manual.red.isEmpty()) {
            binding.textViewRed.visibility = View.INVISIBLE
        } else {
            binding.textViewRed.visibility = View.VISIBLE
            binding.textViewRed.text = controller.manual.red
        }
        if(controller.manual.black.isEmpty()) {
            binding.textViewBlack.visibility = View.INVISIBLE
        } else {
            binding.textViewBlack.visibility = View.VISIBLE
            binding.textViewBlack.text = controller.manual.black
        }
        binding.textViewResult.text = controller.manual.result
        binding.textViewNote.text = controller.manual.annotation

        hideAllChoiceBts()
        val hint = "棋谱加载成功" + "," + controller.getFirstMoveColor()
        binding.statustv.text = hint
    }


    override fun onGameEvent(status: GameStatus?, message: String?) {
        Log.d(  "ManualActivity", "onGameEvent: $status, $message")
        when(status) {
            GameStatus.ILLEGAL -> {
                message?.let { setStatusText(it) }
                soundPlayer.illegal();
            }
            GameStatus.MOVE -> {
                message?.let { setStatusText(it) }
                soundPlayer.move();

                if(binding.choice1bt.visibility == View.VISIBLE){
                    hideAllChoiceBts()
                }
            }
            GameStatus.CAPTURE -> {
                message?.let { setStatusText(it) }
                soundPlayer.capture()
            }
            GameStatus.CHECK -> {
                message?.let { setStatusText(it) }
                soundPlayer.check()
            }
            GameStatus.CHECKMATE -> {
                message?.let { setStatusText(it) }
                soundPlayer.checkmate()
            }
            GameStatus.SELECT -> {
                message?.let { setStatusText(it) }
                soundPlayer.select()
            }
            GameStatus.WIN -> message?.let { setStatusText(it) }
            GameStatus.LOSE -> message?.let { setStatusText(it) }
            GameStatus.DRAW -> message?.let { setStatusText(it) }
            GameStatus.ENGINE -> message?.let { setStatusText(it) }
            null -> TODO()
            GameStatus.MULTIPV -> {
                // show message
                message?.let { setStatusText(it) }

                // show choice buttons
                if(binding.choice1bt.visibility == View.GONE){
                    binding.choice1bt.visibility = View.VISIBLE;
                    if(controller.getMultiPVSize() >= 2){
                        binding.choice2bt.visibility = View.VISIBLE;
                    }
                    if(controller.getMultiPVSize() >= 3){
                        binding.choice3bt.visibility = View.VISIBLE;
                    }
                    if(controller.getMultiPVSize() >= 4){
                        binding.choice4bt.visibility = View.VISIBLE;
                    }
                    if(controller.getMultiPVSize() >= 5){
                        binding.choice5bt.visibility = View.VISIBLE;
                    }
                }

                soundPlayer.ready()
            }
            GameStatus.UPDATEUI -> {
                message?.let { setStatusText(message) }
                // do nothing here
            }
        }

        if(controller.manual != null && controller.moveNode != null) {
            // update textViewNote
            if(controller.moveNode.move == null) {
                // headMove, show manual annotation
                binding.textViewNote.text = controller.manual.annotation
            } else {
                // show move comment
                binding.textViewNote.text = controller.moveNode.move.comment
            }
        }
    }

    // create fun to handle onbackpressed
    override fun onBackPressed() {
        // save game to file
        super.onBackPressed()
        saveThenExit()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放引擎子进程与音效资源，避免反复打开棋谱后引擎进程/MediaPlayer 泄漏导致卡顿
        if (::controller.isInitialized) {
            controller.shutdown()
        }
        if (::soundPlayer.isInitialized) {
            soundPlayer.release()
        }
    }

    override fun onGameEvent(event: GameStatus?) {
        onGameEvent(event, null);
    }

    override fun runOnUIThread(runnable: Runnable?) {
            runOnUiThread(runnable);
        }
}