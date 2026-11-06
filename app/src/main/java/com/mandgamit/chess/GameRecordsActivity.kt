package com.mandgamit.chess

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * 对局棋谱列表：浏览 APP 保存到私有目录（Android/data/<pkg>/files/橘中弈象棋/）的
 * PGN 对局棋谱，按文件修改时间倒序（最新在上）。点击文件进入 ManualActivity 打谱浏览，
 * 打谱页内的上一局/下一局切换同样基于该目录。
 */
class GameRecordsActivity : AppCompatActivity() {

    private lateinit var adapter: RecordsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_list)

        findViewById<TextView>(R.id.title).text = "对局棋谱"
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.fileList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RecordsAdapter { file ->
            val intent = Intent(this, ManualActivity::class.java)
            intent.putExtra("FILE_PATH", file.absolutePath)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
        // 列表加载统一在 onResume：首次进入与从打谱页返回走同一路径
    }

    override fun onResume() {
        super.onResume()
        // 从打谱页/删除操作返回后刷新列表
        loadRecords()
    }

    /** 加载棋谱文件列表：仅 PGN/XQF，按修改时间倒序 */
    private fun loadRecords() {
        val files = com.mandgamit.chess.utils.GameRecordExporter.recordsDir(this)
            .listFiles { f -> f.isFile && isRecordFile(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        adapter.submit(files)
        if (files.isEmpty()) {
            Toast.makeText(this, "还没有已保存的对局棋谱", Toast.LENGTH_SHORT).show()
        }
    }

    /** 是否为可浏览的对局棋谱文件（PGN/XQF，不区分大小写） */
    private fun isRecordFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "pgn" || ext == "xqf"
    }

    private class RecordsAdapter(
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<RecordsAdapter.VH>() {

        private val files = mutableListOf<File>()

        fun submit(list: List<File>) {
            files.clear()
            files.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_study_file, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = files[position]
            // 显示名去掉扩展名（如 "R_20261008_2320"）
            holder.name.text = file.name.substringBeforeLast('.')
            holder.icon.setImageResource(R.drawable.ic_study_manual)
            holder.itemView.setOnClickListener { onClick(file) }
            holder.delete.visibility = View.VISIBLE
            holder.delete.setOnClickListener { confirmDelete(holder, file) }
        }

        override fun getItemCount() = files.size

        /** 弹确认框后删除该棋谱文件，成功则移出列表，失败提示 */
        private fun confirmDelete(holder: VH, file: File) {
            val ctx = holder.itemView.context
            AlertDialog.Builder(ctx)
                .setTitle("删除棋谱")
                .setMessage("确定删除「${file.name.substringBeforeLast('.')}」吗？删除后不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    if (file.delete()) {
                        files.remove(file)
                        notifyDataSetChanged()
                    } else {
                        Toast.makeText(ctx, "删除失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.itemName)
            val icon: ImageView = itemView.findViewById(R.id.itemIcon)
            val delete: ImageButton = itemView.findViewById(R.id.itemDelete)
        }
    }
}
