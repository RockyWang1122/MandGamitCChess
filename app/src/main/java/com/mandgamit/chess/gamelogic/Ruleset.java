package com.mandgamit.chess.gamelogic;

import java.util.List;

/**
 * 行棋规则集接口。
 *
 * 走法合法性分为两层：
 * 1. 几何走法（Rule.PossibleToPositions）：棋子按自身走法能到达的格子；
 * 2. 规则约束（本接口）：各规则集在几何走法之上附加的限制，例如中国规则的"不允许送将"。
 *
 * 目前按中国规则实现（ChineseRules）；后续可扩展亚洲规则、世界规则，
 * 通过 RulesetProvider 切换，所有接入点（选中高亮、落子校验）无需改动。
 */
public interface Ruleset {
    /** 规则集名称，如 "中国规则"、"亚洲规则"、"世界规则" */
    String getName();

    /**
     * 判断一步棋在当前局面下是否完全合法（几何走法 + 本规则集附加约束）。
     */
    boolean isMoveLegal(Move move, Board board);

    /**
     * 返回某棋子在当前局面下规则过滤后的可走点（用于选中棋子后的高亮）。
     */
    List<Position> filterLegalTargets(int pieceId, int fromX, int fromY, Board board);
}
