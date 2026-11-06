# XQF -> PGN batch converter (PowerShell + embedded C#)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File xqf2pgn.ps1 -Root "E:\RedCliffsCChess\XQF单局"
param([string]$Root = ".")

$ErrorActionPreference = "Stop"

$cs = @'
using System;
using System.Collections.Generic;
using System.Text;

public class MoveNodeT {
    public int fx = -1, fy = -1, tx = -1, ty = -1;
    public string comment;
    public List<MoveNodeT> Next = new List<MoveNodeT>();
}

public class XqfGameT {
    public string Title, Event, Date, Site, Red, Black, Annotator, Author, Annotation;
    public string Result = "*";
    public int[,] StartBoard;
    public MoveNodeT Head = new MoveNodeT();
}

public class XqfPgn {
    static Encoding GB;
    static XqfPgn() {
        try { GB = Encoding.GetEncoding(54936); } catch { GB = Encoding.GetEncoding(936); }
    }

    const int WSHUAI=1, WSHI=2, WXIANG=3, WMA=4, WJU=5, WPAO=6, WBING=7;
    const int BJIANG=8, BSHI=9, BXIANG=10, BMA=11, BJU=12, BPAO=13, BZU=14;

    static readonly char[] FEN_C = {' ', 'K','A','B','N','R','C','P','k','a','b','n','r','c','p'};
    // C# literals use \uXXXX escapes so the script stays encoding-safe
    static readonly string[] NAME = { "", "\u5E05", "\u4ED5", "\u76F8", "\u9A6C", "\u8F66", "\u70AE", "\u5175",
                                       "\u5C06", "\u58EB", "\u8C61", "\u9A6C", "\u8F66", "\u70AE", "\u5352" };
    static readonly string[] NUM = { "", "\u4E00","\u4E8C","\u4E09","\u56DB","\u4E94","\u516D","\u4E03","\u516B","\u4E5D" };
    static readonly int[] KINDS = { WJU,WMA,WXIANG,WSHI,WSHUAI,WSHI,WXIANG,WMA,WJU, WPAO,WPAO,
                                    WBING,WBING,WBING,WBING,WBING,
                                    BJU,BMA,BXIANG,BSHI,BJIANG,BSHI,BXIANG,BMA,BJU, BPAO,BPAO,
                                    BZU,BZU,BZU,BZU,BZU };

    static bool Diag(int p) { return p==WXIANG||p==BXIANG||p==WSHI||p==BSHI||p==WMA||p==BMA; }

    static int G(int[,] b, int x, int y) { return (x>=0 && x<9 && y>=0 && y<10) ? b[y,x] : -1; }

    static int[,] CloneB(int[,] b) {
        int[,] n = new int[10,9];
        for (int y=0;y<10;y++) for (int x=0;x<9;x++) n[y,x]=b[y,x];
        return n;
    }

    static string Fen(int[,] b) {
        StringBuilder sb = new StringBuilder();
        for (int y=0;y<10;y++) {
            int z=0;
            for (int x=0;x<9;x++) {
                int p=b[y,x];
                if (p==0) { z++; }
                else { if (z>0) { sb.Append(z); z=0; } sb.Append(FEN_C[p]); }
            }
            if (z>0) sb.Append(z);
            if (y<9) sb.Append('/');
        }
        sb.Append(" w - - 0 1");
        return sb.ToString();
    }

    // prefix for same-type pieces on the same vertical line (qian/hou/zhong...)
    static string VPrefix(int[,] b, int piece, int fx, int fy) {
        if (piece==WSHUAI||piece==WSHI||piece==WXIANG||piece==BJIANG||piece==BSHI||piece==BXIANG) return null;
        int count=0, index=0;
        if (piece <= WBING) {
            for (int y=0;y<10;y++) { if (G(b,fx,y)==piece) { count++; if (y==fy) index=count; } }
        } else {
            for (int y=9;y>=0;y--) { if (G(b,fx,y)==piece) { count++; if (y==fy) index=count; } }
        }
        if (count==1) return null;
        if (piece!=WBING && piece!=BZU) return index==1 ? "\u524D" : "\u540E";
        if (index==1 && count<=3) return "\u524D";
        if (index==count && count<=3) return "\u540E";
        if (index==2 && count==3) return "\u4E2D";
        return piece <= WBING ? NUM[index] : index.ToString();
    }

