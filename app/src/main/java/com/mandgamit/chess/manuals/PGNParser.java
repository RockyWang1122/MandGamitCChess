package com.mandgamit.chess.manuals;

import android.util.Log;

import com.mandgamit.chess.gamelogic.Board;
import com.mandgamit.chess.gamelogic.Move;
import com.mandgamit.chess.gamelogic.Piece;
import com.mandgamit.chess.gamelogic.Position;
import com.mandgamit.chess.gamelogic.Rule;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 PGN 格式的中国象棋棋谱（中文着法记谱）。
 *
 * <p>支持：
 * <ul>
 *   <li>中文着法（红方中文数字；黑方半角/全角阿拉伯数字）</li>
 *   <li>4 字着法（"炮二平六"）</li>
 *   <li>"前/后 + 棋子 + action + num2"（"前车进三"）</li>
 *   <li>直线棋子（车、炮、兵、卒、帅、将）"平"→目标纵线，"进/退"→步数</li>
 *   <li>斜线棋子（马、相、象、仕、士）"进/退"→目标纵线（推算 dy）</li>
 *   <li>变招（分支）{@code (...)}——按首手颜色自动区分"后续应招"（子节点）与
 *       "替代着法"（兄弟节点），递归解析为 MoveNode 树</li>
 *   <li>注释 {@code {...}} 自动剥离（不读取内容）</li>
 * </ul>
 *
 * <p>暂不支持：ICCS 坐标着法、三兵同列的"中"标记。
 *
 * @see XQFParser
 */
public class PGNParser {
    private static final String TAG = "PGNParser";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GB18030 = Charset.forName("GB18030");

    /** 中文数字 → int */
    private static final Map<Character, Integer> CHS_TO_NUM = new HashMap<>();
    static {
        CHS_TO_NUM.put('一', 1); CHS_TO_NUM.put('二', 2); CHS_TO_NUM.put('三', 3);
        CHS_TO_NUM.put('四', 4); CHS_TO_NUM.put('五', 5); CHS_TO_NUM.put('六', 6);
        CHS_TO_NUM.put('七', 7); CHS_TO_NUM.put('八', 8); CHS_TO_NUM.put('九', 9);
    }

    /** 红方棋子名 → 红方 piece value（红黑共名棋子也先映射到红方，后续按颜色 swap） */
    private static final Map<Character, Integer> NAME_TO_RED_PIECE = new HashMap<>();
    static {
        NAME_TO_RED_PIECE.put('帅', Piece.WSHUAI);
        NAME_TO_RED_PIECE.put('仕', Piece.WSHI);
        NAME_TO_RED_PIECE.put('相', Piece.WXIANG);
        NAME_TO_RED_PIECE.put('马', Piece.WMA);
        NAME_TO_RED_PIECE.put('車', Piece.WMA);
        NAME_TO_RED_PIECE.put('车', Piece.WJU);
        NAME_TO_RED_PIECE.put('炮', Piece.WPAO);
        NAME_TO_RED_PIECE.put('砲', Piece.WPAO);
        NAME_TO_RED_PIECE.put('兵', Piece.WBING);
    }

    /** 黑方专属棋子名 → 黑色 piece value */
    private static final Map<Character, Integer> NAME_TO_BLACK_PIECE = new HashMap<>();
    static {
        NAME_TO_BLACK_PIECE.put('将', Piece.BJIANG);
        NAME_TO_BLACK_PIECE.put('士', Piece.BSHI);
        NAME_TO_BLACK_PIECE.put('象', Piece.BXIANG);
        NAME_TO_BLACK_PIECE.put('卒', Piece.BZU);
    }

