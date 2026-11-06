package com.mandgamit.chess.utils

import android.content.Context
import com.mandgamit.chess.gamelogic.Game
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对局棋谱（PGN）导出工具：人机大战(R) / 好友对战(H) / 盲棋训练(M)。
 *
 * 保存到 APP 私有目录 Android/data/<包名>/files/橘中弈象棋/（无需任何存储权限），
 * 文件名形如 R_20261008_1905.pgn（首字母区分对局类型，其后为日期+时分）。
 *
 * PGN 采用中文谱式着法并附 {ICCS 坐标} 注释，与本 APP 打谱功能的 PGNParser
 * 兼容，导出的棋谱可在"学习资料 → 打谱"中直接打开复盘。
 */
object GameRecordExporter {

    /** 棋谱保存目录名 */
    const val RECORDS_DIR_NAME = "橘中弈象棋"

    /** 退出提示保存的条件：任一方走棋超过该步数 */
    private const val MIN_MOVES_PER_SIDE = 5

    /** 对局类型：letter 为文件名首字母（R=人机、H=好友、M=盲棋） */
    enum class Mode(val letter: String, val label: String) {
        AI("R", "人机大战"),
        FRIEND("H", "好友对战"),
        BLIND("M", "盲棋训练")
    }

    /** 红方已走步数 */
    fun redMoveCount(history: List<Game.HistoryRecord>): Int = history.count { it.isRedMove }

    /** 黑方已走步数 */
    fun blackMoveCount(history: List<Game.HistoryRecord>): Int = history.count { !it.isRedMove }

    /** 是否满足退出提示条件：任一方走棋超过 5 步 */
    fun shouldPromptSave(history: List<Game.HistoryRecord>): Boolean =
        redMoveCount(history) > MIN_MOVES_PER_SIDE || blackMoveCount(history) > MIN_MOVES_PER_SIDE

    /**
     * 棋谱根目录：APP 私有外部存储 Android/data/<包名>/files/橘中弈象棋/。
     * 私有目录读写无需任何存储权限；外部存储不可用时回退到内部私有目录。
     */
    fun recordsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, RECORDS_DIR_NAME)
    }

    /**
     * 构建 PGN 文本。
     *
     * @param history    着法历史（建议传入调用时机的快照，弹窗期间对局可能继续）
     * @param initialFen 非标准开局（FEN 开局 / 黑先）时的初始局面，标准红先开局传 null
     * @param result     PGN 结果标记："1-0" / "0-1" / "1/2-1/2" / "*"
     */
    fun buildPgn(
        mode: Mode,
        history: List<Game.HistoryRecord>,
        initialFen: String?,
        redName: String,
        blackName: String,
        result: String,
        date: Date = Date()
    ): String {
        val sb = StringBuilder()
        sb.append("[Event \"橘中弈象棋·").append(mode.label).append("\"]\n")
        sb.append("[Site \"橘中弈象棋\"]\n")
        sb.append("[Date \"")
            .append(SimpleDateFormat("yyyy.MM.dd", Locale.US).format(date))
            .append("\"]\n")
        sb.append("[Round \"1\"]\n")
        sb.append("[Red \"").append(redName).append("\"]\n")
        sb.append("[Black \"").append(blackName).append("\"]\n")
        sb.append("[Result \"").append(result).append("\"]\n")
        if (!initialFen.isNullOrBlank()) {
            sb.append("[FEN \"").append(initialFen.trim()).append("\"]\n")
        }
        sb.append('\n')

        // 着法段：每回合一行，"1. 红着 {iccs} 黑着 {iccs}"；黑先开局首回合记作 "1. ... 黑着"
        val lines = ArrayList<String>()
        var i = 0
        var no = 1
        if (history.isNotEmpty() && !history[0].isRedMove) {
            lines.add("$no. ... ${token(history[0])}")
            i = 1
            no++
        }
        while (i < history.size) {
            val line = StringBuilder("$no. ").append(token(history[i]))
            i++
            if (i < history.size && !history[i].isRedMove) {
                line.append(' ').append(token(history[i]))
                i++
            }
            lines.add(line.toString())
            no++
        }
        if (lines.isEmpty()) {
            lines.add("*")
        }
        sb.append(lines.joinToString("\n")).append('\n').append(result).append('\n')
        return sb.toString()
    }

    /** 单着 token：中文谱式 + {ICCS 坐标} 注释（坐标注释会被 PGNParser 剥离，仅供其他软件参考） */
    private fun token(record: Game.HistoryRecord): String {
        val chs = record.chsString ?: return "*"
        val ucci = record.ucciString
        return if (ucci.isNullOrEmpty()) chs else "$chs {$ucci}"
    }

    /**
     * 写入 PGN 文件到 APP 私有目录下的棋谱文件夹。
     *
     * @return 文件绝对路径；同分钟重复保存时追加 _2/_3 后缀避免覆盖
     * @throws IOException 目录创建失败或写入失败
     */
    @Throws(IOException::class)
    fun exportPgn(context: Context, mode: Mode, pgn: String, date: Date = Date()): String {
        val dir = recordsDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建目录：${dir.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(date)
        var file = File(dir, "${mode.letter}_$stamp.pgn")
        var n = 2
        while (file.exists()) {
            file = File(dir, "${mode.letter}_${stamp}_$n.pgn")
            n++
        }
        file.writeText(pgn, StandardCharsets.UTF_8)
        return file.absolutePath
    }
}
