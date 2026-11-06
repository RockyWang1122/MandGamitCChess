package com.mandgamit.chess.controllers;

import com.mandgamit.chess.battle.BattleStateStore;
import com.mandgamit.chess.battle.BattleSessionManager;
import com.mandgamit.chess.battle.net.BattleMessage;
import com.mandgamit.chess.gamelogic.Game;
import com.mandgamit.chess.gamelogic.GameStatus;
import com.mandgamit.chess.gamelogic.Move;
import com.mandgamit.chess.gamelogic.Position;
import com.mandgamit.chess.gamelogic.RulesetProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 好友对战控制器：本地走子经 BattleSessionManager 发送给对方，
 * 对方走子由 injectRemoteMove 注入。不启动引擎（startEngine=false），
 * 无后台分析/局面评估；悔棋/求和需双方协商（经会话消息），单方重开禁用。
 *
 * 状态模型：己方回合 = WAITING_FOR_USER（复用父类触摸/云库走子路径），
 * 对方回合 = WAITING_FOR_NETWORK（天然屏蔽本地触摸）；断线 = NET_DISCONNECTED（冻结）。
 *
 * 注意：必须与 GameController 同包（ControllerState 枚举包私有）。
 */
public class NetworkGameController extends GameController {

    protected final BattleSessionManager session;
    /** 我方执子颜色：true=红，false=黑。入座时定，每局"再来一局"后交换。 */
    private boolean myColorRed;

    /**
     * 父类构造器会虚调用 startNewGame()，此标记区分构造期的正常初始化与
     * 对局中用户点击"新对局"（后者在网络模式被禁用）。
     */
    private boolean constructed = false;

    public NetworkGameController(ControllerListener cListener,
                                 BattleSessionManager session,
                                 boolean myColorRed) {
        super(cListener, false); // 不起引擎、不开开局库
        this.session = session;
        this.myColorRed = myColorRed;
        // 网络模式无引擎：双方均由人类走子，修正父类默认 enginePlaysBlack=true
        // 否则黑方回合 isHumanTurn() 返回 false，云库着法被判定不可点击
        this.enginePlaysRed = false;
        this.enginePlaysBlack = false;
        this.constructed = true;
        // 父类构造期间 myColorRed 尚未赋值，状态按默认色算错了，此处重算。
        // 注意：此处不能回调 gui.onGameEvent(UPDATEUI)——Activity 的 controller 字段
        // 要等构造返回后才赋值，构造期回调会触发 lateinit 未初始化崩溃；
        // 首次 UI 刷新由 GameActivity.onCreate 末尾的 driveCurrentTurn() 完成。
        setStateByTurn();
    }

    public boolean isMyColorRed() {
        return myColorRed;
    }

    /** 当前是否我方回合（供 UI 显示"等待对方走子"等提示）。 */
    public synchronized boolean isWaitingOpponent() {
        return state == ControllerState.WAITING_FOR_NETWORK;
    }

    // ==================== 状态机覆写 ====================

    @Override
    protected void setStateByColor(boolean redToMove) {
        state = (redToMove == myColorRed)
                ? ControllerState.WAITING_FOR_USER
                : ControllerState.WAITING_FOR_NETWORK;
    }

    /** 网络模式无引擎回合、无后台分析：仅按轮走方设置状态并刷新 UI。 */
    @Override
    public void driveCurrentTurn() {
        synchronized (this) {
            if (game == null || game.isGameOver) {
                return;
            }
            setStateByTurn();
        }
        gui.onGameEvent(GameStatus.UPDATEUI);
    }

    /**
     * 网络模式的关键覆写：父类 doMoveAndUpdateStatus 末尾无条件调用本方法走
     * 引擎评估闭环（WAITING_FOR_EVAL → notifyEvalResult → 延迟推进），
     * 本模式无引擎进程，闭环永远不会返回——置空后状态推进完全由
     * toggleTurn() → setStateByTurn() 完成。
     */
    @Override
    public synchronized void evalCurrentBoard() {
        // 好友对战：不做引擎局面评估
    }

    // ==================== 本地走子 → 发送 ====================

