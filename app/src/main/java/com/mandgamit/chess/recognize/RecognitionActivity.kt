package com.mandgamit.chess.recognize

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mandgamit.chess.BoardEditorActivity
import com.mandgamit.chess.R
import java.io.File

/**
 * 棋盘识别 - 二级界面：历史识别结果 + 拍棋盘/选图片入口。
 *
 * 历史记录点击 → 加载该 FEN 进编辑界面
 * 拍棋盘/选图片 → 跳三级 RecognitionResultActivity 显示图片并识别
 */
class RecognitionActivity : AppCompatActivity() {

    private lateinit var history: RecognitionHistory
    private lateinit var adapter: HistoryAdapter
    private var cameraImageUri: Uri? = null

    // 运行时请求相机权限
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraInternal()
        } else {
            Toast.makeText(this, "需要相机权限才能拍照，请在设置中开启", Toast.LENGTH_LONG).show()
        }
    }

    // 系统相机：输出到 FileProvider 提供的 URI
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = cameraImageUri
        if (ok && uri != null) {
            gotoResult(uri.toString())
        } else {
            Toast.makeText(this, "拍照取消", Toast.LENGTH_SHORT).show()
        }
    }

    // 图库选图
    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) gotoResult(uri.toString())
        else Toast.makeText(this, "未选择图片", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition)

        history = RecognitionHistory(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnEdit).setOnClickListener {
            startActivity(Intent(this, RecognitionEditActivity::class.java))
        }
        findViewById<View>(R.id.btnCamera).setOnClickListener { launchCamera() }
        findViewById<View>(R.id.btnPick).setOnClickListener { launchPick() }

        adapter = HistoryAdapter(history.loadAll()) { record ->
            // 点击历史 → 加载 FEN 进编辑界面
            val intent = Intent(this, BoardEditorActivity::class.java)
            intent.putExtra(BoardEditorActivity.EXTRA_LOAD_FEN, record.fen)
            startActivity(intent)
        }
        findViewById<RecyclerView>(R.id.rvHistory).apply {
            layoutManager = GridLayoutManager(this@RecognitionActivity, 2)
            this.adapter = this@RecognitionActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        // 从编辑界面返回后刷新历史
        adapter.update(history.loadAll())
    }

    private fun launchCamera() {
        // Android 6+ 需运行时请求 CAMERA 权限，否则系统相机无法启动
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraInternal()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    /** 权限已就绪后真正启动系统相机。 */
    private fun startCameraInternal() {
        val imgFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        cameraImageUri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", imgFile
        )
        cameraLauncher.launch(cameraImageUri!!)
    }

    private fun launchPick() {
        pickLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }

    /** 跳三级界面：显示图片并识别。 */
    private fun gotoResult(imageUri: String) {
        val intent = Intent(this, RecognitionResultActivity::class.java)
        intent.putExtra(EXTRA_IMAGE_URI, imageUri)
        startActivity(intent)
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
    }

    // ---- 历史适配器 ----
    private class HistoryAdapter(
        private var items: List<RecognitionHistory.Record>,
        private val onClick: (RecognitionHistory.Record) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        fun update(newItems: List<RecognitionHistory.Record>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recognition, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.bind(r)
            holder.itemView.setOnClickListener { onClick(r) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val iv: ImageView = v.findViewById(R.id.ivThumb)
            private val tv: TextView = v.findViewById(R.id.tvDate)
            fun bind(r: RecognitionHistory.Record) {
                runCatching {
                    val bmp = BitmapFactory.decodeFile(r.imagePath)
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
                tv.text = r.formatDate()
            }
        }
    }
}
