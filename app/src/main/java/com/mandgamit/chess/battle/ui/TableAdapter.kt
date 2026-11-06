package com.mandgamit.chess.battle.ui

import android.graphics.Color
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mandgamit.chess.R
import com.mandgamit.chess.battle.net.BattleMessage
import com.mandgamit.chess.battle.net.TableInfo

/**
 * 大厅棋桌卡片列表。
 *
 * 操作按钮语义：
 * - 空桌：入座（首坐默认红方）；
 * - 等待中：加入（坐另一方空位）；
 * - 对弈中：观战；
 * - 本人已入座未开局：离座。
 */
class TableAdapter(
    private val onSit: (TableInfo) -> Unit,
    private val onWatch: (TableInfo) -> Unit,
    private val onStand: (TableInfo) -> Unit,
) : RecyclerView.Adapter<TableAdapter.VH>() {

    private val items = ArrayList<TableInfo>()

    /** 本人已入座桌号（0 表示未入座），用于把该桌按钮切换为"离座"。 */
    var mySeatedTable: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** 本人昵称：用于在座位列表中高亮"我的昵称"徽章。 */
    var myNick: String = ""
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** 全量替换（lobby_state）。 */
    fun submitAll(tables: List<TableInfo>) {
        items.clear()
        items.addAll(tables.sortedBy { it.no })
        notifyDataSetChanged()
    }

    /** 增量更新（table_update）：closed 移除，其余按桌号插入/替换。 */
    fun applyUpdate(info: TableInfo) {
        val idx = items.indexOfFirst { it.no == info.no }
        if (info.status == BattleMessage.TABLE_CLOSED) {
            if (idx >= 0) {
                items.removeAt(idx)
                notifyItemRemoved(idx)
            }
            return
        }
        if (idx >= 0) {
            items[idx] = info
            notifyItemChanged(idx)
        } else {
            // 按桌号升序插入，保证列表顺序稳定
            var pos = items.indexOfFirst { it.no > info.no }
            if (pos < 0) pos = items.size
            items.add(pos, info)
            notifyItemInserted(pos)
        }
    }

    fun snapshot(): List<TableInfo> = ArrayList(items)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lobby_table, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val accent: View = view.findViewById(R.id.view_accent)
        private val tableNo: TextView = view.findViewById(R.id.text_table_no)
        private val status: TextView = view.findViewById(R.id.text_status)
        private val watchers: TextView = view.findViewById(R.id.text_watchers)
        private val nameRed: TextView = view.findViewById(R.id.name_red)
        private val nameBlack: TextView = view.findViewById(R.id.name_black)
        private val avatarRed: ImageView = view.findViewById(R.id.avatar_red)
        private val avatarBlack: ImageView = view.findViewById(R.id.avatar_black)
        private val action: TextView = view.findViewById(R.id.button_action)

        init {
            // 头像圆形裁剪：oval outline + clipToOutline（纯 framework，无新依赖）
            val circle = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            avatarRed.clipToOutline = true
            avatarRed.outlineProvider = circle
            avatarBlack.clipToOutline = true
            avatarBlack.outlineProvider = circle
        }

        /** 有落座者显示圆形头像（设计稿 ic_logo），空位显示虚线圆+加号占位（ic_empty_seat）。 */
        private fun bindAvatar(avatar: ImageView, nick: String) {
            if (nick.isEmpty()) {
                avatar.setImageResource(R.drawable.ic_empty_seat)
            } else {
                avatar.setImageResource(R.drawable.ic_logo)
            }
        }

        /**
         * 座位昵称：本人（所在桌 + 昵称匹配）显示深金底白字徽章一眼定位，
         * 其余为普通文字；空位显示半透明"虚位以待"。
         */
        private fun bindNick(tv: TextView, nick: String, isMySeat: Boolean) {
            tv.text = nick.ifEmpty { "虚位以待" }
            val mine = isMySeat && nick.isNotEmpty() && nick == myNick
            if (mine) {
                tv.alpha = 1f
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
                tv.setTextColor(Color.WHITE)
                tv.setBackgroundResource(R.drawable.bg_my_nick_badge)
                val padH = dp(8)
                tv.setPadding(padH, dp(2), padH, dp(2))
            } else {
                tv.alpha = if (nick.isEmpty()) 0.55f else 1f
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                tv.setTextColor(Color.parseColor("#4A4033"))
                tv.background = null
                tv.setPadding(0, 0, 0, 0)
            }
        }

        private fun dp(v: Int): Int =
            (v * itemView.resources.displayMetrics.density).toInt()

        fun bind(info: TableInfo) {
            val isMyTable = info.no == mySeatedTable
            tableNo.text = "${info.no} 号桌"
            watchers.text = if (info.watchers > 0) "观战 ${info.watchers}" else ""

            when (info.status) {
                BattleMessage.TABLE_WAITING -> {
                    status.text = "等待中"
                    status.setTextColor(Color.parseColor("#B58A2E"))
                    status.setBackgroundResource(R.drawable.bg_lobby_status_wait)
                    accent.setBackgroundColor(Color.parseColor("#E0B96A"))
                }
                BattleMessage.TABLE_PLAYING -> {
                    status.text = "对弈中"
                    status.setTextColor(Color.parseColor("#3D8A72"))
                    status.setBackgroundResource(R.drawable.bg_lobby_status_play)
                    accent.setBackgroundColor(Color.parseColor("#6FC4A8"))
                }
                else -> {
                    status.text = "空桌"
                    status.setTextColor(Color.parseColor("#8A8175"))
                    status.setBackgroundResource(R.drawable.bg_lobby_status_empty)
                    accent.setBackgroundColor(Color.parseColor("#A89A80"))
                }
            }

            bindNick(nameRed, info.red, isMyTable)
            bindNick(nameBlack, info.black, isMyTable)
            bindAvatar(avatarRed, info.red)
            bindAvatar(avatarBlack, info.black)

            when {
                // 本人已入座且未开局：允许离座
                isMyTable && info.status == BattleMessage.TABLE_WAITING -> {
                    action.text = "离座"
                    action.setTextColor(Color.parseColor("#8A6D2C"))
                    action.setBackgroundResource(R.drawable.bg_lobby_action)
                    action.setOnClickListener { onStand(info) }
                }
                info.status == BattleMessage.TABLE_EMPTY -> {
                    action.text = "入座"
                    action.setTextColor(Color.parseColor("#8A6D2C"))
                    action.setBackgroundResource(R.drawable.bg_lobby_action)
                    action.setOnClickListener { onSit(info) }
                }
                info.status == BattleMessage.TABLE_WAITING -> {
                    action.text = "加入"
                    action.setTextColor(Color.parseColor("#3A2A10"))
                    action.setBackgroundResource(R.drawable.bg_lobby_action_solid)
                    action.setOnClickListener { onSit(info) }
                }
                info.status == BattleMessage.TABLE_PLAYING -> {
                    action.text = "观战"
                    action.setTextColor(Color.parseColor("#8A6D2C"))
                    action.setBackgroundResource(R.drawable.bg_lobby_action)
                    action.setOnClickListener { onWatch(info) }
                }
                else -> {
                    action.text = ""
                    action.setOnClickListener(null)
                }
            }
        }
    }
}
