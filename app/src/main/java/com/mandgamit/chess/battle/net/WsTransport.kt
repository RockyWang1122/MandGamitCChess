package com.mandgamit.chess.battle.net

/**
 * BattleTransport 的 WebSocket 实现：包装 MiniWsClient。
 * 服务器地址由 BuildConfig.BATTLE_SERVER_URL（local.properties: battle.server.url）提供。
 */
class WsTransport(private val url: String) : BattleTransport {

    private var client: MiniWsClient? = null

    override fun connect(listener: BattleTransport.Listener) {
        val c = MiniWsClient(url)
        client = c
        c.connect(object : MiniWsClient.Listener {
            override fun onOpen() = listener.onOpen()
            override fun onMessage(text: String) = listener.onMessage(text)
            override fun onClosed(reason: String) = listener.onClosed(reason)
            override fun onError(t: Throwable) = listener.onError(t)
        })
    }

    override fun send(text: String): Boolean = client?.send(text) ?: false

    override fun close() {
        client?.close()
        client = null
    }
}
