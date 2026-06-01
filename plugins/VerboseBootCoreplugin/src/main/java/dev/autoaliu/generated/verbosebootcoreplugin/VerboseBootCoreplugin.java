package dev.autoaliu.generated.verbosebootcoreplugin;

import android.content.Context;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.CorePlugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.Patcher;
import com.aliucord.patcher.PreHook;
import com.aliucord.utils.DimenUtils;
import com.aliucord.widgets.BottomSheet;
import com.discord.app.AppActivity;
import com.discord.app.AppTransitionActivity;
import com.discord.app.AppLog;
import de.robv.android.xposed.XC_MethodHook;
import com.discord.views.CheckedSetting;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

@AliucordPlugin(requiresRestart = false)
@SuppressWarnings("unused")
public final class VerboseBootCoreplugin extends CorePlugin {
    private static final String SETTINGS_NAME = "VerboseBootCoreplugin";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_COLORS = "colors";
    private static final String KEY_MAX_LINES = "maxLines";
    private static final String OVERLAY_TAG = "VerboseBootCoreplugin.Overlay";
    private static final int DEFAULT_MAX_LINES = 36;
    private static final long NO_LOGO_GRACE_MS = 350L;
    private static final long FALLBACK_REMOVE_DELAY_MS = 3000L;
    private static final Deque<LogLine> BOOT_LINES = new ArrayDeque<>();
    private static final long BOOT_STARTED_AT = System.currentTimeMillis();
    private static XC_MethodHook.Unhook logUnpatch;
    private static VerboseBootCoreplugin activeInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable removeOverlayRunnable = this::removeOverlay;
    private final Deque<LogLine> lines = new ArrayDeque<>();
    private long startedAt;
    private FrameLayout overlayRoot;
    private TextView logText;
    private View watchedDecor;
    private ViewTreeObserver.OnPreDrawListener splashWatcher;
    private long overlayAttachedAt;
    private boolean sawSplashLogo;
    private boolean capturing;

    static {
        ensureLogPatch();
    }

    public VerboseBootCoreplugin() {
        super(new Manifest("VerboseBootCoreplugin"));
        getManifest().description = "VerboseBootCoreplugin plugin fix";
        settingsTab = new SettingsTab(SettingsSheet.class, SettingsTab.Type.BOTTOM_SHEET);
    }

    @Override
    public void start(Context context) throws Throwable {
        ensureLogPatch();
        capturing = settings.getBool(KEY_ENABLED, true);
        if (!capturing) return;

        activeInstance = this;
        startedAt = BOOT_STARTED_AT;
        synchronized (BOOT_LINES) {
            lines.clear();
            lines.addAll(BOOT_LINES);
        }

        runOnMainThread(() -> {
            attachOverlay(context);
            appendLine(4, "VerboseBootCoreplugin overlay attached", null);
        });

        patcher.patch(
            AppTransitionActivity.class,
            "onResume",
            new Class<?>[0],
            new Hook(param -> {
                if (!capturing) return;
                Activity activity = (Activity) param.thisObject;
                mainHandler.post(() -> {
                    attachOverlay(activity);
                    appendLine(3, "AppTransitionActivity.onResume visible", null);
                });
            })
        );

        patcher.patch(
            AppActivity.class,
            "onCreate",
            new Class<?>[] { Bundle.class },
            new PreHook(param -> {
                AppActivity activity = (AppActivity) param.thisObject;
                mainHandler.post(() -> {
                    attachOverlay(activity);
                    appendLine(3, "AppActivity.onCreate visible", null);
                });
            })
        );

        patcher.patch(
            AppActivity.class,
            "onCreate",
            new Class<?>[] { Bundle.class },
            new Hook(param -> {
                AppActivity activity = (AppActivity) param.thisObject;
                mainHandler.postDelayed(() -> attachOverlay(activity), 120L);
            })
        );

        patcher.patch(
            AppActivity.class,
            "onResume",
            new Class<?>[0],
            new Hook(param -> {
                if (!capturing) return;
                AppActivity activity = (AppActivity) param.thisObject;
                mainHandler.post(() -> attachOverlay(activity));
            })
        );
    }

    @Override
    public void stop(Context context) {
        activeInstance = null;
        capturing = false;
        mainHandler.removeCallbacksAndMessages(null);
        runOnMainThread(this::removeOverlay);
        patcher.unpatchAll();
        if (logUnpatch != null) {
            logUnpatch.unhook();
            logUnpatch = null;
        }
        commands.unregisterAll();
        lines.clear();
        synchronized (BOOT_LINES) {
            BOOT_LINES.clear();
        }
    }

