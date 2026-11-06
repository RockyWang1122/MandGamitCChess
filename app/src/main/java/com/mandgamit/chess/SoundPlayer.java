package com.mandgamit.chess;


import android.content.Context;
import android.media.MediaPlayer;

import com.mandgamit.chess.controllers.GameController;

public class SoundPlayer {
    private final GameController controller;
    private MediaPlayer selectSound;
    private MediaPlayer moveSound;
    private MediaPlayer captureSound;
    private MediaPlayer checkSound;
    private MediaPlayer invalidSound;
    private MediaPlayer checkmateSound;
    private MediaPlayer readySound;

    /** 好友对战语音播放期间置 true：棋子点击/落子音效全部静默，避免打断语音。 */
    private boolean muted = false;

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public SoundPlayer(Context context, GameController gameController) {
        selectSound = MediaPlayer.create(context, R.raw.select);
        moveSound = MediaPlayer.create(context, R.raw.move);
        captureSound = MediaPlayer.create(context, R.raw.capture);
        checkSound = MediaPlayer.create(context, R.raw.check);
        invalidSound = MediaPlayer.create(context, R.raw.invalid);
        checkmateSound = MediaPlayer.create(context, R.raw.checkmate);
        readySound = MediaPlayer.create(context, R.raw.ready);

        controller = gameController;
    }

    /**
     * 播放一个音效。
     * 若该音效正在播放（如快速连续选中两颗棋子），先把进度拉回开头再播，
     * 否则第二次 start() 在 Started 状态下是空操作，会听不到第二次声音。
     * controller 允许为 null（如棋盘编辑界面），此时默认播放音效。
     */
    private void playSound(MediaPlayer player) {
        if (player == null || muted) {
            return;
        }
        if (controller != null && !controller.settings.getSound_effect()) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.seekTo(0);
            } else {
                player.start();
            }
        } catch (IllegalStateException ignored) {
            // MediaPlayer 状态异常时忽略本次播放，避免崩溃
        }
    }

    public void select() {
        playSound(selectSound);
    }

    public void move() {
        playSound(moveSound);
    }

    public void capture() {
        playSound(captureSound);
    }

    public void check() {
        playSound(checkSound);
    }

    public void illegal() {
        playSound(invalidSound);
    }

    public void checkmate() {
        playSound(checkmateSound);
    }

    public void ready() {
        playSound(readySound);
    }

    public void release() {
        releasePlayer(selectSound);
        releasePlayer(moveSound);
        releasePlayer(captureSound);
        releasePlayer(checkSound);
        releasePlayer(invalidSound);
        releasePlayer(checkmateSound);
        releasePlayer(readySound);
    }

    private void releasePlayer(MediaPlayer player) {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
                // 忽略释放过程中的异常
            }
        }
    }
}
