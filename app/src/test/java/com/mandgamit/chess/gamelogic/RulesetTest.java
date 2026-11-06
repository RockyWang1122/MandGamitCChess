package com.mandgamit.chess.gamelogic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * 行棋规则（中国规则）单元测试。
 * 核心：不允许自杀式走法——走完后己方将帅被将军或与对方将帅照面。
 */
public class RulesetTest {
    /**
     * 用户例局：黑炮(4,2)与红帅(4,9)同在e路且中间无炮架，
     * 红方炮六平五(3,7)->(4,7)后，黑炮5进7借红炮做炮架直接吃帅。
     */
    private static final String FEN_EXAMPLE = "4k4/4a4/4c4/9/9/9/9/3C5/9/4K4 w - - 0 1";
    // 红炮已平五的局面（黑方行棋）：黑炮吃帅后，e路只剩黑士一个炮架，红炮反而将军黑将
    private static final String FEN_AFTER_FLAT = "4k4/4a4/4c4/9/9/9/9/4C4/9/4K4 b - - 0 1";
    // 黑炮吃帅不送将的正例：e路红兵做炮架，红炮(0,7)远离中路
    private static final String FEN_CAPTURE_KING_OK = "4k4/9/4c4/9/9/4P4/9/C8/9/4K4 b - - 0 1";
    // 黑车正面将军裸帅，另一黑车封住底线：红方被将死
    private static final String FEN_DOUBLE_ROOK_MATE = "4r4/9/9/9/9/9/9/9/9/r3K4 w - - 0 1";
    // 黑车将军，红车可垫子解将：未被将死
    private static final String FEN_CAN_BLOCK = "4r4/9/9/9/R8/9/9/9/9/4K4 w - - 0 1";
    // 帅将同路中间仅隔一红仕：仕移开会造成将帅照面
    private static final String FEN_FACING = "4k4/9/9/9/9/9/9/9/4A4/4K4 w - - 0 1";

    private Ruleset ruleset;

    @Before
    public void setUp() {
        ruleset = new ChineseRules();
    }

    private Board boardFromFEN(String fen) {
        Board board = new Board();
        assertTrue("FEN解析失败: " + fen, board.restoreFromFEN(fen));
        return board;
    }

    private Move move(Board board, int fromX, int fromY, int toX, int toY) {
        return new Move(new Position(fromX, fromY), new Position(toX, toY), board);
    }

    @Test
    public void testCannonFlatFiveRejected() {
        // 炮六平五会送将（黑炮5进7吃帅），必须被拒绝
        Board board = boardFromFEN(FEN_EXAMPLE);
        assertFalse(ruleset.isMoveLegal(move(board, 3, 7, 4, 7), board));
    }

    @Test
    public void testCannonAdvanceAllowed() {
        // 炮六进三不送将，合法
        Board board = boardFromFEN(FEN_EXAMPLE);
        assertTrue(ruleset.isMoveLegal(move(board, 3, 7, 3, 4), board));
    }

    @Test
    public void testKingEscapeAllowed() {
        // 帅五平四离开e路，黑炮打不到，合法
        Board board = boardFromFEN(FEN_EXAMPLE);
        assertTrue(ruleset.isMoveLegal(move(board, 4, 9, 3, 9), board));
    }

    @Test
    public void testBlackCannonCaptureKingAllowed() {
        // 吃对方帅不是送将：黑炮借红兵做炮架吃掉红帅，且走后黑将不被攻击，合法
        Board board = boardFromFEN(FEN_CAPTURE_KING_OK);
        assertTrue(ruleset.isMoveLegal(move(board, 4, 2, 4, 9), board));
    }

    @Test
    public void testCaptureKingRejectedWhenSelfInCheck() {
        // 红炮平五局面：黑炮(4,2)原本挡住红炮(4,7)与黑将之间（两个炮架不构成将军），
        // 黑炮若炮5进7吃帅，e路只剩黑士一个炮架，黑将反被红炮将军——吃帅也必须解将，拒绝
        Board board = boardFromFEN(FEN_AFTER_FLAT);
        assertFalse(ruleset.isMoveLegal(move(board, 4, 2, 4, 9), board));
    }

    @Test
    public void testHighlightExcludesSuicidalTarget() {
        // 选中红炮后，高亮可走点不应包含送将的炮六平五(4,7)
        Game game = new Game(true);
        assertTrue(game.currentBoard.restoreFromFEN(FEN_EXAMPLE));
        game.setStartPos(new Position(3, 7));

        boolean containsSuicidal = false;
        boolean containsSafe = false;
        for (Position pos : game.possibleToPositions) {
            if (pos.x == 4 && pos.y == 7) {
                containsSuicidal = true;
            }
            if (pos.x == 3 && pos.y == 4) {
                containsSafe = true;
            }
        }
        assertFalse("高亮点不应包含送将走法", containsSuicidal);
        assertTrue("高亮点应包含安全走法", containsSafe);
    }

    @Test
    public void testCheckMustBeResolved() {
        // 红帅被黑车正面将军：帅平移可解将，帅进仍被将必须拒绝
        Board board = boardFromFEN("4r4/9/9/9/9/9/9/9/9/4K4 w - - 0 1");
        // 帅五进一(4,9)->(4,8)仍在e路被车将军，拒绝
        assertFalse(ruleset.isMoveLegal(move(board, 4, 9, 4, 8), board));
        // 帅五平四(4,9)->(3,9)脱离e路，允许
        assertTrue(ruleset.isMoveLegal(move(board, 4, 9, 3, 9), board));
        // 帅五平六(4,9)->(5,9)脱离e路，允许
        assertTrue(ruleset.isMoveLegal(move(board, 4, 9, 5, 9), board));
    }

    @Test
    public void testFacingAfterAdvisorMoveRejected() {
        // 仕(4,8)移开任意方向都会造成帅(4,9)将(4,0)照面，必须拒绝
        Board board = boardFromFEN(FEN_FACING);
        assertFalse(ruleset.isMoveLegal(move(board, 4, 8, 3, 7), board));
        assertFalse(ruleset.isMoveLegal(move(board, 4, 8, 5, 7), board));
        assertFalse(ruleset.isMoveLegal(move(board, 4, 8, 3, 9), board));
        // 帅五平四脱离中路，允许
        assertTrue(ruleset.isMoveLegal(move(board, 4, 9, 3, 9), board));
    }

    @Test
    public void testCheckmateDetection() {
        // 双车将死：无子可动解将
        Board board = boardFromFEN(FEN_DOUBLE_ROOK_MATE);
        Position kingPos = Rule.findJiangShuaiPos(Piece.WSHUAI, board);
        assertTrue(Rule.isJiangShuaiDead(Piece.WSHUAI, kingPos, board));

        // 对照：有车可垫子解将，未将死
        Board blockBoard = boardFromFEN(FEN_CAN_BLOCK);
        Position kingPos2 = Rule.findJiangShuaiPos(Piece.WSHUAI, blockBoard);
        assertFalse(Rule.isJiangShuaiDead(Piece.WSHUAI, kingPos2, blockBoard));
    }
}
