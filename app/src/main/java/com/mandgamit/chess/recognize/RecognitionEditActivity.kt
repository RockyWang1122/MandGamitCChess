package com.mandgamit.chess.recognize

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mandgamit.chess.R

/**
 * 棋盘识别 - 识别编辑界面：管理历史识别结果。
 *
 * - 返回：回识别二级界面（RecognitionActivity）
 * - 全选：所有条目勾选/取消
 * - 单击条目：切换选中
 * - 删除：删除已选中的记录
 */
class RecognitionEditActivity : AppCompatActivity() {

    private lateinit var history: RecognitionHistory
    private lateinit var adapter: EditAdapter
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition_edit)

        history = RecognitionHistory(this)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            // 返回识别二级界面（本页面由 RecognitionActivity 启动，直接结束即可）
            finish()
        }
        findViewById<View>(R.id.btnSelectAll).setOnClickListener { toggleSelectAll() }
        findViewById<View>(R.id.btnDelete).setOnClickListener { deleteSelected() }

        adapter = EditAdapter(history.loadAll(), selected) { toggleCount() }
        findViewById<RecyclerView>(R.id.rvHistory).apply {
            layoutManager = GridLayoutManager(this@RecognitionEditActivity, 2)
            this.adapter = this@RecognitionEditActivity.adapter
        }
        toggleCount()
    }

    private fun toggleSelectAll() {
        val all = adapter.items
        if (selected.size == all.size) {
            selected.clear()
        } else {
            selected.clear()
            all.forEach { selected.add(it.id) }
        }
        adapter.notifyDataSetChanged()
        toggleCount()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "未选中任何记录", Toast.LENGTH_SHORT).show()
            return
        }
        val toDelete = selected.toSet()
        history.delete(toDelete)
        selected.clear()
        adapter.update(history.loadAll())
        Toast.makeText(this, "已删除 ${toDelete.size} 条", Toast.LENGTH_SHORT).show()
    }

    private fun toggleCount() {
        findViewById<TextView>(R.id.tvCount).text = "已选中 ${selected.size} 个"
    }

    // ---- 适配器 ----
    private class EditAdapter(
        var items: List<RecognitionHistory.Record>,
        private val selected: MutableSet<String>,
        private val onToggle: () -> Unit,
    ) : RecyclerView.Adapter<EditAdapter.VH>() {

        fun update(newItems: List<RecognitionHistory.Record>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recognition_edit, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.bind(r, r.id in selected)
            holder.itemView.setOnClickListener {
                if (r.id in selected) selected.remove(r.id) else selected.add(r.id)
                notifyItemChanged(position)
                onToggle()
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val iv: ImageView = v.findViewById(R.id.ivThumb)
            private val tv: TextView = v.findViewById(R.id.tvDate)
            private val vOverlay: View = v.findViewById(R.id.vOverlay)
            private val vCheck: View = v.findViewById(R.id.vCheck)
            private val tvCheck: TextView = v.findViewById(R.id.tvCheck)
            fun bind(r: RecognitionHistory.Record, checked: Boolean) {
                runCatching {
                    val bmp = BitmapFactory.decodeFile(r.imagePath)
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
                tv.text = r.formatDate()
                if (checked) {
                    // 选中：红底白勾 + 整条半透明蒙板
                    vCheck.setBackgroundResource(R.drawable.bg_check_selected)
                    tvCheck.visibility = View.VISIBLE
                    vOverlay.visibility = View.VISIBLE
                } else {
                    // 未选中：红色边框空心正方形
                    vCheck.setBackgroundResource(R.drawable.bg_check_unselected)
                    tvCheck.visibility = View.INVISIBLE
                    vOverlay.visibility = View.GONE
                }
            }
        }
    }
}
