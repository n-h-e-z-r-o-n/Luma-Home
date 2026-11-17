package com.lumahome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Global Liquid Glass Quick Settings overlay service.
 * Intercepts the physical remote Settings key (4077 / KEYCODE_SETTINGS / 312) across ANY running
 * application when the stock Google TV launcher dashboard (com.google.android.apps.tv.launcherx)
 * is disabled (e.g. via Launcher Manager), and displays a right-side Liquid Glass Quick Settings
 * dashboard overlay on top of the active app.
 */
public final class QuickSettingsOverlayService extends AccessibilityService {
    private static final String TAG = "LumaQuickSettings";
    public static final String EXTRA_OPEN_LUMA_SETTINGS = "luma.extra.OPEN_LUMA_SETTINGS";
    public static final String EXTRA_OPEN_SCREENSAVER = "luma.extra.OPEN_SCREENSAVER";
    public static final String EXTRA_OPEN_INPUTS = "luma.extra.OPEN_INPUTS";

    private static volatile QuickSettingsOverlayService sInstance;
    private static Dialog sFallbackDialog;
    private static int sLastNavDir = 1;
    private static long sLastNavMs = 0L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService screenshotExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private WindowManager windowManager;
    private AudioManager audioManager;
    private View overlayRoot;
    private View panelCard;
    private boolean overlayShowing;
    private boolean capturingSnapshot;
    private boolean dismissing;
    private long lastToggleMs;
    private String currentForegroundPackage = "com.lumahome.launcher";
    private String lastSnapshotPackage = "";
    private UiSoundEngine sfx;
    private LauncherStore store;
    private Typeface regularFont = Typeface.create("sans-serif", Typeface.NORMAL);
    private Typeface mediumFont = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private View volumeHudRoot;
    private View volumeHudPill;
    private boolean volumeHudShowing;
    private boolean volumeHudDismissing;
    private final int[] currentVolPct = new int[1];
    private final boolean[] currentVolMuted = new boolean[1];
    private View volumeIconView;
    private View volumeBarView;
    private TextView volumeTextView;
    private final Runnable hideVolumeHudRunnable = () -> hideVolumeHud(true);

