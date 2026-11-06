package com.mandgamit.chess.views;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import com.mandgamit.chess.ChessApp;
import com.mandgamit.chess.R;
import com.mandgamit.chess.controllers.GameController;
import com.mandgamit.chess.gamelogic.Board;
import com.mandgamit.chess.gamelogic.Game;
import com.mandgamit.chess.gamelogic.Move;
import com.mandgamit.chess.gamelogic.Piece;
import com.mandgamit.chess.gamelogic.Position;
import com.mandgamit.chess.utils.ArrowShape;
import com.mandgamit.chess.utils.DrawableUtil;

import android.graphics.Path;


public class ChessView extends SurfaceView implements SurfaceHolder.Callback {
    public ChessViewThread thread;

    protected static class XYCoord {
        public int x;
        public int y;
        public XYCoord(int x, int y) { this.x = x; this.y = y; }
    }
    public Paint paint;

    public Bitmap ChessBoardBitmap;
    public Bitmap B_box, R_box, R_pot, B_pot;
    public Bitmap[] PieceBitmaps = new Bitmap[14];
    public Bitmap[] ChoiceBitmaps = new Bitmap[5];
    public Bitmap ThinkBitmap;
    final int MAX_SUGGESTED_MOVES = 5;

    // 如何设置下面的几个参数：
    // 有2个假设：棋盘的每个格子是正方形的, 棋子也是正方形的
    // 棋盘几何统一来源：BoardGeometry.kt（换棋盘图只改那一处）
    final int BOARD_WIDTH = BoardGeometry.BOARD_WIDTH;
    final int BOARD_HEIGHT = BoardGeometry.BOARD_HEIGHT;
    static final int BOARD_PIECE_SIZE = BoardGeometry.PIECE_SIZE;
    static final int BOARD_X_OFFSET = BoardGeometry.X_OFFSET;
    static final int BOARD_Y_OFFSET = BoardGeometry.Y_OFFSET;
    static final int BOARD_GRID_INTERVAL = BoardGeometry.GRID_INTERVAL;

    public Rect srcBoardRect, destBoardRect;
    public int Board_width, Board_height;
    public float scaleRatio;

    public GameController controller;

    public boolean flipped = false;

    /** 盲棋模式：隐藏所有棋子绘制，仅保留棋盘底图 + 选中位置的可走点提示 */
    public boolean blindMode = false;


    public ChessView(Context context, GameController controller) {
        super(context);
        this.controller = controller;
        // 棋盘PNG四角为圆角透明：SurfaceView 默认 surface 不透明（黑底凿洞），
        // 需置于窗口最上层并使用半透明像素格式，透明区域才能露出页面背景
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        initBitmaps();
    }

    public void initBitmaps() {
        ChessBoardBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.chessboard);
        srcBoardRect = new Rect(0, 0, ChessBoardBitmap.getWidth(), ChessBoardBitmap.getHeight());

        B_box = BitmapFactory.decodeResource(getResources(), R.drawable.b_box);
        R_box = BitmapFactory.decodeResource(getResources(), R.drawable.r_box);
        R_pot = BitmapFactory.decodeResource(getResources(), R.drawable.redpot);
        B_pot = BitmapFactory.decodeResource(getResources(), R.drawable.blackpot);

        // these values should be consistent with Piece.java
        PieceBitmaps[0] = BitmapFactory.decodeResource(getResources(), R.drawable.r_shuai);
        PieceBitmaps[1] = BitmapFactory.decodeResource(getResources(), R.drawable.r_shi);
        PieceBitmaps[2] = BitmapFactory.decodeResource(getResources(), R.drawable.r_xiang);
        PieceBitmaps[3] = BitmapFactory.decodeResource(getResources(), R.drawable.r_ma);
        PieceBitmaps[4] = BitmapFactory.decodeResource(getResources(), R.drawable.r_ju);
        PieceBitmaps[5] = BitmapFactory.decodeResource(getResources(), R.drawable.r_pao);
        PieceBitmaps[6] = BitmapFactory.decodeResource(getResources(), R.drawable.r_bing);
        PieceBitmaps[7] = BitmapFactory.decodeResource(getResources(), R.drawable.b_jiang);
        PieceBitmaps[8] = BitmapFactory.decodeResource(getResources(), R.drawable.b_shi);
        PieceBitmaps[9] = BitmapFactory.decodeResource(getResources(), R.drawable.b_xiang);
        PieceBitmaps[10] = BitmapFactory.decodeResource(getResources(), R.drawable.b_ma);
        PieceBitmaps[11] = BitmapFactory.decodeResource(getResources(), R.drawable.b_ju);
        PieceBitmaps[12] = BitmapFactory.decodeResource(getResources(), R.drawable.b_pao);
        PieceBitmaps[13] = BitmapFactory.decodeResource(getResources(), R.drawable.b_zu);