    @Override
    public void touchPosition(Position pos) {
        if (waitingRematchAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待对方确认新一局…");
            return;
        }
        if (waitingUndoAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待对方回复悔棋请求…");
            return;
        }
        int before = game != null ? game.history.size() : 0;
        super.touchPosition(pos);
        sendLastMoveIfNew(before);
    }

    /** 云库点击走子与手动走子等价：同样转发给对方。 */
    @Override
    public synchronized void playerMoveByCloud(String ucci) {
        if (waitingRematchAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待对方确认新一局…");
            return;
        }
        if (waitingUndoAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待对方回复悔棋请求…");
            return;
        }
        int before = game != null ? game.history.size() : 0;
        super.playerMoveByCloud(ucci);
        sendLastMoveIfNew(before);
    }

    private void sendLastMoveIfNew(int historySizeBefore) {
        if (game == null || game.history.size() != historySizeBefore + 1) {
            return;
        }
        // 走子成立即落盘：进程随时可能被系统回收，冷启动后凭此快照恢复棋局
        persistSnapshot();
        String ucci = game.history.get(game.history.size() - 1).ucciString;
        if (!session.sendMove(ucci)) {
            gui.onGameEvent(GameStatus.ILLEGAL, "连接已断开，走子未发送");
        }
    }

    // ==================== 对方走子注入 / 对账 ====================

    /** 收到对方走子（主线程，由 GameActivity 从 BattleListener 转发）。 */
    public synchronized void injectRemoteMove(String ucci) {
        if (game == null || game.isGameOver) {
            return;
        }
        if (state != ControllerState.WAITING_FOR_NETWORK) {
            // 状态不符（可能断线重连后局面已分叉）：走对账流程
            session.requestSync();
            return;
        }
        Move move = new Move(game.currentBoard);
        if (!move.fromUCCIString(ucci)
                || !RulesetProvider.get().isMoveLegal(move, game.currentBoard)) {
            // 对方着法在本地不合法：局面可能分叉，请求对账而不是直接判负
            session.requestSync();
            return;
        }
        game.setStartPos(move.fromPosition);
        game.setEndPos(move.toPosition);
        // doMoveAndUpdateStatus → toggleTurn 要求 state ∈ {WAITING_FOR_USER, WAITING_FOR_ENGINE}
        state = ControllerState.WAITING_FOR_USER;
        doMoveAndUpdateStatus(null);
        persistSnapshot();
    }

    /**
     * 收到对方 sync_state 快照：以着法列表更长方为准。
     * 本地不短于对方 → 回推本地快照；本地落后 → 按对方快照重建。
     */
    public synchronized void applySyncState(String fen, List<String> moves, String turn) {
        if (game != null && game.isGameOver) {
            // 对局已结束（认输/绝杀/和棋）：套用任何快照都会"复活"已终局棋盘
            return;
        }
        int localPlies = game != null ? game.history.size() : 0;
        if (localPlies == moves.size()) {
            // 长度一致即视为已一致，绝不能回推 sync_state：
            // 对方收到后同样会走"回推"分支，两端会陷入 sync_state 互相回推的死循环。
            // 该循环持续发送的旧快照（着法更长的）会在悔棋把本地历史缩短后到达，
            // 触发 rebuildFromMoves 把整局重放（表现为棋子疯狂走很多步并覆盖悔棋结果）。
            return;
        }
        if (localPlies > moves.size()) {
            // 本地局面更完整：以本地为准回推，让对方追平（对方追平后即静默）
            pushLocalSync();
            return;
        }
        rebuildFromMoves(fen, moves);
        persistSnapshot();
    }

    /**
     * 进程重建（或 Activity 被系统回收重建）后，从本地落盘快照静默恢复棋局。
     * 必须在界面组件初始化完成后调用（内部会通知 UPDATEUI）。
     */
    public synchronized void restoreLocalSnapshot(String fen, List<String> moves) {
        if (game == null || fen == null || fen.isEmpty()
                || moves == null || moves.isEmpty()) {
            return;
        }
        rebuildFromMoves(fen, moves);
    }

    /** 对方请求对账（request_sync）：回推本地快照。 */
    public synchronized void pushLocalSync() {
        if (game == null) {
            return;
        }
        List<String> moves = new ArrayList<>();
        for (Game.HistoryRecord r : game.history) {
            moves.add(r.ucciString);
        }
        session.sendSyncState(initialFen(), moves,
                isRedTurn() ? BattleMessage.COLOR_RED : BattleMessage.COLOR_BLACK);
    }

