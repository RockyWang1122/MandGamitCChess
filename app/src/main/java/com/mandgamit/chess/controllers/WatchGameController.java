package com.mandgamit.chess.controllers;

import com.mandgamit.chess.battle.BattleSessionManager;
import com.mandgamit.chess.gamelogic.Game;
import com.mandgamit.chess.gamelogic.GameStatus;
import com.mandgamit.chess.gamelogic.Move;
import com.mandgamit.chess.gamelogic.RulesetProvider;

import java.util.List;

/**
 * 观战控制器：只接收远程走子并本地回放，禁止一切本地操作。
 */
public class WatchGameController extends NetworkGameController {

    public WatchGameController(ControllerListener cListener,
                               BattleSessionManager session) {
        super(cListener, session, true); // 视角默认红方在下
    }

    // 始终为网络等待态，屏蔽本地触摸
    @Override
    protected void setStateByColor(boolean redToMove) {
        state = ControllerState.WAITING_FOR_NETWORK;
    }

    @Override
    public void touchPosition(com.mandgamit.chess.gamelogic.Position pos) {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式，不能走子");
    }

    @Override
    public synchronized void playerMoveByCloud(String ucci) {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式，不能走子");
    }

    @Override
    public synchronized boolean stepBack() {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式，不能悔棋");
        return false;
    }

    @Override
    public synchronized void playerUndo() {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式，不能悔棋");
    }

    @Override
    public synchronized void requestUndo() {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式，不能悔棋");
    }

    @Override
    public synchronized void resign() {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式");
    }

    @Override
    public synchronized void offerDraw() {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式");
    }

    @Override
    public synchronized void requestRematch() {
        gui.onGameEvent(GameStatus.ILLEGAL, "观战模式");
    }

    /** 走子到达时直接应用（不校验回合方，不触发对账）。 */
    @Override
    public synchronized void injectRemoteMove(String ucci) {
        if (game == null || game.isGameOver) return;
        Move move = new Move(game.currentBoard);
        if (!move.fromUCCIString(ucci)
                || !RulesetProvider.get().isMoveLegal(move, game.currentBoard)) {
            return;
        }
        game.setStartPos(move.fromPosition);
        game.setEndPos(move.toPosition);
        state = ControllerState.WAITING_FOR_USER; // 满足 toggleTurn 白名单
        doMoveAndUpdateStatus(null);
        state = ControllerState.WAITING_FOR_NETWORK; // 恢复只读
    }

    /** 重放观战快照。 */
    public synchronized void applyWatchMoves(List<String> moves) {
        Game g = new Game(true);
        restoreLocalSnapshot(g.currentBoard.toFENString(), moves);
    }

    /**
     * 旁观端新一局重置：对弈双方确认再来一局后收到 new_game 转发走到这里。
     * 覆写父类版本——旁观无颜色概念（不交换先后手），也不落盘快照
     * （观战局面以 watch_ok 携带的走子缓存为准，落盘会让冷启动误判对局进行中）。
     * 仅重开棋盘（红先）并清空旧局缓存；对局代数由服务端 start_game.gen 下发，
     * 不在本地自增（避免与对弈双方端漂移，旁观端也不参与对账）。
     */
    @Override
    protected synchronized void rematchBoard() {
        super.startFENGame(initialFen());
        session.clearSyncCache();
    }

    @Override
    public synchronized void onConnectionLost() {
        if (state != ControllerState.NET_DISCONNECTED) {
            state = ControllerState.NET_DISCONNECTED;
            gui.onGameEvent(GameStatus.UPDATEUI);
        }
    }

    @Override
    public synchronized void onConnectionRestored() {
        if (state == ControllerState.NET_DISCONNECTED) {
            state = ControllerState.WAITING_FOR_NETWORK;
            gui.onGameEvent(GameStatus.UPDATEUI);
        }
    }
}
