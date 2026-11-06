package com.mandgamit.chess.cloudbook

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 云库返回的一条参考着法
 * @param ucci UCCI着法串，如 "c3c4"
 * @param score 云库评分（单位：厘，红方视角；正值红优）
 * @param rank  着法评级（2=好着，1=常规，0=未知/劣着）
 * @param winrate 胜率字符串，如 "50.08"，视角为查询局面的走棋方
 * @param note  备注，如 "! (44-02)"
 */
data class CloudMove(
    val ucci: String,
    val score: Int,
    val rank: Int,
    val winrate: String,
    val note: String
)

/** 云库查询结果状态 */
enum class CloudQueryState {
    LOADED,       // 正常返回着法列表
    UNKNOWN,      // 该局面暂无已知着法
    CHECKMATE,    // 走棋方被将死
    STALEMATE,    // 走棋方困毙
    INVALID,      // 局面代码错误
    ERROR         // 网络或解析失败
}

class CloudBookResult(val state: CloudQueryState, val moves: List<CloudMove>? = null)

/**
 * 象棋云库（www.chessdb.cn）queryall 接口客户端：
 * 提交当前 FEN 局面，返回所有已知着法的评分、胜率等信息。
 *
 * - 单线程串行请求，按 FEN 缓存结果；
 * - 相同 FEN 的并发查询合并为一次请求；
 * - 回调统一投递到主线程。
 */
object CloudBookClient {
    private const val TAG = "CloudBookClient"
    private const val ENDPOINT = "https://www.chessdb.cn/chessdb.php"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // FEN -> 查询结果（进程内缓存）
    private val cache = HashMap<String, CloudBookResult>()

    // FEN -> 等待该局面结果的回调（表示查询正在进行中）
    private val pendingCallbacks = HashMap<String, MutableList<(CloudBookResult) -> Unit>>()

    /**
     * 查询指定 FEN 局面的云库着法，回调在主线程执行
     * @param forceRefresh true 时忽略本地缓存重新请求（用于用户手动重试）
     */
    fun query(fen: String, forceRefresh: Boolean = false,
              callback: (CloudBookResult) -> Unit) {
        if (!forceRefresh) {
            cache[fen]?.let {
                mainHandler.post { callback(it) }
                return
            }
        }
        val shouldExecute = synchronized(pendingCallbacks) {
            val waiters = pendingCallbacks[fen]
            if (waiters == null) {
                pendingCallbacks[fen] = mutableListOf(callback)
                true
            } else {
                waiters.add(callback)
                false
            }
        }
        if (shouldExecute) {
            executor.execute { doQuery(fen) }
        }
    }

    private fun doQuery(fen: String) {
        val result = try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = URL("$ENDPOINT?action=queryall&board=$encodedFen")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val text = try {
                conn.inputStream.bufferedReader().readText().trim()
            } finally {
                conn.disconnect()
            }
            parseResponse(text)
        } catch (e: Exception) {
            Log.w(TAG, "query failed: ${e.message}")
            CloudBookResult(CloudQueryState.ERROR)
        }

        // 网络错误属于瞬时故障，不写入缓存，避免重试永远拿到旧失败结果
        if (result.state != CloudQueryState.ERROR) {
            cache[fen] = result
        }
        val waiters = synchronized(pendingCallbacks) {
            pendingCallbacks.remove(fen)
        }
        mainHandler.post {
            waiters?.forEach { cb ->
                try {
                    cb(result)
                } catch (t: Throwable) {
                    Log.w(TAG, "callback error: ${t.message}")
                }
            }
        }
    }

    /**
     * 解析 queryall 返回：
     * 特殊串：invalid board / unknown / checkmate / stalemate；
     * 正常格式：move:c3c4,score:1,rank:2,note:! (44-02),winrate:50.08|...
     */
    private fun parseResponse(text: String): CloudBookResult {
        if (text.isEmpty()) {
            return CloudBookResult(CloudQueryState.ERROR)
        }
        return when (text) {
            "invalid board" -> CloudBookResult(CloudQueryState.INVALID)
            "unknown" -> CloudBookResult(CloudQueryState.UNKNOWN)
            "checkmate" -> CloudBookResult(CloudQueryState.CHECKMATE)
            "stalemate" -> CloudBookResult(CloudQueryState.STALEMATE)
            else -> parseMoves(text)
        }
    }

    private fun parseMoves(text: String): CloudBookResult {
        val moves = ArrayList<CloudMove>()
        for (token in text.split("|")) {
            if (token.isBlank()) continue
            var ucci = ""
            var score = 0
            var rank = 0
            var winrate = ""
            var note = ""
            // 按字段名解析，不依赖字段顺序
            for (field in token.split(",")) {
                when {
                    field.startsWith("move:") -> ucci = field.substring(5)
                    field.startsWith("score:") -> score = field.substring(6).toIntOrNull() ?: 0
                    field.startsWith("rank:") -> rank = field.substring(5).toIntOrNull() ?: 0
                    field.startsWith("winrate:") -> winrate = field.substring(8)
                    field.startsWith("note:") -> note = field.substring(5)
                }
            }
            if (ucci.length == 4) {
                moves.add(CloudMove(ucci, score, rank, winrate, note))
            }
        }
        return if (moves.isEmpty()) {
            CloudBookResult(CloudQueryState.UNKNOWN)
        } else {
            CloudBookResult(CloudQueryState.LOADED, moves)
        }
    }
}
