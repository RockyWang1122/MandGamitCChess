package com.mandgamit.chess.gamelogic;

/**
 * 当前行棋规则集的持有者。
 *
 * 默认使用中国规则。后续若支持亚洲规则、世界规则，
 * 通过 set() 切换即可，所有校验/高亮接入点无需改动。
 */
public class RulesetProvider {
    private static volatile Ruleset current = new ChineseRules();

    public static Ruleset get() {
        return current;
    }

    public static void set(Ruleset ruleset) {
        if (ruleset != null) {
            current = ruleset;
        }
    }
}
