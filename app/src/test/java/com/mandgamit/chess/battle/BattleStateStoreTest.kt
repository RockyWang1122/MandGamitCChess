package com.mandgamit.chess.battle

import com.mandgamit.chess.battle.net.BattleMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 房间身份与对局快照的落盘/恢复测试：进程被杀后冷启动即依赖这些数据重连回房。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BattleStateStoreTest {

    @Test
    fun `session identity persists and reports active game`() {
        BattleStateStore.clear()
        assertFalse(BattleStateStore.hasActiveGame())

        // 建房成功但尚未开局：不应被判定为可直接进入对局页
        BattleStateStore.saveSession(
            room = "123456", token = "tok-host",
            color = BattleMessage.COLOR_RED, isHost = true, gameStarted = false)
        assertFalse(BattleStateStore.hasActiveGame())
        var info = BattleStateStore.loadSession()
        assertNotNull(info)
        assertEquals("123456", info!!.room)
        assertEquals("tok-host", info.token)
        assertEquals(BattleMessage.COLOR_RED, info.color)
        assertTrue(info.isHost)

        // 开局后：冷启动应直达对局页
        BattleStateStore.saveSession(
            room = "123456", token = "tok-host",
            color = BattleMessage.COLOR_RED, isHost = true, gameStarted = true)
        assertTrue(BattleStateStore.hasActiveGame())
        info = BattleStateStore.loadSession()
        assertTrue(info!!.gameStarted)
    }

    @Test
    fun `snapshot persists fen moves and turn`() {
        BattleStateStore.clear()
        assertNull(BattleStateStore.getSnapshot())

        BattleStateStore.saveSnapshot(
            fen = "fen-12",
            moves = listOf("h2e2", "h9e7", "b2e2"),
            turn = BattleMessage.COLOR_RED)
        val snap = BattleStateStore.getSnapshot()
        assertNotNull(snap)
        assertEquals("fen-12", snap!!.fen)
        assertEquals(listOf("h2e2", "h9e7", "b2e2"), snap.moves)
        assertEquals(BattleMessage.COLOR_RED, snap.turn)
    }

    @Test
    fun `clearSnapshot keeps session identity`() {
        BattleStateStore.clear()
        BattleStateStore.saveSession(
            "123456", "tok", BattleMessage.COLOR_BLACK, false, false, tableNo = 3)
        BattleStateStore.saveSnapshot("fen", listOf("h2e2"), BattleMessage.COLOR_BLACK)

        BattleStateStore.clearSnapshot()
        assertNull(BattleStateStore.getSnapshot())
        // 座位身份必须保留，否则对局结束后仍坐在桌上的一方将无法重连回座
        val info = BattleStateStore.loadSession()
        assertNotNull(info)
        assertEquals("123456", info!!.room)
        assertEquals(3, info.tableNo)
    }

    @Test
    fun `clear removes identity and snapshot`() {
        BattleStateStore.saveSession("111111", "t", BattleMessage.COLOR_BLACK, false, true)
        BattleStateStore.saveSnapshot("fen", listOf("h2e2"), BattleMessage.COLOR_BLACK)
        assertTrue(BattleStateStore.hasActiveGame())

        BattleStateStore.clear()
        assertFalse(BattleStateStore.hasActiveGame())
        assertNull(BattleStateStore.loadSession())
        assertNull(BattleStateStore.getSnapshot())
    }

    @Test
    fun `empty room or token is not persisted`() {
        BattleStateStore.clear()
        BattleStateStore.saveSession("", "t", BattleMessage.COLOR_RED, true, true)
        assertNull(BattleStateStore.loadSession())
        BattleStateStore.saveSession("123456", null, BattleMessage.COLOR_RED, true, true)
        assertNull(BattleStateStore.loadSession())
    }
}
