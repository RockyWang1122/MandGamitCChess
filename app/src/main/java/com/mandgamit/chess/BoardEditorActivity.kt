package com.mandgamit.chess

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mandgamit.chess.gamelogic.Board
import com.mandgamit.chess.gamelogic.Piece
import com.mandgamit.chess.gamelogic.Position
import com.mandgamit.chess.views.BoardEditorView
import com.mandgamit.chess.views.BoardGeometry

/**
 * 新棋盘编辑界面（P1标题/P2棋盘/P3待排棋子/P4功能按钮）。
 */
class BoardEditorActivity : AppCompatActivity() {

    private lateinit var boardView: BoardEditorView
    private lateinit var board: Board
    private lateinit var soundPlayer: SoundPlayer
    private lateinit var gestureDetector: GestureDetector

    /** 当前选中的棋盘位置（无选中为 null） */
    private var selectedPos: Position? = null

    /** 当前选中的待排布棋子类型（无选中为 0 = Piece.EMPTY） */
    private var selectedPalettePiece: Int = Piece.EMPTY

    /** 当前是"空"态还是"满"态：true=已清空（只有将帅），false=初始满子 */
    private var isCleared: Boolean = false

    /** 各兵种的最大数量 */
    private val maxCount: Map<Int, Int> = mapOf(
        Piece.WSHUAI to 1, Piece.WSHI to 2, Piece.WXIANG to 2,
        Piece.WMA to 2, Piece.WJU to 2, Piece.WPAO to 2, Piece.WBING to 5,
        Piece.BJIANG to 1, Piece.BSHI to 2, Piece.BXIANG to 2,
        Piece.BMA to 2, Piece.BJU to 2, Piece.BPAO to 2, Piece.BZU to 5
    )

    companion object {
        private const val TAG = "BoardEditor"
        private const val PREFS_NAME = "board_editor_prefs"
        private const val KEY_LAST_FEN = "last_fen"
        const val EXTRA_LOAD_FEN = "load_fen"

        /** 棋盘左右缘占屏宽比例（与 activity_game.xml 的 Guideline 一致：54/1080、1027/1080） */
        const val BOARD_LEFT_PERCENT = 0.05f
        const val BOARD_RIGHT_PERCENT = 0.9509f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_board_editor)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        soundPlayer = SoundPlayer(this, null)

        // 初始化棋盘：仅有 将(4,0) 与 帅(4,9)
        board = Board()
        board.clear()
        board.setPieceByPosition(4, 0, Piece.BJIANG)
        board.setPieceByPosition(4, 9, Piece.WSHUAI)
        isCleared = true