    private void runOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private static synchronized void ensureLogPatch() {
        if (logUnpatch != null) return;

        try {
            logUnpatch = Patcher.addPatch(
                AppLog.class,
                "b",
                new Class<?>[] { String.class, int.class, Throwable.class, Map.class },
                new PreHook(param -> {
                    String message = (String) param.args[0];
                    int priority = (int) param.args[1];
                    Throwable throwable = (Throwable) param.args[2];
                    VerboseBootCoreplugin instance = activeInstance;
                    if (instance != null) {
                        instance.appendLine(priority, message, throwable);
                    } else {
                        appendBootLine(priority, message, throwable);
                    }
                })
            );
        } catch (Throwable ignored) {
            logUnpatch = null;
        }
    }

    private static void appendBootLine(int priority, String message, Throwable throwable) {
        if (message == null) return;

        String text = sanitize(message);
        if (throwable != null) {
            text += " (" + throwable.getClass().getSimpleName() + ": " + sanitize(String.valueOf(throwable.getMessage())) + ")";
        }

        synchronized (BOOT_LINES) {
            BOOT_LINES.addLast(new LogLine(priority, elapsedPrefix(BOOT_STARTED_AT, priority) + text));
            while (BOOT_LINES.size() > DEFAULT_MAX_LINES) BOOT_LINES.removeFirst();
        }
    }

    private void appendLine(int priority, String message, Throwable throwable) {
        if (!capturing || message == null) return;

        String text = sanitize(message);
        if (throwable != null) {
            text += " (" + throwable.getClass().getSimpleName() + ": " + sanitize(String.valueOf(throwable.getMessage())) + ")";
        }

        int maxLines = Math.max(8, settings.getInt(KEY_MAX_LINES, DEFAULT_MAX_LINES));
        synchronized (lines) {
            lines.addLast(new LogLine(priority, elapsedPrefix(startedAt, priority) + text));
            while (lines.size() > maxLines) lines.removeFirst();
        }
        mainHandler.post(this::renderLines);
    }

