package com.mandgamit.chess.battle

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mandgamit.chess.R

/**
 * 好友对战前台保活服务：对局中（含建房等待阶段）切后台时保持 WebSocket 连接不被系统回收。
 *
 * 关键：持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠导致 socket 读线程卡死、连接被服务器判定超时断开；
 * 同时以 IMPORTANCE_LOW 持续通知提升进程优先级，避免被 Doze/后台限制杀死。
 * 服务在建房成功时即启动（BattleRoomDialog），离开房间/退出对局时停止。
 */
class BattleKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // ACTION_START 与系统重建（intent==null，START_STICKY）都必须在 5 秒内
            // 升到前台，否则 Android 12+ 会抛 RemoteServiceException 直接杀死进程
            else -> startForegroundInternal()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "好友对战", NotificationManager.IMPORTANCE_LOW)
        ch.description = "好友对战连接保持中"
        nm.createNotificationChannel(ch)

        // 点击通知返回：打开任务根 Activity（singleTask）。
        // 任务栈里还有对局页/大厅时原样带回栈顶界面；栈被系统清空时
        // 由 MainActivity 的 maybeRedirectToActiveBattle/maybeRedirectToLobby 送回正确页面。
        // 不能写死 GameActivity：大厅阶段服务也在运行，点通知会把浏览中的用户硬拉进对战页。
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.mandgamit.chess.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("橘中弈象棋·好友对战进行中")
            .setContentText("连接保持中，点击返回")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        // foregroundServiceType 已在 Manifest 声明为 dataSync（targetSdk 34+ 强制要求）
        ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)

        // 持有部分唤醒锁：保持 CPU 运行，WebSocket 读线程才能持续收发心跳，
        // 避免后台时连接因读阻塞被服务器 90s 超时断开
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MandGamitChess::BattleKeepAlive"
        ).apply {
            setReferenceCounted(false)
            // 不设超时：旧实现 10 分钟后自动释放，长局切后台超过 10 分钟连接即失去保活。
            // 锁的释放完全由 ACTION_STOP/onDestroy 负责；若进程被杀，系统会随进程回收唤醒锁，无泄漏风险。
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "battle_keepalive"
        private const val NOTIF_ID = 4201
        private const val ACTION_START = "com.mandgamit.chess.battle.START"
        private const val ACTION_STOP = "com.mandgamit.chess.battle.STOP"

        fun start(context: Context) {
            val i = Intent(context, BattleKeepAliveService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, BattleKeepAliveService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}

/**
 * 好友对战后台保活引导（前台服务之外的两道补充手段）：
 *
 * 1. [ensureNotificationPermission]：Android 13+ 运行时申请 POST_NOTIFICATIONS，
 *    保活通知被拒则不显示，部分国产 ROM 上会削弱前台服务的保活权重；
 * 2. [showGuideIfNeeded]：检测 Doze 电池优化白名单，建房/加入成功后引导一次，
 *    并尝试直达国产 ROM（vivo/OPPO/小米/华为/荣耀/魅族）的自启动/后台管理页。
 *
 * 前台服务 + WakeLock 只能对抗原生 Doze，国产 ROM 自家的省电/后台清理策略在此之上
 * 再杀一层，必须把用户引导到对应开关页（用户记忆中 VIVO 后台回收断线正是此场景）。
 */
object BattleBackgroundGuide {

    private const val PREFS = "battle_keepalive"
    private const val KEY_SKIP_BATTERY_GUIDE = "skip_battery_guide"
    private const val REQ_POST_NOTIFICATIONS = 4202

    /** 进程内只申请一次通知权限，避免每次建房/回前台都弹系统授权框。 */
    @Volatile
    private var notificationRequested = false

    /** 是否已在电池优化白名单（API < M 默认视为已豁免）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Android 13+ 申请通知权限；未授权时系统授权框一个进程内最多弹一次。 */
    fun ensureNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationRequested) return
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            notificationRequested = true
            return
        }
        notificationRequested = true
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_POST_NOTIFICATIONS
        )
    }

    /** 未加白名单且用户未选"不再提示"时才需要引导。 */
    fun shouldShowBatteryGuide(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SKIP_BATTERY_GUIDE, false)
    }

    /** 建房/加入成功后调用：必要时弹一次后台保活设置引导。 */
    fun showGuideIfNeeded(activity: Activity) {
        if (activity.isFinishing || !shouldShowBatteryGuide(activity)) return
        AlertDialog.Builder(activity)
            .setTitle("允许后台保持对战连接")
            .setMessage(
                "对局中切换到其他APP或锁屏时，部分手机（vivo、OPPO、小米、华为等）" +
                    "会清理后台网络，导致对战断线。\n\n建议在接下来的设置中：\n" +
                    "· 允许本应用后台运行 / 自启动\n· 关闭对本应用的电池优化"
            )
            .setPositiveButton("去设置") { _, _ -> openBackgroundSettings(activity) }
            .setNeutralButton("不再提示") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SKIP_BATTERY_GUIDE, true).apply()
            }
            .setNegativeButton("以后再说", null)
            .show()
    }

    /**
     * 打开后台保活相关设置：优先系统电池优化白名单直达弹窗，
     * 再尝试国产 ROM 自启动管理页，最后回退到本应用系统详情页。
     */
    fun openBackgroundSettings(activity: Activity): Boolean {
        // 1) 直接申请加入 Doze 白名单（系统确认框，需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !isIgnoringBatteryOptimizations(activity)
        ) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${activity.packageName}"))
            if (tryStart(activity, direct)) return true
            // 部分 ROM 屏蔽直达申请：退到电池优化全量列表
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (tryStart(activity, list)) return true
        }

        // 2) 国产 ROM：按厂商匹配自启动/后台管理候选页，逐个尝试
        for ((pkg, cls) in romAutoStartComponents()) {
            if (tryStart(activity, Intent().setClassName(pkg, cls))) return true
        }

        // 3) 兜底：应用系统详情页，用户自行进入"电量/后台"项
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${activity.packageName}"))
        return tryStart(activity, detail)
    }

    /** 先探测可解析再启动，组件在当前 ROM 不存在时静默返回 false。 */
    private fun tryStart(activity: Activity, intent: Intent): Boolean {
        if (intent.resolveActivity(activity.packageManager) == null) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 各厂商 ROM 已知的自启动/后台管理页（系统版本不同类名有差异，全部作为候选）。 */
    private fun romAutoStartComponents(): List<Pair<String, String>> {
        val mfr = Build.MANUFACTURER.lowercase()
        return when {
            mfr.contains("xiaomi") || mfr.contains("redmi") -> listOf(
                "com.miui.securitycenter" to
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            mfr.contains("oppo") || mfr.contains("realme") -> listOf(
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.permission.background.BackgroundStartingPermissions",
                "com.oppo.safe" to
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
            mfr.contains("vivo") || mfr.contains("iqoo") -> listOf(
                "com.iqoo.relationmanager" to
                    "com.iqoo.relationmanager.autostart.AutoStartManageActivity",
                "com.iqoo.relationmanager" to
                    "com.iqoo.relationmanager.suspension.SuspensionManagerActivity"
            )
            mfr.contains("huawei") -> listOf(
                "com.huawei.systemmanager" to
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            mfr.contains("honor") -> listOf(
                "com.hihonor.systemmanager" to
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            mfr.contains("meizu") -> listOf(
                "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
            )
            else -> emptyList()
        }
    }
}