    // coordinate move -> Chinese notation (board state = before the move)
    static string Chs(int[,] b, int fx, int fy, int tx, int ty) {
        int piece = G(b, fx, fy);
        if (piece < 1 || piece > 14) return "\u672A\u77E5\u52A8\u4F5C";
        bool red = piece <= WBING;
        string num1, action, num2;
        if (red) {
            num1 = NUM[9 - fx];
            if (ty < fy) action = "\u8FDB"; else if (ty > fy) action = "\u9000"; else action = "\u5E73";
            if (action == "\u5E73" || Diag(piece)) num2 = NUM[9 - tx]; else num2 = NUM[Math.Abs(ty - fy)];
        } else {
            num1 = (fx + 1).ToString();
            if (ty > fy) action = "\u8FDB"; else if (ty < fy) action = "\u9000"; else action = "\u5E73";
            if (action == "\u5E73" || Diag(piece)) num2 = (tx + 1).ToString(); else num2 = Math.Abs(ty - fy).ToString();
        }
        string pre = VPrefix(b, piece, fx, fy);
        if (pre == null) return NAME[piece] + num1 + action + num2;
        return pre + NAME[piece] + action + num2;
    }

    class Keys { public int XY, XYf, XYt, RMK; public int[] F32 = new int[32]; }

    static int Poly(int b, int mul) {
        int t = b*b*3 + 9;
        t = t*3 + 8;
        t = t*2 + 1;
        t = t*3 + 8;
        return (t * mul) & 0xFF;
    }

    static Keys MakeKeys(byte[] d) {
        Keys k = new Keys();
        int mask = d[3] & 0xFF;
        int orA = d[8] & 0xFF, orB = d[9] & 0xFF, orC = d[10] & 0xFF, orD = d[11] & 0xFF;
        int sum = d[12] & 0xFF, xy = d[13] & 0xFF, xyf = d[14] & 0xFF, xyt = d[15] & 0xFF;
        k.XY  = Poly(xy, xy);
        k.XYf = Poly(xyf, k.XY);
        k.XYt = Poly(xyt, k.XYf);
        // RMK and the four key bytes use the RAW header bytes (buffer[12..15]),
        // NOT the derived k.XY/k.XYf/k.XYt (those are only for pieces & moves).
        k.RMK = ((sum * 256 + xy) % 32000 + 767) & 0xFFFF;
        int[] fkb = new int[4];
        fkb[0] = (sum  & mask) | orA;
        fkb[1] = (xy   & mask) | orB;
        fkb[2] = (xyf  & mask) | orC;
        fkb[3] = (xyt  & mask) | orD;
        byte[] s = Encoding.ASCII.GetBytes("[(C) Copyright Mr. Dong Shiwei.]");
        for (int i = 0; i < 32; i++) k.F32[i] = s[i] & fkb[i % 4];
        return k;
    }

    static byte[] PiecePos(byte[] d, int version, Keys k) {
        byte[] tmp = new byte[32];
        if (k == null) { Array.Copy(d, 16, tmp, 0, 32); return tmp; }
        for (int i = 0; i < 32; i++) {
            if (version >= 12) tmp[(k.XY + i + 1) & 0x1F] = d[16 + i];
            else tmp[i] = d[16 + i];
        }
        for (int i = 0; i < 32; i++) {
            tmp[i] = (byte)((tmp[i] - k.XY) & 0xFF);
            if (tmp[i] > 89) tmp[i] = 0xFF;
        }
        return tmp;
    }

    static string HStr(byte[] d, int off, int cap) {
        int len = d[off] & 0xFF;
        if (len > cap - 1) len = cap - 1;
        return GB.GetString(d, off + 1, len).Trim('\0').Trim();
    }

