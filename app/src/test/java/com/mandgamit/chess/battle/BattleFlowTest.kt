package com.mandgamit.chess.battle

import android.os.Looper
import com.mandgamit.chess.battle.net.BattleMessage
import com.mandgamit.chess.battle.net.BattleProtocol
import com.mandgamit.chess.battle.net.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 好友对战端到端流程测试（Robolectric + LoopbackTransport）：
 * 协议编解码、双会话走子互通、对账快照、认输/离开、断线重连触发 sync。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BattleFlowTest {

    /** 记录型监听器：把会话回调落成可断言的数据。 */
    private class Rec : BattleSessionManager.BattleListener {
        val remoteMoves = ArrayList<String>()
        var syncState: BattleMessage.SyncState? = null
        var syncRequested = false
        var resigned = false
        var opponentLeft = false
        var gameStartCount = 0
        var resumedColor: String? = null
        val errors = ArrayList<String>()

        override fun onRoomCreated(room: String) {}
        override fun onRoomJoined(color: String) {}
        override fun onGameStart() { gameStartCount++ }
        override fun onGameResumed(color: String) { resumedColor = color }
        override fun onRemoteMove(ucci: String, seq: Int) { remoteMoves.add(ucci) }
        override fun onOpponentOffline(ttlSeconds: Int) {}
        override fun onOpponentOnline() {}
        override fun onSyncState(fen: String, moves: List<String>, turn: String) {
            syncState = BattleMessage.SyncState(fen, moves, turn)
        }
        override fun onSyncRequested() { syncRequested = true }
        override fun onOpponentResign() { resigned = true }
        override fun onOpponentLeave(reason: String?) { opponentLeft = true }
        override fun onError(msg: String) { errors.add(msg) }
        override fun onStateChanged(state: BattleSessionManager.State) {}
    }

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    private fun newPair(): Triple<BattleSessionManager, BattleSessionManager, LoopbackTransport> {
        val (hostEnd, guestEnd) = LoopbackTransport.createPair()
        val host = BattleSessionManager({ hostEnd })
        val guest = BattleSessionManager({ guestEnd })
        host.injectDebugState("888888", "ht", BattleMessage.COLOR_RED, host = true)
        guest.injectDebugState("888888", "gt", BattleMessage.COLOR_BLACK, host = false)
        return Triple(host, guest, guestEnd)
    }

    @Test
    fun `protocol encode decode roundtrip`() {
        val m1 = BattleProtocol.decode(
            BattleProtocol.encode(BattleMessage.MoveMade("h2e2", 3), "123456", "tok"))
        assertTrue(m1 is BattleMessage.MoveMade)
        m1 as BattleMessage.MoveMade
        assertEquals("h2e2", m1.ucci)
        assertEquals(3, m1.seq)

        val m2 = BattleProtocol.decode(
            BattleProtocol.encode(
                BattleMessage.SyncState("fen abc", listOf("h2e2", "h9e7"), "black"), "1", "t"))
        assertTrue(m2 is BattleMessage.SyncState)
        m2 as BattleMessage.SyncState
        assertEquals(listOf("h2e2", "h9e7"), m2.moves)
        assertEquals("black", m2.turn)

        assertNull(BattleProtocol.decode("not json"))
        assertNull(BattleProtocol.decode("{\"type\":\"unknown_type\"}"))
    }

    @Test
    fun `loopback pair delivers moves sync resign leave`() {
        val (host, guest, _) = newPair()
        val hostRec = Rec(); val guestRec = Rec()
        host.listener = hostRec; guest.listener = guestRec
        idle(200)

        // 红方走子 → 黑方收到
        assertTrue(host.sendMove("h2e2"))
        idle(200)
        assertEquals(listOf("h2e2"), guestRec.remoteMoves)

        // 黑方走子 → 红方收到
        assertTrue(guest.sendMove("h9e7"))
        idle(200)
        assertEquals(listOf("h9e7"), hostRec.remoteMoves)

        // 对账：黑方推快照 → 红方收到并落库 lastSync
        guest.sendSyncState("fenX", listOf("h2e2", "h9e7"), BattleMessage.COLOR_RED)
        idle(200)
        assertNotNull(hostRec.syncState)
        assertEquals("fenX", hostRec.syncState!!.fen)
        assertEquals("fenX", host.lastSyncFen)
        assertEquals(2, host.lastSyncMoves.size)

        // 认输转发
        guest.sendResign()
        idle(200)
        assertTrue(hostRec.resigned)

        // 离开转发
        host.leave()
        idle(200)
        assertTrue(guestRec.opponentLeft)
        assertTrue(guestRec.errors.isEmpty())
    }

    @Test
    fun `drop and auto reconnect triggers sync request`() {
        val (host, guest, guestEnd) = newPair()
        val hostRec = Rec(); val guestRec = Rec()
        host.listener = hostRec; guest.listener = guestRec
        idle(200)

        // 黑方断线 → 进入 DISCONNECTED 并安排重连（首次延迟 1s）
        guestEnd.simulateDrop()
        idle(200)
        assertEquals(BattleSessionManager.State.DISCONNECTED, guest.state)

        // 重连成功（connect 50ms）后应重发 join_room 并主动 request_sync → 红方收到对账请求
        // 对局已开局，重连后应恢复到 PLAYING（而非初始就位的 READY）
        idle(3000)
        assertEquals(BattleSessionManager.State.PLAYING, guest.state)
        assertTrue(hostRec.syncRequested)
    }

    /**
     * 断线恢复后服务端补发同一局（代数未变）：保留落盘快照，只同步执子色不重置棋盘。
     */
    @Test
    fun `resumed start_game keeps snapshot for same gen`() {
        BattleStateStore.clear()
        val (hostEnd, guestEnd) = LoopbackTransport.createPair()
        val host = BattleSessionManager({ hostEnd })
        host.injectDebugState("888888", "ht", BattleMessage.COLOR_RED, host = true)
        val guest = BattleSessionManager({ guestEnd })
        val rec = Rec()
        guest.listener = rec
        BattleStateStore.saveSession(
            "888888", "gt", BattleMessage.COLOR_BLACK, false, false, tableNo = 5)
        BattleStateStore.saveProgress(3, 2, 2)
        BattleStateStore.saveSnapshot(
            "fen-old", listOf("h2e2", "h9e7"), BattleMessage.COLOR_RED)
        guest.restoreSession(
            "888888", "gt", BattleMessage.COLOR_BLACK, false, false, tableNo = 5)
        idle(200)

        hostEnd.send("""{"type":"start_game","red":"甲","black":"乙","color":"black","gen":3}""")
        idle(200)

        assertEquals("black", rec.resumedColor)
        assertEquals(0, rec.gameStartCount)
        assertNotNull(BattleStateStore.getSnapshot())
        assertEquals(BattleSessionManager.State.PLAYING, guest.state)
    }

    /**
     * 代数变化的 start_game 属于真正的新局（含棋桌重建后从 1 重新计数）：
     * 上一局落盘快照与内存对账基准必须作废，否则新打开的 GameActivity 会恢复旧棋局。
     */
    @Test
    fun `changed gen start_game discards snapshot and sync cache`() {
        BattleStateStore.clear()
        val (hostEnd, guestEnd) = LoopbackTransport.createPair()
        val host = BattleSessionManager({ hostEnd })
        host.injectDebugState("888888", "ht", BattleMessage.COLOR_RED, host = true)
        val guest = BattleSessionManager({ guestEnd })
        val rec = Rec()
        guest.listener = rec
        BattleStateStore.saveSession(
            "888888", "gt", BattleMessage.COLOR_BLACK, false, false, tableNo = 5)
        BattleStateStore.saveProgress(3, 2, 2)
        BattleStateStore.saveSnapshot(
            "fen-old", listOf("h2e2", "h9e7"), BattleMessage.COLOR_RED)
        guest.restoreSession(
            "888888", "gt", BattleMessage.COLOR_BLACK, false, false, tableNo = 5)
        idle(200)
        guest.sendSyncState("fen-sync", listOf("h2e2"), BattleMessage.COLOR_RED)
        idle(100)
        assertEquals("fen-sync", guest.lastSyncFen)

        // 棋桌重建后新桌从 gen=1 重新计数（比旧值 3 小），同样必须视为新局
        hostEnd.send("""{"type":"start_game","red":"甲","black":"乙","color":"black","gen":1}""")
        idle(200)

        assertEquals(1, rec.gameStartCount)
        assertNull(rec.resumedColor)
        assertNull(BattleStateStore.getSnapshot())
        assertEquals("", guest.lastSyncFen)
        // 新局走子序号归零：双方都从此对齐，避免旧局序号把新着法判重
        assertEquals(0, BattleStateStore.loadProgress().mySeq)
        assertEquals(0, BattleStateStore.loadProgress().oppSeq)
        assertEquals(BattleSessionManager.State.PLAYING, guest.state)
    }
}
