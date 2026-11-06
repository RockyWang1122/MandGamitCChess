package com.mandgamit.chess.gamelogic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 中国规则（参照《中国象棋竞赛规则》基本行棋规定）。
 *
 * 已实现：
 * - 几何走法（各棋子基本走法，见 Rule.PossibleToPositions）；
 * - 不允许自杀式走法：走完后己方将帅不能被对方攻击，也不能与对方将帅照面
 *   （例：4k4/4a4/4c4/9/9/9/9/3C5/9/4K4 w 时，红方不允许炮六平五，
 *   否则黑棋炮5进7借红炮做炮架直接吃帅）。
 *
 * 将军检测由 Rule.isJiangShuaiInDanger 提供（含将帅照面）。
 */
public class ChineseRules implements Ruleset {
    @Override
    public String getName() {
        return "中国规则";
    }

    @Override
    public boolean isMoveLegal(Move move, Board board) {
        if (move == null || board == null) {
            return false;
        }
        // 第一步：几何走法校验
        if (!Rule.isValidMove(move, board)) {
            return false;
        }
        // 第二步：规则约束——走完后己方将帅必须安全
        return Rule.isKingSafeAfterMove(
                board.getPieceByPosition(move.fromPosition),
                move.fromPosition.x, move.fromPosition.y,
                move.toPosition.x, move.toPosition.y,
                board);
    }

    @Override
    public List<Position> filterLegalTargets(int pieceId, int fromX, int fromY, Board board) {
        List<Position> ret = new ArrayList<>();
        List<Position> candidates = Rule.PossibleToPositions(pieceId, fromX, fromY, board);
        Iterator<Position> it = candidates.iterator();
        while (it.hasNext()) {
            Position pos = it.next();
            if (Rule.isKingSafeAfterMove(pieceId, fromX, fromY, pos.x, pos.y, board)) {
                ret.add(pos);
            }
        }
        return ret;
    }
}
