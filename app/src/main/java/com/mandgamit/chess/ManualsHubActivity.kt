package com.mandgamit.chess

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 棋谱入口页：主页"棋谱"按钮进入，仅展示两类棋谱来源。
 * 1. 对局棋谱：APP 内人机/好友对战/盲棋保存的 PGN（APP 私有目录/橘中弈象棋/）
 * 2. 内置棋谱：APK 内置古谱（assets/studies/nations，同原有列表行为）
 *
 * 复用 activity_study_list.xml 布局，保持学习列表页的视觉风格。
 */
class ManualsHubActivity : AppCompatActivity() {

    private data class HubItem(val name: String, val iconRes: Int, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_list)

        findViewById<TextView>(R.id.title).text = "棋谱"
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val items = listOf(
            HubItem("对局棋谱", R.drawable.folder) {
                startActivity(Intent(this, GameRecordsActivity::class.java))
            },
            HubItem("内置棋谱", R.drawable.folder) {
                val intent = Intent(this, StudyListActivity::class.java)
                intent.putExtra(StudyListActivity.EXTRA_CATEGORY, StudyListActivity.CATEGORY_NATIONS)
                intent.putExtra(StudyListActivity.EXTRA_TITLE, "内置棋谱")
                startActivity(intent)
            }
        )

        val recyclerView = findViewById<RecyclerView>(R.id.fileList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = HubAdapter(items)
    }

    private class HubAdapter(
        private val items: List<HubItem>
    ) : RecyclerView.Adapter<HubAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_study_file, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.icon.setImageResource(item.iconRes)
            holder.itemView.setOnClickListener { item.action() }
        }

        override fun getItemCount() = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.itemName)
            val icon: ImageView = itemView.findViewById(R.id.itemIcon)
        }
    }
}