    class Rd {
        public byte[] D; public int Pos;
        public byte[] Take(int n) {
            if (Pos >= D.Length) return null;
            int real = Math.Min(n, D.Length - Pos);
            byte[] r = new byte[real];
            Array.Copy(D, Pos, r, 0, real);
            Pos += real;
            return r;
        }
        public int I32() {
            byte[] b = Take(4);
            if (b == null || b.Length < 4) return 0;
            return b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24);
        }
        public string Str(int n) {
            if (n <= 0) return null;
            if (n > D.Length - Pos) n = D.Length - Pos;
            if (n <= 0) return null;
            string s = GB.GetString(D, Pos, n);
            Pos += n;
            return s.Trim('\0').Trim();
        }
    }

    // recursive move-tree reader; variations become extra children of parent
    static void Steps(Rd r, int version, Keys k, MoveNodeT parent) {
        byte[] si = r.Take(4);
        if (si == null || si.Length < 4) return;
        bool hasNext = false, hasVar = false;
        int al = 0, mf, mt;
        if (version <= 0x0A) {
            hasNext = (si[2] & 0xF0) != 0;
            hasVar  = (si[2] & 0x0F) != 0;
            al = r.I32();
            mf = ((si[0] & 0xFF) - 0x18) & 0xFF;
            mt = ((si[1] & 0xFF) - 0x20) & 0xFF;
        } else {
            int f2 = si[2] & 0xE0;
            hasNext = (f2 & 0x80) != 0;
            hasVar  = (f2 & 0x40) != 0;
            if ((f2 & 0x20) != 0) al = r.I32() - k.RMK;
            mf = ((((si[0] & 0xFF) - 0x18) & 0xFF) - k.XYf) & 0xFF;
            mt = ((((si[1] & 0xFF) - 0x20) & 0xFF) - k.XYt) & 0xFF;
        }
        MoveNodeT n = new MoveNodeT();
        n.fx = mf / 10; n.fy = 9 - (mf % 10);
        n.tx = mt / 10; n.ty = 9 - (mt % 10);
        if (al > 0) n.comment = r.Str(al);
        parent.Next.Add(n);
        if (hasNext) Steps(r, version, k, n);
        if (hasVar)  Steps(r, version, k, parent);
    }

    static string Cmt(string c) {
        if (c == null) return null;
        return c.Replace("{", "(").Replace("}", ")").Replace("\r", " ").Replace("\n", " ").Trim();
    }

    static string Esc(string s) {
        if (s == null) return "";
        return s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", " ").Replace("\n", " ");
    }

    static void Do(int[,] b, int fx, int fy, int tx, int ty) { b[ty, tx] = b[fy, fx]; b[fy, fx] = 0; }

    static string Emit(MoveNodeT n, int[,] bd, int moveNum, bool red) {
        bool ok = n.fx>=0 && n.fx<9 && n.fy>=0 && n.fy<10 && n.tx>=0 && n.tx<9 && n.ty>=0 && n.ty<10;
        string s;
        if (ok) {
            s = red ? (" " + moveNum + ". ") : " ";
            s += Chs(bd, n.fx, n.fy, n.tx, n.ty);
            Do(bd, n.fx, n.fy, n.tx, n.ty);
        } else {
            s = red ? (" " + moveNum + ". ?") : " ?";
        }
        string c = Cmt(n.comment);
        if (c != null && c.Length > 0) s += " {" + c + "}";
        if (n.Next.Count == 0) return s;
        int nn = red ? moveNum : moveNum + 1;
        bool nr = !red;
        // variations (extra children) must appear right after this move,
        // before the main continuation
        for (int i = 1; i < n.Next.Count; i++) {
            s += " (" + Emit(n.Next[i], CloneB(bd), nn, nr).TrimStart() + ")";
        }
        s += Emit(n.Next[0], bd, nn, nr);
        return s;
    }

    public static XqfGameT Parse(byte[] d) {
        if (d == null || d.Length < 0x400) return null;
        if (d[0] != (byte)'X' || d[1] != (byte)'Q') return null;
        int version = d[2];
        Keys k = version > 0x0A ? MakeKeys(d) : null;

        XqfGameT g = new XqfGameT();
        g.Title     = HStr(d, 80, 64);
        g.Event     = HStr(d, 208, 64);
        g.Date      = HStr(d, 272, 16);
        g.Site      = HStr(d, 288, 16);
        g.Red       = HStr(d, 304, 16);
        g.Black     = HStr(d, 320, 16);
        g.Annotator = HStr(d, 464, 16);
        g.Author    = HStr(d, 480, 16);

        int rb = d[51];
        if (rb == 1) g.Result = "1-0";
        else if (rb == 2) g.Result = "0-1";
        else if (rb == 3) g.Result = "1/2-1/2";
        else g.Result = "*";

        int[,] bd = new int[10,9];
        byte[] pp = PiecePos(d, version, k);
        for (int i = 0; i < 32; i++) {
            int v = pp[i] & 0xFF;
            if (v == 0xFF) continue;
            int x = v / 10, y = 9 - (v % 10);
            if (x >= 0 && x < 9 && y >= 0 && y < 10) bd[y, x] = KINDS[i];
        }
        g.StartBoard = bd;

        Rd r = new Rd();
        r.D = new byte[d.Length - 0x400];
        Array.Copy(d, 0x400, r.D, 0, r.D.Length);
        if (k != null) {
            byte[] dec = new byte[r.D.Length];
            for (int i = 0; i < r.D.Length; i++) {
                dec[i] = (byte)((r.D[i] - k.F32[(0x400 + i) % 32]) & 0xFF);
            }
            r.D = dec;
        }

        // game-level annotation record (no move, comment only)
        byte[] si = r.Take(4);
        if (si != null && si.Length >= 4) {
            int al = 0;
            if (version <= 0x0A) { al = r.I32(); }
            else { int f2 = si[2] & 0xE0; if ((f2 & 0x20) != 0) al = r.I32() - k.RMK; }
            if (al > 0) g.Annotation = r.Str(al);
        }

        Steps(r, version, k, g.Head);
        return g;
    }

    public static string ToPGN(XqfGameT g) {
        string ev = !string.IsNullOrEmpty(g.Event) ? g.Event
                  : (!string.IsNullOrEmpty(g.Title) ? g.Title : "");
        StringBuilder sb = new StringBuilder();
        sb.Append("[Game \"Chinese Chess\"]\r\n");
        sb.Append("[Event \"").Append(Esc(ev)).Append("\"]\r\n");
        sb.Append("[Round \"\"]\r\n");
        sb.Append("[Date \"").Append(Esc(g.Date)).Append("\"]\r\n");
        sb.Append("[Site \"").Append(Esc(g.Site)).Append("\"]\r\n");
        sb.Append("[Red \"").Append(Esc(g.Red)).Append("\"]\r\n");
        sb.Append("[RedTeam \"\"]\r\n");
        sb.Append("[Black \"").Append(Esc(g.Black)).Append("\"]\r\n");
        sb.Append("[BlackTeam \"\"]\r\n");
        sb.Append("[Result \"").Append(g.Result).Append("\"]\r\n");
        sb.Append("[FEN \"").Append(Fen(g.StartBoard)).Append("\"]\r\n");
        sb.Append("\r\n");
        string ga = Cmt(g.Annotation);
        if (ga != null && ga.Length > 0) sb.Append("{").Append(ga).Append("}\r\n");
        if (g.Head.Next.Count > 0) {
            // root-level variations first (alternatives to the opening move),
            // then the main line; trim leading space of the assembled text
            StringBuilder mv = new StringBuilder();
            for (int i = 1; i < g.Head.Next.Count; i++) {
                mv.Append(" (").Append(Emit(g.Head.Next[i], CloneB(g.StartBoard), 1, true).TrimStart()).Append(")");
            }
            mv.Append(Emit(g.Head.Next[0], CloneB(g.StartBoard), 1, true));
            sb.Append(mv.ToString().TrimStart());
        }
        sb.Append(" ").Append(g.Result).Append("\r\n");
        return sb.ToString();
    }
}
'@

Add-Type -TypeDefinition $cs -Language CSharp

if (-not (Test-Path -LiteralPath $Root)) {
    Write-Output ("ERROR dir not found: " + $Root)
    exit 1
}

$files = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter *.xqf)
Write-Output ("TOTAL " + $files.Count)

$utf8 = New-Object System.Text.UTF8Encoding($false)
$ok = 0; $fail = 0; $idx = 0
foreach ($f in $files) {
    $idx++
    try {
        $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
        $g = [XqfPgn]::Parse($bytes)
        if ($g -eq $null) { throw "not a valid XQF file" }
        $pgn = [XqfPgn]::ToPGN($g)
        $out = [System.IO.Path]::ChangeExtension($f.FullName, ".pgn")
        [System.IO.File]::WriteAllText($out, $pgn, $utf8)
        $ok++
        if ($idx % 500 -eq 0) { Write-Output ("PROGRESS " + $idx + "/" + $files.Count) }
    } catch {
        $fail++
        Write-Output ("FAIL " + $f.FullName + " : " + $_.Exception.Message)
    }
}
Write-Output ("DONE ok=" + $ok + " fail=" + $fail)
