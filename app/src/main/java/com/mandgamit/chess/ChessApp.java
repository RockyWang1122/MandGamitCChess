package com.mandgamit.chess;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChessApp extends Application {

    /** 底部容器打上此 tag：自动追加导航栏高度的底部内边距。 */
    private static final String TAG_BOTTOM_INSET = "inset_nav_bar";

    /** 顶部栏打上此 tag：整体下移以避开状态栏/前置相机挖孔区。 */
    private static final String TAG_TOP_INSET = "inset_status_bar";

    /**
     * 顶部避让量比例：标题栏只需避开居中相机孔，完整 inset 下移过多，
     * 实测按 70% 应用视觉位置刚好。
     */
    private static final float TOP_INSET_RATIO = 0.7f;

    /** 每个已适配视图的基准尺寸（原始 padding / 固定高度）。 */
    private static final Map<View, BaseMetrics> BASE_METRICS = new HashMap<>();

    private static Context appContext;

    /** 好友对战会话（独立于 Activity 生命周期，断线重连/切后台不丢局）。 */
    private static com.mandgamit.chess.battle.BattleSessionManager battleSession;

    /** 好友对战默认会话实例：真实服务器通道（WsTransport）。 */
    public static com.mandgamit.chess.battle.BattleSessionManager getBattleSession() {
        if (battleSession == null) {
            battleSession = new com.mandgamit.chess.battle.BattleSessionManager(
                    () -> new com.mandgamit.chess.battle.net.WsTransport(
                            BuildConfig.BATTLE_SERVER_URL),
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        }
        return battleSession;
    }

    /** 回环自测/调试用：替换全局对战会话（LoopbackTransport 双端互联）。 */
    public static void setBattleSessionForDebug(
            com.mandgamit.chess.battle.BattleSessionManager session) {
        battleSession = session;
    }

    /**
     * 冷启动时恢复未结束的好友对战：读取落盘房间身份（房间号/token/执子），
     * 令会话自动重连并重新 join_room；对局已开始时会话会主动 request_sync 对账。
     * 落盘损坏/无记录时静默忽略，按普通启动处理。
     */
    private void restoreBattleSessionIfNeeded() {
        try {
            com.mandgamit.chess.battle.BattleStateStore.SessionInfo info =
                    com.mandgamit.chess.battle.BattleStateStore.loadSession();
            if (info == null) {
                return;
            }
            android.util.Log.i("ChessApp", "restore battle session: room=" + info.getRoom()
                    + ", gameStarted=" + info.getGameStarted());
            getBattleSession().restoreSession(
                    info.getRoom(),
                    info.getToken(),
                    info.getColor(),
                    info.isHost(),
                    info.getGameStarted(),
                    info.getTableNo());
        } catch (Throwable t) {
            android.util.Log.w("ChessApp", "restoreBattleSessionIfNeeded failed", t);
        }
    }

    /** Get the application context. */
    public static Context getContext() {
        return appContext;
    }

    public ChessApp() {
        appContext = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 进程被系统回收后的冷启动：若好友对战房间仍落盘，立即自动重连并重新加入房间。
        // 必须早于任何 Activity 创建——MainActivity 据此直达对局页，通知点击进入同样受益。
        restoreBattleSessionIfNeeded();

        // 本应用全部界面均为浅色设计；固定浅色，避免系统深色模式下
        // 窗口背景变深色而在状态栏/过场处露出黑色条块。
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        // 全局粘性沉浸式全屏：对所有 Activity 生效，隐藏状态栏（时间/信号/电量）。
        // 用户从屏幕顶端下滑时状态栏仅短暂出现，随后自动重新隐藏。
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                // 最早时机：在 setContentView / 首次布局之前就让内容延伸到整屏
                applyImmersive(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                // 视图已 attach：在 content 最顶层安装统一 inset 分发，
                // 并立即按当前 root insets 补算一次
                installInsetDispatcher(activity);
                contentPost(activity);
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                View content = activity.findViewById(android.R.id.content);
                if (content != null) {
                    List<View> targets = new ArrayList<>();
                    collectTagged(content, targets);
                    BASE_METRICS.keySet().removeAll(targets);
                }
            }
        });
    }

    private static void applyImmersive(Activity activity) {
        // 允许内容延伸到刘海/挖孔区域：否则状态栏隐藏后该区域会被系统漆成黑条
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            activity.getWindow().setAttributes(lp);
        }
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(activity.getWindow(),
                        activity.getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.statusBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /** 在 content 顶层视图上安装统一监听：每次 inset 变化时直接应用到标记容器。 */
    private static void installInsetDispatcher(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        // content 处于分发路径最顶端，必定先于所有子视图收到，避免子树传递中断
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            applyInsets(activity, insets);
            return insets;
        });
    }

    private static void contentPost(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        content.post(() -> {
            // 首次布局完成后 root insets 已就绪；若监听尚未触发则立即补算
            WindowInsetsCompat root = ViewCompat.getRootWindowInsets(content);
            if (root != null) {
                applyInsets(activity, root);
            }
            ViewCompat.requestApplyInsets(content);
        });
    }

    /** 根据窗口 insets，调整所有带标记的顶/底容器。 */
    private static void applyInsets(Activity activity, @NonNull WindowInsetsCompat insets) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        int topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
                        | WindowInsetsCompat.Type.displayCutout()).top;
        // 完整避让量下移过多，按比例回收，使标题栏仅避开相机孔
        int appliedTop = (int) (topInset * TOP_INSET_RATIO);
        int bottomInset = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars()).bottom;

        List<View> targets = new ArrayList<>();
        collectTagged(content, targets);
        for (View target : targets) {
            Object tag = target.getTag();
            BaseMetrics base = BASE_METRICS.get(target);
            if (base == null) {
                base = BaseMetrics.from(target);
                BASE_METRICS.put(target, base);
            }
            if (TAG_TOP_INSET.equals(tag)) {
                target.setPadding(base.paddingLeft, base.paddingTop + appliedTop,
                        base.paddingRight, base.paddingBottom);
                if (base.fixedHeight > 0) {
                    ViewGroup.LayoutParams lp = target.getLayoutParams();
                    if (lp != null) {
                        lp.height = base.fixedHeight + appliedTop;
                        target.setLayoutParams(lp);
                    }
                }
            } else if (TAG_BOTTOM_INSET.equals(tag)) {
                target.setPadding(base.paddingLeft, base.paddingTop,
                        base.paddingRight, base.paddingBottom + bottomInset);
            }
        }
    }

    private static void collectTagged(View view, List<View> out) {
        Object tag = view.getTag();
        if (TAG_BOTTOM_INSET.equals(tag) || TAG_TOP_INSET.equals(tag)) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTagged(group.getChildAt(i), out);
            }
        }
    }

    /** 视图的原始内边距与固定高度（适配时在此基础上叠加避让量）。 */
    private static final class BaseMetrics {
        final int paddingLeft;
        final int paddingTop;
        final int paddingRight;
        final int paddingBottom;
        final int fixedHeight;

        private BaseMetrics(View v) {
            paddingLeft = v.getPaddingLeft();
            paddingTop = v.getPaddingTop();
            paddingRight = v.getPaddingRight();
            paddingBottom = v.getPaddingBottom();
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            fixedHeight = (lp != null && lp.height > 0) ? lp.height : -1;
        }

        static BaseMetrics from(View v) {
            return new BaseMetrics(v);
        }
    }
}
