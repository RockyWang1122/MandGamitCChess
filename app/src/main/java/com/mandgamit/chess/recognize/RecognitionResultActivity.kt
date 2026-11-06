package com.mandgamit.chess.recognize

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mandgamit.chess.BoardEditorActivity
import com.mandgamit.chess.R
import com.mandgamit.chess.assist.YoloBoardDetector
import com.mandgamit.chess.gamelogic.Board
import com.mandgamit.chess.gamelogic.Piece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 棋盘识别 - 三级界面：显示图片 + "图片识别中..."，后台调 YOLO 识别。
 *
 * 失败 → Toast 提示并 finish 返回二级界面
 * 成功 → 保存历史记录 → 跳 BoardEditorActivity 加载识别出的 FEN
 */
class RecognitionResultActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var detector: YoloBoardDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition_result)

        val uriStr = intent.getStringExtra(RecognitionActivity.EXTRA_IMAGE_URI)
        if (uriStr.isNullOrEmpty()) {
            Toast.makeText(this, "图片无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 显示图片
        val iv = findViewById<ImageView>(R.id.ivImage)
        val tv = findViewById<TextView>(R.id.tvStatus)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { loadBitmap(Uri.parse(uriStr)) }
            if (bmp == null) {
                Toast.makeText(this@RecognitionResultActivity, "图片读取失败", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            iv.setImageBitmap(bmp)
            tv.text = "图片识别中..."

            // 后台 YOLO 识别
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    if (detector == null) detector = YoloBoardDetector(this@RecognitionResultActivity)
                    detector!!.detectBitmap(bmp)
                }.getOrNull()
            }

            if (result == null) {
                Toast.makeText(this@RecognitionResultActivity, "识别失败，请重新拍照或选图", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            // 识别成功：canonical 数组 → FEN
            val fen = canonicalToFen(result.canonical)
            Log.d(TAG, "识别成功 FEN: $fen pieces=${result.pieceCount}")

            // 保存历史记录（图片 + FEN）
            val history = RecognitionHistory(this@RecognitionResultActivity)
            history.save(bmp, fen)

            // 跳编辑界面，加载该 FEN
            val intent = Intent(this@RecognitionResultActivity, BoardEditorActivity::class.java)
            intent.putExtra(BoardEditorActivity.EXTRA_LOAD_FEN, fen)
            intent.flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        detector = null
    }

    /** canonical[y][x]（红在 y=9）→ FEN 串。 */
    private fun canonicalToFen(canonical: Array<IntArray>): String {
        val board = Board()
        board.clear()
        for (y in 0 until Board.BOARD_PIECE_HEIGHT) {
            for (x in 0 until Board.BOARD_PIECE_WIDTH) {
                val p = canonical[y][x]
                if (p != Piece.EMPTY) {
                    board.setPieceByPosition(x, y, p)
                }
            }
        }
        // 识别后默认红方先行
        board.bRedGo = true
        return board.toFENString()
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "RecognitionResult"
    }
}
