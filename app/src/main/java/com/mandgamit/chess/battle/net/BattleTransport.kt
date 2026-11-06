package com.mandgamit.chess.battle.net

/**
 * 对战传输通道抽象：真实实现走 WebSocket（WsTransport），
 * 自测实现走同进程回环（LoopbackTransport）。
 *
 * 回调线程不保证是主线程，由上层（BattleSessionManager）负责投递主线程。
 */
interface BattleTransport {

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        /** 连接关闭（对端关闭/网络断开/心跳超时）。reason 仅用于日志与提示。 */
        fun onClosed(reason: String)
        fun onError(t: Throwable)
    }

    /** 异步建立连接，成功后回调 Listener.onOpen。 */
    fun connect(listener: Listener)

    /** 发送文本帧；连接已断开时返回 false。 */
    fun send(text: String): Boolean

    /** 主动关闭连接（正常离开，不触发自动重连语义）。 */
    fun close()
}
