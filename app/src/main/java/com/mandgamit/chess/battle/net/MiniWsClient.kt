package com.mandgamit.chess.battle.net

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 WebSocket 客户端（RFC6455），纯 JDK 实现，无第三方依赖
 * （离线构建环境 Gradle 缓存无 OkHttp，故自实现）。
 *
 * 一期约束：
 * - 仅支持文本帧（opcode 0x1），且不支持分片（收到分片帧按协议错误关闭）；
 * - 自动回应 ping → pong；收到 close 帧回 close 后断开；
 * - 客户端发送帧按规范掩码；
 * - 单帧负载上限 1MB（对局消息远小于此）。
 *
 * 回调均在读线程触发，调用方需自行切线程（BattleSessionManager 已 post 到主线程）。
 */
class MiniWsClient(private val url: String) {

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(reason: String)
        fun onError(t: Throwable)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var out: BufferedOutputStream? = null
    @Volatile private var open = false
    @Volatile private var closedByUser = false
    private var readerThread: Thread? = null
    private val random = SecureRandom()

    /**
     * 专用写线程（写泵）：所有 socket 写操作必须在此线程执行。
     * 会话回调经 Handler 切到主线程后，send() 多由主线程触发，
     * 直接写会触发 NetworkOnMainThreadException；这里串行投递保证线程安全且不阻塞调用方。
     */
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mini-ws-writer").apply { isDaemon = true }
    }

    fun connect(listener: Listener) {
        readerThread = Thread({
            try {
                val uri = URI(url)
                val secure = uri.scheme.equals("wss", ignoreCase = true)
                val host = uri.host ?: throw IllegalArgumentException("bad url: $url")
                val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
                val path = buildString {
                    append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                    if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
                }

                val s = if (secure) SSLSocketFactory.getDefault().createSocket(host, port)
                        else Socket(host, port)
                s.tcpNoDelay = true
                // 开启 TCP keepalive：降低后台时因连接 idle 被系统/中间网络断开的概率
                s.keepAlive = true
                socket = s

                // ---- HTTP Upgrade 握手 ----
                val keyBytes = ByteArray(16).also { random.nextBytes(it) }
                val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                val req = buildString {
                    append("GET ").append(path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(host).append(':').append(port).append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                val rawOut = BufferedOutputStream(s.getOutputStream())
                rawOut.write(req.toByteArray(Charsets.US_ASCII))
                rawOut.flush()

                val rawIn = BufferedInputStream(s.getInputStream())
                val statusLine = readHttpLine(rawIn)
                if (!statusLine.contains(" 101")) {
                    throw IOException("handshake rejected: $statusLine")
                }
                var acceptOk = false
                while (true) {
                    val line = readHttpLine(rawIn)
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    if (line.substring(0, idx).trim().equals("Sec-WebSocket-Accept", true)) {
                        val expect = Base64.encodeToString(
                            MessageDigest.getInstance("SHA-1")
                                .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
                            Base64.NO_WRAP)
                        acceptOk = line.substring(idx + 1).trim() == expect
                    }
                }
                if (!acceptOk) throw IOException("bad Sec-WebSocket-Accept")

                out = rawOut
                open = true
                listener.onOpen()

                // ---- 读帧循环 ----
                val din = DataInputStream(rawIn)
                while (open) {
                    val b0 = din.readUnsignedByte()
                    val fin = b0 and 0x80 != 0
                    val opcode = b0 and 0x0F
                    val b1 = din.readUnsignedByte()
                    val masked = b1 and 0x80 != 0
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) len = din.readUnsignedShort().toLong()
                    else if (len == 127L) len = din.readLong()
                    if (len < 0 || len > MAX_PAYLOAD) throw IOException("frame too large: $len")
                    val mask = if (masked) ByteArray(4).also { din.readFully(it) } else null
                    val payload = ByteArray(len.toInt()).also { din.readFully(it) }
                    if (mask != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                        }
                    }
                    when (opcode) {
                        0x1 -> {
                            if (!fin) throw IOException("fragmented text frame not supported")
                            listener.onMessage(String(payload, Charsets.UTF_8))
                        }
                        0x8 -> { // close：透传关闭码（4001=房间终态关闭，APP 不重连）
                            open = false
                            val closeCode = if (payload.size >= 2) {
                                ((payload[0].toInt() and 0xFF) shl 8) or
                                    (payload[1].toInt() and 0xFF)
                            } else 1000
                            sendCloseEcho(payload)
                            closeQuietly()
                            listener.onClosed("closed:$closeCode")
                            return@Thread
                        }
                        0x9 -> enqueueWrite(0xA, payload) // ping → pong
                        0xA -> Unit // pong：无需处理
                        else -> throw IOException("unsupported opcode: $opcode")
                    }
                }
            } catch (t: Throwable) {
                if (!closedByUser) {
                    Log.w(TAG, "ws error: ${t.message}")
                    try { listener.onError(t) } catch (_: Throwable) {}
                    try { listener.onClosed(t.message ?: "error") } catch (_: Throwable) {}
                }
            } finally {
                open = false
                closeQuietly()
                // 连接生命周期结束，写泵不再接受新任务（已排队的仍会执行完）
                writeExecutor.shutdown()
            }
        }, "mini-ws-reader")
        readerThread?.isDaemon = true
        readerThread?.start()
    }

    /** 发送文本帧。连接未开或写泵已关闭返回 false（实际写异常仅记录日志）。 */
    fun send(text: String): Boolean {
        if (!open) return false
        return enqueueWrite(0x1, text.toByteArray(Charsets.UTF_8))
    }

    /** 主动关闭：排队发送 close 帧后断 socket（读线程退出，不再回调 onError/onClosed）。 */
    fun close() {
        closedByUser = true
        open = false
        // 经写泵有序发出 close 帧后关闭 socket。写泵可能已被关闭（重复 close、
        // 连接失败后的二次清理）：池拒绝任务时直接同步收尾，保证 close 幂等不抛。
        try {
            writeExecutor.execute {
                runCatching { sendFrame(0x8, ByteArray(0)) }
                closeQuietly()
            }
        } catch (_: RejectedExecutionException) {
            closeQuietly()
        }
        writeExecutor.shutdown()
    }

    // ==================== 内部 ====================

    /** 把一帧投递到写泵执行；写泵已关闭时返回 false。 */
    private fun enqueueWrite(opcode: Int, payload: ByteArray): Boolean {
        return try {
            writeExecutor.execute {
                try {
                    sendFrame(opcode, payload)
                } catch (t: Throwable) {
                    Log.w(TAG, "write frame failed: ${t.message}")
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 回应服务端 close 帧；连接可能已坏，失败静默即可。 */
    private fun sendCloseEcho(payload: ByteArray) {
        enqueueWrite(0x8, payload)
    }

    /** 客户端→服务端帧必须掩码（RFC6455 §5.3）。仅在写泵线程调用。 */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val o = out ?: throw IOException("not open")
        val mask = ByteArray(4).also { random.nextBytes(it) }
        o.write(0x80 or opcode)
        when {
            payload.size < 126 -> o.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                o.write(0x80 or 126)
                o.write(payload.size ushr 8)
                o.write(payload.size)
            }
            else -> {
                o.write(0x80 or 127)
                for (i in 7 downTo 0) o.write((payload.size.toLong() ushr (8 * i)).toInt())
            }
        }
        o.write(mask)
        for (i in payload.indices) {
            o.write(payload[i].toInt() xor mask[i % 4].toInt())
        }
        o.flush()
    }

    /** 读取一行 HTTP 头（以 \r\n 结尾，返回不含换行的内容）。 */
    private fun readHttpLine(input: BufferedInputStream): String {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("connection closed during handshake")
            if (prev == '\r'.code && b == '\n'.code) {
                sb.setLength(sb.length - 1) // 去掉 '\r'
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        out = null
    }

    companion object {
        private const val TAG = "MiniWsClient"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_PAYLOAD = 1024 * 1024
    }
}
