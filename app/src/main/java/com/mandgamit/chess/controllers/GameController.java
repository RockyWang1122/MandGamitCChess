package com.mandgamit.chess.controllers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mandgamit.chess.ChessApp;
import com.mandgamit.chess.Settings;
import com.mandgamit.chess.gamelogic.Board;
import com.mandgamit.chess.gamelogic.Game;
import com.mandgamit.chess.gamelogic.GameStatus;
import com.mandgamit.chess.gamelogic.Move;
import com.mandgamit.chess.gamelogic.Piece;
import com.mandgamit.chess.gamelogic.Position;
import com.mandgamit.chess.gamelogic.PvInfo;
import com.mandgamit.chess.gamelogic.Rule;
import com.mandgamit.chess.gamelogic.RulesetProvider;
import com.mandgamit.chess.openbook.BHOpenBook;
import com.mandgamit.chess.openbook.BookData;
import com.mandgamit.chess.openbook.OpenBook;

import org.jetbrains.annotations.NotNull;
import org.petero.droidfish.player.ComputerPlayer;
import org.petero.droidfish.player.EngineListener;
import org.petero.droidfish.player.SearchListener;
import org.petero.droidfish.player.SearchRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GameController implements EngineListener, SearchListener {
    public ComputerPlayer player = null;
    private String engineName = "pikafish";
    public String engineInfo; // returned by engine

    // 如果开启了电脑的着法随机选项，那么前N步会随机选择一个着法。这个数值不易过大，否则电脑棋力下降太多
    private final int random_before_max_rounds = 12;

    public Game game = null;
    private int searchId;
    private long searchStartTime;
    private long searchEndTime;

    public boolean isComputerPlaying = true;
    public boolean isAutoPlay = true;

    // 对局模式：哪一方由引擎走棋（双方都true=引擎对引擎，双方都false=玩家对玩家）
    public boolean enginePlaysRed = false;
    public boolean enginePlaysBlack = true;

    public boolean isShowTrends = false;

    /** 本局起始 FEN（FEN 开局成功后记录，标准开局为 null）；导出棋谱用 */
    public String startFen = null;

    protected ControllerListener gui = null;
    ArrayList<PvInfo> multiPVs = new ArrayList<>();
    // 最近一次多PV弹窗是否来自"玩家寻求帮助"（取消时恢复分析）；false表示"引擎变着"（取消时接受第一着）
    public boolean lastMultiPVForHuman = false;

    // 引擎思考详情（供界面对弈页"思考"面板展示）
    public int thinkDepth = 0;
    public long thinkNodes = 0;
    public int thinkTime = 0;
    public ArrayList<String> thinkPvMoves = new ArrayList<>();
    // 与thinkPvMoves一一对应：该着法是否为红方
    public ArrayList<Boolean> thinkPvIsRed = new ArrayList<>();
    // 与thinkPvMoves一一对应：UCCI着法，供"思考细节推演"小棋盘回放
    public ArrayList<String> thinkPvUcci = new ArrayList<>();
    private PvInfo latestPv = null;

    BHOpenBook bhBook = null;

    public ControllerState state;
    public ControllerState preEvalState;

    public Settings settings = null;

    public void toggleShowTrends() {
        isShowTrends = !isShowTrends;
    }

    // create enum for controller state
    enum ControllerState {
        IDLE,
        WAITING_FOR_USER, // 当前轮走方由玩家出子
        WAITING_FOR_USER_MULTIPV, // 玩家寻求帮助，等待多PV结果
        WAITING_FOR_USER_ANALYZING, // 玩家回合，引擎后台分析中（go infinite）
        WAITING_FOR_ENGINE, // 当前轮走方由引擎出子
        WAITING_FOR_ENGINE_BESTMV, // 引擎寻找最佳着法
        WAITING_FOR_ENGINE_MULTIPV, // 引擎变着，等待多PV结果
        WAITING_FOR_EVAL, // 等待eval的结果
        MANUAL_MODE, // 打谱模式
        WAITING_FOR_NETWORK, // 好友对战：等待对方网络着法（NetworkGameController）
        NET_DISCONNECTED, // 好友对战：断线冻结，等待重连
    }

    public GameController(ControllerListener cListener) {
        this(cListener, true);
    }

    /**
     * @param startEngine 是否立即启动引擎子进程。
     *                    打谱等不需要引擎分析的场景传 false，避免每次打开棋谱都启动一个
     *                    pikafish 进程（约200MB native内存）且退出时不释放，导致多次打开后手机变卡。
     */
    protected GameController(ControllerListener cListener, boolean startEngine) {
        gui = cListener;

        isComputerPlaying = true;
        isAutoPlay = true;
        searchId = 0;

        settings = new Settings(ChessApp.getContext());

        // Initialize computer player
        if (player == null) {
            player = new ComputerPlayer(this, this, this);
        }
        if (startEngine) {
            // 开局库仅引擎对弈时使用；打谱模式无需打开，避免每次打开棋谱都开一个 SQLite 连接
            try {
                bhBook = new BHOpenBook(ChessApp.getContext());
            } catch (Throwable t) {
                // 开局库资源缺失（如开源构建不内置桔库）时降级为纯引擎对弈，不阻塞启动
                Log.w("GameController", "openbook init failed", t);
                bhBook = null;
            }
            // 未启动引擎时 stopSearch()/uciNewGame()（startNewGame内调用）对 DEAD 状态是安全空操作
            player.queueStartEngine(searchId++, engineName);
        }
        startNewGame();
    }

    /** 释放引擎子进程等资源，应在 Activity.onDestroy 中调用 */
    public void shutdown() {
        Log.d("GameController", "shutdown: stopping engine process");
        if (player != null) {
            player.shutdownEngine();
        }
    }

    public synchronized void startNewGame() {
        Log.d("GameController", "New game");
        game = new Game(settings.getRed_go_first());
        startFen = null;
        setStateByTurn();
        player.stopSearch();
        player.uciNewGame();

        // 重置思考详情
        thinkDepth = 0;
        thinkNodes = 0;
        thinkTime = 0;
        thinkPvMoves.clear();
        thinkPvIsRed.clear();
        thinkPvUcci.clear();
        latestPv = null;
    }

    public synchronized void startFENGame(String fen) {
        Log.d("GameController", "New FEN game" + fen);
        Game tempGame = new Game(true);
        boolean result = tempGame.currentBoard.restoreFromFEN(fen);
        if (!result) {
            Log.e("GameController", "Failed to restore from FEN: " + fen);
            gui.onGameEvent(GameStatus.ILLEGAL, "无效的FEN串");
            return;
        }

        game = tempGame;
        startFen = fen;
        setStateByTurn();
        game.history.clear();
        game.startPos = null;
        game.endPos = null;
        gui.onGameEvent(GameStatus.UPDATEUI, "从FEN开局");
        player.stopSearch();
        player.uciNewGame();
    }

    public synchronized void toggleComputer() {
        isComputerPlaying = !isComputerPlaying;
    }

    public synchronized void toggleComputerAutoPlay() {
        isAutoPlay = !isAutoPlay;
    }

    /** 当前是否轮到红方走（以棋盘为准，与该方是玩家还是引擎无关） */
    public boolean isRedTurn() {
        return game != null && game.currentBoard.bRedGo;
    }

    public boolean isBlackTurn() {
        return !isRedTurn();
    }

    /** 当前轮走方是否由引擎/玩家走 */
    public boolean isEngineToMove() {
        return isRedTurn() ? enginePlaysRed : enginePlaysBlack;
    }

    public boolean isHumanTurn() {
        return !isEngineToMove();
    }

    /** 玩家回合的后台分析是否进行中 */
    public boolean isAnalyzing() {
        return state == ControllerState.WAITING_FOR_USER_ANALYZING;
    }

    /** 引擎是否正忙（搜索/评估/分析），界面据此显示思考指示 */
    public boolean isEngineBusy() {
        return state == ControllerState.WAITING_FOR_ENGINE_BESTMV
                || state == ControllerState.WAITING_FOR_ENGINE_MULTIPV
                || state == ControllerState.WAITING_FOR_USER_MULTIPV
                || state == ControllerState.WAITING_FOR_USER_ANALYZING
                || state == ControllerState.WAITING_FOR_EVAL;
    }

    /** 根据当前局面轮走方与对局模式，设置 WAITING_FOR_USER / WAITING_FOR_ENGINE */
    protected void setStateByTurn() {
        setStateByColor(game.currentBoard.bRedGo);
    }

    /** 按指定轮走方颜色与对局模式设置状态（子类打谱等场景使用） */
    protected void setStateByColor(boolean redToMove) {
        boolean engineMoves = redToMove ? enginePlaysRed : enginePlaysBlack;
        state = engineMoves
                ? ControllerState.WAITING_FOR_ENGINE
                : ControllerState.WAITING_FOR_USER;
    }

    /**
     * 推进当前回合：
     * 引擎回合 → 自动走棋；玩家回合 → 启动后台分析供参考。
     * 在新对局/FEN开局/读档/悔棋/切换对手模式后调用。
     */
    public void driveCurrentTurn() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (GameController.this) {
                if (game == null || game.isGameOver) {
                    return;
                }
                if (state != ControllerState.WAITING_FOR_USER
                        && state != ControllerState.WAITING_FOR_ENGINE) {
                    return;
                }
                setStateByTurn();
                if (isEngineToMove()) {
                    computerForward();
                } else {
                    startPlayerAnalysis();
                }
                gui.onGameEvent(GameStatus.UPDATEUI);
            }
        }, 500);
    }

    /** 切换对局模式：引擎执红 / 引擎执黑 / 引擎对引擎 / 玩家对玩家 */
    public synchronized void setOpponentMode(boolean engineRed, boolean engineBlack) {
        Log.d("GameController", "setOpponentMode: engineRed=" + engineRed + ", engineBlack=" + engineBlack);
        enginePlaysRed = engineRed;
        enginePlaysBlack = engineBlack;
        isComputerPlaying = engineRed || engineBlack;
        // 停止正在进行的搜索/分析，随后按当前局面与新模式重新驱动
        if (state == ControllerState.WAITING_FOR_USER_ANALYZING
                || state == ControllerState.WAITING_FOR_ENGINE_BESTMV
                || state == ControllerState.WAITING_FOR_USER_MULTIPV
                || state == ControllerState.WAITING_FOR_ENGINE_MULTIPV) {
            player.stopSearch();
        }
        driveCurrentTurn();
    }

    /**
     * 玩家回合：引擎在后台无限分析当前局面（go infinite），
     * PV 实时显示在"思考"面板供玩家参考，返回的 bestmove 不落子。
     */
    public synchronized void startPlayerAnalysis() {
        if (state != ControllerState.WAITING_FOR_USER || !isHumanTurn()) {
            return;
        }
        // 引擎对引擎时没有人类玩家，无需后台分析
        if (enginePlaysRed && enginePlaysBlack) {
            return;
        }
        state = ControllerState.WAITING_FOR_USER_ANALYZING;
        thinkDepth = 0;
        thinkNodes = 0;
        thinkTime = 0;
        thinkPvMoves.clear();
        thinkPvIsRed.clear();
        thinkPvUcci.clear();
        latestPv = null;

        Board board0 = game.history.size() == 0
                ? game.currentBoard
                : game.history.get(0).move.board;
        SearchRequest sr = SearchRequest.analysisRequest(
                searchId++,
                board0,
                game.getMoveList(),
                new Board(game.currentBoard),
                engineName);
        player.queueSearchRequest(sr);
    }

    /** 玩家落子前停止后台分析 */
    private void stopPlayerAnalysis() {
        if (state == ControllerState.WAITING_FOR_USER_ANALYZING) {
            player.stopSearch();
            state = ControllerState.WAITING_FOR_USER;
        }
    }

    // this can be called by either GUI or computer
    public synchronized boolean stepBack() {
        // 后台分析中允许悔棋，先停掉分析
        stopPlayerAnalysis();
        // 只在WAITING_FOR_USER或WAITING_FOR_ENGINE状态下才能悔棋
        if (state != ControllerState.WAITING_FOR_USER && state != ControllerState.WAITING_FOR_ENGINE) {
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索中，请稍候...");
            return false;
        }
        if (game.history.size() > 0) {
            game.undoMove();
            setStateByTurn();
            gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
            return true;
        } else {
            gui.onGameEvent(GameStatus.ILLEGAL, "无棋可悔");
            return false;
        }
    }

    /**
     * 玩家悔棋（只允许玩家悔，引擎不悔）：
     * - 轮到玩家、上一着是引擎走的：连悔两步（引擎一着+玩家一着），悔完仍轮到玩家；
     * - 轮到玩家、上一着是玩家走的（玩家对玩家，或引擎尚未应对）：只悔一步；
     * - 轮到引擎但引擎尚未出子：只悔玩家刚走的一步。
     */
    public synchronized void playerUndo() {
        if (state == ControllerState.WAITING_FOR_ENGINE_BESTMV
                || state == ControllerState.WAITING_FOR_ENGINE_MULTIPV
                || state == ControllerState.WAITING_FOR_USER_MULTIPV
                || state == ControllerState.WAITING_FOR_EVAL) {
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索中，请稍候...");
            return;
        }
        stopPlayerAnalysis();
        if (state != ControllerState.WAITING_FOR_USER && state != ControllerState.WAITING_FOR_ENGINE) {
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索中，请稍候...");
            return;
        }
        if (game.history.size() == 0) {
            gui.onGameEvent(GameStatus.ILLEGAL, "无棋可悔");
            return;
        }

        if (isHumanTurn()) {
            // 判断上一着是否由引擎走出
            Game.HistoryRecord last = game.history.get(game.history.size() - 1);
            boolean lastByEngine = last.isRedMove ? enginePlaysRed : enginePlaysBlack;
            if (lastByEngine && game.history.size() >= 2) {
                stepBack(); // 撤掉引擎刚走的一步
                stepBack(); // 撤掉玩家自己的一步
            } else {
                stepBack();
            }
        } else {
            // 引擎回合但引擎尚未出子，仅后退玩家刚走的一步
            stepBack();
        }

        // 悔棋后按新局面继续推进（引擎走或重新分析）
        if (state == ControllerState.WAITING_FOR_ENGINE) {
            computerForward();
        } else if (state == ControllerState.WAITING_FOR_USER) {
            startPlayerAnalysis();
        }
    }

    // computer to play his turn
    public synchronized void computerForward() {
        if (state == ControllerState.WAITING_FOR_USER) {
            gui.onGameEvent(GameStatus.ILLEGAL, "当前是玩家回合");
            return;
        }
        if (state != ControllerState.WAITING_FOR_ENGINE) {
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索中，请稍候或闪电出着");
            return;
        }

        if(settings.getOpenbook()) {
            // search openbook first
            gui.onGameEvent(GameStatus.UPDATEUI, "检索开局库...");

            long vkey = game.currentBoard.getZobrist(isRedTurn());
            List<BookData> bookData = bhBook == null
                    ? null
                    : bhBook.query(vkey, isRedTurn(), OpenBook.SortRule.BEST_SCORE);
            if (bookData != null && bookData.size() > 0) {
                int idx = 0;
                if(game.currentBoard.rounds <= random_before_max_rounds && settings.getRandom_move()) {
                    // 如果在前12步，那么可以随机选择一个着法
                    idx =  (int) (Math.random() * bookData.size());
                }
                Log.d("GameController", "Openbook hit: size = " + bookData.size() + "; bestmove = " + bookData.get(0).getMove() + "; currentMove = " + bookData.get(idx).getMove());
                computerMovePiece(bookData.get(idx).getMove());
                return;
            }
        }

        if (settings.getGo_infinite()) {
            gui.onGameEvent(GameStatus.UPDATEUI, "无限搜索着法中, 须闪电出着!");
        } else {
            gui.onGameEvent(GameStatus.UPDATEUI, "搜索着法中...");
        }
        state = ControllerState.WAITING_FOR_ENGINE_BESTMV;

        // trigger searchrequest, engine will call notifySearchResult for bestmove
        searchStartTime = System.currentTimeMillis();
        Board board = null;
        if (game.history.size() == 0) {
            board = game.currentBoard;
        } else {
            board = game.history.get(0).move.board;
        }
        SearchRequest sr = SearchRequest.searchRequest(
                searchId++,
                board,
                game.getMoveList(),
                new Board(game.currentBoard),
                null,
                false,
                engineName,
                settings.getRandom_move() ? 3 : 1);
        player.queueSearchRequest(sr);
    }

    public synchronized void computerAskForMultiPV() {
        if (state == ControllerState.WAITING_FOR_ENGINE_MULTIPV) {
            gui.onGameEvent(GameStatus.ILLEGAL, "搜索变着中,请稍候或闪电出着");
            return;
        }
        if (state == ControllerState.WAITING_FOR_ENGINE_BESTMV) {
            gui.onGameEvent(GameStatus.ILLEGAL, "电脑正在出着,请稍候或闪电出着");
            return;
        }

        // 玩家回合（含后台分析）：先停分析并悔棋一步，退回到引擎出招前的局面让引擎变着
        if (state == ControllerState.WAITING_FOR_USER_ANALYZING
                || state == ControllerState.WAITING_FOR_USER) {
            stopPlayerAnalysis();
            boolean result = stepBack();
            if (!result) {
                gui.onGameEvent(GameStatus.ILLEGAL, "无棋可变着");
                driveCurrentTurn();
                return;
            }
        }

        // 最后检查目前的状态是否是等待引擎出着
        if (state != ControllerState.WAITING_FOR_ENGINE) {
            Log.d("GameController", "Invalid state, computerAskForMultiPV should not be called now." + state);
            gui.onGameEvent(GameStatus.ILLEGAL, "引擎出场时方可变着");
            return;
        }

        // 开始搜索中
        if (settings.getGo_infinite()) {
            gui.onGameEvent(GameStatus.UPDATEUI, "无限搜索变着中, 须闪电出着!");
        } else {
            gui.onGameEvent(GameStatus.UPDATEUI, "搜索变着中...");
        }
        state = ControllerState.WAITING_FOR_ENGINE_MULTIPV;
        multiPVs.clear();

        // trigger searchrequest, engine will call notifySearchResult for bestmove
        searchStartTime = System.currentTimeMillis();
        Board board = null;
        if (game.history.size() == 0) {
            board = game.currentBoard;
        } else {
            board = game.history.get(0).move.board;
        }
        SearchRequest sr = SearchRequest.searchRequest(
                searchId++,
                board,
                game.getMoveList(),
                new Board(game.currentBoard),
                null,
                false,
                engineName,
                3);
        player.queueSearchRequest(sr);
    }


    public synchronized void evalCurrentBoard() {
        if (!(state == ControllerState.WAITING_FOR_USER || state == ControllerState.WAITING_FOR_ENGINE)) {
            gui.onGameEvent(GameStatus.ILLEGAL, "等待出着时方可评估局面");
            return;
        }

        preEvalState = state;
        state = ControllerState.WAITING_FOR_EVAL;

        // trigger searchrequest, engine will call notifySearchResult for bestmove
        searchStartTime = System.currentTimeMillis();
        Board board = game.currentBoard;
        SearchRequest sr = SearchRequest.evalRequest(
                searchId++,
                board,
                engineName);
        player.queueSearchRequest(sr);
        // result will be returned by notifyEvalResult
    }

    public void touchPosition(@NotNull Position pos) {
        // 仅当轮到人类玩家行棋时才接受棋盘操作
        // （引擎思考、引擎对引擎、寻求提示等状态下忽略一切点击）
        if (state != ControllerState.WAITING_FOR_USER
                && state != ControllerState.WAITING_FOR_USER_ANALYZING) {
            return;
        }

        boolean redToMove = game.currentBoard.bRedGo;
        int clickedPiece = game.currentBoard.getPieceByPosition(pos);
        boolean isOwnPiece = Piece.isValid(clickedPiece)
                && Piece.isRed(clickedPiece) == redToMove;

        if (game.startPos == null) {
            // 尚未选子：点己方棋子才选中；点空白或对方棋子不做任何处理
            if (isOwnPiece) {
                game.setStartPos(pos);
                gui.onGameEvent(GameStatus.SELECT, "选择棋子");
            }
            return;
        }

        // 已选中己方棋子
        if (game.startPos.equals(pos)) {
            // 再次点击当前选中的棋子：保持选中状态
            return;
        }

        if (isOwnPiece) {
            // 点击己方其它棋子：切换选中到新棋子
            game.setStartPos(pos);
            gui.onGameEvent(GameStatus.SELECT, "选择棋子");
            return;
        }

        // 点击空白或对方棋子：判定是否满足行棋规则
        Move tempMove = new Move(game.startPos, pos, game.currentBoard);
        if (RulesetProvider.get().isMoveLegal(tempMove, game.currentBoard)) {
            game.setEndPos(pos);
            // 若后台分析正在进行，先停止（其 bestmove 回调会被忽略）
            if (state == ControllerState.WAITING_FOR_USER_ANALYZING) {
                stopPlayerAnalysis();
            }
            doMoveAndUpdateStatus(null);
        } else {
            // 非法着法：播放错误提示音，但保持当前选中棋子不变
            gui.onGameEvent(GameStatus.ILLEGAL, "非法走法");
        }
    }

    /**
     * 玩家通过云库着法列表走棋：
     * - 仅在人类玩家回合有效（WAITING_FOR_USER / 后台分析中）；
     * - 引擎回合（含引擎搜索中、引擎对引擎）直接忽略，云库点击不能代替引擎走棋；
     * - 玩家对玩家模式下两边都是人类，两边均可点击云库走棋；
     * - 走棋后走正常流程（更新状态、评估、推进下一回合）。
     */
    public synchronized void playerMoveByCloud(String ucci) {
        if (state != ControllerState.WAITING_FOR_USER
                && state != ControllerState.WAITING_FOR_USER_ANALYZING) {
            Log.d("GameController", "playerMoveByCloud ignored, state=" + state);
            return;
        }
        if (game == null || game.isGameOver) {
            return;
        }

        Move move = new Move(game.currentBoard);
        if (!move.fromUCCIString(ucci)) {
            gui.onGameEvent(GameStatus.ILLEGAL, "云库着法无效");
            return;
        }
        if (!RulesetProvider.get().isMoveLegal(move, game.currentBoard)) {
            gui.onGameEvent(GameStatus.ILLEGAL, "云库着法在当前局面不合法");
            return;
        }

        // 后台分析中先停止（其随后的bestmove回调不在任何等待状态，会被忽略）
        if (state == ControllerState.WAITING_FOR_USER_ANALYZING) {
            stopPlayerAnalysis();
        }

        game.setStartPos(move.fromPosition);
        game.setEndPos(move.toPosition);
        doMoveAndUpdateStatus(null);
    }

    public void stopSearchNow() {
        // send "stop" to engine for "bestmove"
        if (state == ControllerState.WAITING_FOR_ENGINE_BESTMV || state == ControllerState.WAITING_FOR_ENGINE_MULTIPV) {
            player.stopSearch();
            gui.onGameEvent(GameStatus.SELECT, "闪电出着中...");
        } else if (state == ControllerState.WAITING_FOR_USER_MULTIPV) {
            player.stopSearch();
            gui.onGameEvent(GameStatus.SELECT, "停止寻求帮助...");
        } else {
            gui.onGameEvent(GameStatus.ILLEGAL, "无搜索任务");
        }
    }

    public void applyEngineSetting() {
        // apply settings to engine
        Log.d("GameController", "Apply engine settings");
        Map<String, String> uciOptions = new HashMap<>();
        uciOptions.put("Hash", String.valueOf(settings.getHash_size()));
        player.setUCIOptions(uciOptions);
    }


    public void computerMovePiece(String bestmove) {
        // validate the move（解析 + 规则集合法性双重防御，正常不会触发后者）
        Move move = new Move(game.currentBoard);
        boolean result = move.fromUCCIString(bestmove)
                && RulesetProvider.get().isMoveLegal(move, game.currentBoard);

        if (result) {
            Log.d("GameController", "computer move: " + move.getChsString());

            game.setStartPos(move.fromPosition);
            game.setEndPos(move.toPosition);
            doMoveAndUpdateStatus(null);
        } else {
            Log.e("GameController", "Invalid move: " + bestmove);
            gui.onGameEvent(GameStatus.LOSE, "无路可走");
        }
    }

    public void processMultiPVInfos(String bestmove) {
        // show multiPV infos
        for (PvInfo pv : multiPVs) {
            Log.d("GameController", "PV: " + pv);
        }
        if (multiPVs.size() == 0) {
            // add bestmove to multiPVs
            ArrayList<Move> moves = new ArrayList<>();
            Move m = new Move(game.currentBoard);
            boolean result = m.fromUCCIString(bestmove);
            if (result) {
                moves.add(m);
                PvInfo pvinfo = new PvInfo(0, 0, 0, 0, 0, 0, 0, 0, false, false, false, moves);
                multiPVs.add(pvinfo);
            } else {
                Log.e("GameController", "Invalid move: " + bestmove);
                gui.onGameEvent(GameStatus.LOSE, "无路可走");
                return;
            }
        }

        game.generateSuggestedMoves(multiPVs);

        // notify GUI
        gui.onGameEvent(GameStatus.MULTIPV, "选择编号或直接移动棋子：");
    }

    public void selectMultiPV(int index) {
        Move move = game.getSuggestedMove(index);
        game.clearSuggestedMoves();
        PvInfo pvinfo = multiPVs.get(index);

        if (move != null) {
            game.startPos = move.fromPosition;
            game.endPos = move.toPosition;
            doMoveAndUpdateStatus(pvinfo);
        }
    }

    /** 玩家取消"寻求帮助"弹窗：不走棋，清掉候选并恢复后台分析 */
    public synchronized void cancelMultiPV() {
        game.clearSuggestedMoves();
        multiPVs.clear();
        setStateByTurn();
        gui.onGameEvent(GameStatus.UPDATEUI);
        if (isHumanTurn()) {
            startPlayerAnalysis();
        }
    }


    public void doMoveAndUpdateStatus(PvInfo pvinfo) {
        // game.startPos & game.endPos should be ready
        if (state != ControllerState.WAITING_FOR_USER && state != ControllerState.WAITING_FOR_ENGINE) {
            Log.e("GameController", "Invalid state, doMoveAndUpdateStatus should not be called now." + state);
            return;
        }

        // check piece color to move
        int piece = game.currentBoard.getPieceByPosition(game.startPos);
        if (Piece.isRed(piece) != isRedTurn()) {
            Log.e("GameController", "Invalid move, piece color is not match");
            if (isRedTurn()) {
                gui.onGameEvent(GameStatus.ILLEGAL, "该红方出着");
            } else {
                gui.onGameEvent(GameStatus.ILLEGAL, "该黑方出着");
            }
            // reset start/end position
            game.startPos = null;
            game.endPos = null;
            return;
        }

        game.movePiece();

        // update controller status
        toggleTurn();

        // update game status after the move
        GameStatus status = game.updateGameStatus();

        // clear multipv status
        game.clearSuggestedMoves();

        // send notification to GUI
        if (status == GameStatus.CHECKMATE) {
            gui.onGameEvent(GameStatus.CHECKMATE, "将死！");
        } else if (status == GameStatus.CHECK) {
            gui.onGameEvent(GameStatus.CHECK, "将军！");
        } else {
            if (pvinfo != null) {
                Log.d("GameController", "PV: " + pvinfo);

                // show multiPV infos
                Board b = new Board(game.currentBoard);
                ArrayList<Move> moves = pvinfo.pv;
                String desc = "预测着法：";
                for (int i = 1; i < moves.size() && i <= 4; i++) {
                    Move m = moves.get(i);
                    m.setBoard(b);
                    b.doMove(m);
                    desc += m.getChsString() + " ";
                }
                gui.onGameEvent(GameStatus.MOVE, desc);
            } else {
                gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
            }
        }

        // start request to evaluate board status
        // result will be returned by notifyEvalResult
        evalCurrentBoard();
    }

    protected void toggleTurn() {
        // movePiece已翻转棋盘轮走方，按新局面与对局模式决定下一状态
        if (state == ControllerState.WAITING_FOR_USER
                || state == ControllerState.WAITING_FOR_ENGINE) {
            setStateByTurn();
        } else {
            Log.e("GameController", "Invalid state, toggleTurn should not be called now." + state);
        }
    }

    @Override
    public void reportEngineError(String errMsg) {
        Log.d("GameController", "Engine error: " + errMsg);
    }

    @Override
    public void notifyEngineName(String engineName) {
        Log.d("GameController", "Engine name: " + engineName);
        this.engineInfo = engineName;
    }

    @Override
    public void notifyDepth(int id, int depth) {
        Log.d("GameController", "Depth: " + depth);
    }

    @Override
    public void notifyCurrMove(int id, Board board, Move m, int moveNr) {
        Log.d("GameController", "Current move: " + m.getUCCIString());
    }

    @Override
    public void notifyPV(int id, Board board, ArrayList<PvInfo> pvInfos, Move ponderMove) {
        // show infos about all pvInfos
        multiPVs.clear();
        for (PvInfo pv : pvInfos) {
            multiPVs.add(pv);
        }
        Log.d("GameController", "PV: " + pvInfos);

        // 更新"思考"面板的主PV着法链
        latestPv = pvInfos.isEmpty() ? null : pvInfos.get(0);
        rebuildThinkPv(board);
        if (latestPv != null) {
            thinkDepth = latestPv.getDepth();
        }
        gui.runOnUIThread(() -> gui.onGameEvent(GameStatus.UPDATEUI));
    }

    @Override
    public void notifyStats(int id, long nodes, int nps, long tbHits, int hash, int time, int seldepth) {
        Log.d("GameController", "Stats: nodes=" + nodes + ", nps=" + nps + ", tbHits=" + tbHits + ", hash=" + hash + ", time=" + time + ", seldepth=" + seldepth);
        thinkNodes = nodes;
        thinkTime = time;
        gui.runOnUIThread(() -> gui.onGameEvent(GameStatus.UPDATEUI));
    }

    // 把引擎主PV着法链翻译成中文着法，供"思考"面板展示
    private void rebuildThinkPv(Board board) {
        thinkPvMoves.clear();
        thinkPvIsRed.clear();
        thinkPvUcci.clear();
        if (latestPv != null && latestPv.getPv() != null && board != null) {
            Board b = new Board(board);
            int n = Math.min(latestPv.getPv().size(), 16);
            for (int i = 0; i < n; i++) {
                Move m = latestPv.getPv().get(i);
                if (m == null || m.fromPosition == null || m.toPosition == null) {
                    break;
                }
                m.setBoard(b);
                int p = b.getPieceByPosition(m.fromPosition);
                if (!Piece.isValid(p)) {
                    // 当前棋盘上该着法起点无棋子，说明PV与局面不匹配，停止推演
                    break;
                }
                thinkPvMoves.add(m.getChsString());
                thinkPvIsRed.add(Piece.isRed(p));
                thinkPvUcci.add(m.getUCCIString());
                b.doMove(m);
            }
        }
    }

    // 多PV建议着法的中文描述，供弹窗选择
    public List<String> getSuggestedMoveDescs() {
        List<String> descs = new ArrayList<>();
        Board b = new Board(game.currentBoard);
        for (PvInfo pv : multiPVs) {
            if (pv.getPv() != null && !pv.getPv().isEmpty()) {
                Move m = pv.getPv().get(0);
                m.setBoard(b);
                descs.add(m.getChsString());
            }
        }
        return descs;
    }

    @Override
    public void notifyBookInfo(int id, String bookInfo, ArrayList<Move> moveList, String eco, int distToEcoTree) {
        Log.d("GameController", "Book info: bookInfo=" + bookInfo + ", eco=" + eco + ", distToEcoTree=" + distToEcoTree);
    }

    @Override
    public void notifySearchResult(int searchId, String bestMove, String nextPonderMove) {
        // engine返回bestmove, 有3种情况，一种是电脑搜索走棋的结果，一种是红方寻求帮助的结果，一种是电脑被强制要求变着的结果

        Log.d("GameController", "Search result: bestMove=" + bestMove + ", nextPonderMove=" + nextPonderMove);
        searchEndTime = System.currentTimeMillis();

        if (state == ControllerState.WAITING_FOR_USER_MULTIPV) {
            // 玩家寻求帮助
            // 把multiPV的结果显示在界面上，让用户选择
            state = ControllerState.WAITING_FOR_USER;
            lastMultiPVForHuman = true;
            gui.runOnUIThread(() -> processMultiPVInfos(bestMove));
        } else if (state == ControllerState.WAITING_FOR_ENGINE_MULTIPV) {
            // 电脑被强制要求变着
            // 把multiPV的结果显示在界面上，让用户选择
            state = ControllerState.WAITING_FOR_ENGINE;
            lastMultiPVForHuman = false;
            gui.runOnUIThread(() -> processMultiPVInfos(bestMove));
        } else if (state == ControllerState.WAITING_FOR_USER_ANALYZING) {
            // 玩家回合后台分析被stop后返回的bestmove：不落子，仅回到玩家回合。
            // 玩家落子/悔棋/切模式/开新局等场景会自行驱动下一步，这里不重复发起分析。
            state = ControllerState.WAITING_FOR_USER;
            Log.d("GameController", "Analysis stopped, bestmove ignored: " + bestMove);
        } else if (state == ControllerState.WAITING_FOR_ENGINE_BESTMV) {
            // 电脑发起的请求，走下一步棋子
            state = ControllerState.WAITING_FOR_ENGINE;
            // 如果设置了引擎的随机性，则从multiPV中随机选择一个着法。这个只针对前12步有效，后期不让电脑随机选择，否则棋力降低太多
            if (settings.getRandom_move() && multiPVs.size() > 0 && game.currentBoard.rounds <= random_before_max_rounds) {
                int idx = (int) (Math.random() * multiPVs.size());
                String randomMove = multiPVs.get(idx).pv.get(0).getUCCIString();
                gui.runOnUIThread(() -> computerMovePiece(randomMove));
                Log.d("GameController", "Search result: bestMove=" + bestMove + ", randomMove=" + randomMove);
            } else {
                gui.runOnUIThread(() -> computerMovePiece(bestMove));
                Log.d("GameController", "Search result: bestMove=" + bestMove);
            }
        }
    }

    @Override
    public void notifyEvalResult(int searchId, float eval) {
        Log.d("GameController", "Eval result: eval=" + eval);
        game.currentBoard.score = eval;

        gui.runOnUIThread(() -> {
                gui.onGameEvent(GameStatus.UPDATEUI);
        });

        // restore state
        state = preEvalState;

        if (game.isGameOver) {
            return;
        }

        // 延迟500ms推进下一回合：引擎回合自动走棋；玩家回合启动后台分析供参考
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (GameController.this) {
                if (state != ControllerState.WAITING_FOR_USER
                        && state != ControllerState.WAITING_FOR_ENGINE) {
                    return;
                }
                if (isEngineToMove()) {
                    computerForward();
                } else {
                    startPlayerAnalysis();
                }
            }
        }, 500);
    }

    @Override
    public void notifyEngineInitialized() {
        Log.d("GameController", "Engine initialized");
    }

    public int getMultiPVSize() {
        return multiPVs.size();
    }

    public void saveGameStatus() {
        // save game status
        try {
            game.saveToFile(ChessApp.getContext());
            Log.d("GameController", "Game status saved");
        } catch (IOException e) {
            Log.e("GameController", "Failed to save game status" + e);
        }
    }

    public void loadGameStatus() {
        // load game status
        try {
            game = Game.loadFromFile(ChessApp.getContext());
            setStateByTurn();
            Log.d("GameController", "Game status loaded");
        } catch (IOException e) {
            Log.e("GameController", "Failed to load game status: " + e);
        } catch (ClassNotFoundException e) {
            Log.e("GameController", "Failed to load game status: " + e);
        }
    }
}
