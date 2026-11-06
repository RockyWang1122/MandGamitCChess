package com.mandgamit.chess.recognize

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 棋盘识别历史记录管理：图片存内部存储，元数据存 JSON。
 *
 * - 图片目录：filesDir/history_images/
 * - 元数据文件：filesDir/recognition_history.json
 * - 最多保留 6 条（参考 UI 2×3 网格），超出自动删除最旧一条
 */
class RecognitionHistory(private val context: Context) {

    data class Record(
        val id: String,
        val imagePath: String,
        val fen: String,
        val timestamp: Long,
    ) {
        /** 格式化日期（yyyy-MM-dd HH:mm） */
        fun formatDate(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private val imageDir: File
        get() = File(context.filesDir, "history_images").apply { if (!exists()) mkdirs() }

    private val metaFile: File
        get() = File(context.filesDir, "recognition_history.json")

    /** 保存一张图片 + FEN，返回新记录。 */
    fun save(bitmap: Bitmap, fen: String): Record {
        val id = UUID.randomUUID().toString()
        val imgFile = File(imageDir, "$id.jpg")
        FileOutputStream(imgFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }
        val record = Record(id, imgFile.absolutePath, fen, System.currentTimeMillis())
        val list = loadAll().toMutableList()
        list.add(0, record) // 最新的放最前
        while (list.size > MAX_RECORDS) {
            val removed = list.removeAt(list.size - 1)
            runCatching { File(removed.imagePath).delete() }
        }
        saveAll(list)
        return record
    }

    /** 加载所有记录（按时间倒序）。 */
    fun loadAll(): List<Record> {
        val file = metaFile
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Record(
                    id = o.getString("id"),
                    imagePath = o.getString("imagePath"),
                    fen = o.getString("fen"),
                    timestamp = o.getLong("timestamp"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 删除指定 id 的记录（含图片）。 */
    fun delete(ids: Collection<String>) {
        val toDelete = ids.toSet()
        val remaining = loadAll().filter { it.id !in toDelete }
        loadAll().filter { it.id in toDelete }.forEach {
            runCatching { File(it.imagePath).delete() }
        }
        saveAll(remaining)
    }

    private fun saveAll(list: List<Record>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("imagePath", r.imagePath)
                put("fen", r.fen)
                put("timestamp", r.timestamp)
            })
        }
        metaFile.writeText(arr.toString())
    }

    companion object {
        private const val MAX_RECORDS = 6
    }
}
