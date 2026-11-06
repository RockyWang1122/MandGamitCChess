package com.mandgamit.chess.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.mandgamit.chess.R;
import com.mandgamit.chess.gamelogic.Board;
import com.mandgamit.chess.gamelogic.Piece;
import com.mandgamit.chess.gamelogic.Position;

/**
 * 棋盘编辑专用视图：仅绘制棋盘与棋子，不依赖 GameController。
 * 坐标/位图加载逻辑与 ChessView 保持一致，确保棋子落在正确交叉点。
 */
public class BoardEditorView extends SurfaceView implements SurfaceHolder.Callback {

    public Bitmap ChessBoardBitmap;
    public Bitmap B_box, R_box;
    public Bitmap[] PieceBitmaps = new Bitmap[14];

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

    private Board board;
    /** 视角翻转开关：true 时黑方显示在下方、红方在上方，棋盘数据不变（与对弈界面 ChessView 一致） */
    public boolean flipped = false;
    /** 当前选中的棋盘位置（null 表示无棋盘选中） */
    private Position selectedPos;
    private DrawThread thread;

    public BoardEditorView(Context context) {
        super(context);
        // 棋盘PNG四角为圆角透明：SurfaceView 默认不透明黑底，需置顶并改用半透明像素格式
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        initBitmaps();
    }

    public void setBoard(Board board) {
        this.board = board;
    }

    /** 设置/清除棋盘上的选中焦点框。 */
    public void setSelectedPos(Position pos) {
        this.selectedPos = pos;
    }

    private void initBitmaps() {
        ChessBoardBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.chessboard);
        srcBoardRect = new Rect(0, 0, ChessBoardBitmap.getWidth(), ChessBoardBitmap.getHeight());

        B_box = BitmapFactory.decodeResource(getResources(), R.drawable.b_box);
        R_box = BitmapFactory.decodeResource(getResources(), R.drawable.r_box);

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
    }

    public void Draw(Canvas canvas) {
        if (canvas == null || board == null) return;

        // 每帧先清空画布，保证圆角透明区域不残留黑底或上一帧画面
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        // 棋盘底图
        canvas.drawBitmap(ChessBoardBitmap, srcBoardRect, destBoardRect, null);

        // 绘制所有棋子
        Rect tempSrcRect, tempDesRect;
        for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
            for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
                int piece = board.getPieceByPosition(x, y);
                if (Piece.isValid(piece)) {
                    Bitmap bitmap = PieceBitmaps[piece - 1];
                    tempSrcRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                    tempDesRect = getDestRect(new Position(x, y));
                    canvas.drawBitmap(bitmap, tempSrcRect, tempDesRect, null);
                }
            }
        }

        // 绘制选中焦点框（与对弈界面一致：红棋用红框、黑棋用黑框）
        if (selectedPos != null) {
            int piece = board.getPieceByPosition(selectedPos);
            if (Piece.isValid(piece)) {
                Bitmap box = Piece.isRed(piece) ? R_box : B_box;
                tempSrcRect = new Rect(0, 0, box.getWidth(), box.getHeight());
                canvas.drawBitmap(box, tempSrcRect, getDestRect(selectedPos), null);
            }
        }
    }

    public int Scale(int x) {
        return (int) (x * scaleRatio);
    }

    private Rect getDestRect(Position pos) {
        int dx = flipped ? Board.BOARD_PIECE_WIDTH - 1 - pos.x : pos.x;
        int dy = flipped ? Board.BOARD_PIECE_HEIGHT - 1 - pos.y : pos.y;
        return new Rect(
                Scale(dx * BOARD_GRID_INTERVAL + BOARD_X_OFFSET),
                Scale(dy * BOARD_GRID_INTERVAL + BOARD_Y_OFFSET),
                Scale(dx * BOARD_GRID_INTERVAL + BOARD_X_OFFSET + BOARD_PIECE_SIZE),
                Scale(dy * BOARD_GRID_INTERVAL + BOARD_Y_OFFSET + BOARD_PIECE_SIZE));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Board_width = MeasureSpec.getSize(widthMeasureSpec);
        Board_height = Board_width * BOARD_HEIGHT / BOARD_WIDTH;

        // 短屏幕上按宽度算出的高度可能超过可用空间，改用高度反推宽度，保持比例不变形
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

    public Position getPosByCoord(float x, float y) {
        float vx = x / scaleRatio;
        float vy = y / scaleRatio;
        int ix = (int) ((vx - BOARD_X_OFFSET) / BOARD_GRID_INTERVAL);
        int iy = (int) ((vy - BOARD_Y_OFFSET) / BOARD_GRID_INTERVAL);
        if (ix < 0 || ix >= Board.BOARD_PIECE_WIDTH || iy < 0 || iy >= Board.BOARD_PIECE_HEIGHT) {
            return null;
        }
        if (flipped) {
            ix = Board.BOARD_PIECE_WIDTH - 1 - ix;
            iy = Board.BOARD_PIECE_HEIGHT - 1 - iy;
        }
        Rect rect = getDestRect(new Position(ix, iy));
        if (rect.contains((int) x, (int) y)) {
            return new Position(ix, iy);
        }
        return null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        thread = new DrawThread(getHolder());
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (thread != null) {
            thread.running = false;
        }
    }

    class DrawThread extends Thread {
        public boolean running = true;
        private final SurfaceHolder surfaceHolder;

        DrawThread(SurfaceHolder surfaceHolder) {
            this.surfaceHolder = surfaceHolder;
        }

        @Override
        public void run() {
            while (running) {
                Canvas c = surfaceHolder.lockCanvas();
                if (c != null) {
                    try {
                        Draw(c);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        surfaceHolder.unlockCanvasAndPost(c);
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
}
