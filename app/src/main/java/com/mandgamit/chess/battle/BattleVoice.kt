package com.mandgamit.chess.battle

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * 好友对战语音消息：AMR-NB 12.2kbps / 8kHz，最长 20 秒。
 * 数据不落地保留：录音只写 cacheDir 临时文件，发送/取消后立即删除；
 * 接收方解码到临时文件，播完即删。
 */
object BattleVoice {
    const val MAX_DURATION_MS = 20_000
    const val MIN_DURATION_MS = 800

    private const val TAG = "BattleVoice"
    private const val REC_FILE = "battle_voice_rec.amr"
    private const val PLAY_FILE = "battle_voice_play.amr"

    /** 按住说话录音器。同一时刻只允许一段录音。 */
    class Recorder(private val context: Context) {

        /** 到达 20 秒上限自动停止（回调在主线程），UI 应接着弹出发送确认框。 */
        var onMaxDurationReached: (() -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())
        private var recorder: MediaRecorder? = null
        private var outFile: File? = null
        private var startAt = 0L
        private var autoStopped = false

        val isRecording: Boolean get() = recorder != null

        /** 当前已录音时长（毫秒），未在录音返回 0。 */
        val elapsedMs: Long
            get() = if (recorder != null) System.currentTimeMillis() - startAt else 0L

        /** 需要 RECORD_AUDIO 权限（调用方保证已申请）。返回 false 表示启动失败。 */
        fun start(): Boolean {
            if (recorder != null) return false
            return try {
                val file = File(context.cacheDir, REC_FILE)
                file.delete()
                @Suppress("DEPRECATION")
                val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    MediaRecorder()
                }
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                r.setAudioSamplingRate(8000)
                r.setAudioEncodingBitRate(12200)
                r.setMaxDuration(MAX_DURATION_MS)
                r.setOutputFile(file.absolutePath)
                r.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        autoStopped = true
                        mainHandler.post { onMaxDurationReached?.invoke() }
                    }
                }
                r.prepare()
                r.start()
                recorder = r
                outFile = file
                startAt = System.currentTimeMillis()
                autoStopped = false
                true
            } catch (t: Throwable) {
                Log.w(TAG, "recorder start failed", t)
                releaseRecorder()
                false
            }
        }

        /** 录音结果：AMR 数据与实际时长。 */
        class Result(val bytes: ByteArray, val durationMs: Int)

        /**
         * 正常结束（松手/到时长）并取出编码数据；临时文件随即删除。
         * 时长过短或读取失败返回 null。
         */
        fun finish(): Result? {
            val file = outFile
            val duration = elapsedMs.toInt()
            releaseRecorder()
            if (file == null) return null
            return try {
                if (!autoStopped && duration < MIN_DURATION_MS) {
                    null
                } else {
                    Result(file.readBytes(), duration)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "read recording failed", t)
                null
            } finally {
                file.delete()
            }
        }

        /** 放弃本次录音（确认框"取消"/界面销毁）。 */
        fun cancel() {
            releaseRecorder()
            outFile?.delete()
            outFile = null
        }

        private fun releaseRecorder() {
            val r = recorder ?: return
            recorder = null
            try {
                r.setOnInfoListener(null)
                r.stop()
            } catch (t: Throwable) {
                // stop 在极短录音等场景会抛 RuntimeException，按失败处理
                Log.d(TAG, "recorder stop: ${t.message}")
            }
            try {
                r.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** 接收侧语音播放器。同时只播一条，新语音到达时打断旧语音。 */
    class Player(private val context: Context) {

        /** 播放状态变化（主线程）：true 开始播放，false 播完/被打断。用于喇叭动画与音效互斥。 */
        var onPlayStateChanged: ((Boolean) -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())
        private var player: MediaPlayer? = null
        private var playFile: File? = null

        val isPlaying: Boolean get() = player != null

        fun play(amrBytes: ByteArray) {
            stopInternal()
            try {
                val file = File(context.cacheDir, PLAY_FILE)
                file.writeBytes(amrBytes)
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { mainHandler.post { stopInternal() } }
                mp.setOnErrorListener { _, _, _ ->
                    mainHandler.post { stopInternal() }
                    true
                }
                mp.prepare()
                mp.start()
                player = mp
                playFile = file
                notifyState(true)
            } catch (t: Throwable) {
                Log.w(TAG, "play failed", t)
                stopInternal()
            }
        }

        fun stop() = stopInternal()

        private fun stopInternal() {
            val hadPlayer = player != null
            player?.let {
                try {
                    it.stop()
                } catch (_: Throwable) {
                }
                try {
                    it.release()
                } catch (_: Throwable) {
                }
            }
            player = null
            playFile?.delete()
            playFile = null
            if (hadPlayer) notifyState(false)
        }

        private fun notifyState(playing: Boolean) {
            mainHandler.post { onPlayStateChanged?.invoke(playing) }
        }
    }
}