        // 加载 FEN：优先 intent extra（识别跳转），其次上次保存
        val intentFen = intent.getStringExtra(EXTRA_LOAD_FEN)
        val savedFen = intentFen
            ?: getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LAST_FEN, null)
        if (!savedFen.isNullOrEmpty()) {
            try {
                if (board.restoreFromFEN(savedFen)) {
                    Log.d(TAG, "已加载 FEN: $savedFen")
                    isCleared = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载 FEN 失败", e)
            }
        }

        // 创建棋盘视图
        boardView = BoardEditorView(this)
        boardView.setBoard(board)
        // WRAP_CONTENT + gravity：短屏棋盘等比缩小后在容器内居中
        findViewById<FrameLayout>(R.id.editorBoardLayout).addView(boardView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        // 手势检测：单击选中/放置，双击移除
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val pos = boardView.getPosByCoord(e.x, e.y) ?: return false
                handleBoardSingleClick(pos)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val pos = boardView.getPosByCoord(e.x, e.y) ?: return false
                handleBoardDoubleClick(pos)
                return true
            }
        })
        boardView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // P1 返回
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        // P4 按钮
        findViewById<LinearLayout>(R.id.clearFillButton).setOnClickListener { toggleClearFill() }
        findViewById<LinearLayout>(R.id.recognizeButton).setOnClickListener { gotoRecognize() }
        findViewById<LinearLayout>(R.id.playButton).setOnClickListener { gotoPlay() }
        findViewById<LinearLayout>(R.id.flipButton).setOnClickListener { boardView.flipped = !boardView.flipped }
        findViewById<LinearLayout>(R.id.moreButton).setOnClickListener { showMoreOptionsSheet() }

        // 先后手切换
        findViewById<LinearLayout>(R.id.btnBlackFirst).setOnClickListener { setRedFirst(false) }
        findViewById<LinearLayout>(R.id.btnRedFirst).setOnClickListener { setRedFirst(true) }

        // P3 卡片位置/高度由 XML Guideline 约束（设计稿 54,1467、高302）；布局完成后按实际空间重算棋子尺寸
        findViewById<FrameLayout>(R.id.editorBoardLayout).post {
            refreshPalette()
        }

        // 初始刷新
        updateSideButtons()
        refreshPalette()
    }

    override fun onPause() {
        super.onPause()
        // 退出编辑界面时保存当前 FEN
        try {
            val fen = board.toFENString()
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_FEN, fen)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存 FEN 失败", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPlayer.release()
    }

    // ===================== 棋盘点击处理 =====================

    /** 单击棋盘：选中棋子 或 放置已选中的待排棋子 */
    private fun handleBoardSingleClick(pos: Position) {
        val piece = board.getPieceByPosition(pos)

        // 1) 有选中的待排棋子：点击空位=放置；点击已有棋子=取消待排选中并改选该棋子
        if (selectedPalettePiece != Piece.EMPTY) {
            if (Piece.isValid(piece)) {
                // 取消待排棋子的选中状态，改为选中P2上点击的棋子
                selectedPalettePiece = Piece.EMPTY
                selectedPos = pos
                boardView.setSelectedPos(pos)
                refreshPalette()
                soundPlayer.select()
                return
            }
            val (valid, reason) = isValidPlacement(selectedPalettePiece, pos)
            if (!valid) {
                soundPlayer.illegal()
                Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
                return
            }
            board.setPieceByPosition(pos, selectedPalettePiece)
            selectedPalettePiece = Piece.EMPTY
            refreshPalette()
            soundPlayer.move()
            return
        }

        // 2) 无待排选中，点击棋盘上有棋子的位置
        if (Piece.isValid(piece)) {
            if (selectedPos == pos) {
                // 点击已选中的棋子本身：保持选中状态，不做任何改变
                return
            }
            selectedPos = pos
            boardView.setSelectedPos(pos)
            soundPlayer.select()
        } else {
            // 3) 点击空位：若棋盘上有选中的棋子，则尝试移动到此处；否则忽略
            val fromPos = selectedPos
            if (fromPos != null) {
                val movingPiece = board.getPieceByPosition(fromPos)
                val (valid, reason) = isValidPlacement(movingPiece, pos)
                if (!valid) {
                    soundPlayer.illegal()
                    Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
                    return
                }
                board.setPieceByPosition(fromPos, Piece.EMPTY)
                board.setPieceByPosition(pos, movingPiece)
                // 选中焦点跟随棋子到新位置，方便继续调整
                selectedPos = pos
                boardView.setSelectedPos(pos)
                soundPlayer.move()
            }
        }
    }

    /** 双击棋盘：移除棋子（任何棋子均可移除，含识别误判产生的多余将帅） */
    private fun handleBoardDoubleClick(pos: Position) {
        val piece = board.getPieceByPosition(pos)
        if (!Piece.isValid(piece)) return
        // 移除后待排布区按剩余数量自动补充：普通棋子立即回到待排布区；
        // 将帅仅在盘上缺失时出现在待排布区，开局/生成FEN前仍校验双方各一
        board.setPieceByPosition(pos, Piece.EMPTY)
        if (selectedPos == pos) selectedPos = null
        boardView.setSelectedPos(null)
        refreshPalette()
        soundPlayer.capture()
    }

    // ===================== P3 待排布棋子 =====================

    /** 刷新 P3 待排布棋子列表 */
    private fun refreshPalette() {
        val counts = countPieces()

        // 每行6种常规棋子 + 将帅（仅当盘上缺失时出现，摆回后自动隐藏）。从左到右：
        // 黑方：將車馬象士炮卒
        val blackOrder = intArrayOf(
            Piece.BJIANG, Piece.BJU, Piece.BMA, Piece.BXIANG, Piece.BSHI, Piece.BPAO, Piece.BZU)
        // 红方：帥車馬相仕炮兵
        val redOrder   = intArrayOf(
            Piece.WSHUAI, Piece.WJU, Piece.WMA, Piece.WXIANG, Piece.WSHI, Piece.WPAO, Piece.WBING)

        val rowBlack = findViewById<FrameLayout>(R.id.paletteRowBlack)
        val rowRed = findViewById<FrameLayout>(R.id.paletteRowRed)

        rowBlack.removeAllViews()
        rowRed.removeAllViews()

        val screenW = resources.displayMetrics.widthPixels

        // ---- 与 P2 棋盘共用一套水平坐标（棋盘左右边距同 activity_game：5% ~ 95.09%）----
        val boardLeft = screenW * BOARD_LEFT_PERCENT
        val boardWidthPx = screenW * (BOARD_RIGHT_PERCENT - BOARD_LEFT_PERCENT)
        val boardScale = boardWidthPx / BoardGeometry.BOARD_WIDTH.toFloat()
        val interval = BoardGeometry.GRID_INTERVAL * boardScale            // 棋盘格距（像素）
        val boardCol0Center = boardLeft + BoardGeometry.GRID_COL0_CENTER * boardScale  // 棋盘第0列棋子中心（相对屏幕左边缘）

        // 棋子行左边缘（相对屏幕）= 卡片左缘(5%) + 卡片左内边距4dp
        val rowLeft = boardLeft + dp(4)

        // 第 i 个棋子单元相对行左边缘的偏移：整体右移一列，使其中心 = 棋盘第(i+1)列中心
        fun cellLeft(i: Int) =
            (boardCol0Center - rowLeft + interval / 2f + i * interval).toInt()

        // 棋子尺寸与 P2 棋盘棋子完全一致：原图棋子边长144 × 棋盘缩放比
        val size = (144f * boardScale).toInt()

        // 第0个单元会略微进入卡片左内边距区，放开裁剪链（棋子整体仍在卡片内）
        val card = findViewById<LinearLayout>(R.id.paletteCard)
        card.clipToPadding = false
        (rowBlack.parent as? android.view.ViewGroup)?.clipChildren = false
        rowBlack.clipChildren = false
        rowRed.clipChildren = false

        // 左对齐：可见棋子从第0列起依次摆放，间距固定为棋盘格距；
        // 某兵种全部上盘后不显示，其余棋子顺次左移，最右侧可能出现空位
        var blackSlot = 0
        for (pType in blackOrder) {
            if ((counts[pType] ?: 0) < (maxCount[pType] ?: 0)) {
                rowBlack.addView(createPaletteCell(pType, interval.toInt(), size, cellLeft(blackSlot)))
                blackSlot++
            }
        }
        var redSlot = 0
        for (pType in redOrder) {
            if ((counts[pType] ?: 0) < (maxCount[pType] ?: 0)) {
                rowRed.addView(createPaletteCell(pType, interval.toInt(), size, cellLeft(redSlot)))
                redSlot++
            }
        }
    }

    private fun createPaletteCell(pType: Int, cellWidth: Int, size: Int, leftOffset: Int): View {
        val cell = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(cellWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                leftMargin = leftOffset
            }
        }

        val iv = ImageView(this).apply {
            setImageResource(pieceBitmapRes(pType))
        }
        cell.addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        // 选中标记：与P2一致，使用四角标记框（红子红框、黑子黑框）
        val boxRes = if (Piece.isRed(pType)) R.drawable.r_box else R.drawable.b_box
        val box = ImageView(this).apply {
            setImageResource(boxRes)
            visibility = if (selectedPalettePiece == pType) View.VISIBLE else View.GONE
        }
        cell.addView(box, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        cell.setOnClickListener {
            if (selectedPalettePiece == pType) {
                // 点击已选中的棋子本身：保持选中状态，不做任何改变
                return@setOnClickListener
            }
            selectedPalettePiece = pType
            selectedPos = null
            boardView.setSelectedPos(null)
            soundPlayer.select()
            refreshPalette()
        }

        return cell
    }

    private fun pieceBitmapRes(pType: Int): Int {
        return when (pType) {
            Piece.WSHUAI -> R.drawable.r_shuai
            Piece.WSHI -> R.drawable.r_shi
            Piece.WXIANG -> R.drawable.r_xiang
            Piece.WMA -> R.drawable.r_ma
            Piece.WJU -> R.drawable.r_ju
            Piece.WPAO -> R.drawable.r_pao
            Piece.WBING -> R.drawable.r_bing
            Piece.BJIANG -> R.drawable.b_jiang
            Piece.BSHI -> R.drawable.b_shi
            Piece.BXIANG -> R.drawable.b_xiang
            Piece.BMA -> R.drawable.b_ma
            Piece.BJU -> R.drawable.b_ju
            Piece.BPAO -> R.drawable.b_pao
            Piece.BZU -> R.drawable.b_zu
            else -> R.drawable.r_shuai
        }
    }

    // ===================== 棋子摆放规则 =====================

    private fun isValidPlacement(pType: Int, pos: Position): Pair<Boolean, String> {
        val (x, y) = pos.x to pos.y
        val inRedPalace = x in 3..5 && y in 7..9
        val inBlackPalace = x in 3..5 && y in 0..2

        return when (pType) {
            Piece.WSHUAI -> if (inRedPalace) true to "" else false to "红帅只能放在红方九宫"
            Piece.BJIANG -> if (inBlackPalace) true to "" else false to "黑将只能放在黑方九宫"
            Piece.WSHI -> if (inRedPalace) true to "" else false to "红仕只能放在红方九宫"
            Piece.BSHI -> if (inBlackPalace) true to "" else false to "黑士只能放在黑方九宫"
            Piece.WXIANG -> {
                val ok = x % 2 == 0 && y in setOf(5, 7, 9)
                if (ok) true to "" else false to "红相不能过河，只能放在偶数列第5、7、9行"
            }
            Piece.BXIANG -> {
                val ok = x % 2 == 0 && y in setOf(0, 2, 4)
                if (ok) true to "" else false to "黑象不能过河，只能放在偶数列第0、2、4行"
            }
            Piece.WBING -> {
                val crossedRiver = y <= 4
                val onOwnSide = x % 2 == 0 && y in setOf(5, 6)
                if (crossedRiver || onOwnSide) true to "" else false to "红兵未过河时只能放在第5、6行偶数列"
            }
            Piece.BZU -> {
                val crossedRiver = y >= 5
                val onOwnSide = x % 2 == 0 && y in setOf(3, 4)
                if (crossedRiver || onOwnSide) true to "" else false to "黑卒未过河时只能放在第3、4行偶数列"
            }
            else -> true to ""
        }
    }

    private fun countPieces(): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (x in 0 until Board.BOARD_PIECE_WIDTH) {
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
                val p = board.getPieceByPosition(x, y)
                if (Piece.isValid(p)) counts[p] = (counts[p] ?: 0) + 1
            }
        }
        return counts
    }

    // ===================== P4 功能按钮 =====================

    /** 空/满：清空棋盘（只剩将帅） ↔ 恢复初始布局 */
    private fun toggleClearFill() {
        if (isCleared) {
            resetToInitial()
        } else {
            resetToClear()
        }
        isCleared = !isCleared
        selectedPos = null
        selectedPalettePiece = Piece.EMPTY
        boardView.setSelectedPos(null)
        refreshPalette()
    }

    private fun resetToClear() {
        board.clear()
        board.setPieceByPosition(4, 0, Piece.BJIANG)
        board.setPieceByPosition(4, 9, Piece.WSHUAI)
    }

    private fun resetToInitial() {
        val fresh = Board()
        board.clear()
        for (x in 0 until Board.BOARD_PIECE_WIDTH) {
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
                board.setPieceByPosition(x, y, fresh.getPieceByPosition(x, y))
            }
        }
    }

    /** 识图：跳转到主界面的识别功能 */
    private fun gotoRecognize() {
        val intent = Intent(this, com.mandgamit.chess.recognize.RecognitionActivity::class.java)
        startActivity(intent)
    }

    /** 对弈：校验后启动 GameActivity */
    private fun gotoPlay() {
        val (ok, msg) = validateBoard()
        if (!ok) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        val fen = board.toFENString()
        val intent = Intent(this, GameActivity::class.java)
        intent.putExtra("FENString", fen)
        intent.putExtra("Flipped", boardView.flipped)
        startActivity(intent)
        finish()
    }

    /** 更多：底部弹窗（生成局面 / 加载局面） */
    private fun showMoreOptionsSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_more_options, null)
        val grid = view.findViewById<GridLayout>(R.id.more_grid)
        val dialog = showBottomSheet(view)

        data class MoreOption(val label: String, val iconRes: Int, val action: () -> Unit)
        val options = listOf(
            MoreOption("生成局面", R.drawable.ic_more_export_fen) { generateFEN() },
            MoreOption("加载局面", R.drawable.ic_more_import_fen) { loadFromClipboard() }
        )

        options.forEach { option ->
            val cell = createIconCell(option.label, option.iconRes, 36) {
                option.action()
                dialog.dismiss()
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
    }

    // ===================== 先后手切换 =====================

    private fun setRedFirst(redFirst: Boolean) {
        board.bRedGo = redFirst
        updateSideButtons()
    }

    private fun updateSideButtons() {
        val blackRadio = findViewById<ImageView>(R.id.radioBlack)
        val redRadio = findViewById<ImageView>(R.id.radioRed)
        val blackText = findViewById<TextView>(R.id.btnBlackFirstText)
        val redText = findViewById<TextView>(R.id.btnRedFirstText)

        // 全局统一使用内置魏碑字体（@font/weibei）：选中态加粗，未选中态常规
        val weibei = ResourcesCompat.getFont(this, R.font.weibei)

        if (board.bRedGo) {
            // 红先选中：红RadioButton实心，文字加粗（红先始终为暗红）；黑先恢复常规
            redRadio.setImageResource(R.drawable.ic_radio_checked)
            blackRadio.setImageResource(R.drawable.ic_radio_unchecked)
            redText.setTextColor(Color.parseColor("#C30600"))
            redText.typeface = Typeface.create(weibei, Typeface.BOLD)
            blackText.setTextColor(Color.parseColor("#3D2C22"))
            blackText.typeface = weibei ?: Typeface.DEFAULT
        } else {
            // 黑先选中：黑RadioButton实心，文字加粗变深炭灰；红先保持暗红常规
            blackRadio.setImageResource(R.drawable.ic_radio_checked)
            redRadio.setImageResource(R.drawable.ic_radio_unchecked)
            blackText.setTextColor(Color.parseColor("#4E4E4C"))
            blackText.typeface = Typeface.create(weibei, Typeface.BOLD)
            redText.setTextColor(Color.parseColor("#C30600"))
            redText.typeface = weibei ?: Typeface.DEFAULT
        }
    }

    // ===================== FEN 加载 / 生成 =====================

    private fun loadFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "剪贴板为空，请先复制 FEN 串", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "剪贴板为空，请先复制 FEN 串", Toast.LENGTH_SHORT).show()
            return
        }

        val ok = try {
            board.restoreFromFEN(text)
        } catch (e: Exception) {
            Log.e(TAG, "loadFromClipboard FEN parse error", e)
            false
        }
        if (ok) {
            isCleared = false
            selectedPos = null
            selectedPalettePiece = Piece.EMPTY
            boardView.setSelectedPos(null)
            updateSideButtons()
            refreshPalette()
            Toast.makeText(this, "已从剪贴板加载 FEN", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "FEN 格式不正确，请检查剪贴板内容", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateFEN() {
        val (ok, msg) = validateBoard()
        if (!ok) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        val fen = board.toFENString()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FEN", fen))
        Toast.makeText(this, "FEN串已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    // ===================== 校验 =====================

    private fun validateBoard(): Pair<Boolean, String> {
        // 将帅必须各一（编辑时可双击移除，缺失时会出现在待排布区供摆回）
        val counts = countPieces()
        if ((counts[Piece.WSHUAI] ?: 0) != 1 || (counts[Piece.BJIANG] ?: 0) != 1) {
            return false to "棋盘必须包含红帅和黑将各一个"
        }
        if (isFlyingGeneral()) {
            return false to "将与帅不能在同一直线上且中间无子（飞将），请调整"
        }
        for (x in 0 until Board.BOARD_PIECE_WIDTH) {
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
                val p = board.getPieceByPosition(x, y)
                if (p == Piece.EMPTY) continue
                val (valid, reason) = isValidPlacement(p, Position(x, y))
                if (!valid) return false to "棋子位置不合规：$reason"
            }
        }
        return true to ""
    }

    private fun isFlyingGeneral(): Boolean {
        var shuaiPos: Position? = null
        var jiangPos: Position? = null
        for (x in 0 until Board.BOARD_PIECE_WIDTH) {
            for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
                val p = board.getPieceByPosition(x, y)
                if (p == Piece.WSHUAI) shuaiPos = Position(x, y)
                else if (p == Piece.BJIANG) jiangPos = Position(x, y)
            }
        }
        if (shuaiPos == null || jiangPos == null) return false
        if (shuaiPos.x != jiangPos.x) return false
        val topY = minOf(shuaiPos.y, jiangPos.y)
        val bottomY = maxOf(shuaiPos.y, jiangPos.y)
        for (y in topY + 1 until bottomY) {
            if (board.getPieceByPosition(shuaiPos.x, y) != Piece.EMPTY) return false
        }
        return true
    }

    // ===================== 通用 UI 工具 =====================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showBottomSheet(contentView: View): BottomSheetDialog {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(contentView)
        (contentView.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.show()
        return dialog
    }

    private fun createIconCell(
        label: String,
        iconRes: Int,
        iconSizeDp: Int,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
            addView(ImageView(this@BoardEditorActivity).apply {
                setImageResource(iconRes)
            }, LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)))
            addView(TextView(this@BoardEditorActivity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFF4E4E4C.toInt())
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}