    /** 按快照静默重建局面（eval 已空操作，天然无引擎副作用）。 */
    private void rebuildFromMoves(String fen, List<String> moves) {
        super.startFENGame(fen); // 内部 setStateByTurn + UPDATEUI
        for (String ucci : moves) {
            Move move = new Move(game.currentBoard);
            if (!move.fromUCCIString(ucci)
                    || !RulesetProvider.get().isMoveLegal(move, game.currentBoard)) {
                break; // 快照自身异常：截断，以已成功重放的部分为准
            }
            game.setStartPos(move.fromPosition);
            game.setEndPos(move.toPosition);
            state = ControllerState.WAITING_FOR_USER; // 满足 toggleTurn 白名单
            doMoveAndUpdateStatus(null);
        }
    }

    /** 将当前局面（FEN+全部着法+轮走方）落盘，供进程被杀后冷启动恢复。 */
    private void persistSnapshot() {
        if (game == null) {
            return;
        }
        try {
            List<String> moves = new ArrayList<>();
            for (Game.HistoryRecord r : game.history) {
                moves.add(r.ucciString);
            }
            // FEN 必须是着法列表的起点（好友对战恒为标准初始局面），
            // 恢复时与网络对账一致：从初始 FEN 重放全部着法得到当前局面
            BattleStateStore.saveSnapshot(
                    initialFen(),
                    moves,
                    isRedTurn() ? BattleMessage.COLOR_RED : BattleMessage.COLOR_BLACK);
        } catch (Throwable t) {
            // 落盘失败不影响对局：内存局面仍在，只是无法跨进程恢复
        }
    }

    /** 初始局面 FEN（好友对战恒从初始局面开局）。旁观覆写 rematchBoard 也用。 */
    protected static String initialFen() {
        Game g = new Game(true);
        return g.currentBoard.toFENString();
    }

    // ==================== 禁用的单方操作 ====================

    /** 悔棋改为双方协商：stepBack/playerUndo 均走悔棋请求流程。 */
    @Override
    public synchronized boolean stepBack() {
        requestUndo();
        return waitingUndoAck;
    }

    @Override
    public synchronized void playerUndo() {
        requestUndo();
    }

    // ==================== 悔棋协商 ====================

    /** 已发悔棋请求、等待对方回复期间锁定走子。 */
    private boolean waitingUndoAck = false;

    public synchronized boolean isWaitingUndoAck() {
        return waitingUndoAck;
    }