    /** 取半角或全角阿拉伯数字的 int 值；非数字返回 -1 */
    private static int digitToInt(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= '０' && c <= '９') return c - '０';
        return -1;
    }

    /** 扫描 token 中第一个数字字符判定颜色：中文数字→红(true)；阿拉伯数字→黑(false)；都没有→null */
    private static Boolean detectColor(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            // 只跳过开头位置(i==0)的序数词前缀(前/二/三/四/五/中/后),
            // 避免把"四卒平6"中的"四"误判为红方路数;
            // 但不跳过非开头位置的"三/五"等(它们是路数中文数字,如"车三平五"中的"三"和"五")
            if (i == 0 && (c == '前' || c == '二' || c == '三' || c == '四' || c == '五' || c == '中' || c == '后')) continue;
            if (CHS_TO_NUM.containsKey(c)) return true;
            if (digitToInt(c) >= 0) return false;
        }
        return null;
    }

    /**
     * 解析 PGN 字节流为 XQFManual。
     * @param buffer PGN 文件字节
     * @return 解析成功返回 XQFManual（manual.board 保持初始局面，moveNode 树已构建，含变招分支）；失败返回 null
     */
    public static XQFManual parse(byte[] buffer) {
        if (buffer == null || buffer.length == 0) return null;

        // 编码：先按 UTF-8；若出现常见乱码标志则回退 GB18030
        String content = new String(buffer, UTF8);
        if (content.indexOf('ï') >= 0 || content.indexOf('¿') >= 0) {
            content = new String(buffer, GB18030);
        }

        // 1. 解析头部标签 [Key "Value"]，分离着法段
        Map<String, String> tags = new HashMap<>();
        StringBuilder movesText = new StringBuilder();
        boolean inMoves = false;
        Pattern tagPattern = Pattern.compile("^\\s*\\[(\\w+)\\s+\"([^\"]*)\"\\]\\s*$");

        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!tags.isEmpty()) inMoves = true;
                continue;
            }
            Matcher m = tagPattern.matcher(trimmed);
            if (!inMoves && m.matches()) {
                tags.put(m.group(1), m.group(2));
            } else {
                inMoves = true;
                movesText.append(trimmed).append(' ');
            }
        }

        // 2. 构造 Manual
        XQFManual manual = new XQFManual();
        manual.setEvent(tags.get("Event"));
        manual.setTitle(tags.get("Event"));
        manual.setRed(tags.getOrDefault("Red", "红方"));
        manual.setBlack(tags.getOrDefault("Black", "黑方"));
        manual.setResult(tags.get("Result"));
        manual.setDate(tags.get("Date"));
        manual.setSite(tags.get("Site"));
        manual.setFormat("PGN");

        // 初始局面：有 FEN 标签则按 FEN 起始，否则用标准开局
        Board startBoard = new Board();
        String fenTag = tags.get("FEN");
        if (fenTag != null && !fenTag.isEmpty()) {
            if (!startBoard.restoreFromFEN(fenTag)) {
                Log.e(TAG, "Invalid FEN tag: " + fenTag);
                return null;
            }
        }
        manual.board = startBoard;  // ← 关键：把初始局面写回 manual.board

        // 3. 词法分析：把着法段切成 token 流，含 ( ) 作为分支标记
        // 先剥离注释 {...}（PGN 标准不支持嵌套注释，按贪心匹配）
        String movesStr = movesText.toString().replaceAll("\\{[^}]*\\}", " ");
        // 剥离回合数前缀 "1." "10."
        movesStr = movesStr.replaceAll("\\d+\\.", " ");
        // 剥离结果标识
        movesStr = movesStr.replaceAll("(1-0|0-1|1/2-1/2|\\*)", " ");
        // 在 ( 和 ) 两侧加空格，使其成为独立 token
        movesStr = movesStr.replace("(", " ( ").replace(")", " ) ");
        String[] rawTokens = movesStr.split("\\s+");

        List<String> tokens = new ArrayList<>();
        for (String t : rawTokens) {
            // 跳过黑先开局的空着占位 "..."（棋谱导出走标准 PGN 记法，黑先首回合记作 "1. ..."）
            if (!t.isEmpty() && !t.matches("\\.+")) tokens.add(t);
        }

        // 4. 递归下降解析为 MoveNode 树（带回溯：遇到"前卒"等全局歧义记法时尝试多个候选起点）
        int[] idx = new int[]{0};
        // 已挂过"后续应招型"子变着的节点集合：解析到主线续着时需插到第 0 位，保证 nextMoves[0] 为主线
        Set<XQFManual.MoveNode> childVarParents =
                Collections.newSetFromMap(new IdentityHashMap<>());
        boolean ok = parseSequence(tokens, idx, manual.getHeadMove(), new Board(startBoard),
                manual, startBoard, childVarParents);

        if (!ok || idx[0] != tokens.size()) {
            String at = (idx[0] < tokens.size()) ? tokens.get(idx[0]) : "<end>";
            Log.e(TAG, "Parsing failed at token '" + at + "', idx=" + idx[0]
                    + " tokens.size=" + tokens.size());
            return null;
        }

        return manual;
    }

    /**
     * 递归解析一个着法段（主分支或括号内），支持候选着法回溯。
     *
     * <p>变着括号 {@code (...)} 有两种语义，按括号内首手的颜色判定：
     * <ul>
     *   <li><b>后续应招型（子分支）</b>：首手颜色 = 上一着走完后轮到的一方（与上一着异色）。
     *       如 {@code 炮五平九 (士6进5 ...) 马7进5}，变着是炮五平九走完后黑方的另一种应法，
     *       挂为上一着的子节点，起始局面 = 上一着走完后的局面；</li>
     *   <li><b>替代着法型（兄弟分支）</b>：首手颜色与上一着同色。
     *       如 {@code 马八进七 (马八退六 ...) ...}，变着是同一方的另一种走法，
     *       挂为上一着的兄弟节点，起始局面 = 上一着走之前的局面。</li>
     * </ul>
     *
     * <p>对于"前卒进一"这类多列同名棋子的歧义记法，一个 token 可能对应多个几何合法的起点；
     * 本函数依次尝试每个候选，若后续着法无法继续则回滚已挂节点并换下一个候选。
     *
     * @param tokens           全部 token 流
     * @param idx              共享游标（单元素数组；失败时会恢复到进入本段时的位置）
     * @param parent           本段的起始父节点
     * @param parentBoard      parent 走完后的局面快照（本段第一着执行前的 board 状态）
     * @param manual           XQFManual 对象（walkToParent 时从 headMove 开始追溯路径）
     * @param startBoard       整盘 PGN 的初始局面（标准开局或 FEN 指定）
     * @param childVarParents  已挂过"后续应招型"子变着的节点集合（主线续着需插到首位）
     * @return true=本段（含其嵌套变着）完整解析；false=候选全部失败，调用方应回滚并尝试下一候选
     */
    private static boolean parseSequence(List<String> tokens, int[] idx, XQFManual.MoveNode parent,
                                         Board parentBoard, XQFManual manual, Board startBoard,
                                         Set<XQFManual.MoveNode> childVarParents) {
        Board workingBoard = new Board(parentBoard);  // copy，本段着法在此 board 上推进
        XQFManual.MoveNode current = parent;
        // 进入本段时 childVarParents 的快照，失败时整体恢复
        Set<XQFManual.MoveNode> cvpSnapshot =
                Collections.newSetFromMap(new IdentityHashMap<>());
        cvpSnapshot.addAll(childVarParents);

        while (idx[0] < tokens.size()) {
            String token = tokens.get(idx[0]);
            if (token.equals(")")) {
                // 本变招段正常结束
                idx[0]++;
                return true;
            } else if (token.equals("(")) {
                int parenIdx = idx[0] + 1;  // 跳过 (
                Boolean firstColor = peekVariationFirstColor(tokens, parenIdx);
                // workingBoard 此刻 = 上一着(current)走完后的局面，bRedGo 即下一回合方
                boolean continuation =
                        firstColor != null && workingBoard.bRedGo == firstColor;
                boolean nestedOk;
                idx[0] = parenIdx;
                if (continuation) {
                    // 后续应招型：首手是上一着走完后轮到的一方 → 挂为 current 的子节点，
                    // 起始局面 = current 走完后的局面
                    nestedOk = parseSequence(tokens, idx, current, new Board(workingBoard), manual,
                            startBoard, childVarParents);
                    if (nestedOk) childVarParents.add(current);
                } else if (current.parent == null) {
                    // 根变招——作为 headMove 的子节点（与主分支并列）
                    nestedOk = parseSequence(tokens, idx, manual.getHeadMove(),
                            new Board(startBoard), manual, startBoard, childVarParents);
                } else {
                    // 替代着法型：首手与 current 同色 → 作为 current 的兄弟
                    // 从初始局面走到 current.parent 走完后的状态
                    Board varStartBoard = new Board(startBoard);
                    walkToParent(current, varStartBoard);
                    nestedOk = parseSequence(tokens, idx, current.parent, varStartBoard, manual,
                            startBoard, childVarParents);
                }
                if (!nestedOk) {
                    // 嵌套段内部回滚后，恢复本段标记状态并上抛失败
                    restoreSet(childVarParents, cvpSnapshot);
                    return false;
                }
                // 变招结束后，workingBoard 不变（主分支继续从 current 之后）
            } else {
                List<Move> candidates = parseChsMoves(token, workingBoard);
                if (candidates.isEmpty()) {
                    restoreSet(childVarParents, cvpSnapshot);
                    return false;
                }
                int savedIdx = idx[0];
                boolean advanced = false;
                for (Move mv : candidates) {
                    Board trial = new Board(workingBoard);
                    if (!trial.doMove(mv)) continue;

                    XQFManual.MoveNode newNode = new XQFManual.MoveNode(mv);
                    newNode.setParent(current);
                    boolean mainInsert = childVarParents.remove(current);
                    if (mainInsert) {
                        // current 此前已先挂了子变着（文本顺序在前），主线续着必须插到第 0 位，
                        // 保证 nextMoves[0] 始终是主线
                        current.nextMoves.add(0, newNode);
                    } else {
                        current.addNextMove(newNode);
                    }

                    idx[0] = savedIdx + 1;
                    if (parseSequence(tokens, idx, newNode, trial, manual, startBoard,
                            childVarParents)) {
                        advanced = true;
                        break;
                    }
                    // 该候选导致后续失败：摘掉试探节点，恢复主线标记，尝试下一候选
                    current.nextMoves.remove(newNode);
                    if (mainInsert) childVarParents.add(current);
                }
                if (!advanced) {
                    idx[0] = savedIdx;
                    restoreSet(childVarParents, cvpSnapshot);
                    return false;
                }
                // 成功的候选已由递归 parseSequence 消费完后续 token（含闭合括号），本段使命完成
                return true;
            }
        }
        return true;  // token 流自然结束
    }

    private static void restoreSet(Set<XQFManual.MoveNode> target,
                                   Set<XQFManual.MoveNode> snapshot) {
        target.clear();
        target.addAll(snapshot);
    }

    /**
     * 预读括号变着内的第一手着法，判定其颜色（不移动共享游标）。
     * @param start 已跳过 '(' 后的游标位置
     * @return 首手颜色（true=红，false=黑）；变着为空或无法判定时返回 null
     */
    private static Boolean peekVariationFirstColor(List<String> tokens, int start) {
        int depth = 1;
        for (int i = start; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("(")) {
                depth++;
            } else if (t.equals(")")) {
                depth--;
                if (depth == 0) break;
            } else if (depth == 1) {
                return detectColor(t);
            }
        }
        return null;
    }

    /**
     * 从初始局面执行 root -> ... -> target 路径上所有 move（不含 target.move），
     * 得到 target 走完前的局面（即 target.parent 走完后的状态）。
     * 调用方需先把 board 设为初始局面（new Board(startBoard)）。
     */
    private static void walkToParent(XQFManual.MoveNode target, Board board) {
        // 从 target 沿 parent 回溯到 root（headMove.move == null 时停止）
        List<XQFManual.MoveNode> path = new ArrayList<>();
        XQFManual.MoveNode node = target;
        while (node != null && node.move != null) {
            path.add(node);
            node = node.parent;
        }
        Collections.reverse(path);  // root -> ... -> target
        // 不含 target 本身——只走到 target.parent
        for (int i = 0; i < path.size() - 1; i++) {
            board.doMove(path.get(i).move);
        }
    }

    /**
     * 解析单步中文着法 token，返回所有几何合法的候选 Move（按优先级排序，首选在前）。
     * 支持 4 字着法（"炮二平六"）与序数词开头（"前车进三"、"中卒平４"、"二兵平四"等）。
     *
     * <p>通常只有 1 个候选；仅在记谱本身存在全局歧义时出现多个（例如两条纵线各有双卒，
     * "前卒进一"按 Move.getChsString 的生成规则只区分本列，两个列的前卒都可能匹配——
     * 此时由 parseSequence 结合后续着法回溯选定）。
     */
    private static List<Move> parseChsMoves(String token, Board board) {
        if (token == null || token.length() < 3) return Collections.emptyList();

        // 序数词前缀:前=1, 二=2, 三=3, 四=4, 五=5, 后=末位, 中=同列棋子数 N 的中间位 ((N+1)/2, N 奇数时有效)
        // 0 表示无序数前缀(走 4 字着法分支)
        int ordinal = 0;
        int idx = 0;
        char first = token.charAt(0);
        switch (first) {
            case '前': ordinal = 1; idx = 1; break;
            case '二': ordinal = 2; idx = 1; break;
            case '三': ordinal = 3; idx = 1; break;
            case '四': ordinal = 4; idx = 1; break;
            case '五': ordinal = 5; idx = 1; break;
            case '中': ordinal = -1; idx = 1; break;
            case '后': ordinal = -2; idx = 1; break;  // -2 = 末位
        }

        char pieceName = token.charAt(idx);
        Integer redPiece = NAME_TO_RED_PIECE.get(pieceName);
        Integer blackPiece = NAME_TO_BLACK_PIECE.get(pieceName);
        if (redPiece == null && blackPiece == null) {
            Log.e(TAG, "Unknown piece name: " + pieceName + " in " + token);
            return Collections.emptyList();
        }

        Boolean isRedObj = detectColor(token);
        if (isRedObj == null) {
            Log.e(TAG, "Cannot detect color from token: " + token);
            return Collections.emptyList();
        }
        boolean isRed = isRedObj;

        int piece;
        if (blackPiece != null) {
            piece = blackPiece;
        } else {
            piece = redPiece;
            if (!isRed) {
                piece = Piece.swapColor(piece);
            }
        }

        char action, num2Char;
        List<Position> origins;
        if (ordinal != 0) {
            if (token.length() < idx + 3) return Collections.emptyList();
            action = token.charAt(idx + 1);
            num2Char = token.charAt(idx + 2);
            origins = findOrdinals(board, piece, isRed, ordinal);
        } else {
            if (token.length() < idx + 4) return Collections.emptyList();
            char num1Char = token.charAt(idx + 1);
            action = token.charAt(idx + 2);
            num2Char = token.charAt(idx + 3);

            int vLine1;
            if (isRed) {
                vLine1 = CHS_TO_NUM.getOrDefault(num1Char, -1);
            } else {
                vLine1 = digitToInt(num1Char);
            }
            if (vLine1 < 1 || vLine1 > 9) {
                Log.e(TAG, "Invalid num1: " + num1Char + " in " + token);
                return Collections.emptyList();
            }
            int fromX = isRed ? (9 - vLine1) : (vLine1 - 1);
            // 同一纵线上可能有多个相同棋子（如两个士同在六路），须逐个试走，
            // 选出几何上合法的那个，而不是固定取最靠前的一个
            return resolveMovesOnLine(board, piece, fromX, action, num2Char, isRed, token);
        }

        // 序数词候选：逐个起点构造着法并做几何合法性过滤（保持 origins 的优先级顺序）
        List<Move> moves = new ArrayList<>();
        for (Position from : origins) {
            Position to = computeTo(from, piece, action, num2Char, isRed);
            if (to == null) continue;
            Move mv = new Move(from, to);
            if (Rule.isValidMove(mv, board) && !containsMove(moves, mv)) {
                moves.add(mv);
            }
        }
        // 候选可能为空：试探分支上属正常（由 parseSequence 回溯），最终失败由 parse() 统一记录
        return moves;
    }

    private static boolean containsMove(List<Move> moves, Move mv) {
        for (Move m : moves) {
            if (m.fromPosition.equals(mv.fromPosition) && m.toPosition.equals(mv.toPosition)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在指定纵线上枚举所有该种棋子，返回几何合法的候选着法（纵线 y 从 0 到 9 的扫描顺序）。
     * 用于消解"同列多个相同棋子"时 4 字记谱的起点歧义（如两个士同在六路时的"士6退5"）。
     */
    private static List<Move> resolveMovesOnLine(Board board, int piece, int fromX,
                                                 char action, char num2Char, boolean isRed,
                                                 String token) {
        List<Move> legalMoves = new ArrayList<>();
        for (int y = 0; y < 10; y++) {
            if (board.getPieceByPosition(fromX, y) != piece) continue;
            Position from = new Position(fromX, y);
            Position to = computeTo(from, piece, action, num2Char, isRed);
            if (to == null) continue;
            Move mv = new Move(from, to);
            if (Rule.isValidMove(mv, board)) {
                legalMoves.add(mv);
            }
        }
        if (!legalMoves.isEmpty() && legalMoves.size() > 1) {
            Log.w(TAG, "Ambiguous move " + token + " (" + legalMoves.size()
                    + " legal origins); will backtrack if needed");
        }
        return legalMoves;
    }

    /**
     * 按序数词定位同色同种棋子，返回按优先级排序的候选起点（首选在前）。
     *
     * <p>序数词含义(以同一纵线上同色同种棋子数 N 为基准,从前到后排序):
     * <ul>
     *   <li>前 = 第 1 个</li>
     *   <li>二 = 第 2 个</li>
     *   <li>三 = 第 3 个</li>
     *   <li>四 = 第 4 个</li>
     *   <li>五 = 第 5 个</li>
     *   <li>后 = 第 N 个(末位)</li>
     *   <li>中 = 第 (N+1)/2 个(N 为奇数时有效)</li>
     * </ul>
     *
     * <p>红方"前" = y 较小者;黑方"前" = y 较大者。同 y 时按 x 升序稳定排序。
     *
     * <p>候选顺序:
     * <ol>
     *   <li>全局池化定位结果（与旧版行为一致，保证无歧义谱例首选不变）；</li>
     *   <li>每个"同列 ≥2 个"的列按本列序数取的同位点——处理多条纵线各有多子时
     *       "前卒/前车"只区分本列的全局歧义（Move.getChsString 即按本列生成）；</li>
     *   <li>全局池中的其他位点（兼容非标准记法的回溯兜底）。</li>
     * </ol>
     *
     * @param ordinal 1-5 表示序数;-1 = 中;-2 = 后
     */
    private static List<Position> findOrdinals(Board board, int piece, boolean isRed, int ordinal) {
        Map<Integer, List<Position>> byColumn = new HashMap<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 10; y++) {
                if (board.getPieceByPosition(x, y) == piece) {
                    byColumn.computeIfAbsent(x, k -> new ArrayList<>()).add(new Position(x, y));
                }
            }
        }
        if (byColumn.isEmpty()) return Collections.emptyList();

        // 每列内部按"从前到后"排序
        for (List<Position> list : byColumn.values()) {
            sortByFront(list, isRed);
        }

        // 收集"同列 >= 2 个"的列；池化候选 = 这些列的全部位点，若无则退化为全盘
        List<Position> pooled = new ArrayList<>();
        List<Integer> multiCols = new ArrayList<>();
        for (Map.Entry<Integer, List<Position>> e : byColumn.entrySet()) {
            if (e.getValue().size() >= 2) {
                multiCols.add(e.getKey());
                pooled.addAll(e.getValue());
            }
        }
        if (pooled.isEmpty()) {
            for (List<Position> list : byColumn.values()) pooled.addAll(list);
        }
        sortByFront(pooled, isRed);

        List<Position> result = new ArrayList<>();
        // 1) 旧版全局池化首选（保持行为一致）
        int pooledIdx = ordinalIndex(ordinal, pooled.size());
        if (pooledIdx >= 0) addPositionOnce(result, pooled.get(pooledIdx));

        // 2) 每个多子列各自的本列序数位点
        Collections.sort(multiCols);
        for (int x : multiCols) {
            List<Position> col = byColumn.get(x);
            int ci = ordinalIndex(ordinal, col.size());
            if (ci >= 0) addPositionOnce(result, col.get(ci));
        }

        // 3) 全局池中其余位点（兜底）
        for (Position p : pooled) addPositionOnce(result, p);
        return result;
    }

    /** 红方 y 升序、黑方 y 降序；同 y 时 x 升序（"从前到后"） */
    private static void sortByFront(List<Position> positions, boolean isRed) {
        positions.sort((a, b) -> {
            int dy = isRed ? Integer.compare(a.y, b.y) : Integer.compare(b.y, a.y);
            if (dy != 0) return dy;
            return Integer.compare(a.x, b.x);
        });
    }

    /** 序数 → 0 基下标；越界返回 -1 */
    private static int ordinalIndex(int ordinal, int n) {
        int idx;
        if (ordinal > 0) {
            idx = ordinal - 1;
        } else if (ordinal == -2) {  // 后
            idx = n - 1;
        } else {  // 中
            idx = (n % 2 == 0) ? n / 2 : (n + 1) / 2 - 1;
        }
        return (idx >= 0 && idx < n) ? idx : -1;
    }

    private static void addPositionOnce(List<Position> list, Position p) {
        for (Position q : list) {
            if (q.x == p.x && q.y == p.y) return;
        }
        list.add(p);
    }

    /** 根据 action + num2 计算目标位置 */
    private static Position computeTo(Position from, int piece, char action, char num2Char, boolean isRed) {
        boolean isDiagonal = Piece.isDiagonalPiece(piece);
        int fromX = from.x, fromY = from.y;

        if (action == '平') {
            int vLine2 = isRed ? CHS_TO_NUM.getOrDefault(num2Char, -1) : digitToInt(num2Char);
            if (vLine2 < 1 || vLine2 > 9) return null;
            int toX = isRed ? (9 - vLine2) : (vLine2 - 1);
            return new Position(toX, fromY);
        }

        if (action == '进' || action == '退') {
            int dirY;
            if (isRed) {
                dirY = (action == '进') ? -1 : 1;
            } else {
                dirY = (action == '进') ? 1 : -1;
            }

            if (isDiagonal) {
                int vLine2 = isRed ? CHS_TO_NUM.getOrDefault(num2Char, -1) : digitToInt(num2Char);
                if (vLine2 < 1 || vLine2 > 9) return null;
                int toX = isRed ? (9 - vLine2) : (vLine2 - 1);
                int dx = Math.abs(toX - fromX);
                int[] dyCandidates;
                if (piece == Piece.WMA || piece == Piece.BMA) {
                    if (dx == 1) dyCandidates = new int[]{2};
                    else if (dx == 2) dyCandidates = new int[]{1};
                    else return null;
                } else if (piece == Piece.WXIANG || piece == Piece.BXIANG) {
                    if (dx != 2) return null;
                    dyCandidates = new int[]{2};
                } else {
                    if (dx != 1) return null;
                    dyCandidates = new int[]{1};
                }
                for (int dy : dyCandidates) {
                    int toY = fromY + dirY * dy;
                    if (toY < 0 || toY > 9) continue;
                    return new Position(toX, toY);
                }
                return null;
            } else {
                int steps = isRed ? CHS_TO_NUM.getOrDefault(num2Char, -1) : digitToInt(num2Char);
                if (steps < 1 || steps > 9) return null;
                int toY = fromY + dirY * steps;
                if (toY < 0 || toY > 9) return null;
                return new Position(fromX, toY);
            }
        }
        return null;
    }
}