        // load drawables for choice bitmaps
        ChoiceBitmaps[0] = BitmapFactory.decodeResource(getResources(), R.drawable.digit1);
        ChoiceBitmaps[1] = BitmapFactory.decodeResource(getResources(), R.drawable.digit2);
        ChoiceBitmaps[2] = BitmapFactory.decodeResource(getResources(), R.drawable.digit3);
        ChoiceBitmaps[3] = BitmapFactory.decodeResource(getResources(), R.drawable.digit4);
        ChoiceBitmaps[4] = BitmapFactory.decodeResource(getResources(), R.drawable.digit5);

        // get drawable for think state
        ThinkBitmap = DrawableUtil.drawableToBitmap(ChessApp.getContext().getDrawable(R.drawable.intelligence));
    }

    public void Draw(Canvas canvas) {
        Game game = controller.game;
        if(canvas == null) {
            return;
        }
        // 每帧先清空画布，保证圆角透明区域不残留黑底或上一帧画面
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        // draw chess board
        canvas.drawBitmap(ChessBoardBitmap, srcBoardRect, destBoardRect, null);

        Board board = game.currentBoard;

        // draw controller state in the middle of destBoardRect
        showControllerState(canvas);

        // draw piece（盲棋模式跳过棋子绘制）
        if (!blindMode) {
            Rect tempSrcRect, tempDesRect;
            for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
                for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
                    Position pos = new Position(x, y);
                    int piece = board.getPieceByPosition(pos);
                    if (Piece.isValid(piece)) {
                        // valid piece, draw the bitmap
                        Bitmap bitmap = PieceBitmaps[piece-1];
                        tempSrcRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                        tempDesRect = getDestRect(pos);
                        canvas.drawBitmap(bitmap, tempSrcRect, tempDesRect, null);
                    }
                }
            }

            if(game.startPos != null) {
                // highlight selected piece
                HighlighSelectedPiece(canvas);

                // show all possible moves for selected piece
                int piece = game.currentBoard.getPieceByPosition(game.startPos);
                // draw all possible moves
                if(Piece.isRed(piece)) {
                    tempSrcRect = new Rect(0, 0, R_pot.getWidth(), R_pot.getHeight());
                    for (Position pos : game.possibleToPositions) {
                        tempDesRect = getDestRect(pos);
                        canvas.drawBitmap(R_pot, tempSrcRect, tempDesRect, null);
                    }
                } else if(Piece.isBlack(piece)){
                    tempSrcRect = new Rect(0, 0, B_pot.getWidth(), B_pot.getHeight());
                    for (Position pos : game.possibleToPositions) {
                        tempDesRect = getDestRect(pos);
                        canvas.drawBitmap(B_pot, tempSrcRect, tempDesRect, null);
                    }
                }
            }

            // draw arrows for last moves
            if(game.history.size() > 0) {
                DrawMoveHistory(canvas);
            }
        } else {
            // 盲棋模式：仅显示选中位置的可走点提示（帮助玩家确认选对了棋子）
            if(game.startPos != null) {
                HighlighSelectedPiece(canvas);
                int piece = game.currentBoard.getPieceByPosition(game.startPos);
                Rect tempSrcRect, tempDesRect;
                if(Piece.isRed(piece)) {
                    tempSrcRect = new Rect(0, 0, R_pot.getWidth(), R_pot.getHeight());
                    for (Position pos : game.possibleToPositions) {
                        tempDesRect = getDestRect(pos);
                        canvas.drawBitmap(R_pot, tempSrcRect, tempDesRect, null);
                    }
                } else if(Piece.isBlack(piece)){
                    tempSrcRect = new Rect(0, 0, B_pot.getWidth(), B_pot.getHeight());
                    for (Position pos : game.possibleToPositions) {
                        tempDesRect = getDestRect(pos);
                        canvas.drawBitmap(B_pot, tempSrcRect, tempDesRect, null);
                    }
                }
            }
        }

        // if there are suggested moves, show them on the board
        if(game.suggestedMoves.size() > 0) {
            for(int i = 0; i < game.suggestedMoves.size() && i < MAX_SUGGESTED_MOVES ; i++) {
                Move move = game.suggestedMoves.get(i);
                XYCoord crd0 = getCoordByPosition(move.fromPosition);
                XYCoord crd1 = getCoordByPosition(move.toPosition);
                Paint p = new Paint();
                p.setStyle(Paint.Style.FILL);
                p.setAntiAlias(true);
                p.setColor(Color.GREEN);
                DrawArrow(canvas, crd0, crd1, p, ChoiceBitmaps[i]);
            }
        }
    }

    private void showControllerState(Canvas canvas) {
        int targetSize = Scale(30);
        int xOffset = Scale(BOARD_GRID_INTERVAL * 4 + 45);
        if (flipped) {
            xOffset = -xOffset;
        }
        Rect tempDesRect = new Rect(destBoardRect.centerX() - targetSize + xOffset, destBoardRect.centerY() - targetSize,
                destBoardRect.centerX() + targetSize + xOffset, destBoardRect.centerY() + targetSize);
        if (controller.isEngineBusy()) {
            // 引擎搜索/评估/后台分析中：显示思考动画
            Bitmap bitmap = ThinkBitmap;
            Rect tempSrcRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            canvas.drawBitmap(bitmap, tempSrcRect, tempDesRect, null);
        } else if (controller.isRedTurn()) {
            Bitmap bitmap = PieceBitmaps[0];
            Rect tempSrcRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            canvas.drawBitmap(bitmap, tempSrcRect, tempDesRect, null);
        } else {
            Bitmap bitmap = PieceBitmaps[7];
            Rect tempSrcRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            canvas.drawBitmap(bitmap, tempSrcRect, tempDesRect, null);
        }
    }

    private void HighlighSelectedPiece(Canvas canvas) {
        // draw selected piece
        Game game = controller.game;
        Board board = game.currentBoard;
        Rect tempDesRect, tempSrcRect;
        Position pos = game.startPos;
        int piece = board.getPieceByPosition(pos);
        if (Piece.isValid(piece)) {
            // valid piece is selected
            tempDesRect = getDestRect(pos);
            if (Piece.isRed(piece)) {
                tempSrcRect = new Rect(0, 0, R_box.getWidth(), R_box.getHeight());
                canvas.drawBitmap(R_box, tempSrcRect, tempDesRect, null);
            } else {
                tempSrcRect = new Rect(0, 0, B_box.getWidth(), B_box.getHeight());
                canvas.drawBitmap(B_box, tempSrcRect, tempDesRect, null);
            }
        }
    }

    private void DrawMoveHistory(Canvas canvas) {
        Game game = controller.game;
        Board board = game.currentBoard;
        XYCoord crd0, crd1;

        Paint p = new Paint();
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(true);

        // draw arrow for the last several moves in historyMoves
        int num_of_history_moves = 2;
        if(controller!= null && controller.settings != null) {
            num_of_history_moves = controller.settings.getHistory_moves();
        }
        for(int i = game.history.size() - 1; i >= 0 && i >= game.history.size() - num_of_history_moves; i--) {
            Game.HistoryRecord record = game.history.get(i);
            crd0 = getCoordByPosition(record.move.fromPosition);
            crd1 = getCoordByPosition(record.move.toPosition);

            // color
            if(Piece.isRed(record.move.piece)) {
                p.setColor(Color.RED);
            } else {
                p.setColor(Color.BLACK);
            }

            // calculate alpha value, the last move is the most opaque one
            int idx = (game.history.size() - 1 - i);
            int value = 220 - idx * 40;
            if(value < 0) value = 0;
            p.setAlpha(value);

            DrawArrow(canvas, crd0, crd1, p, null);
        }
    }

    /*
        * 画箭头，并在箭头上显示bitmap。这个bitmap一般是数字，来标识箭头
     */
    void DrawArrow(Canvas canvas, XYCoord crd0, XYCoord crd1, Paint p, Bitmap bitmap) {
        ArrowShape arrow = new ArrowShape();
        Path path = new Path();
        arrow.getTransformedPath(path, crd0.x, crd0.y, crd1.x, crd1.y);
        canvas.drawPath(path, p);

        if(bitmap != null) {
            int offset_to_endpos = 80;
            int width_of_bitmap = 80;
            // 找到合适的位置，然后在那个位置画bitmap

            // 离crd1 offset_to_endpos个像素的位置
            XYCoord crd3 = new XYCoord(0, 0);
            int dx = crd1.x - crd0.x;
            int dy = crd1.y - crd0.y;
            int d = (int)Math.sqrt(dx*dx + dy*dy);
            crd3.x = crd1.x - offset_to_endpos * dx / d;
            crd3.y = crd1.y - offset_to_endpos * dy / d;

            // 两者之间3/5的位置
            XYCoord crd4 = new XYCoord((crd0.x*2 + crd1.x*3) / 5, (crd0.y*2 + crd1.y*3) / 5);

            // 取离crd1最近的点, 防止箭头太长时，数字离箭头太远
            int d3 = (crd3.x - crd1.x) * (crd3.x - crd1.x) + (crd3.y - crd1.y) * (crd3.y - crd1.y);
            int d4 = (crd4.x - crd1.x) * (crd4.x - crd1.x) + (crd4.y - crd1.y) * (crd4.y - crd1.y);
            XYCoord crd = d3 < d4 ? crd3 : crd4;

            // draw bitmap to crd position
            int sx = bitmap.getWidth();
            int sy = bitmap.getHeight();
            int nx = width_of_bitmap / 2;
            int ny = nx * sy / sx / 2;
            Rect tempSrcRect = new Rect(0, 0, sx, sy);
            Rect tempDesRect = new Rect(crd.x - nx, crd.y - ny, crd.x + nx, crd.y + ny);
            canvas.drawBitmap(bitmap, tempSrcRect, tempDesRect, null);
        }
    }

    public int Scale(int x) {
        return (int)(x * scaleRatio);
    }

        @NonNull
    private Rect getDestRect(Position pos) {
        int dx = flipped ? Board.BOARD_PIECE_WIDTH - 1 - pos.x : pos.x;
        int dy = flipped ? Board.BOARD_PIECE_HEIGHT - 1 - pos.y : pos.y;
        return new Rect(
                Scale(dx * BOARD_GRID_INTERVAL + BOARD_X_OFFSET),
                Scale(dy * BOARD_GRID_INTERVAL + BOARD_Y_OFFSET),
                Scale(dx * BOARD_GRID_INTERVAL + BOARD_X_OFFSET + BOARD_PIECE_SIZE),
                Scale(dy * BOARD_GRID_INTERVAL + BOARD_Y_OFFSET + BOARD_PIECE_SIZE));
    }


    public XYCoord getCoordByPosition(Position pos) {
        Rect r = getDestRect(pos);
        return new XYCoord(r.centerX(), r.centerY());
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        Board_width = MeasureSpec.getSize(widthMeasureSpec);
        Board_height = Board_width * BOARD_HEIGHT / BOARD_WIDTH;

        // 短屏幕上按宽度算出的高度可能超过可用空间（棋盘顶部到信息卡片顶部），
        // 导致棋盘盖住下方信息卡片。此时改用可用高度反推宽度，保持 1414:1716 比例不变形。
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int maxHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (heightMode != MeasureSpec.UNSPECIFIED && maxHeight > 0 && Board_height > maxHeight) {
            Board_height = maxHeight;
            Board_width = Board_height * BOARD_WIDTH / BOARD_HEIGHT;
        }

        scaleRatio = (float) Board_width / BOARD_WIDTH;
        destBoardRect = new Rect(0, 0, Board_width, Board_height);
        setMeasuredDimension(Board_width, Board_height);
    }


    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
    }

    public void surfaceCreated(SurfaceHolder holder) {
        this.thread = new ChessViewThread(getHolder());
        this.thread.start();
    }

    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    public Position getPosByCoord(float x, float y) {
        float vx = x / scaleRatio;
        float vy = y / scaleRatio;

        int ix = (int)((vx - BOARD_X_OFFSET) / BOARD_GRID_INTERVAL);
        int iy = (int)((vy - BOARD_Y_OFFSET) / BOARD_GRID_INTERVAL);

        if (flipped) {
            ix = Board.BOARD_PIECE_WIDTH - 1 - ix;
            iy = Board.BOARD_PIECE_HEIGHT - 1 - iy;
        }

        Rect rect = getDestRect(new Position(ix, iy));
        if(rect.contains((int)x, (int)y)) {
            Log.d("ChessView", "getPosByCoord: " + ix + ", " + iy);
            return new Position(ix, iy);
        } else {
            Log.d("ChessView", "getPosByCoord: " + "out of bound");
        }

        return null;
    }

    class ChessViewThread extends Thread {
        //刷帧线程
        public int span = 100;//睡眠100毫秒数
        public SurfaceHolder surfaceHolder;

        public ChessViewThread(SurfaceHolder surfaceHolder) {
            this.surfaceHolder = surfaceHolder;
        }

        public void run() {//重写的方法
            Canvas c;//画布
            while (true) {//循环绘制
                c = this.surfaceHolder.lockCanvas();
                try {
                    Draw(c);//绘制方法
                } catch (Exception e) {
                    e.printStackTrace();//输出异常堆栈信息
                }
                if (c != null) this.surfaceHolder.unlockCanvasAndPost(c);
                try {
                    Thread.sleep(span);//睡眠时间，单位是毫秒
                } catch (Exception e) {
                    e.printStackTrace();//输出异常堆栈信息
                }
            }
        }
    }
}