    /** 我方请求悔棋：仅在己方回合且已有完整一回合（我方一着+对方一着）时允许。 */
    public synchronized void requestUndo() {
        if (game == null || game.isGameOver) {
            gui.onGameEvent(GameStatus.ILLEGAL, "对局已结束，无法悔棋");
            return;
        }
        if (waitingRematchAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待对方确认新一局…");
            return;
        }
        if (waitingUndoAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "悔棋请求已发送，等待对方回复…");
            return;
        }
        if (state != ControllerState.WAITING_FOR_USER) {
            gui.onGameEvent(GameStatus.ILLEGAL, "请轮到你走棋时再悔棋");
            return;
        }
        if (game.history.size() < 2) {
            gui.onGameEvent(GameStatus.ILLEGAL, "开局无棋可悔");
            return;
        }
        waitingUndoAck = true;
        session.sendUndoRequest();
        gui.onGameEvent(GameStatus.UPDATEUI, "悔棋请求已发送，等待对方回复…");
    }

    /** 对方同意悔棋（我方为请求方）：撤两步回到我方走子前。 */
    public synchronized void onUndoAccepted() {
        waitingUndoAck = false;
        if (rollBackTwoPlies()) {
            gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
        }
    }

    /** 对方拒绝悔棋（我方为请求方）：继续比赛。 */
    public synchronized void onUndoDeclined() {
        waitingUndoAck = false;
        gui.onGameEvent(GameStatus.UPDATEUI);
    }

    /** 我方同意对方悔棋：先回退本地局面（撤两步，回到请求方走子前）。 */
    public synchronized void acceptUndo() {
        waitingUndoAck = false;
        if (rollBackTwoPlies()) {
            gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
        }
    }

    /**
     * 收到对方悔棋请求时判断是否双方同时请求（互为同意）：
     * 返回 true 表示已本地直接回退，无需再弹确认框。
     */
    public synchronized boolean onUndoRequestMutualIfPending() {
        if (waitingUndoAck) {
            waitingUndoAck = false;
            if (rollBackTwoPlies()) {
                gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
            }
            return true;
        }
        return false;
    }

    /**
     * 撤回最近一回合（请求方一着 + 对方一着，共两步），局面回到轮请求方走棋。
     * 双方着法历史一致，各自本地撤两步即收敛，无需传 FEN。
     */
    private boolean rollBackTwoPlies() {
        if (game == null || game.history.size() < 2) {
            return false;
        }
        game.undoMove(); // 撤掉对方的应对
        game.undoMove(); // 撤掉我方上一走子
        setStateByTurn();
        persistSnapshot();
        return true;
    }

    /** 网络模式不可单方重开（构造期父类初始化除外）。 */
    @Override
    public synchronized void startNewGame() {
        if (!constructed) {
            super.startNewGame();
            return;
        }
        gui.onGameEvent(GameStatus.ILLEGAL, "好友对战中不可重开，退出后可重新建房");
    }

    /** 网络模式不调引擎 shutdown（引擎从未启动）；会话生命周期由 GameActivity 管理。 */
    @Override
    public void shutdown() {
        // no-op
    }

    // ==================== 认输 / 求和 / 再来一局 / 断线 ====================

    /** 我方认输：通知对方并本地判负。等待对方确认新一局时不可认输（双方都就绪才算新一局开始）。 */
    public synchronized void resign() {
        if (waitingRematchAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待对方确认新一局…");
            return;
        }
        session.sendResign();
        if (game != null) {
            game.isGameOver = true;
        }
        gui.onGameEvent(GameStatus.LOSE, "已认输");
    }

    /** 对方认输：本地判胜。 */
    public synchronized void onOpponentResign() {
        if (game != null) {
            game.isGameOver = true;
        }
        gui.onGameEvent(GameStatus.WIN, "对方认输，你赢了！");
    }

    /**
     * 我方请求求和（结果经对方同意/拒绝后由 Activity 回调 onDrawAccepted/onDrawDeclined）。
     * 等待对方确认新一局时不可求和（双方都就绪才算新一局开始）。
     */
    public synchronized void offerDraw() {
        if (waitingRematchAck) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待对方确认新一局…");
            return;
        }
        session.sendDrawOffer();
    }

    /**
     * 求和成立（我方为提议方收到同意，或同意对方提议时由 Activity 直接调用）：
     * 和棋成立，标记对局结束，由 Activity 弹出"新一局/退出"对话框（与认输同流程）。
     */
    public synchronized void onDrawAccepted() {
        if (game != null) {
            game.isGameOver = true;
        }
        gui.onGameEvent(GameStatus.DRAW, "和棋成立");
    }

    /**
     * 再来一局：重置为初始局面、红方先行。
     *
     * 双方确认机制（new_game 既是请求也是确认，靠 peerRequestedRematch 区分）：
     *  - 本方点击"新一局"：若对方此前已请求（peerRequestedRematch=true）→ 双方一致，
     *    本方直接解锁并回发 new_game 通知对方解锁；
     *    否则重置棋盘并置 waitingRematchAck=true 锁定，发送 new_game 等待对方点击确认。
     *  - 收到对方 new_game：
     *      * 本方在等待（waitingRematchAck=true）：对方这条就是确认 → 解锁本方，不回发
     *        （对方已在点击时自行解锁）；
     *      * 本方未确认：对方先发起 → 仅同步重置本方棋盘并记 peerRequestedRematch=true，
     *        不回发 new_game（不能替本方确认！本方点"新一局"时才达成一致）。
     */
    private boolean waitingRematchAck = false;
    private boolean peerRequestedRematch = false;

    /** 本方点击新一局：对方已请求则双方一致直接开局，否则锁定等待对方确认。 */
    public synchronized void requestRematch() {
        // 必须先取标记再重置棋盘：rematchBoard 会清掉 peerRequestedRematch，顺序反了永远走锁定分支
        boolean peerAlreadyRequested = peerRequestedRematch;
        rematchBoard();
        if (peerAlreadyRequested) {
            // 对方已先发起并等待：本方点击即达成一致，回发 new_game 通知对方解锁
            waitingRematchAck = false;
            session.sendNewGame();
            gui.onGameEvent(GameStatus.UPDATEUI);
        } else {
            waitingRematchAck = true;
            session.sendNewGame();
            gui.onGameEvent(GameStatus.UPDATEUI, "等待对方确认新一局…");
        }
    }

    /** 收到对方 new_game：本方在等 → 对方确认到达解锁本方；本方未确认 → 仅同步重置+记标记。 */
    public synchronized void onRemoteNewGame() {
        if (waitingRematchAck) {
            // 本方已确认在等对方：对方这条就是"对方也确认了"，解锁本方（对方点击时已自行解锁，不回发）
            waitingRematchAck = false;
            peerRequestedRematch = false;
            gui.onGameEvent(GameStatus.UPDATEUI);
        } else {
            // 对方先发起：同步重置本方棋盘并记录"对方已请求"，等本方在对话框点"开始新一局"才算一致
            // 注意：先 rematchBoard 再置标记——rematchBoard 会清掉该标记，顺序反了会丢"对方已请求"状态
            rematchBoard();
            peerRequestedRematch = true;
            gui.onGameEvent(GameStatus.UPDATEUI);
        }
    }

    public synchronized boolean isWaitingRematchAck() {
        return waitingRematchAck;
    }

    /**
     * 再来一局的棋盘重置：重置为初始局面、红方先行。
     * 先后手交换由服务端仲裁（双方各发一次 new_game 达成后服务端翻转
     * hostPlaysRed 并分帧重发 start_game 带本人颜色）——客户端不自行换色，
     * 收到 start_game 后经 {@link #applyServerStart} 更新颜色再走本方法。
     * WatchGameController（旁观端）覆写本方法：旁观无颜色概念，仅重开棋盘。
     */
    protected void rematchBoard() {
        // 走 startFENGame 而非 startNewGame：后者按本地设置的红先黑先开局面，
        // 好友对战恒定红方先行；startFENGame 内部会 new Game(true) 并通知 UPDATEUI
        super.startFENGame(initialFen());
        // 对局代数不在此自增：代数唯一权威在服务端 start_game.gen（见
        // BattleSessionManager），客户端各自点"新一局"的本地协商若也自增，
        // 两端代数会漂移，重连对账会被误判为旧局而丢弃。
        // 仅清会话内缓存的旧局面基准（不可再用于恢复）。
        session.clearSyncCache();
        waitingRematchAck = false;
        peerRequestedRematch = false;
        waitingUndoAck = false;
        persistSnapshot();
    }

    /**
     * 服务端仲裁达成新局（收到 start_game 且带本人颜色）：更新执子色后重置棋盘。
     * red 为 null 表示旁观（无颜色概念），仅重置。颜色以服务端为准，
     * 本方法是把服务端颜色同步进控制器的唯一入口。
     */
    public synchronized void applyServerStart(Boolean red) {
        if (red != null) {
            myColorRed = red;
        }
        rematchBoard();
        gui.onGameEvent(GameStatus.UPDATEUI);
    }

    /**
     * 断线重连恢复：start_game 只是同一局的补发（代数未变），只同步服务端执子色，
     * 不重置棋盘——本地棋盘已由落盘快照恢复，重置会清空着法并覆盖写回快照。
     */
    public synchronized void applyServerColor(boolean red) {
        myColorRed = red;
        gui.onGameEvent(GameStatus.UPDATEUI);
    }

    /** 断线冻结（GameActivity 在 session DISCONNECTED 时调用）。 */
    public synchronized void onConnectionLost() {
        if (state != ControllerState.NET_DISCONNECTED) {
            state = ControllerState.NET_DISCONNECTED;
            gui.onGameEvent(GameStatus.UPDATEUI);
        }
    }

    /** 重连恢复：按当前轮走方重算状态。 */
    public synchronized void onConnectionRestored() {
        if (state == ControllerState.NET_DISCONNECTED) {
            setStateByTurn();
            gui.onGameEvent(GameStatus.UPDATEUI);
        }
    }
}
