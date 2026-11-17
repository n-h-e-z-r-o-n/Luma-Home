package com.lumahome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * System DreamService allowing Luma Home to serve as the system-wide Android TV screensaver.
 * Replaces the stock Google TV backdrop across the OS when the device goes idle.
 */
public class LumaScreensaverDreamService extends DreamService {
    private static final String TAG = "LumaDreamService";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LauncherStore store;
    private Typeface mediumFont = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Runnable autoTickRunnable;
    private android.animation.ValueAnimator driftXAnim, driftYAnim;
    private final ImageView[] photoLayers = new ImageView[2];

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        store = new LauncherStore(this);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                mediumFont = getResources().getFont(getResources().getIdentifier("outfit_medium", "font", getPackageName()));
            } catch (Exception ignored) {}
        }
        buildAndAttachScreensaver();
    }

    @Override
    public void onDreamingStopped() {
        if (autoTickRunnable != null) handler.removeCallbacks(autoTickRunnable);
        if (driftXAnim != null) { driftXAnim.cancel(); driftXAnim = null; }
        if (driftYAnim != null) { driftYAnim.cancel(); driftYAnim = null; }
        for (ImageView iv : photoLayers) {
            if (iv != null) iv.animate().cancel();
        }
        super.onDreamingStopped();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String greetingText() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return "Quiet night";
        if (hour < 12) return "Good morning";
        if (hour < 17) return "Good afternoon";
        if (hour < 22) return "Good evening";
        return "Good night";
    }

    private void buildAndAttachScreensaver() {
        final boolean minimal = "minimal".equals(store.value("screensaver_style", "cinema"));
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final int screenW = dm.widthPixels;
        final int screenH = dm.heightPixels;

        if (minimal) {
            FrameLayout stage = new FrameLayout(this);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF04060C, 0xFF080E1C, 0xFF030408});
            stage.setBackground(bg);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);

            TextView greet = new TextView(this);
            greet.setText(greetingText());
            greet.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18));
            greet.setTextColor(0xFF4FC3F7);
            greet.setTypeface(mediumFont);
            greet.setLetterSpacing(0.18f);
            box.addView(greet, new LinearLayout.LayoutParams(-2, -2));

            TextClock time = new TextClock(this);
            time.setFormat12Hour("h:mm");
            time.setFormat24Hour("H:mm");
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(110));
            time.setTextColor(0xEEFFFFFF);
            time.setTypeface(mediumFont);
            time.setIncludeFontPadding(false);
            box.addView(time, new LinearLayout.LayoutParams(-2, -2));

            TextClock date = new TextClock(this);
            date.setFormat12Hour("EEEE, d MMMM");
            date.setFormat24Hour("EEEE, d MMMM");
            date.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18));
            date.setTextColor(0x99FFFFFF);
            date.setTypeface(mediumFont);
            box.addView(date, new LinearLayout.LayoutParams(-2, -2));

            TextView hint = new TextView(this);
            hint.setText("Press any button to wake");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(14));
            hint.setTextColor(0x55FFFFFF);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
            hp.topMargin = dp(24);
            box.addView(hint, hp);

            stage.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            final float rangeX = dp(45), rangeY = dp(28);
            driftXAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            driftXAnim.setDuration(29000L);
            driftXAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            driftXAnim.setRepeatMode(android.animation.ValueAnimator.RESTART);
            driftXAnim.setInterpolator(new android.view.animation.LinearInterpolator());
            driftXAnim.addUpdateListener(a -> {
                float f = a.getAnimatedFraction();
                box.setTranslationX((float) (Math.sin(f * (2.0 * Math.PI)) * rangeX));
            });

            driftYAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            driftYAnim.setDuration(37000L);
            driftYAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            driftYAnim.setRepeatMode(android.animation.ValueAnimator.RESTART);
            driftYAnim.setInterpolator(new android.view.animation.LinearInterpolator());
            driftYAnim.addUpdateListener(a -> {
                float f = a.getAnimatedFraction();
                box.setTranslationY((float) (Math.sin(f * (2.0 * Math.PI)) * rangeY));
            });

            box.postDelayed(() -> {
                if (driftXAnim != null) driftXAnim.start();
                if (driftYAnim != null) driftYAnim.start();
            }, 600);

            setContentView(stage);
            return;
        }

        // Cinema Photo Frame Screensaver
        List<File> rawFiles = PhoneDropServer.listSavedFiles(this, store);
        final List<String> photos = new ArrayList<>();
        for (File f : rawFiles) {
            if (f != null && f.exists()) {
                photos.add(f.getAbsolutePath());
            }
        }
        if (store.flag("screensaver_shuffle", true)) {
            Collections.shuffle(photos);
        }

        final String motionMode = store.value("screensaver_motion", "cinematic");
        final String clockPos = store.value("screensaver_clock_pos", "bottom_left");
        final long slideMs = store.number("screensaver_slide_sec", 60) * 1000L;
        final long crossfadeMs = 1400L;
        final int dimPct = store.number("screensaver_dimming", 15);
        final int dimAlpha = Math.round(dimPct * 2.55f);

        final FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF060911);
        stage.setClipChildren(true);

        final FrameLayout photoDeck = new FrameLayout(this);
        photoDeck.setClipChildren(true);
        stage.addView(photoDeck, new FrameLayout.LayoutParams(-1, -1));

        final LinearInterpolator linearInterp = new LinearInterpolator();
        final DecelerateInterpolator crispDecel = new DecelerateInterpolator(1.4f);
        final int[] curPhotoIdx = {-1};
        final int[] moveCounter = {0};
        final int[] activeLayerIdx = {1};
        final FrameLayout[] wraps = new FrameLayout[2];
        final int[] ssAccent = {0xFF4FC3F7};

        for (int i = 0; i < 2; i++) {
            final int slot = i;
            final FrameLayout wrap = new FrameLayout(this);
            wrap.setClipChildren(true);
            wrap.setAlpha(0f);
            wraps[i] = wrap;

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photoLayers[i] = iv;
            wrap.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            photoDeck.addView(wrap, new FrameLayout.LayoutParams(-1, -1));
        }

        // Vignette
        View vignette = new View(this);
        GradientDrawable vig = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x28000000, 0x00000000, 0x58000000});
        vignette.setBackground(vig);
        stage.addView(vignette, new FrameLayout.LayoutParams(-1, -1));

        if (dimAlpha > 0) {
            View dimVeil = new View(this);
            dimVeil.setBackgroundColor(Color.argb(dimAlpha, 0, 0, 0));
            stage.addView(dimVeil, new FrameLayout.LayoutParams(-1, -1));
        }

        // Floating Liquid Glass Clock Plaque
        final boolean centerClock = "center".equals(clockPos);
        final LinearLayout glassCard = new LinearLayout(this);
        glassCard.setOrientation(LinearLayout.VERTICAL);
        glassCard.setGravity(centerClock ? Gravity.CENTER : Gravity.START);
        int padH = dp(centerClock ? 36 : 28);
        int padV = dp(centerClock ? 24 : 18);
        glassCard.setPadding(padH, padV, padH, padV);

        final String rawClockStyle = store != null ? store.value("screensaver_clock_surface_style", "glass") : "glass";
        final String surfaceStyle = "system".equals(rawClockStyle)
                ? (store != null ? store.value("ui_surface_style", "glass") : "glass")
                : rawClockStyle;
        final boolean isGlass = "glass".equals(surfaceStyle);
        final boolean isSolid = "solid".equals(surfaceStyle);
        final int frost = store != null ? Math.max(0, Math.min(100, store.number("glass_frost", 0))) : 0;
        final int sheenMode = store != null ? Math.max(0, Math.min(3, store.number("glass_sheen", 0))) : 0;
        final float rawSh = sheenMode == 0 ? 0.0f : sheenMode == 1 ? 0.55f : sheenMode == 3 ? 1.48f : 1.0f;
        final float clockSh = isGlass ? Math.max(1.15f, rawSh) : rawSh;
        final boolean contrast = store != null && store.flag("contrast", false);

        // Glass background drawable adhering to screensaver_clock_surface_style (liquid glass, frosted, solid dark)
        glassCard.setBackground(new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();

            @Override
            public void draw(Canvas c) {
                r.set(getBounds());
                if (r.width() <= 0 || r.height() <= 0) return;
                float rad = dp(24);

                // Base surface tint (neutral obsidian base matching dockBar)
                int baseRgb = 0x0E1725;
                int topAlpha, botAlpha;
                if (isSolid) {
                    topAlpha = 250; botAlpha = 255;
                } else if (isGlass) {
                    // Crystal-clear low opacity optical glass
                    topAlpha = 28; botAlpha = 48;
                } else {
                    // Balanced dark obsidian base underneath the frosted diffusion veil
                    topAlpha = 75; botAlpha = 115;
                }
                p.setStyle(Paint.Style.FILL);
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                        (topAlpha << 24) | baseRgb, (botAlpha << 24) | baseRgb, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, p);
                p.setShader(null);

                // Frosted Glass Luminous Diffusion Veil (milky translucent light scattering)
                if (!isGlass && !isSolid) {
                    p.setStyle(Paint.Style.FILL);
                    p.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                            0x48FFFFFF, 0x22CBD5E1, Shader.TileMode.CLAMP));
                    c.drawRoundRect(r, rad, rad, p);
                    p.setShader(null);
                }

                // Specular top catchlight (Liquid Glass only)
                if (isGlass) {
                    c.save();
                    c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.45f);
                    int topGleam = Color.argb(Math.min(140, Math.round(52 * clockSh)), 255, 255, 255);
                    p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.45f,
                            topGleam, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawRoundRect(r, rad, rad, p);
                    c.restore();
                    p.setShader(null);
                }

                // Edge stroke
                if (contrast) {
                    float sw = Math.max(1f, dp(2.0f));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    p.setColor(0xFF4FC3F7);
                    float inset = sw / 2f;
                    r.inset(inset, inset);
                    c.drawRoundRect(r, Math.max(0f, rad - inset), Math.max(0f, rad - inset), p);
                    p.setShader(null);
                } else if (isGlass) {
                    float sw = Math.max(1f, dp(1.45f));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    int rimTop = Color.argb(Math.min(240, Math.round(180 * clockSh)), 255, 255, 255);
                    int rimBot = Color.argb(Math.min(200, Math.round(120 * clockSh)), 255, 255, 255);
                    p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                            new int[]{rimTop, 0x22FFFFFF, 0x10000000, 0x55BEE6FD, rimBot},
                            new float[]{0f, 0.25f, 0.52f, 0.78f, 1f}, Shader.TileMode.CLAMP));
                    float inset = sw / 2f;
                    r.inset(inset, inset);
                    c.drawRoundRect(r, Math.max(0f, rad - inset), Math.max(0f, rad - inset), p);
                    p.setShader(null);
                } else if (isSolid) {
                    float sw = Math.max(1f, dp(1.2f));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    p.setColor(0x28FFFFFF);
                    float inset = sw / 2f;
                    r.inset(inset, inset);
                    c.drawRoundRect(r, Math.max(0f, rad - inset), Math.max(0f, rad - inset), p);
                } else {
                    // Soft matte etched border
                    float sw = Math.max(1f, dp(1.4f));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    p.setColor(0x60FFFFFF);
                    float inset = sw / 2f;
                    r.inset(inset, inset);
                    c.drawRoundRect(r, Math.max(0f, rad - inset), Math.max(0f, rad - inset), p);
                }
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });

        TextView eyebrow = new TextView(this);
        eyebrow.setText(greetingText());
        eyebrow.setTypeface(mediumFont);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(14));
        eyebrow.setTextColor(ssAccent[0]);
        eyebrow.setLetterSpacing(0.16f);
        eyebrow.setShadowLayer(dp(3), 0, dp(1), 0x90000000);
        glassCard.addView(eyebrow, new LinearLayout.LayoutParams(-2, -2));

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(centerClock ? 80 : 56));
        time.setTextColor(0xF8FFFFFF);
        time.setTypeface(mediumFont);
        time.setIncludeFontPadding(false);
        time.setShadowLayer(dp(5), 0, dp(2), 0xB0000000);
        glassCard.addView(time, new LinearLayout.LayoutParams(-2, -2));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(centerClock ? 18 : 15));
        date.setTextColor(0xCCFFFFFF);
        date.setTypeface(mediumFont);
        date.setShadowLayer(dp(3), 0, dp(1), 0x90000000);
        glassCard.addView(date, new LinearLayout.LayoutParams(-2, -2));

        if (!"hidden".equals(clockPos)) {
            int grav = centerClock ? Gravity.CENTER
                    : ("bottom_right".equals(clockPos) ? (Gravity.BOTTOM | Gravity.END) : (Gravity.BOTTOM | Gravity.START));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, grav);
            if (!centerClock) {
                cp.leftMargin = dp(48);
                cp.rightMargin = dp(48);
                cp.bottomMargin = dp(40);
            }
            stage.addView(glassCard, cp);
        }

        // Slide loading and Ken Burns logic
        final Runnable advanceSlide = new Runnable() {
            @Override
            public void run() {
                if (photos.isEmpty()) {
                    // Draw aurora gradient if no photos
                    View aurora = new View(LumaScreensaverDreamService.this);
                    aurora.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                            new int[]{0xFF0A1424, 0xFF142B47, 0xFF0D1E33}));
                    photoDeck.addView(aurora, new FrameLayout.LayoutParams(-1, -1));
                    return;
                }
                curPhotoIdx[0] = (curPhotoIdx[0] + 1) % photos.size();
                activeLayerIdx[0] = 1 - activeLayerIdx[0];
                final int slot = activeLayerIdx[0];
                final FrameLayout wrap = wraps[slot];
                final FrameLayout otherWrap = wraps[1 - slot];
                final ImageView iv = photoLayers[slot];

                String photoPath = photos.get(curPhotoIdx[0]);
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(photoPath, opts);
                    opts.inSampleSize = Math.max(1, Math.min(opts.outWidth / 1920, opts.outHeight / 1080));
                    opts.inJustDecodeBounds = false;
                    Bitmap bmp = BitmapFactory.decodeFile(photoPath, opts);
                    if (bmp != null) {
                        iv.setImageBitmap(bmp);
                    }
                } catch (Exception | OutOfMemoryError e) {
                    android.util.Log.w(TAG, "Failed to decode screensaver photo: " + e.getMessage());
                }

                wrap.animate().cancel();
                wrap.bringToFront();
                wrap.setAlpha(0f);

                // Start Ken Burns on iv
                if (!"off".equals(motionMode)) {
                    float amp = "subtle".equals(motionMode) ? 0.5f : 1.0f;
                    float sMin = 1f + 0.05f * amp;
                    float sMax = 1f + 0.14f * amp;
                    float dx = dp(20f * amp);
                    float dy = dp(12f * amp);
                    int mv = (moveCounter[0]++) % 4;
                    float s0, s1, tx0, tx1, ty0, ty1;
                    switch (mv) {
                        case 0: s0 = sMin; s1 = sMax; tx0 = -dx; tx1 = dx; ty0 = dy; ty1 = -dy; break;
                        case 1: s0 = sMax; s1 = sMin; tx0 = dx; tx1 = -dx; ty0 = -dy; ty1 = dy; break;
                        case 2: s0 = (sMin + sMax) * 0.5f; s1 = sMax; tx0 = -dx * 1.1f; tx1 = dx * 1.1f; ty0 = -dy * 0.4f; ty1 = dy * 0.4f; break;
                        default: s0 = sMin; s1 = sMax * 0.98f; tx0 = dx * 0.7f; tx1 = -dx * 0.7f; ty0 = dy; ty1 = -dy; break;
                    }
                    iv.setScaleX(s0); iv.setScaleY(s0);
                    iv.setTranslationX(tx0); iv.setTranslationY(ty0);
                    long moveTotal = slideMs + crossfadeMs + 400L;
                    iv.animate().scaleX(s1).scaleY(s1).translationX(tx1).translationY(ty1)
                            .setInterpolator(linearInterp).setDuration(moveTotal).start();
                }

                wrap.animate().alpha(1f).setInterpolator(crispDecel)
                        .setDuration(moveCounter[0] <= 1 ? 550L : crossfadeMs)
                        .withEndAction(() -> {
                            if (otherWrap != null) otherWrap.setAlpha(0f);
                        }).start();

                if (photos.size() > 1) {
                    handler.postDelayed(this, slideMs);
                }
            }
        };
        autoTickRunnable = advanceSlide;
        advanceSlide.run();

        // Subtle continuous clock micro-drift (hardware-accelerated continuous Lissajous glide)
        final float rangeX = dp(centerClock ? 24 : 8);
        final float rangeY = dp(centerClock ? 15 : 6);
        driftXAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        driftXAnim.setDuration(29000L);
        driftXAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        driftXAnim.setRepeatMode(android.animation.ValueAnimator.RESTART);
        driftXAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        driftXAnim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            glassCard.setTranslationX((float) (Math.sin(f * (2.0 * Math.PI)) * rangeX));
        });

        driftYAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        driftYAnim.setDuration(37000L);
        driftYAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        driftYAnim.setRepeatMode(android.animation.ValueAnimator.RESTART);
        driftYAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        driftYAnim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            glassCard.setTranslationY((float) (Math.sin(f * (2.0 * Math.PI)) * rangeY));
        });

        glassCard.postDelayed(() -> {
            if (driftXAnim != null) driftXAnim.start();
            if (driftYAnim != null) driftYAnim.start();
        }, 600);

        setContentView(stage);
    }
}
