package com.mandgamit.chess.battle.net

import android.os.Handler
import android.os.Looper

/**
 * 同进程回环传输通道（LoopbackTransport）：用于无服务器自测。
 *
 * `createPair()` 返回 (A, B) 两个已互联的端点，A.send 会被投递到 B 的监听器，
 * 反之亦然。消息通过 Handler 异步投递到主线程，模拟真实网络延迟并避免重入。
 *
 * 支持 `simulateDrop()` 模拟单方断线、重连状态。
 */
class LoopbackTransport private constructor() : BattleTransport {

    private var listener: BattleTransport.Listener? = null
    private var other: LoopbackTransport? = null
    private var isOpen = false
    private var closed = false

    companion object {
        /**
         * 创建一对已互联的回环端点。返回 (host, guest)，语义上 host 是建房者。
         */
        fun createPair(): Pair<LoopbackTransport, LoopbackTransport> {
            val host = LoopbackTransport()
            val guest = LoopbackTransport()
            host.other = guest
            guest.other = host
            return Pair(host, guest)
        }
    }

    override fun connect(listener: BattleTransport.Listener) {
        this.listener = listener
        isOpen = true
        closed = false
        Handler(Looper.getMainLooper()).postDelayed({
            if (!closed) listener.onOpen()
        }, 50L)
    }

    override fun send(text: String): Boolean {
        if (!isOpen || closed) return false
        val target = other ?: return false
        Handler(Looper.getMainLooper()).postDelayed({
            if (!target.closed) {
                target.listener?.onMessage(text)
            }
        }, 30L) // 30ms 模拟延迟
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        isOpen = false
        val reason = "loopback_closed"
        listener?.onClosed(reason)
        listener = null
        // 通知对端
        other?.let { peer ->
            Handler(Looper.getMainLooper()).postDelayed({
                if (!peer.closed) {
                    peer.listener?.onClosed("peer_closed")
                }
            }, 30L)
        }
    }

    /** 模拟网络中断：本端关闭但保留对象，对端收到 onClosed 后可重新 connect。 */
    fun simulateDrop() {
        if (!isOpen || closed) return
        closed = true
        isOpen = false
        val savedListener = listener
        listener = null
        savedListener?.onClosed("network_drop")
    }

    /** 模拟断线后重新连上（恢复 isOpen 状态，对端 connect 后自动重连）。 */
    fun simulateReconnect() {
        closed = false
        isOpen = true
        listener?.let { l ->
            Handler(Looper.getMainLooper()).postDelayed({
                if (!closed) l.onOpen()
            }, 50L)
        }
    }
}
