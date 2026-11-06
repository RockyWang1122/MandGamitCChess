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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 学习资料列表：递归浏览 assets/studies/<category>。
 * 任意层级下，目录点击进入下一层，文件点击按类别加载
 * （古谱→ManualActivity，残局/杀法→PuzzleActivity）。
 */
class StudyListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_TITLE = "title"

        const val CATEGORY_NATIONS = "nations"
        const val CATEGORY_ENDGAMES = "endgames"
        const val CATEGORY_TRAIN = "train"

        private const val ASSET_ROOT = "studies"

        // 已知的资料文件扩展名（小写）。命中即视为文件，无需再调 assets.list 判定
        private val FILE_EXTS = setOf("xqf", "fen", "pgn")
    }

    private data class StudyItem(val name: String, val isDir: Boolean) {
        /** 文件扩展名（小写），目录为空串 */
        val ext: String = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    }

    private lateinit var category: String
    private var titleText: String = "学习"
    private lateinit var itemAdapter: ItemAdapter

    // 当前所在子目录层级（相对 studies/<category> 的路径段），空表示在顶层
    private var currentPath: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_list)

        category = intent.getStringExtra(EXTRA_CATEGORY) ?: CATEGORY_NATIONS
        titleText = intent.getStringExtra(EXTRA_TITLE) ?: "学习"

        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            if (!goBack()) finish()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.fileList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        itemAdapter = ItemAdapter { item -> onEntryClick(item) }
        recyclerView.adapter = itemAdapter

        loadCurrent()
    }

    /** 返回键处理：在子目录内回退一层，到顶层再按一次退出 */
    private fun goBack(): Boolean {
        if (currentPath.isNotEmpty()) {
            currentPath.removeAt(currentPath.lastIndex)
            loadCurrent()
            return true
        }
        return false
    }

    @Deprecated("使用 OnBackPressedDispatcher", ReplaceWith("getOnBackPressedDispatcher().onBackPressed()"))
    override fun onBackPressed() {
        if (!goBack()) super.onBackPressed()
    }

    /** 当前所在 assets 路径段：studies / category / 子目录… */
    private fun currentAssetPath(): List<String> =
        listOf(ASSET_ROOT, category) + currentPath

    /** 加载当前层级内容：枚举条目并区分目录/文件 */
    private fun loadCurrent() {
        val basePath = currentAssetPath().joinToString("/")

        // 标题：顶层显示原标题，子层只显示当前所在目录名
        findViewById<TextView>(R.id.title).text =
            if (currentPath.isEmpty()) titleText else currentPath.last()

        val entries = try {
            assets.list(basePath)?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 分类：.xqf/.fen/.pgn→文件；能再列出子项的→目录；其余忽略不显示
        // 注意：目录名可能含点号（如 "01.运子战术"），不能用扩展名判断
        val items = entries.mapNotNull { name ->
            when {
                isKnownFile(name)             -> StudyItem(name, isDir = false)
                isAssetDir("$basePath/$name") -> StudyItem(name, isDir = true)
                else                          -> null
            }
        }
        itemAdapter.submitList(items)
    }

    /** 用 assets.list 实测判定是否为目录 */
    private fun isAssetDir(path: String): Boolean = try {
        !assets.list(path).isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /** 名称是否为已知资料文件（.xqf/.fen/.pgn） */
    private fun isKnownFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in FILE_EXTS
    }

    /** 点击条目：目录→进入下一层；文件→按文件类型加载 */
    private fun onEntryClick(item: StudyItem) {
        if (item.isDir) {
            currentPath.add(item.name)
            loadCurrent()
        } else {
            onFileClick(item)
        }
    }

    /**
     * 点击文件：按文件实际类型（扩展名）跳转，而不按主页入口决定，
     * 避免不同类型文件混放在同一目录时打开错误页面。
     * - .fen        → PuzzleActivity（残局/杀法做题，支持上一题/下一题/重置/提示）
     * - .xqf/.pgn   → ManualActivity（棋谱浏览，内部再按内容区分 XQF/PGN 解析）
     */
    private fun onFileClick(item: StudyItem) {
        val assetPath = (currentAssetPath() + item.name).joinToString("/")

        when (item.ext) {
            "fen" -> {
                val intent = Intent(this, PuzzleActivity::class.java)
                intent.putExtra(PuzzleActivity.EXTRA_ASSET_PATH, assetPath)
                startActivity(intent)
            }
            "xqf", "pgn" -> {
                val intent = Intent(this, ManualActivity::class.java)
                intent.putExtra("ASSET_PATH", assetPath)
                startActivity(intent)
            }
            else -> Toast.makeText(this, "不支持的文件类型", Toast.LENGTH_SHORT).show()
        }
    }

    /** 通用列表 Adapter */
    private class ItemAdapter(
        private val onClick: (StudyItem) -> Unit
    ) : RecyclerView.Adapter<ItemAdapter.VH>() {

        private val items = mutableListOf<StudyItem>()

        fun submitList(list: List<StudyItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_study_file, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            // 目录显示原名（常带书名号/编号，如 "01《梅花谱》"），
            // 文件去掉扩展名（如 "当头炮破过宫炮"）
            holder.name.text = if (item.isDir) item.name else item.name.substringBeforeLast('.')
            holder.icon.setImageResource(iconResFor(item))
            holder.itemView.setOnClickListener { onClick(item) }
        }

        /** 图标：目录→文件夹；FEN→红色闪电；XQF/PGN→红色书本（均为列表专用，不与主页白色图标共用） */
        private fun iconResFor(item: StudyItem): Int = when {
            item.isDir -> R.drawable.folder
            item.ext == "fen" -> R.drawable.ic_study_fen
            else -> R.drawable.ic_study_manual
        }

        override fun getItemCount() = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.itemName)
            val icon: ImageView = itemView.findViewById(R.id.itemIcon)
        }
    }
}