    private static String elapsedPrefix(long startTime, int priority) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - startTime);
        return String.format(Locale.US, "+%04dms %s  ", elapsed, priorityLabel(priority));
    }

    private static String priorityLabel(int priority) {
        switch (priority) {
            case 2:
                return "V";
            case 3:
                return "D";
            case 4:
                return "I";
            case 5:
                return "W";
            case 6:
                return "E";
            default:
                return "?";
        }
    }

    private static String sanitize(String value) {
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 220 ? compact.substring(0, 217) + "..." : compact;
    }

    private void attachOverlay(Context rawContext) {
        if (!capturing) return;
        Context context = rawContext != null ? rawContext : Utils.appActivity;
        if (!(context instanceof Activity)) return;

        Activity activity = (Activity) context;
        View decorView = activity.getWindow().getDecorView();
        if (!(decorView instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) decorView;

        View existing = content.findViewWithTag(OVERLAY_TAG);
        if (existing instanceof FrameLayout) {
            overlayRoot = (FrameLayout) existing;
            overlayRoot.bringToFront();
            renderLines();
            watchSplashLifetime(activity);
            return;
        }

        FrameLayout root = new FrameLayout(activity);
        root.setTag(OVERLAY_TAG);
        root.setClickable(false);
        root.setFitsSystemWindows(false);
        root.setElevation(DimenUtils.dpToPx(32));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.BOTTOM);
        int padding = DimenUtils.dpToPx(18);
        panel.setPadding(padding, DimenUtils.dpToPx(34), padding, DimenUtils.dpToPx(24));

        TextView title = new TextView(activity);
        title.setText("VerboseBootCoreplugin");
        title.setTextColor(0xffffffff);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16f);

        logText = new TextView(activity);
        logText.setTextColor(0xffd7dce2);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11f);
        logText.setIncludeFontPadding(false);
        logText.setGravity(Gravity.BOTTOM | Gravity.START);

        panel.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(logText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));
        root.addView(panel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        content.addView(root, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlayRoot = root;
        overlayRoot.bringToFront();
        renderLines();
        watchSplashLifetime(activity);
    }

    private void watchSplashLifetime(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (decor == null || decor == watchedDecor) return;

        stopSplashWatcher();
        watchedDecor = decor;
        overlayAttachedAt = System.currentTimeMillis();
        sawSplashLogo = containsSplashLogo(decor);
        splashWatcher = () -> {
            updateSplashLifetime();
            return true;
        };

        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnPreDrawListener(splashWatcher);
        mainHandler.removeCallbacks(removeOverlayRunnable);
        mainHandler.postDelayed(removeOverlayRunnable, FALLBACK_REMOVE_DELAY_MS);
        mainHandler.post(this::updateSplashLifetime);
        mainHandler.postDelayed(this::updateSplashLifetime, NO_LOGO_GRACE_MS);
    }

    private void updateSplashLifetime() {
        if (!capturing || overlayRoot == null || watchedDecor == null) {
            stopSplashWatcher();
            return;
        }

        boolean hasLogo = containsSplashLogo(watchedDecor);
        if (hasLogo) {
            sawSplashLogo = true;
            return;
        }

        if (sawSplashLogo) removeOverlay();
        else if (System.currentTimeMillis() - overlayAttachedAt >= NO_LOGO_GRACE_MS) removeOverlay();
    }

    private boolean containsSplashLogo(View view) {
        if (view == null || view == overlayRoot || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.05f) {
            return false;
        }

        if (view instanceof ImageView && isCenteredLogoCandidate(view)) {
            return true;
        }

        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsSplashLogo(group.getChildAt(i))) return true;
        }
        return false;
    }

    private boolean isCenteredLogoCandidate(View view) {
        int decorWidth = watchedDecor != null ? watchedDecor.getWidth() : 0;
        int decorHeight = watchedDecor != null ? watchedDecor.getHeight() : 0;
        if (decorWidth <= 0 || decorHeight <= 0 || view.getWidth() <= 0 || view.getHeight() <= 0) return false;

        int minSize = DimenUtils.dpToPx(24);
        int maxSize = Math.min(decorWidth, decorHeight) / 2;
        if (view.getWidth() < minSize || view.getHeight() < minSize || view.getWidth() > maxSize || view.getHeight() > maxSize) {
            return false;
        }

        int[] viewLocation = new int[2];
        int[] decorLocation = new int[2];
        view.getLocationOnScreen(viewLocation);
        watchedDecor.getLocationOnScreen(decorLocation);

        float centerX = viewLocation[0] - decorLocation[0] + (view.getWidth() / 2f);
        float centerY = viewLocation[1] - decorLocation[1] + (view.getHeight() / 2f);
        return centerX > decorWidth * 0.25f
            && centerX < decorWidth * 0.75f
            && centerY > decorHeight * 0.20f
            && centerY < decorHeight * 0.80f;
    }

    private void renderLines() {
        if (logText == null) return;

        boolean colors = settings.getBool(KEY_COLORS, true);
        SpannableStringBuilder builder = new SpannableStringBuilder();
        synchronized (lines) {
            for (LogLine line : lines) {
                int start = builder.length();
                builder.append(line.text).append('\n');
                if (colors) {
                    builder.setSpan(
                        new ForegroundColorSpan(colorForPriority(line.priority)),
                        start,
                        builder.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            }
        }
        logText.setText(builder);
    }

    private static int colorForPriority(int priority) {
        switch (priority) {
            case 2:
                return 0xff8b949e;
            case 3:
                return 0xff79c0ff;
            case 4:
                return 0xff7ee787;
            case 5:
                return 0xffffd166;
            case 6:
                return 0xffff7b72;
            default:
                return 0xffd7dce2;
        }
    }

    private void removeOverlay() {
        capturing = false;
        stopSplashWatcher();
        if (overlayRoot == null) return;

        ViewGroup parent = (ViewGroup) overlayRoot.getParent();
        if (parent != null) parent.removeView(overlayRoot);
        overlayRoot = null;
        logText = null;
    }

    private void stopSplashWatcher() {
        mainHandler.removeCallbacks(removeOverlayRunnable);
        if (watchedDecor != null && splashWatcher != null) {
            ViewTreeObserver observer = watchedDecor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(splashWatcher);
        }
        watchedDecor = null;
        splashWatcher = null;
        overlayAttachedAt = 0L;
        sawSplashLogo = false;
    }

    private static final class LogLine {
        private final int priority;
        private final String text;

        private LogLine(int priority, String text) {
            this.priority = priority;
            this.text = text;
        }
    }

    public static final class SettingsSheet extends BottomSheet {
        @Override
        public void onViewCreated(View view, Bundle bundle) {
            super.onViewCreated(view, bundle);

            SettingsAPI api = new SettingsAPI(SETTINGS_NAME);
            addSwitch(api, KEY_ENABLED, true, "Enable boot overlay", "Show debug logs while Discord is starting.");
            addSwitch(api, KEY_COLORS, true, "Colorize log levels", "Use separate colors for verbose, debug, info, warning, and error lines.");
            addMaxLines(api, 24, "Compact", "Keep 24 log lines on screen.");
            addMaxLines(api, DEFAULT_MAX_LINES, "Normal", "Keep 36 log lines on screen.");
            addMaxLines(api, 56, "Dense", "Keep 56 log lines on screen.");
        }

        private void addSwitch(SettingsAPI api, String key, boolean def, String title, String subtitle) {
            CheckedSetting setting = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle);
            setting.setChecked(api.getBool(key, def));
            setting.setOnCheckedListener(checked -> api.setBool(key, checked));
            addView(setting);
        }

        private void addMaxLines(SettingsAPI api, int value, String title, String subtitle) {
            CheckedSetting setting = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.RADIO, title, subtitle);
            setting.setChecked(api.getInt(KEY_MAX_LINES, DEFAULT_MAX_LINES) == value);
            setting.setOnCheckedListener(checked -> {
                if (checked) {
                    api.setInt(KEY_MAX_LINES, value);
                    dismiss();
                }
            });
            addView(setting);
        }
    }
}