    private int volumeRepeatDirection = 0;
    private long volumeRepeatStartTime = 0L;
    private final Runnable volumeRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (volumeRepeatDirection == 0) return;
            handleVolumeStep(volumeRepeatDirection);
            long heldMs = android.os.SystemClock.uptimeMillis() - volumeRepeatStartTime;
            long nextInterval = heldMs > 900 ? 50L : 75L;
            handler.postDelayed(this, nextInterval);
        }
    };

    private void startVolumeRepeat(int direction) {
        stopVolumeRepeat();
        volumeRepeatDirection = direction;
        volumeRepeatStartTime = android.os.SystemClock.uptimeMillis();
        handler.postDelayed(volumeRepeatRunnable, 320);
    }

    private void stopVolumeRepeat() {
        volumeRepeatDirection = 0;
        handler.removeCallbacks(volumeRepeatRunnable);
    }

    private final BroadcastReceiver tclKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if ("tcl.sys.intent.action.USERSTATE_KEYDOWN".equals(action)) {
                int code = intent.getIntExtra("keycode", -1);
                if (isSettingsKey(code)) {
                    if (code == 4077 && isStockDashboardAvailable(QuickSettingsOverlayService.this)) {
                        return;
                    }
                    android.util.Log.i(TAG, "Received global TCL key broadcast (code=" + code + ") over pkg=" + currentForegroundPackage);
                    toggleOverlay();
                }
            } else if ("android.media.VOLUME_CHANGED_ACTION".equals(action)
                    || "luma.intent.action.SHOW_VOLUME_HUD".equals(action)) {
                android.util.Log.i(TAG, "Received volume broadcast: " + action);
                if (store == null) store = new LauncherStore(QuickSettingsOverlayService.this);
                if (intent.hasExtra("orientation")) {
                    store.set("vol_orientation", intent.getStringExtra("orientation"));
                }
                if (intent.hasExtra("pos")) {
                    store.set("vol_pos", intent.getStringExtra("pos"));
                }
                if (store.flag("vol_hud_enabled", true) || "luma.intent.action.SHOW_VOLUME_HUD".equals(action)) {
                    handler.post(QuickSettingsOverlayService.this::showVolumeHud);
                }
            }
        }
    };

    public static QuickSettingsOverlayService getInstance() {
        return sInstance;
    }

    public static boolean isAccessibilityServiceEnabled(Context context) {
        if (sInstance != null) return true;
        if (context == null) return false;
        try {
            String component = new ComponentName(context.getPackageName(),
                    QuickSettingsOverlayService.class.getName()).flattenToString();
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices != null && enabledServices.contains(component)) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static void openAccessibilitySettings(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    public static void showVolumeHudPreview(Context host) {
        if (host == null) return;
        ensureServiceEnabled(host);
        QuickSettingsOverlayService svc = sInstance;
        if (svc != null) {
            svc.handler.post(svc::showVolumeHud);
        } else {
            try {
                Intent i = new Intent("luma.intent.action.SHOW_VOLUME_HUD");
                i.setPackage(host.getPackageName());
                host.sendBroadcast(i);
            } catch (Exception ignored) {}
        }
    }

    public static boolean isSettingsKey(int code) {
        return code == 4077 // TCL Quick Menu / Settings button
                || code == KeyEvent.KEYCODE_SETTINGS
                || code == 312; // KEYCODE_QUICK_SETTINGS
    }

    public static boolean isStockDashboardAvailable(Context context) {
        if (context == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            int state = pm.getApplicationEnabledSetting("com.google.android.apps.tv.launcherx");
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            Intent dash = new Intent("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL");
            dash.setPackage("com.google.android.apps.tv.launcherx");
            ResolveInfo info = pm.resolveActivity(dash, 0);
            return info != null && info.activityInfo != null
                    && info.activityInfo.enabled
                    && info.activityInfo.applicationInfo != null
                    && info.activityInfo.applicationInfo.enabled;
        } catch (Exception e) {
            return false;
        }
    }

    public static void ensureServiceEnabled(Context context) {
        if (context == null) return;
        try {
            String component = new ComponentName(context.getPackageName(),
                    QuickSettingsOverlayService.class.getName()).flattenToString();
            String current = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || current.trim().isEmpty()) {
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        component);
            } else if (!current.contains(component)) {
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        current + ":" + component);
            }
            Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1);
        } catch (SecurityException ignored) {
            // Normal if WRITE_SECURE_SETTINGS was not granted via ADB; service can still be enabled in Settings.
        } catch (Exception ignored) {}
    }

    public static boolean showOrToggle(Activity fallbackHost) {
        ensureServiceEnabled(fallbackHost);
        QuickSettingsOverlayService svc = sInstance;
        if (svc != null) {
            svc.handler.post(svc::toggleOverlay);
            return true;
        }
        if (fallbackHost != null && !fallbackHost.isFinishing()) {
            toggleFallbackDialog(fallbackHost);
            return true;
        }
        return false;
    }

    private static void toggleFallbackDialog(Activity host) {
        if (sFallbackDialog != null && sFallbackDialog.isShowing()) {
            try {
                sFallbackDialog.dismiss();
            } catch (Exception ignored) {}
            sFallbackDialog = null;
            return;
        }
        Dialog dialog = new Dialog(host, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
        LauncherStore store = new LauncherStore(host);
        UiSoundEngine sfx = new UiSoundEngine(host, store);
        Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                reg = host.getResources().getFont(host.getResources().getIdentifier("outfit_regular", "font", host.getPackageName()));
                med = host.getResources().getFont(host.getResources().getIdentifier("outfit_medium", "font", host.getPackageName()));
            } catch (Exception ignored) {}
        }
        final Dialog[] ref = new Dialog[]{dialog};
        Runnable dismissAction = () -> {
            if (ref[0] != null && ref[0].isShowing()) {
                try {
                    ref[0].dismiss();
                } catch (Exception ignored) {}
            }
            sFallbackDialog = null;
            sfx.close();
        };
        View content = buildDashboardView(host, store, sfx, reg, med, dismissAction);
        applyViewSoundPolicy(content);
        dialog.setContentView(content);
        if (dialog.getWindow() != null && dialog.getWindow().getDecorView() != null) {
            dialog.getWindow().getDecorView().setSoundEffectsEnabled(false);
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int dir = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ? View.FOCUS_LEFT
                            : (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ? View.FOCUS_RIGHT
                            : (keyCode == KeyEvent.KEYCODE_DPAD_UP) ? View.FOCUS_UP : View.FOCUS_DOWN;
                    sLastNavDir = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) ? 1 : -1;
                    sLastNavMs = android.os.SystemClock.uptimeMillis();
                    View cur = dialog.getCurrentFocus();
                    if (cur != null) {
                        View next = null;
                        int nextId = (dir == View.FOCUS_DOWN) ? cur.getNextFocusDownId()
                                : (dir == View.FOCUS_UP) ? cur.getNextFocusUpId()
                                : (dir == View.FOCUS_LEFT) ? cur.getNextFocusLeftId()
                                : (dir == View.FOCUS_RIGHT) ? cur.getNextFocusRightId()
                                : View.NO_ID;
                        if (nextId != View.NO_ID && nextId != cur.getId()) {
                            View root = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : cur.getRootView();
                            if (root != null) {
                                next = root.findViewById(nextId);
                            }
                        }
                        if (next == null) {
                            next = cur.focusSearch(dir);
                        }
                        if (next != null && next != cur) {
                            next.requestFocus(dir);
                        } else if (sfx != null) {
                            sfx.playDetent(0.72f, 50);
                        }
                    }
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    View cur = dialog.getCurrentFocus();
                    if (cur != null) {
                        cur.callOnClick();
                    }
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_BACK || isSettingsKey(keyCode))) {
                sfx.playBack();
                dismissAction.run();
                return true;
            }
            return isSettingsKey(keyCode);
        });
        sFallbackDialog = dialog;
        dialog.show();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        store = new LauncherStore(this);
        sfx = new UiSoundEngine(this, store);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                regularFont = getResources().getFont(getResources().getIdentifier("outfit_regular", "font", getPackageName()));
                mediumFont = getResources().getFont(getResources().getIdentifier("outfit_medium", "font", getPackageName()));
            } catch (Exception ignored) {}
        }
        try {
            IntentFilter filter = new IntentFilter("tcl.sys.intent.action.USERSTATE_KEYDOWN");
            filter.addAction("android.media.VOLUME_CHANGED_ACTION");
            filter.addAction("luma.intent.action.SHOW_VOLUME_HUD");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(tclKeyReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(tclKeyReceiver, filter);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to register tclKeyReceiver", e);
        }
        android.util.Log.i(TAG, "QuickSettingsOverlayService created");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) info = new AccessibilityServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to update service info flags", e);
        }
        android.util.Log.i(TAG, "QuickSettingsOverlayService connected and filtering key events");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && pkg.length() > 0) {
                String p = pkg.toString();
                if (!overlayShowing || !"com.lumahome.launcher".equals(p)) {
                    currentForegroundPackage = p;
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Feedback interruption requested by framework; keep user overlay intact.
    }

    @Override
    public void onDestroy() {
        hideOverlay(false);
        stopVolumeRepeat();
        hideVolumeHud(false);
        try {
            unregisterReceiver(tclKeyReceiver);
        } catch (Exception ignored) {}
        screenshotExecutor.shutdownNow();
        if (sfx != null) {
            sfx.close();
            sfx = null;
        }
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();

        boolean isVol = (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE);
        if (isVol) {
            android.util.Log.i(TAG, "onKeyEvent volume: code=" + code + ", action=" + event.getAction() + ", repeat=" + event.getRepeatCount());
            if (store == null) store = new LauncherStore(this);
            if (store.flag("vol_hud_enabled", true)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                            handleVolumeStep(AudioManager.ADJUST_RAISE);
                            startVolumeRepeat(AudioManager.ADJUST_RAISE);
                        } else if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                            handleVolumeStep(AudioManager.ADJUST_LOWER);
                            startVolumeRepeat(AudioManager.ADJUST_LOWER);
                        } else {
                            handleVolumeStep(AudioManager.ADJUST_TOGGLE_MUTE);
                        }
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    stopVolumeRepeat();
                }
                return true;
            }
        }

        if (!isSettingsKey(code)) {
            if (overlayShowing && code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    if (sfx != null) sfx.playBack();
                    hideOverlay(true);
                }
                return true;
            }
            return false;
        }

        // If our overlay is currently visible, pressing the Settings key toggles it closed.
        if (overlayShowing) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (sfx != null) sfx.playBack();
                hideOverlay(true);
            }
            return true;
        }

        // When the stock Google TV launcher is enabled, let the system DashboardHandler handle 4077.
        if (code == 4077 && isStockDashboardAvailable(this)) {
            return false;
        }

        // Stock Google TV launcher is disabled (e.g. Luma Home is set as default in Launcher Manager)
        // or a dedicated Settings key was pressed: intercept globally over ANY running app.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            android.util.Log.i(TAG, "Global Settings key intercepted (code=" + code + "), toggling Liquid Glass Quick Settings");
            toggleOverlay();
            return true;
        }
        return true;
    }

    public void toggleOverlay() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastToggleMs < 350L) return;
        lastToggleMs = now;
        if (overlayShowing) {
            if (sfx != null) sfx.playBack();
            hideOverlay(true);
        } else {
            showOverlay();
        }
    }

    public void showOverlay() {
        if (overlayShowing || capturingSnapshot || windowManager == null) return;
        dismissing = false;
        if (store == null) store = new LauncherStore(this);
        if (sfx == null) sfx = new UiSoundEngine(this, store);

        // Clear any stale snapshot from a different app before capturing the live screen
        if (!currentForegroundPackage.equals(lastSnapshotPackage)) {
            if ("com.lumahome.launcher".equals(currentForegroundPackage)) {
                LiquidGlassRenderer.setOverlayBackdrop(LiquidGlassRenderer.getActiveBackdrop());
            } else {
                LiquidGlassRenderer.setOverlayBackdrop(null);
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            capturingSnapshot = true;
            final boolean[] presented = new boolean[]{false};
            final Runnable timeoutPresent = () -> {
                if (!presented[0]) {
                    presented[0] = true;
                    capturingSnapshot = false;
                    presentOverlayWindow();
                }
            };
            handler.postDelayed(timeoutPresent, 160);
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        android.graphics.Bitmap captured = null;
                        try {
                            android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                            if (hb != null) {
                                android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                hb.close();
                                if (hw != null) {
                                    android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                    if (sw != null) {
                                        captured = android.graphics.Bitmap.createScaledBitmap(sw, 960, 540, true);
                                        if (captured != sw) sw.recycle();
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            android.util.Log.w(TAG, "Failed converting screenshot buffer", t);
                        }
                        final android.graphics.Bitmap finalCaptured = captured;
                        handler.post(() -> {
                            capturingSnapshot = false;
                            if (finalCaptured != null) {
                                lastSnapshotPackage = currentForegroundPackage;
                                LiquidGlassRenderer.setOverlayBackdrop(finalCaptured);
                                android.util.Log.i(TAG, "Captured live screen snapshot (960x540) for pkg=" + currentForegroundPackage);
                            }
                            if (!presented[0]) {
                                presented[0] = true;
                                handler.removeCallbacks(timeoutPresent);
                                presentOverlayWindow();
                            } else if (overlayShowing && overlayRoot != null) {
                                invalidateViewTree(overlayRoot);
                            }
                        });
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        android.util.Log.w(TAG, "takeScreenshot failed with code=" + errorCode + " for pkg=" + currentForegroundPackage);
                        handler.post(() -> {
                            capturingSnapshot = false;
                            if (!presented[0]) {
                                presented[0] = true;
                                handler.removeCallbacks(timeoutPresent);
                                presentOverlayWindow();
                            }
                        });
                    }
                });
                return;
            } catch (Throwable t) {
                capturingSnapshot = false;
                handler.removeCallbacks(timeoutPresent);
            }
        }

        presentOverlayWindow();
    }

    private static void invalidateViewTree(View v) {
        if (v == null) return;
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                invalidateViewTree(vg.getChildAt(i));
            }
        }
    }

    private static void applyViewSoundPolicy(View v) {
        if (v == null) return;
        v.setSoundEffectsEnabled(false);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyViewSoundPolicy(vg.getChildAt(i));
            }
        }
    }

    private void presentOverlayWindow() {
        if (overlayShowing || windowManager == null) return;

        FrameLayout host = new FrameLayout(this) {
            @Override
            public void playSoundEffect(int soundConstant) {
                // Suppress Android TV default system sounds so only Luma's sound system plays
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event != null) {
                    int code = event.getKeyCode();
                    if (code == KeyEvent.KEYCODE_BACK || isSettingsKey(code)) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                            if (sfx != null) sfx.playBack();
                            hideOverlay(true);
                        }
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_HOME) {
                        hideOverlay(false);
                        return false;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                            || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            int dir = (code == KeyEvent.KEYCODE_DPAD_LEFT) ? View.FOCUS_LEFT
                                    : (code == KeyEvent.KEYCODE_DPAD_RIGHT) ? View.FOCUS_RIGHT
                                    : (code == KeyEvent.KEYCODE_DPAD_UP) ? View.FOCUS_UP : View.FOCUS_DOWN;
                            sLastNavDir = (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN) ? 1 : -1;
                            sLastNavMs = android.os.SystemClock.uptimeMillis();
                            View cur = findFocus();
                            if (cur != null) {
                                View next = null;
                                int nextId = (dir == View.FOCUS_DOWN) ? cur.getNextFocusDownId()
                                        : (dir == View.FOCUS_UP) ? cur.getNextFocusUpId()
                                        : (dir == View.FOCUS_LEFT) ? cur.getNextFocusLeftId()
                                        : (dir == View.FOCUS_RIGHT) ? cur.getNextFocusRightId()
                                        : View.NO_ID;
                                if (nextId != View.NO_ID && nextId != cur.getId()) {
                                    View root = getRootView();
                                    if (root != null) {
                                        next = root.findViewById(nextId);
                                    }
                                }
                                if (next == null) {
                                    next = cur.focusSearch(dir);
                                }
                                if (next != null && next != cur) {
                                    next.requestFocus(dir);
                                } else if (sfx != null) {
                                    sfx.playDetent(0.72f, 50);
                                }
                            }
                        }
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                            View cur = findFocus();
                            if (cur != null) {
                                cur.callOnClick();
                            }
                        }
                        return true;
                    }
                }
                return super.dispatchKeyEvent(event);
            }
        };
        host.setSoundEffectsEnabled(false);

        if (store == null) store = new LauncherStore(this);
        if (sfx == null) sfx = new UiSoundEngine(this, store);
        View dashboard = buildDashboardView(this, store, sfx, regularFont, mediumFont, () -> hideOverlay(true));
        applyViewSoundPolicy(dashboard);
        applyViewSoundPolicy(host);
        host.addView(dashboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        boolean isLeftOverlay = store != null && "left".equals(store.value("qs_position", "right"));
        lp.gravity = (isLeftOverlay ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
        lp.setTitle("LumaQuickSettingsOverlay");

        try {
            windowManager.addView(host, lp);
            overlayRoot = host;
            overlayShowing = true;
            if (sfx != null) sfx.playBladeOpen();
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to add TYPE_ACCESSIBILITY_OVERLAY view", e);
        }
    }

    public void hideOverlay(boolean animate) {
        if (!overlayShowing || overlayRoot == null || windowManager == null) return;
        if (dismissing) return;
        lastToggleMs = android.os.SystemClock.uptimeMillis();
        final View rootToRemove = overlayRoot;
        final View card = panelCard;
        if (!animate || card == null) {
            overlayShowing = false;
            dismissing = false;
            overlayRoot = null;
            panelCard = null;
            try {
                windowManager.removeViewImmediate(rootToRemove);
            } catch (Exception ignored) {}
            return;
        }
        dismissing = true;
        if (store == null) store = new LauncherStore(this);
        boolean isLeftOverlay = "left".equals(store.value("qs_position", "right"));
        float slidePx = isLeftOverlay ? -dp(this, 64) : dp(this, 64);
        card.animate().cancel();
        rootToRemove.animate().cancel();
        card.animate()
                .translationX(slidePx)
                .alpha(0f)
                .setDuration(170)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
        rootToRemove.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    overlayShowing = false;
                    dismissing = false;
                    if (overlayRoot == rootToRemove) {
                        overlayRoot = null;
                        panelCard = null;
                    }
                    try {
                        windowManager.removeViewImmediate(rootToRemove);
                    } catch (Exception ignored) {}
                })
                .start();
    }

    private void handleVolumeStep(int direction) {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to adjust volume: " + e.getMessage());
        }
        handler.post(this::showVolumeHud);
    }

    public void showVolumeHud() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        int cur = 0;
        int max = 100;
        int min = 0;
        boolean isMuted = false;
        if (audioManager != null) {
            try {
                cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
                }
            } catch (Exception ignored) {}
        }
        if (cur <= min) isMuted = true;
        int range = Math.max(1, max - min);
        int pct = Math.max(0, Math.min(100, Math.round(((cur - min) * 100f) / range)));

        currentVolPct[0] = pct;
        currentVolMuted[0] = isMuted;
        android.util.Log.i(TAG, "showVolumeHud called: pct=" + pct + ", muted=" + isMuted + ", showing=" + volumeHudShowing);

        handler.removeCallbacks(hideVolumeHudRunnable);
        handler.postDelayed(hideVolumeHudRunnable, 2400);

        if (volumeHudShowing && volumeHudRoot != null && !volumeHudDismissing) {
            updateVolumeHudViews(cur, max, pct, isMuted);
            return;
        }

        presentVolumeHudWindow(cur, max, pct, isMuted);
    }

    private void updateVolumeHudViews(int cur, int max, int pct, boolean isMuted) {
        if (volumeIconView != null) volumeIconView.invalidate();
        if (volumeBarView != null) volumeBarView.invalidate();
        if (volumeTextView != null) {
            boolean isVert = store != null && !"horizontal".equals(store.value("vol_orientation", "vertical"));
            volumeTextView.setText(isMuted ? (isVert ? "MUTE" : "MUTED") : (pct + "%"));
            volumeTextView.setTextColor(isMuted ? 0xFFFFA07A : 0xFFFFFFFF);
        }
    }

    private void presentVolumeHudWindow(int cur, int max, int pct, boolean isMuted) {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        if (volumeHudRoot != null) {
            try {
                windowManager.removeViewImmediate(volumeHudRoot);
            } catch (Exception ignored) {}
            volumeHudRoot = null;
            volumeHudPill = null;
        }

        if (store == null) store = new LauncherStore(this);
        String volStyle = store.value("vol_style", "frosted");
        int volFrost = store.number("vol_frost", 0);
        String volPos = store.value("vol_pos", "right_edge");
        final boolean isVertical = !"horizontal".equals(store.value("vol_orientation", "vertical"));

        int pillW = isVertical ? dp(this, 56) : dp(this, 310);
        int pillH = isVertical ? dp(this, 240) : dp(this, 54);
        float radiusDp = isVertical ? 28f : 27f;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        if ("top_center".equals(volPos)) {
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(this, 32);
        } else if ("top_right".equals(volPos)) {
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(this, 36);
            lp.y = dp(this, 32);
        } else {
            // Default: right_edge
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.x = dp(this, 36);
        }

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(isVertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        pill.setGravity(isVertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        if (isVertical) {
            pill.setPadding(dp(this, 8), dp(this, 16), dp(this, 8), dp(this, 16));
        } else {
            pill.setPadding(dp(this, 16), dp(this, 8), dp(this, 18), dp(this, 8));
        }
        pill.setLayoutParams(new FrameLayout.LayoutParams(pillW, pillH));
        pill.setBackground(buildLiquidGlassPanelDrawable(this, pill, radiusDp, volStyle, volFrost));

        // Speaker Vector Icon View
        View iconView = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                super.onDraw(c);
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;
                int iconColor = currentVolMuted[0] ? 0xFFFFA07A : 0xFFFFFFFF;
                drawVolumeVectorIcon(c, w, h, iconColor, currentVolPct[0], currentVolMuted[0], p);
            }
        };
        volumeIconView = iconView;

        // Custom Progress Bar Track + Fill
        View barView = new View(this) {
            private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF trackRect = new RectF();
            private final RectF fillRect = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;

                int pct = currentVolPct[0];
                boolean muted = currentVolMuted[0];

                if (isVertical) {
                    float barW = dp(QuickSettingsOverlayService.this, 8f);
                    float left = (w - barW) / 2f;
                    float r = barW / 2f;

                    // Track groove
                    trackRect.set(left, 0, left + barW, h);
                    trackPaint.setColor(0x33FFFFFF);
                    canvas.drawRoundRect(trackRect, r, r, trackPaint);

                    // Progress Fill (rising from bottom to top)
                    float fillH = (h * pct) / 100f;
                    if (fillH > 0 && !muted) {
                        fillRect.set(left, h - Math.max(fillH, barW), left + barW, h);
                        fillPaint.setShader(new LinearGradient(0f, h, 0f, 0f,
                                new int[]{0xFF4FC3F7, 0xFF64FFDA},
                                null, Shader.TileMode.CLAMP));
                        canvas.drawRoundRect(fillRect, r, r, fillPaint);
                        fillPaint.setShader(null);
                    } else if (muted) {
                        fillRect.set(left, h - barW, left + barW, h);
                        fillPaint.setColor(0x88FFA07A);
                        canvas.drawRoundRect(fillRect, r, r, fillPaint);
                    }
                } else {
                    float barH = dp(QuickSettingsOverlayService.this, 8f);
                    float top = (h - barH) / 2f;
                    float r = barH / 2f;

                    // Track groove
                    trackRect.set(0, top, w, top + barH);
                    trackPaint.setColor(0x33FFFFFF);
                    canvas.drawRoundRect(trackRect, r, r, trackPaint);

                    // Progress Fill (left to right)
                    float fillW = (w * pct) / 100f;
                    if (fillW > 0 && !muted) {
                        fillRect.set(0, top, Math.max(fillW, barH), top + barH);
                        fillPaint.setShader(new LinearGradient(0f, 0f, w, 0f,
                                new int[]{0xFF4FC3F7, 0xFF64FFDA},
                                null, Shader.TileMode.CLAMP));
                        canvas.drawRoundRect(fillRect, r, r, fillPaint);
                        fillPaint.setShader(null);
                    } else if (muted) {
                        fillRect.set(0, top, barH, top + barH);
                        fillPaint.setColor(0x88FFA07A);
                        canvas.drawRoundRect(fillRect, r, r, fillPaint);
                    }
                }
            }
        };
        volumeBarView = barView;

        // Percentage Text View
        TextView tv = new TextView(this);
        tv.setTypeface(mediumFont != null ? mediumFont : Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, isVertical ? dp(this, 11) : dp(this, 14));
        tv.setTextColor(isMuted ? 0xFFFFA07A : 0xFFFFFFFF);
        tv.setText(isMuted ? (isVertical ? "MUTE" : "MUTED") : (pct + "%"));
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER);
        volumeTextView = tv;

        if (isVertical) {
            // Vertical order: Percentage at top, vertical bar in middle, speaker icon at bottom
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tvLp.bottomMargin = dp(this, 10);
            pill.addView(tv, tvLp);

            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    dp(this, 12), 0, 1f);
            barLp.bottomMargin = dp(this, 12);
            pill.addView(barView, barLp);

            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    dp(this, 22), dp(this, 22));
            pill.addView(iconView, iconLp);
        } else {
            // Horizontal order: Speaker icon at left, horizontal bar in center, percentage at right
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    dp(this, 26), dp(this, 26));
            iconLp.setMarginEnd(dp(this, 14));
            pill.addView(iconView, iconLp);

            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            barLp.setMarginEnd(dp(this, 14));
            pill.addView(barView, barLp);

            pill.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        root.addView(pill);
        volumeHudRoot = root;
        volumeHudPill = pill;
        volumeHudShowing = true;
        volumeHudDismissing = false;

        // Intro animation: soft spring scale & alpha
        pill.setAlpha(0f);
        pill.setScaleX(0.88f);
        pill.setScaleY(0.88f);
        if (isVertical || "right_edge".equals(volPos)) {
            pill.setTranslationX(dp(this, 30));
        } else {
            pill.setTranslationY(-dp(this, 18));
        }

        try {
            windowManager.addView(root, lp);
            android.util.Log.i(TAG, "Added volume HUD window to WindowManager, pos=" + volPos + ", style=" + volStyle + ", frost=" + volFrost);
            pill.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .start();
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to present volume HUD: " + e.getMessage());
            volumeHudShowing = false;
        }
    }

    public void hideVolumeHud(boolean animate) {
        stopVolumeRepeat();
        if (!volumeHudShowing || volumeHudRoot == null || windowManager == null) {
            volumeHudShowing = false;
            return;
        }
        if (volumeHudDismissing && animate) return;

        final View rootToRemove = volumeHudRoot;
        final View pill = volumeHudPill;

        if (!animate || pill == null) {
            volumeHudShowing = false;
            volumeHudDismissing = false;
            volumeHudRoot = null;
            volumeHudPill = null;
            try {
                windowManager.removeViewImmediate(rootToRemove);
            } catch (Exception ignored) {}
            return;
        }

        volumeHudDismissing = true;
        pill.animate().cancel();
        pill.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    volumeHudShowing = false;
                    volumeHudDismissing = false;
                    if (volumeHudRoot == rootToRemove) {
                        volumeHudRoot = null;
                        volumeHudPill = null;
                    }
                    try {
                        windowManager.removeViewImmediate(rootToRemove);
                    } catch (Exception ignored) {}
                })
                .start();
    }

    private static void drawVolumeVectorIcon(Canvas c, int w, int h, int color, int pct, boolean isMuted, Paint p) {
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);

        float cx = w * 0.38f;
        float cy = h * 0.5f;

        // Speaker body path
        Path speaker = new Path();
        float left = cx - w * 0.24f;
        float bodyW = w * 0.16f;
        float coneW = w * 0.22f;
        float bodyH = h * 0.30f;
        float coneH = h * 0.60f;

        speaker.moveTo(left, cy - bodyH * 0.5f);
        speaker.lineTo(left + bodyW, cy - bodyH * 0.5f);
        speaker.lineTo(left + bodyW + coneW, cy - coneH * 0.5f);
        speaker.lineTo(left + bodyW + coneW, cy + coneH * 0.5f);
        speaker.lineTo(left + bodyW, cy + bodyH * 0.5f);
        speaker.lineTo(left, cy + bodyH * 0.5f);
        speaker.close();
        c.drawPath(speaker, p);

        if (isMuted) {
            // Draw an X beside the speaker
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2.2f, w * 0.08f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float xCenter = cx + w * 0.36f;
            float xSize = w * 0.13f;
            c.drawLine(xCenter - xSize, cy - xSize, xCenter + xSize, cy + xSize, p);
            c.drawLine(xCenter - xSize, cy + xSize, xCenter + xSize, cy - xSize, p);
        } else {
            // Sound waves
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2.2f, w * 0.08f));
            p.setStrokeCap(Paint.Cap.ROUND);

            if (pct > 0) {
                RectF wave1 = new RectF(cx - w * 0.02f, cy - h * 0.25f, cx + w * 0.36f, cy + h * 0.25f);
                c.drawArc(wave1, -45f, 90f, false, p);
            }
            if (pct >= 40) {
                RectF wave2 = new RectF(cx + w * 0.14f, cy - h * 0.42f, cx + w * 0.58f, cy + h * 0.42f);
                c.drawArc(wave2, -50f, 100f, false, p);
            }
        }
    }

    public static View buildDashboardView(
            Context context,
            LauncherStore store,
            UiSoundEngine sfx,
            Typeface regular,
            Typeface medium,
            Runnable closeOverlay) {
        return buildDashboardWidget(context, store, sfx, regular, medium, false, closeOverlay);
    }

    public static View buildDashboardWidget(
            Context context,
            LauncherStore store,
            UiSoundEngine sfx,
            Typeface regular,
            Typeface medium,
            boolean previewMode,
            Runnable closeOverlay) {

        String qsStyle = store != null ? store.value("qs_style", "frosted") : "frosted";
        int qsFrost = store != null ? store.number("qs_frost", 0) : 0;
        boolean qsScrim = store != null && store.flag("qs_scrim", false);
        String qsLayout = store != null ? store.value("qs_layout", "bento") : "bento";
        boolean isCompact = "compact".equals(qsLayout);
        boolean isExpanded = "expanded".equals(qsLayout);
        boolean isBlade = "blade".equals(qsLayout);
        String qsPosition = store != null ? store.value("qs_position", "right") : "right";
        boolean isLeft = "left".equals(qsPosition);

        FrameLayout scrim = new FrameLayout(context) {
            @Override
            public void playSoundEffect(int soundConstant) {
                // Suppress Android default sound effects
            }
        };
        scrim.setSoundEffectsEnabled(false);
        if (previewMode) {
            scrim.setClickable(false);
            scrim.setFocusable(false);
            scrim.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        } else {
            scrim.setClickable(true);
            scrim.setFocusable(false);
            scrim.setOnClickListener(v -> {
                if (closeOverlay != null) closeOverlay.run();
            });
        }
        if (qsScrim) {
            scrim.setBackground(new Drawable() {
                private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                public void draw(Canvas canvas) {
                    int w = getBounds().width();
                    int h = getBounds().height();
                    if (w <= 0 || h <= 0) return;
                    if (isLeft) {
                        p.setShader(new LinearGradient(0f, 0f, w, 0f,
                                new int[]{0x7A04070D, 0x2404070D, 0x00000000},
                                new float[]{0f, 0.55f, 1f},
                                Shader.TileMode.CLAMP));
                    } else {
                        p.setShader(new LinearGradient(0f, 0f, w, 0f,
                                new int[]{0x00000000, 0x2404070D, 0x7A04070D},
                                new float[]{0f, 0.45f, 1f},
                                Shader.TileMode.CLAMP));
                    }
                    canvas.drawRect(0, 0, w, h, p);
                    p.setShader(null);
                }
                @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
                @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            });
        } else {
            scrim.setBackgroundColor(Color.TRANSPARENT);
        }

        LinearLayout card = new LinearLayout(context) {
            @Override
            public void playSoundEffect(int soundConstant) {
                // Suppress Android default sound effects
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int screenH = getResources().getDisplayMetrics().heightPixels;
                int maxH = (isBlade && !previewMode) ? screenH : (screenH - dp(getContext(), 48));
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int size = MeasureSpec.getSize(heightMeasureSpec);
                if (mode == MeasureSpec.UNSPECIFIED || size > maxH) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        card.setSoundEffectsEnabled(false);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        if (previewMode) {
            card.setClickable(false);
            card.setFocusable(false);
            card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        } else {
            card.setClickable(true);
        }

        final float cardCornerRad = dp(context, isBlade ? (previewMode ? 24 : 0) : 28);
        if (cardCornerRad > 0f) {
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cardCornerRad);
                }
            });
            card.setClipToOutline(true);
        }

        int panelW = isExpanded ? dp(context, 580)
                : isCompact ? dp(context, 324)
                : isBlade ? dp(context, 395)
                : dp(context, 456);

        FrameLayout.LayoutParams cardLp;
        if (previewMode) {
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            int bladePanelW = Math.min(Math.round(screenW * 0.27f), dp(context, 395));
            int stageWidth = screenW - (bladePanelW + dp(context, 28));
            int leftMargin = isLeft
                    ? dp(context, 28)
                    : Math.max(dp(context, 28), stageWidth - panelW - dp(context, 20));
            if (isBlade) {
                cardLp = new FrameLayout.LayoutParams(
                        panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.CENTER_VERTICAL);
                cardLp.leftMargin = leftMargin;
                cardLp.topMargin = dp(context, 24);
                cardLp.bottomMargin = dp(context, 24);
                card.setBackground(buildLiquidGlassPanelDrawable(context, card, 24f, qsStyle, qsFrost));
                card.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
            } else {
                cardLp = new FrameLayout.LayoutParams(
                        panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
                cardLp.leftMargin = leftMargin;
                card.setBackground(buildLiquidGlassPanelDrawable(context, card, 28f, qsStyle, qsFrost));
                card.setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14));
            }
        } else {
            if (isBlade) {
                cardLp = new FrameLayout.LayoutParams(
                        panelW, ViewGroup.LayoutParams.MATCH_PARENT,
                        isLeft ? Gravity.START : Gravity.END);
                cardLp.leftMargin = 0;
                cardLp.rightMargin = 0;
                cardLp.topMargin = 0;
                cardLp.bottomMargin = 0;
                card.setBackground(buildLiquidGlassPanelDrawable(context, card, 0f, qsStyle, qsFrost));
                card.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
            } else {
                cardLp = new FrameLayout.LayoutParams(
                        panelW, ViewGroup.LayoutParams.WRAP_CONTENT,
                        (isLeft ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
                if (isLeft) {
                    cardLp.leftMargin = dp(context, isCompact ? 28 : 32);
                    cardLp.rightMargin = 0;
                } else {
                    cardLp.rightMargin = dp(context, isCompact ? 28 : 32);
                    cardLp.leftMargin = 0;
                }
                cardLp.topMargin = dp(context, 24);
                cardLp.bottomMargin = dp(context, 24);
                card.setBackground(buildLiquidGlassPanelDrawable(context, card, 28f, qsStyle, qsFrost));
                card.setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14));
            }
        }

        // Header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);

        final int crumbId = View.generateViewId();
        if (isBlade) {
            TextView crumb = new TextView(context);
            crumb.setId(crumbId);
            crumb.setText("‹  Quick Settings");
            crumb.setTypeface(medium);
            crumb.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 19));
            crumb.setTextColor(0xFFF8FAFC);
            crumb.setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 12));
            if (previewMode) {
                crumb.setFocusable(false);
                crumb.setClickable(false);
            } else {
                crumb.setFocusable(true);
                crumb.setNextFocusLeftId(crumbId);
                crumb.setNextFocusRightId(crumbId);
                crumb.setNextFocusUpId(crumbId);
                crumb.setOnClickListener(v -> {
                    if (closeOverlay != null) closeOverlay.run();
                });
                crumb.setOnFocusChangeListener((v, hasFocus) -> {
                    crumb.setTextColor(hasFocus ? 0xFFFFFFFF : 0xFFF8FAFC);
                    v.setTranslationZ(hasFocus ? dp(context, 4) : 0f);
                    v.animate().cancel();
                    v.animate()
                            .scaleX(hasFocus ? 1.02f : 1.0f)
                            .scaleY(hasFocus ? 1.02f : 1.0f)
                            .setDuration(160)
                            .start();
                    if (hasFocus && sfx != null) {
                        int sw = context.getResources().getDisplayMetrics().widthPixels;
                        sfx.playFocus(v, sw, sLastNavDir);
                    }
                });
            }
            header.addView(crumb);

            LinearLayout timeRow = new LinearLayout(context);
            timeRow.setOrientation(LinearLayout.HORIZONTAL);
            timeRow.setGravity(Gravity.CENTER_VERTICAL);
            timeRow.setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 4));

            TextClock clock = new TextClock(context);
            clock.setFormat12Hour("h:mm a");
            clock.setFormat24Hour("HH:mm");
            clock.setTypeface(medium);
            clock.setTextColor(0xFFFFFFFF);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 26));
            timeRow.addView(clock);

            TextView dateView = new TextView(context);
            String dateText = new SimpleDateFormat("  ·  EEE, MMM d", Locale.getDefault()).format(new Date());
            dateView.setText(dateText);
            dateView.setTypeface(regular);
            dateView.setTextColor(0xB8E2E8F0);
            dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 13));
            timeRow.addView(dateView);

            header.addView(timeRow);
        } else if (isCompact) {
            LinearLayout compHeader = new LinearLayout(context);
            compHeader.setOrientation(LinearLayout.HORIZONTAL);
            compHeader.setGravity(Gravity.CENTER_VERTICAL);

            TextView badge = new TextView(context);
            badge.setText("QUICK SETTINGS");
            badge.setTypeface(medium);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 11));
            badge.setLetterSpacing(0.14f);
            badge.setTextColor("adaptive".equals(qsStyle) ? 0xFF93C5FD : 0xFF7DD3FC);
            compHeader.addView(badge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextClock clock = new TextClock(context);
            clock.setFormat12Hour("h:mm a");
            clock.setFormat24Hour("HH:mm");
            clock.setTypeface(medium);
            clock.setTextColor(0xFFFFFFFF);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 18));
            compHeader.addView(clock);

            header.addView(compHeader);
        } else {
            LinearLayout stdHeader = new LinearLayout(context);
            stdHeader.setOrientation(LinearLayout.HORIZONTAL);
            stdHeader.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout clockCol = new LinearLayout(context);
            clockCol.setOrientation(LinearLayout.VERTICAL);

            TextView badge = new TextView(context);
            badge.setText(isExpanded ? "QUICK SETTINGS · CONTROL CENTER" : "QUICK SETTINGS");
            badge.setTypeface(medium);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 11));
            badge.setLetterSpacing(0.14f);
            if ("adaptive".equals(qsStyle)) {
                int ext = LiquidGlassRenderer.getExtractedOverlayColor();
                float[] hsv = new float[3];
                Color.colorToHSV(ext, hsv);
                hsv[1] = Math.min(0.65f, hsv[1]);
                hsv[2] = 0.96f;
                badge.setTextColor(Color.HSVToColor(hsv));
            } else {
                badge.setTextColor(0xFF7DD3FC);
            }
            clockCol.addView(badge);

            LinearLayout timeRow = new LinearLayout(context);
            timeRow.setOrientation(LinearLayout.HORIZONTAL);
            timeRow.setGravity(Gravity.BOTTOM);

            TextClock clock = new TextClock(context);
            clock.setFormat12Hour("h:mm a");
            clock.setFormat24Hour("HH:mm");
            clock.setTypeface(medium);
            clock.setTextColor(0xFFFFFFFF);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 30));
            timeRow.addView(clock);

            TextView dateView = new TextView(context);
            String dateText = new SimpleDateFormat("  ·  EEE, MMM d", Locale.getDefault()).format(new Date());
            dateView.setText(dateText);
            dateView.setTypeface(regular);
            dateView.setTextColor(0xB8E2E8F0);
            dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 14));
            LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dateLp.bottomMargin = dp(context, 3);
            timeRow.addView(dateView, dateLp);

            LinearLayout.LayoutParams timeRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timeRowLp.topMargin = dp(context, 2);
            clockCol.addView(timeRow, timeRowLp);

            stdHeader.addView(clockCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            header.addView(stdHeader);
        }

        // Subtle crystal divider
        View divider = new View(context);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(context, 1)));
        divLp.topMargin = dp(context, isCompact ? 10 : 12);
        divLp.bottomMargin = dp(context, isCompact ? 8 : 10);

        // Build the Quick Settings Tiles
        final Runnable safeClose = closeOverlay != null ? closeOverlay : () -> {};
        String wifiSubtitle = getWifiStatusSummary(context);
        List<TileSpec> specs = new ArrayList<>();
        specs.add(new TileSpec("settings", "All Settings", "System & device", 0xFF38BDF8, () -> {
            safeClose.run();
            launchSystemSettings(context, Settings.ACTION_SETTINGS);
        }));
        specs.add(new TileSpec("sliders", "Picture & Sound", "TV Quick Menu", 0xFFA78BFA, () -> {
            safeClose.run();
            new Handler(Looper.getMainLooper()).postDelayed(() -> launchTclQuickMenuOrDisplay(context), 80);
        }));
        specs.add(new TileSpec("wifi", "Wi-Fi & Network", wifiSubtitle, 0xFF34D399, () -> {
            safeClose.run();
            launchSystemSettings(context, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS);
        }));
        specs.add(new TileSpec("bluetooth", "Bluetooth & Remote", "Accessories", 0xFF60A5FA, () -> {
            safeClose.run();
            launchSystemSettings(context,
                    Settings.ACTION_BLUETOOTH_SETTINGS,
                    "com.google.android.tv.settings.ACCESSORIES",
                    Settings.ACTION_SETTINGS);
        }));
        specs.add(new TileSpec("display", "Display & Audio", "Screen & sound", 0xFFFBBF24, () -> {
            safeClose.run();
            launchSystemSettings(context,
                    Settings.ACTION_DISPLAY_SETTINGS,
                    "android.settings.PICTURE_SETTINGS",
                    Settings.ACTION_SOUND_SETTINGS,
                    Settings.ACTION_SETTINGS);
        }));
        specs.add(new TileSpec("hdmi", "TV Inputs", "HDMI & sources", 0xFFF472B6, () -> {
            safeClose.run();
            launchTvInputs(context);
        }));
        specs.add(new TileSpec("luma", "Luma Settings", "Themes & glass", 0xFF2DD4BF, () -> {
            safeClose.run();
            launchLumaAction(context, EXTRA_OPEN_LUMA_SETTINGS);
        }));
        specs.add(new TileSpec("moon", "Screensaver", "Ambient clock", 0xFF818CF8, () -> {
            safeClose.run();
            launchLumaAction(context, EXTRA_OPEN_SCREENSAVER);
        }));
        if (isExpanded) {
            specs.add(new TileSpec("power", "Sleep & Power", "TV standby & timer", 0xFFF87171, () -> {
                safeClose.run();
                launchSystemSettings(context, "android.settings.DATE_SETTINGS", Settings.ACTION_SETTINGS);
            }));
        }

        final boolean isCompactLayout = isCompact;
        ScrollView scroll = new ScrollView(context) {
            @Override
            public void playSoundEffect(int soundConstant) {
                // Suppress Android default sound effects
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                if (mode != MeasureSpec.EXACTLY) {
                    int screenH = getResources().getDisplayMetrics().heightPixels;
                    int maxAllowedH = (isBlade && !previewMode) ? screenH : (screenH - dp(getContext(), 48));
                    int reserved = dp(getContext(), isCompactLayout ? 135 : 155);
                    int maxScrollH = Math.max(dp(getContext(), 180), maxAllowedH - reserved);
                    int size = MeasureSpec.getSize(heightMeasureSpec);
                    if (mode == MeasureSpec.UNSPECIFIED || size > maxScrollH) {
                        heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxScrollH, MeasureSpec.AT_MOST);
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                if (getChildCount() == 0 || rect == null) return 0;
                int padding = dp(getContext(), 16);
                Rect padded = new Rect(rect);
                padded.top = Math.max(0, padded.top - padding);
                padded.bottom = Math.min(getChildAt(0).getHeight(), padded.bottom + padding);
                return super.computeScrollDeltaToGetChildRectOnScreen(padded);
            }
        };
        scroll.setSoundEffectsEnabled(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout contentBox = new LinearLayout(context);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setClipChildren(false);
        contentBox.setClipToPadding(false);
        contentBox.setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 16));

        // Unpinned header and divider inside contentBox so the entire view scrolls naturally
        contentBox.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentBox.addView(divider, divLp);

        List<View> tileViews = new ArrayList<>();

        if (isCompact) {
            int[] compactIndices = {0, 1, 2, 5, 6, 7};
            for (int i = 0; i < compactIndices.length; i++) {
                TileSpec spec = specs.get(compactIndices[i]);
                View tile = buildTileView(context, spec, sfx, regular, medium, qsStyle, qsFrost, true, previewMode);
                tile.setId(View.generateViewId());
                tileViews.add(tile);

                LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52));
                if (i > 0) tileLp.topMargin = dp(context, 7);
                contentBox.addView(tile, tileLp);
            }
            for (int i = 0; i < tileViews.size(); i++) {
                View v = tileViews.get(i);
                v.setNextFocusLeftId(v.getId());
                v.setNextFocusRightId(v.getId());
                v.setNextFocusUpId(i > 0 ? tileViews.get(i - 1).getId() : v.getId());
                v.setNextFocusDownId(i < tileViews.size() - 1 ? tileViews.get(i + 1).getId() : v.getId());
            }
        } else if (isBlade) {
            for (int i = 0; i < specs.size() && i < 8; i++) {
                TileSpec spec = specs.get(i);
                View tile = buildTileView(context, spec, sfx, regular, medium, qsStyle, qsFrost, true, previewMode);
                tile.setId(View.generateViewId());
                tileViews.add(tile);

                LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52));
                if (i > 0) tileLp.topMargin = dp(context, 7);
                contentBox.addView(tile, tileLp);
            }
            for (int i = 0; i < tileViews.size(); i++) {
                View v = tileViews.get(i);
                v.setNextFocusLeftId(v.getId());
                v.setNextFocusRightId(v.getId());
                v.setNextFocusUpId(i > 0 ? tileViews.get(i - 1).getId() : (previewMode ? v.getId() : crumbId));
                v.setNextFocusDownId(i < tileViews.size() - 1 ? tileViews.get(i + 1).getId() : v.getId());
            }
            if (!tileViews.isEmpty() && !previewMode) {
                View crumb = header.findViewById(crumbId);
                if (crumb != null) {
                    crumb.setNextFocusDownId(tileViews.get(0).getId());
                }
            }
        } else if (isExpanded) {
            int gap = dp(context, 10);
            for (int row = 0; row < 3; row++) {
                LinearLayout rowLayout = new LinearLayout(context);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setClipChildren(false);
                rowLayout.setClipToPadding(false);

                for (int col = 0; col < 3; col++) {
                    int idx = row * 3 + col;
                    TileSpec spec = specs.get(idx);
                    View tile = buildTileView(context, spec, sfx, regular, medium, qsStyle, qsFrost, false, previewMode);
                    tile.setId(View.generateViewId());
                    tileViews.add(tile);

                    LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                            0, dp(context, 80), 1f);
                    if (col == 0) tileLp.rightMargin = gap / 2;
                    else if (col == 1) { tileLp.leftMargin = gap / 2; tileLp.rightMargin = gap / 2; }
                    else tileLp.leftMargin = gap / 2;
                    rowLayout.addView(tile, tileLp);
                }

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (row > 0) rowLp.topMargin = gap;
                contentBox.addView(rowLayout, rowLp);
            }
            for (int i = 0; i < tileViews.size(); i++) {
                int r = i / 3;
                int c = i % 3;
                View v = tileViews.get(i);
                v.setNextFocusLeftId(c > 0 ? tileViews.get(i - 1).getId() : v.getId());
                v.setNextFocusRightId(c < 2 ? tileViews.get(i + 1).getId() : v.getId());
                v.setNextFocusUpId(r > 0 ? tileViews.get(i - 3).getId() : v.getId());
                v.setNextFocusDownId(r < 2 ? tileViews.get(i + 3).getId() : v.getId());
            }
        } else {
            int gap = dp(context, 12);
            for (int row = 0; row < 4; row++) {
                LinearLayout rowLayout = new LinearLayout(context);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setClipChildren(false);
                rowLayout.setClipToPadding(false);

                for (int col = 0; col < 2; col++) {
                    int idx = row * 2 + col;
                    TileSpec spec = specs.get(idx);
                    View tile = buildTileView(context, spec, sfx, regular, medium, qsStyle, qsFrost, false, previewMode);
                    tile.setId(View.generateViewId());
                    tileViews.add(tile);

                    LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                            0, dp(context, 84), 1f);
                    if (col == 0) tileLp.rightMargin = gap / 2;
                    else tileLp.leftMargin = gap / 2;
                    rowLayout.addView(tile, tileLp);
                }

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (row > 0) rowLp.topMargin = gap;
                contentBox.addView(rowLayout, rowLp);
            }
            for (int i = 0; i < tileViews.size(); i++) {
                int r = i / 2;
                int c = i % 2;
                View v = tileViews.get(i);
                v.setNextFocusLeftId(c == 1 ? tileViews.get(i - 1).getId() : v.getId());
                v.setNextFocusRightId(c == 0 ? tileViews.get(i + 1).getId() : v.getId());
                v.setNextFocusUpId(r > 0 ? tileViews.get(i - 2).getId() : v.getId());
                v.setNextFocusDownId(r < 3 ? tileViews.get(i + 2).getId() : v.getId());
            }
        }

        scroll.addView(contentBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollLp;
        if (isBlade) {
            scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        } else {
            scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        card.addView(scroll, scrollLp);

        scrim.addView(card, cardLp);

        QuickSettingsOverlayService svc = sInstance;
        if (svc != null && !previewMode) {
            svc.panelCard = card;
        }

        if (!previewMode) {
            // Entrance animation
            float slideDist = isLeft ? -dp(context, 56) : dp(context, 56);
            card.setTranslationX(slideDist);
            card.setAlpha(0f);
            card.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(230)
                    .setInterpolator(new OvershootInterpolator(0.85f))
                    .withEndAction(() -> {
                        card.invalidate();
                        for (View tv : tileViews) tv.invalidate();
                    })
                    .start();

            if (!tileViews.isEmpty()) {
                final View firstTile = tileViews.get(0);
                firstTile.post(firstTile::requestFocus);
            }
        }

        applyViewSoundPolicy(scrim);
        return scrim;
    }

    private static View buildTileView(
            Context context,
            TileSpec spec,
            UiSoundEngine sfx,
            Typeface regular,
            Typeface medium,
            String qsStyle,
            int qsFrost,
            boolean horizontal,
            boolean previewMode) {

        LinearLayout tile = new LinearLayout(context) {
            @Override
            public void playSoundEffect(int soundConstant) {
                // Suppress Android default system sounds
            }
        };
        tile.setSoundEffectsEnabled(false);
        if (previewMode) {
            tile.setFocusable(false);
            tile.setFocusableInTouchMode(false);
            tile.setClickable(false);
        } else {
            tile.setFocusable(true);
            tile.setFocusableInTouchMode(true);
            tile.setClickable(true);
        }

        TileGlassDrawable bg = new TileGlassDrawable(context, tile, spec.accentColor, qsStyle, qsFrost);
        tile.setBackground(bg);

        View iconView = new View(context) {
            private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                drawTileVectorIcon(canvas, spec.iconType, getWidth(), getHeight(),
                        tile.isFocused() ? 0xFFFFFFFF : spec.accentColor, iconPaint);
            }
        };

        TextView titleView = new TextView(context);
        titleView.setText(spec.title);
        titleView.setTypeface(medium);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextColor(0xFFF8FAFC);

        TextView subView = new TextView(context);
        subView.setText(spec.subtitle);
        subView.setTypeface(regular);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        subView.setTextColor(0xA6CBD5E1);

        if (horizontal) {
            tile.setOrientation(LinearLayout.HORIZONTAL);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(context, 14);
            int padV = dp(context, 10);
            tile.setPadding(padH, padV, padH, padV);

            int iconBoxSize = dp(context, 34);
            int innerIconSize = dp(context, 20);
            FrameLayout iconBox = new FrameLayout(context);
            iconBox.setBackground(new Drawable() {
                private final Paint boxP = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                public void draw(Canvas c) {
                    boxP.setColor(tile.isFocused() ? 0x33FFFFFF : 0x16FFFFFF);
                    float r = dp(context, 10);
                    c.drawRoundRect(new RectF(0, 0, getBounds().width(), getBounds().height()), r, r, boxP);
                    boxP.setColor(tile.isFocused() ? (spec.accentColor & 0x66FFFFFF) : 0x22FFFFFF);
                    boxP.setStyle(Paint.Style.STROKE);
                    boxP.setStrokeWidth(dp(context, 1));
                    c.drawRoundRect(new RectF(0, 0, getBounds().width(), getBounds().height()), r, r, boxP);
                    boxP.setStyle(Paint.Style.FILL);
                }
                @Override public void setAlpha(int a) {}
                @Override public void setColorFilter(ColorFilter cf) {}
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            });
            FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
                    innerIconSize, innerIconSize, Gravity.CENTER);
            iconBox.addView(iconView, ivLp);
            tile.addView(iconBox, new LinearLayout.LayoutParams(iconBoxSize, iconBoxSize));

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setGravity(Gravity.CENTER_VERTICAL);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 14f));
            textCol.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 11.5f));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(context, 2);
            textCol.addView(subView, subLp);

            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.leftMargin = dp(context, 12);
            textLp.rightMargin = dp(context, 8);
            tile.addView(textCol, textLp);

            TextView chevron = new TextView(context);
            chevron.setText("›");
            chevron.setTypeface(medium);
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 16));
            chevron.setTextColor(0x55E2E8F0);
            tile.addView(chevron, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(context, 14);
            int padV = dp(context, 11);
            tile.setPadding(padH, padV, padH, padV);

            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            int iconSize = dp(context, 22);
            topRow.addView(iconView, new LinearLayout.LayoutParams(iconSize, iconSize));

            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 14.5f));
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleLp.leftMargin = dp(context, 10);
            topRow.addView(titleView, titleLp);

            tile.addView(topRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 12f));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(context, 5);
            tile.addView(subView, subLp);
        }

        if (!previewMode) {
            tile.setOnFocusChangeListener((v, hasFocus) -> {
                bg.setFocused(hasFocus);
                iconView.invalidate();
                titleView.setTextColor(hasFocus ? 0xFFFFFFFF : 0xFFF8FAFC);
                subView.setTextColor(hasFocus ? 0xE6E2E8F0 : 0xA6CBD5E1);
                v.setTranslationZ(hasFocus ? dp(context, 6) : 0f);
                float targetScale = hasFocus ? (horizontal ? 1.018f : 1.025f) : 1.0f;
                v.animate().cancel();
                v.animate()
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .setDuration(160)
                        .setInterpolator(new OvershootInterpolator(1.04f))
                        .start();
                if (hasFocus && sfx != null) {
                    if (android.os.SystemClock.uptimeMillis() - sLastNavMs < 350L) {
                        int sw = context.getResources().getDisplayMetrics().widthPixels;
                        sfx.playFocus(v, sw, sLastNavDir);
                    }
                }
            });

            tile.setOnClickListener(v -> {
                if (sfx != null) {
                    int sw = context.getResources().getDisplayMetrics().widthPixels;
                    sfx.playSelect(v, sw);
                }
                if (spec.action != null) spec.action.run();
            });
        }

        return tile;
    }

    private static void drawTileVectorIcon(Canvas c, String type, int w, int h, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        float sw = Math.max(2f, w * 0.095f);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = Math.min(w, h) * 0.34f;

        if ("settings".equals(type)) {
            c.drawCircle(cx, cy, r * 0.52f, p);
            for (int i = 0; i < 8; i++) {
                double ang = i * Math.PI / 4.0;
                float x1 = cx + (float) Math.cos(ang) * (r * 0.72f);
                float y1 = cy + (float) Math.sin(ang) * (r * 0.72f);
                float x2 = cx + (float) Math.cos(ang) * (r * 1.05f);
                float y2 = cy + (float) Math.sin(ang) * (r * 1.05f);
                c.drawLine(x1, y1, x2, y2, p);
            }
        } else if ("sliders".equals(type)) {
            float y1 = h * 0.28f, y2 = h * 0.50f, y3 = h * 0.72f;
            float xL = w * 0.16f, xR = w * 0.84f;
            c.drawLine(xL, y1, xR, y1, p);
            c.drawLine(xL, y2, xR, y2, p);
            c.drawLine(xL, y3, xR, y3, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(w * 0.38f, y1, sw * 1.15f, p);
            c.drawCircle(w * 0.66f, y2, sw * 1.15f, p);
            c.drawCircle(w * 0.46f, y3, sw * 1.15f, p);
        } else if ("wifi".equals(type)) {
            RectF arc1 = new RectF(cx - r * 1.05f, cy - r * 0.65f, cx + r * 1.05f, cy + r * 1.45f);
            RectF arc2 = new RectF(cx - r * 0.68f, cy - r * 0.28f, cx + r * 0.68f, cy + r * 1.08f);
            c.drawArc(arc1, 220f, 100f, false, p);
            c.drawArc(arc2, 220f, 100f, false, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy + r * 0.55f, sw * 0.9f, p);
        } else if ("bluetooth".equals(type)) {
            Path path = new Path();
            path.moveTo(cx - r * 0.45f, cy - r * 0.45f);
            path.lineTo(cx + r * 0.45f, cy + r * 0.45f);
            path.lineTo(cx, cy + r * 0.95f);
            path.lineTo(cx, cy - r * 0.95f);
            path.lineTo(cx + r * 0.45f, cy - r * 0.45f);
            path.lineTo(cx - r * 0.45f, cy + r * 0.45f);
            c.drawPath(path, p);
        } else if ("display".equals(type)) {
            RectF rect = new RectF(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.68f);
            c.drawRoundRect(rect, sw, sw, p);
            c.drawLine(w * 0.34f, h * 0.82f, w * 0.66f, h * 0.82f, p);
            c.drawLine(cx, h * 0.68f, cx, h * 0.82f, p);
        } else if ("hdmi".equals(type)) {
            Path port = new Path();
            port.moveTo(w * 0.16f, h * 0.32f);
            port.lineTo(w * 0.84f, h * 0.32f);
            port.lineTo(w * 0.84f, h * 0.56f);
            port.lineTo(w * 0.70f, h * 0.70f);
            port.lineTo(w * 0.30f, h * 0.70f);
            port.lineTo(w * 0.16f, h * 0.56f);
            port.close();
            c.drawPath(port, p);
            c.drawLine(w * 0.34f, h * 0.48f, w * 0.66f, h * 0.48f, p);
        } else if ("luma".equals(type)) {
            Path star = new Path();
            star.moveTo(cx, cy - r);
            star.quadTo(cx, cy, cx + r, cy);
            star.quadTo(cx, cy, cx, cy + r);
            star.quadTo(cx, cy, cx - r, cy);
            star.quadTo(cx, cy, cx, cy - r);
            star.close();
            c.drawPath(star, p);
        } else if ("power".equals(type)) {
            RectF arc = new RectF(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f);
            c.drawArc(arc, -60f, 300f, false, p);
            c.drawLine(cx, cy - r * 0.95f, cx, cy - r * 0.1f, p);
        } else {
            // Moon / Screensaver
            c.drawArc(new RectF(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f),
                    35f, 270f, false, p);
        }
    }

    private static Drawable buildLiquidGlassPanelDrawable(
            Context context, View anchor, float radiusDp, String qsStyle, int qsFrost) {
        final float radius = dp(context, radiusDp);
        final float stroke = Math.max(1.5f, dp(context, 1.45f));
        final int screenW = context.getResources().getDisplayMetrics().widthPixels;
        final int screenH = context.getResources().getDisplayMetrics().heightPixels;
        final String style = qsStyle != null ? qsStyle : "frosted";
        final float frostScale = qsFrost <= 0 ? 0f : Math.max(0.25f, Math.min(2.2f, qsFrost / 35f));
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();
            private final int[] loc = new int[2];
            private final Matrix shaderMatrix = new Matrix();
            private android.graphics.Bitmap refractedPatch;
            private android.graphics.Bitmap lastBackdrop;
            private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastW, lastH;

            @Override
            public void draw(Canvas canvas) {
                rect.set(getBounds());
                if (rect.width() <= 0 || rect.height() <= 0) return;

                if (anchor != null) {
                    anchor.getLocationOnScreen(loc);
                }

                int adaptiveCol = "adaptive".equals(style)
                        ? LiquidGlassRenderer.getExtractedOverlayColor()
                        : 0xFF0E1725;

                // 1A. Frosted mode: draw the blurred screen/wallpaper backdrop inside the rounded panel
                if ("frosted".equals(style)) {
                    android.graphics.Bitmap blurred = LiquidGlassRenderer.getFrostedOverlayBackdrop();
                    if (blurred != null && !blurred.isRecycled() && screenW > 0 && screenH > 0) {
                        BitmapShader bs = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        shaderMatrix.reset();
                        shaderMatrix.postScale(
                                (float) screenW / blurred.getWidth(),
                                (float) screenH / blurred.getHeight());
                        shaderMatrix.postTranslate(-loc[0], -loc[1]);
                        bs.setLocalMatrix(shaderMatrix);
                        p.setStyle(Paint.Style.FILL);
                        p.setShader(bs);
                        canvas.drawRoundRect(rect, radius, radius, p);
                        p.setShader(null);
                    }
                }

                // 1B. Outer bevel-ring optical refraction (for liquid_glass, adaptive, and frosted edge polish)
                if (!"solid_dark".equals(style)) {
                    android.graphics.Bitmap backdrop = LiquidGlassRenderer.getOverlayBackdrop();
                    if (backdrop != null && anchor != null && screenW > 0 && screenH > 0) {
                        int iw = Math.round(rect.width()), ih = Math.round(rect.height());
                        if (refractedPatch == null || lastBackdrop != backdrop
                                || Math.abs(loc[0] - lastX) > 2 || Math.abs(loc[1] - lastY) > 2
                                || iw != lastW || ih != lastH) {
                            float bevelW = dp(context, "frosted".equals(style) ? 12f : 18f);
                            float refrH = dp(context, "frosted".equals(style) ? 26f : 42f);
                            float tintStr = frostScale <= 0f ? 0f : ("adaptive".equals(style) ? 0.42f : 0.24f);
                            refractedPatch = LiquidGlassRenderer.renderRefractedBevelRing(
                                    backdrop, screenW, screenH, loc[0], loc[1],
                                    rect.width(), rect.height(), radius, bevelW, refrH,
                                    0.16f, 1.65f, 0.52f, adaptiveCol, tintStr, refractedPatch);
                            lastBackdrop = backdrop;
                            lastX = loc[0];
                            lastY = loc[1];
                            lastW = iw;
                            lastH = ih;
                        }
                        if (refractedPatch != null) {
                            LiquidGlassRenderer.drawPatch(canvas, refractedPatch, rect, p);
                        }
                    }
                }

                // 2. Interior surface tint based on selected style & frost depth
                p.setStyle(Paint.Style.FILL);
                if ("solid_dark".equals(style)) {
                    p.setShader(new LinearGradient(
                            0f, rect.top, 0f, rect.bottom,
                            0xF2101726, 0xF7080C15,
                            Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(rect, radius, radius, p);
                    p.setShader(null);
                } else {
                    if ("adaptive".equals(style)) {
                        int aTop = Math.min(235, Math.max(40, Math.round(128 * frostScale)));
                        int aBot = Math.min(245, Math.max(55, Math.round(168 * frostScale)));
                        int cTop = (adaptiveCol & 0x00FFFFFF) | (aTop << 24);
                        int rB = Math.max(0, (int) (Color.red(adaptiveCol) * 0.58f));
                        int gB = Math.max(0, (int) (Color.green(adaptiveCol) * 0.58f));
                        int bB = Math.max(0, (int) (Color.blue(adaptiveCol) * 0.65f));
                        int cBot = Color.argb(aBot, rB, gB, bB);
                        p.setShader(new LinearGradient(
                                rect.left, rect.top, rect.right, rect.bottom,
                                cTop, cBot,
                                Shader.TileMode.CLAMP));
                    } else if ("frosted".equals(style)) {
                        int aTop = Math.min(220, Math.max(36, Math.round(92 * frostScale)));
                        int aBot = Math.min(235, Math.max(48, Math.round(132 * frostScale)));
                        p.setShader(new LinearGradient(
                                0f, rect.top, 0f, rect.bottom,
                                (aTop << 24) | 0x121B2C,
                                (aBot << 24) | 0x0A101C,
                                Shader.TileMode.CLAMP));
                    } else {
                        // Default "liquid_glass"
                        int aTop = Math.min(185, Math.max(18, Math.round(48 * Math.max(0.28f, frostScale))));
                        int aBot = Math.min(205, Math.max(26, Math.round(70 * Math.max(0.28f, frostScale))));
                        p.setShader(new LinearGradient(
                                0f, rect.top, 0f, rect.bottom,
                                (aTop << 24) | 0x0E1725,
                                (aBot << 24) | 0x0A101C,
                                Shader.TileMode.CLAMP));
                    }
                    canvas.drawRoundRect(rect, radius, radius, p);
                    p.setShader(null);
                }

                // 3. Subtle diagonal Fresnel & internal caustic bounce
                int causticColor = "adaptive".equals(style)
                        ? ((adaptiveCol & 0x00FFFFFF) | 0x38000000)
                        : 0x24BAE6FD;
                p.setShader(new LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{0x22FFFFFF, 0x00FFFFFF, 0x00FFFFFF, causticColor},
                        new float[]{0f, 0.26f, 0.74f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, p);
                p.setShader(null);

                // 4. Capped top crown catchlight
                float domeH = Math.min(dp(context, 52), rect.height() * 0.28f);
                canvas.save();
                canvas.clipRect(rect.left, rect.top, rect.right, rect.top + domeH);
                p.setShader(new LinearGradient(
                        0f, rect.top, 0f, rect.top + domeH,
                        0x20FFFFFF, 0x00FFFFFF,
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, p);
                canvas.restore();
                p.setShader(null);

                // 5. Beveled 5-stop diagonal specular crystal rim
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                int rimTint = "adaptive".equals(style)
                        ? ((adaptiveCol & 0x00FFFFFF) | 0x78000000)
                        : 0x52BAE6FD;
                p.setShader(new LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{0xB8FFFFFF, 0x22FFFFFF, 0x10000000, rimTint, 0x80FFFFFF},
                        new float[]{0f, 0.26f, 0.52f, 0.78f, 1f},
                        Shader.TileMode.CLAMP));
                RectF strokeRect = new RectF(rect);
                float inset = stroke * 0.5f;
                strokeRect.inset(inset, inset);
                canvas.drawRoundRect(strokeRect, Math.max(0f, radius - inset), Math.max(0f, radius - inset), p);
                p.setShader(null);
            }

            @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private static final class TileGlassDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int[] loc = new int[2];
        private final Context context;
        private final View anchor;
        private final float radius;
        private final float strokeWidth;
        private final int accentColor;
        private final String style;
        private final float frostScale;
        private final int screenW, screenH;
        private boolean focused;
        private android.graphics.Bitmap refractedPatch;
        private android.graphics.Bitmap lastBackdrop;
        private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastW, lastH;

        TileGlassDrawable(Context context, View anchor, int accentColor, String qsStyle, int qsFrost) {
            this.context = context;
            this.anchor = anchor;
            this.radius = dp(context, 18);
            this.strokeWidth = Math.max(1.5f, dp(context, 1.5f));
            this.accentColor = accentColor;
            this.style = qsStyle != null ? qsStyle : "frosted";
            this.frostScale = qsFrost <= 0 ? 0f : Math.max(0.25f, Math.min(2.2f, qsFrost / 35f));
            this.screenW = context.getResources().getDisplayMetrics().widthPixels;
            this.screenH = context.getResources().getDisplayMetrics().heightPixels;
        }

        void setFocused(boolean focused) {
            if (this.focused != focused) {
                this.focused = focused;
                invalidateSelf();
            }
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            if (rect.width() <= 0 || rect.height() <= 0) return;

            int adaptiveCol = "adaptive".equals(style)
                    ? LiquidGlassRenderer.getExtractedOverlayColor()
                    : 0xFF0E1725;

            // 1. Outer bevel-ring refraction on each tile (for liquid_glass and adaptive modes)
            if (!"solid_dark".equals(style) && !"frosted".equals(style)) {
                android.graphics.Bitmap backdrop = LiquidGlassRenderer.getOverlayBackdrop();
                if (backdrop != null && anchor != null && screenW > 0 && screenH > 0) {
                    anchor.getLocationOnScreen(loc);
                    int iw = Math.round(rect.width()), ih = Math.round(rect.height());
                    if (refractedPatch == null || lastBackdrop != backdrop
                            || Math.abs(loc[0] - lastX) > 2 || Math.abs(loc[1] - lastY) > 2
                            || iw != lastW || ih != lastH) {
                        float bevelW = dp(context, 10f);
                        float refrH = dp(context, 24f);
                        float tintStr = frostScale <= 0f ? 0f : ("adaptive".equals(style) ? 0.36f : 0.20f);
                        refractedPatch = LiquidGlassRenderer.renderRefractedBevelRing(
                                backdrop, screenW, screenH, loc[0], loc[1],
                                rect.width(), rect.height(), radius, bevelW, refrH,
                                0.16f, 1.65f, 0.55f, adaptiveCol, tintStr, refractedPatch);
                        lastBackdrop = backdrop;
                        lastX = loc[0];
                        lastY = loc[1];
                        lastW = iw;
                        lastH = ih;
                    }
                    if (refractedPatch != null) {
                        LiquidGlassRenderer.drawPatch(canvas, refractedPatch, rect, p);
                    }
                }
            }

            // 2. Tile interior fill based on style & focus
            p.setStyle(Paint.Style.FILL);
            if (focused) {
                int topTint = (accentColor & 0x00FFFFFF) | 0x44000000;
                int botTint = (accentColor & 0x00FFFFFF) | 0x22000000;
                int midTint = "solid_dark".equals(style)
                        ? 0xB8182234
                        : ("adaptive".equals(style)
                                ? ((adaptiveCol & 0x00FFFFFF) | 0x52000000)
                                : 0x36141E30);
                p.setShader(new LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{topTint, midTint, botTint},
                        new float[]{0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP));
            } else if ("solid_dark".equals(style)) {
                p.setShader(new LinearGradient(
                        0f, rect.top, 0f, rect.bottom,
                        0xCC1B2436, 0xD8121928,
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, p);
                p.setShader(null);
            } else if (frostScale > 0f) {
                if ("adaptive".equals(style)) {
                    int aTop = Math.min(180, Math.max(28, Math.round(68 * frostScale)));
                    int aBot = Math.min(200, Math.max(36, Math.round(92 * frostScale)));
                    int cTop = (adaptiveCol & 0x00FFFFFF) | (aTop << 24);
                    int cBot = (adaptiveCol & 0x00FFFFFF) | (aBot << 24);
                    p.setShader(new LinearGradient(
                            0f, rect.top, 0f, rect.bottom,
                            cTop, cBot,
                            Shader.TileMode.CLAMP));
                } else if ("frosted".equals(style)) {
                    int aTop = Math.min(140, Math.max(22, Math.round(48 * frostScale)));
                    int aBot = Math.min(165, Math.max(30, Math.round(64 * frostScale)));
                    p.setShader(new LinearGradient(
                            0f, rect.top, 0f, rect.bottom,
                            (aTop << 24) | 0xFFFFFF,
                            (aBot << 24) | 0x101827,
                            Shader.TileMode.CLAMP));
                } else {
                    // Default "liquid_glass"
                    int aTop = Math.min(95, Math.max(14, Math.round(28 * frostScale)));
                    int aBot = Math.min(120, Math.max(18, Math.round(36 * frostScale)));
                    p.setShader(new LinearGradient(
                            0f, rect.top, 0f, rect.bottom,
                            (aTop << 24) | 0xFFFFFF,
                            (aBot << 24) | 0x0B111E,
                            Shader.TileMode.CLAMP));
                }
                canvas.drawRoundRect(rect, radius, radius, p);
                p.setShader(null);
            }
            if (focused) {
                canvas.drawRoundRect(rect, radius, radius, p);
                p.setShader(null);
            }

            // 3. Top crown catchlight & caustic rim
            if (focused) {
                p.setShader(new RadialGradient(
                        rect.left + rect.width() * 0.25f,
                        rect.top,
                        rect.width() * 0.85f,
                        new int[]{(accentColor & 0x00FFFFFF) | 0x42000000, 0x00000000},
                        null,
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, p);
                p.setShader(null);
            }

            // 4. Beveled 5-stop specular border
            p.setStyle(Paint.Style.STROKE);
            float sw = focused ? strokeWidth * 1.4f : strokeWidth * 0.9f;
            p.setStrokeWidth(sw);
            if (focused) {
                int chrom = (accentColor & 0x00FFFFFF) | 0xB8000000;
                p.setShader(new LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{0xFFFFFFFF, 0x44FFFFFF, chrom, 0xD0FFFFFF},
                        new float[]{0f, 0.35f, 0.72f, 1f},
                        Shader.TileMode.CLAMP));
            } else {
                int rimTint = "adaptive".equals(style)
                        ? ((adaptiveCol & 0x00FFFFFF) | 0x58000000)
                        : 0x34BAE6FD;
                p.setShader(new LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{0x78FFFFFF, 0x18FFFFFF, 0x10000000, rimTint, 0x48FFFFFF},
                        new float[]{0f, 0.28f, 0.52f, 0.78f, 1f},
                        Shader.TileMode.CLAMP));
            }
            RectF strokeRect = new RectF(rect);
            float inset = sw * 0.5f;
            strokeRect.inset(inset, inset);
            canvas.drawRoundRect(strokeRect, Math.max(0f, radius - inset), Math.max(0f, radius - inset), p);
            p.setShader(null);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class TileSpec {
        final String iconType;
        final String title;
        final String subtitle;
        final int accentColor;
        final Runnable action;

        TileSpec(String iconType, String title, String subtitle, int accentColor, Runnable action) {
            this.iconType = iconType;
            this.title = title;
            this.subtitle = subtitle;
            this.accentColor = accentColor;
            this.action = action;
        }
    }

    private static String getWifiStatusSummary(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    if (info.getType() == ConnectivityManager.TYPE_ETHERNET) return "Ethernet connected";
                    if (info.getType() == ConnectivityManager.TYPE_WIFI) return "Wi-Fi connected";
                    return "Connected";
                }
            }
        } catch (Exception ignored) {}
        return "Network settings";
    }

    private static void launchSystemSettings(Context context, String... actions) {
        PackageManager pm = context.getPackageManager();
        for (String action : actions) {
            try {
                Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(pm) != null) {
                    context.startActivity(intent);
                    return;
                }
            } catch (Exception ignored) {}
        }
        try {
            context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    private static void launchTclQuickMenuOrDisplay(Context context) {
        try {
            Intent svc = new Intent("com.tcl.settings.SHOW_WINDOW");
            svc.setClassName("com.tcl.settings", "com.tcl.settings.ShowWindowService");
            svc.putExtra("flag", 1);
            svc.putExtra("Type", "Settings");
            svc.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (context.startService(svc) != null) {
                return;
            }
        } catch (Exception ignored) {}
        try {
            Intent act = new Intent("com.tcl.settings.action.QUICKSETTINGS")
                    .setPackage("com.tcl.settings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (act.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(act);
                return;
            }
        } catch (Exception ignored) {}
        launchSystemSettings(context, Settings.ACTION_DISPLAY_SETTINGS, "android.settings.PICTURE_SETTINGS", Settings.ACTION_SETTINGS);
    }

    private static void launchTvInputs(Context context) {
        String[][] candidates = new String[][]{
                {"com.tcl.tv", "com.tcl.sourcemenu.sourcemanager.EnableViewInputLauncher"},
                {"com.tcl.tvinput", "com.tcl.tvinput.TvInputActivity"}
        };
        PackageManager pm = context.getPackageManager();
        for (String[] c : candidates) {
            try {
                Intent i = new Intent().setClassName(c[0], c[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(pm) != null) {
                    context.startActivity(i);
                    return;
                }
            } catch (Exception ignored) {}
        }
        launchLumaAction(context, EXTRA_OPEN_INPUTS);
    }

    private static void launchLumaAction(Context context, String extraKey) {
        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra(extraKey, true);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
