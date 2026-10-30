package com.lumahome.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zero-asset synthesized acoustic Micro-Sound Engine ("Haptics-for-Ears").
 * Synthesizes physical-modeling 44.1kHz 16-bit PCM micro-samples into SoundPool's
 * native FastMixer for sub-12ms polyphonic playback, spatial TV stereo panning,
 * and subtle melodic glide contours.
 */
final class UiSoundEngine {
    static final String[] PACK_IDS = {"glass", "wood", "pulse", "dew"};
    private static final int SR = 44100;

    private static final int ROLE_FOCUS = 0;
    private static final int ROLE_SELECT = 1;
    private static final int ROLE_BACK = 2;
    private static final int ROLE_BLADE = 3;
    private static final int ROLE_DETENT = 4;
    private static final int ROLE_COUNT = 5;

    private final Context context;
    private final LauncherStore store;
    private final SoundPool pool;
    private final Map<String, int[]> soundIds = new HashMap<>();
    private final ExecutorService initWorker = Executors.newSingleThreadExecutor();
    private final int[] tmpLoc = new int[2];

    private volatile boolean ready;
    private boolean closed;
    private long lastFocusPlayMs;
    private long lastDetentPlayMs;
    private int glideStep;

    UiSoundEngine(Context context, LauncherStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        this.pool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(attrs)
                .build();
        initWorker.execute(this::prepareAllPacks);
    }

    boolean enabled() {
        return store.flag("ui_sounds_enabled", true);
    }

    String pack() {
        String p = store.value("ui_sounds_pack", "glass");
        for (String id : PACK_IDS) if (id.equals(p)) return p;
        return "glass";
    }

    String packName() {
        switch (pack()) {
            case "wood": return "Warm wood";
            case "pulse": return "Velvet pulse";
            case "dew": return "Dewdrop";
            default: return "Liquid glass";
        }
    }

    int volumePct() {
        return Math.max(10, Math.min(100, store.number("ui_sounds_volume", 65)));
    }

    String summary() {
        if (!enabled()) return "Off";
        return packName() + " · " + volumePct() + "%";
    }

    void playFocus(View target, int screenWidth, int direction) {
        if (!enabled() || !ready) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastFocusPlayMs < 34L) return;

        float rate = 1.0f;
        if (store.flag("ui_sounds_pitch", true)) {
            if (now - lastFocusPlayMs < 360L) {
                glideStep = Math.max(-4, Math.min(4, glideStep + (direction >= 0 ? 1 : -1)));
            } else {
                glideStep = 0;
            }
            rate = 1.0f + glideStep * 0.016f;
        } else {
            glideStep = 0;
        }
        lastFocusPlayMs = now;
        playInternal(pack(), ROLE_FOCUS, computePan(target, screenWidth), rate, 0.36f, -1);
    }

    void playSelect(View target, int screenWidth) {
        if (!enabled() || !ready) return;
        glideStep = 0;
        playInternal(pack(), ROLE_SELECT, computePan(target, screenWidth), 1.0f, 0.44f, -1);
    }

    void playSelectAtPan(float pan01) {
        if (!enabled() || !ready) return;
        glideStep = 0;
        playInternal(pack(), ROLE_SELECT, pan01, 1.0f, 0.44f, -1);
    }

    void playBack() {
        if (!enabled() || !ready) return;
        glideStep = 0;
        playInternal(pack(), ROLE_BACK, 0.5f, 1.0f, 0.38f, -1);
    }

    void playBladeOpen() {
        if (!enabled() || !ready) return;
        glideStep = 0;
        playInternal(pack(), ROLE_BLADE, 0.72f, 1.0f, 0.40f, -1);
    }

    void playDetent(float pan01, int stepPct) {
        if (!enabled() || !ready || !store.flag("ui_sounds_detents", true)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastDetentPlayMs < 26L) return;
        lastDetentPlayMs = now;
        float rate = 1.0f;
        if (stepPct >= 0 && store.flag("ui_sounds_pitch", true)) {
            rate = 0.94f + Math.max(0f, Math.min(1f, stepPct / 100f)) * 0.14f;
        }
        playInternal(pack(), ROLE_DETENT, pan01, rate, 0.30f, -1);
    }

    /** Plays a sound unconditionally for Settings preview buttons/sliders even if toggled off. */
    void preview(String packId, int role, int overrideVolPct) {
        if (!ready) return;
        float baseGain = role == ROLE_SELECT ? 0.44f : role == ROLE_BLADE ? 0.40f
                : role == ROLE_BACK ? 0.38f : role == ROLE_DETENT ? 0.30f : 0.36f;
        playInternal(packId, role, 0.65f, 1.0f, baseGain, overrideVolPct);
    }

    static int roleFocus() { return ROLE_FOCUS; }
    static int roleSelect() { return ROLE_SELECT; }
    static int roleBack() { return ROLE_BACK; }
    static int roleBlade() { return ROLE_BLADE; }
    static int roleDetent() { return ROLE_DETENT; }

    void close() {
        closed = true;
        ready = false;
        initWorker.shutdownNow();
        try { pool.release(); } catch (Exception ignored) {}
    }

    private float computePan(View target, int screenWidth) {
        if (target == null || screenWidth <= 0) return 0.5f;
        try {
            target.getLocationOnScreen(tmpLoc);
            float cx = tmpLoc[0] + target.getWidth() * 0.5f;
            return Math.max(0f, Math.min(1f, cx / screenWidth));
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void playInternal(String packId, int role, float pan01, float rate, float maxGain, int overrideVol) {
        int[] ids = soundIds.get(packId);
        if (ids == null || role < 0 || role >= ids.length || ids[role] == 0) return;

        int vPct = overrideVol >= 0 ? overrideVol : volumePct();
        float norm = Math.max(0.05f, Math.min(1f, vPct / 100f));
        float gain = (float) Math.pow(norm, 1.55) * maxGain;

        float left = gain;
        float right = gain;
        if (store.flag("ui_sounds_spatial", true)) {
            // Gentle ±28% living-room stereo image so ticks follow focus without hard-panning
            float centered = (Math.max(0f, Math.min(1f, pan01)) - 0.5f) * 2f; // -1..+1
            left = gain * (1f - 0.28f * centered);
            right = gain * (1f + 0.28f * centered);
        }
        float clampedRate = Math.max(0.8f, Math.min(1.25f, rate));
        try {
            pool.play(ids[role], Math.min(1f, left), Math.min(1f, right), 1, 0, clampedRate);
        } catch (Exception ignored) {}
    }

    private void prepareAllPacks() {
        try {
            File dir = new File(context.getCacheDir(), "luma_sfx_v1");
            if (!dir.exists()) dir.mkdirs();
            for (String p : PACK_IDS) {
                if (closed) return;
                int[] ids = new int[ROLE_COUNT];
                for (int r = 0; r < ROLE_COUNT; r++) {
                    File wav = new File(dir, p + "_" + r + ".wav");
                    if (!wav.exists() || wav.length() < 64) {
                        short[] pcm = synthesize(p, r);
                        writeWav(wav, pcm);
                    }
                    ids[r] = pool.load(wav.getAbsolutePath(), 1);
                }
                soundIds.put(p, ids);
            }
            ready = !closed;
        } catch (Exception ignored) {}
    }

    private static short[] synthesize(String pack, int role) {
        int durMs;
        switch (role) {
            case ROLE_SELECT: durMs = 125; break;
            case ROLE_BACK:   durMs = 92;  break;
            case ROLE_BLADE:  durMs = 108; break;
            case ROLE_DETENT: durMs = 26;  break;
            default:          durMs = 46;  break;
        }
        int n = (SR * durMs) / 1000;
        float[] buf = new float[n];

        if ("wood".equals(pack)) {
            synthWood(buf, role);
        } else if ("pulse".equals(pack)) {
            synthPulse(buf, role);
        } else if ("dew".equals(pack)) {
            synthDew(buf, role);
        } else {
            synthGlass(buf, role);
        }

        // Normalize peak to 0.80 and apply click-free micro-ramps at boundaries
        float peak = 0.001f;
        for (float v : buf) peak = Math.max(peak, Math.abs(v));
        float scale = 0.80f / peak;

        int fadeIn = Math.min(n / 4, (int) (SR * 0.0018f));
        int fadeOut = Math.min(n / 3, (int) (SR * 0.0045f));
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            float env = 1f;
            if (i < fadeIn) {
                env = 0.5f * (1f - (float) Math.cos(Math.PI * i / fadeIn));
            } else if (i >= n - fadeOut) {
                int rem = n - 1 - i;
                env = 0.5f * (1f - (float) Math.cos(Math.PI * rem / fadeOut));
            }
            int s = Math.round(buf[i] * scale * env * 32767f);
            pcm[i] = (short) Math.max(-32767, Math.min(32767, s));
        }
        return pcm;
    }

    private static void synthGlass(float[] b, int role) {
        if (role == ROLE_FOCUS) {
            addMode(b, 0, 540f, 540f, 0.0018f, 0.0065f, 0.42f);
            addMode(b, 0, 1490f, 1490f, 0.0015f, 0.0095f, 0.52f);
            addMode(b, 0, 3420f, 3420f, 0.0010f, 0.0038f, 0.12f);
        } else if (role == ROLE_SELECT) {
            addMode(b, 0, 370f, 370f, 0.003f, 0.032f, 0.32f);
            addMode(b, 0, 740f, 740f, 0.002f, 0.028f, 0.48f);
            int delay = (int) (SR * 0.024f);
            addMode(b, delay, 1110f, 1110f, 0.0035f, 0.038f, 0.52f);
            addMode(b, delay, 2220f, 2220f, 0.0025f, 0.018f, 0.14f);
        } else if (role == ROLE_BACK) {
            addMode(b, 0, 780f, 740f, 0.002f, 0.018f, 0.44f);
            int delay = (int) (SR * 0.020f);
            addMode(b, delay, 520f, 495f, 0.003f, 0.028f, 0.52f);
        } else if (role == ROLE_BLADE) {
            addMode(b, 0, 440f, 660f, 0.006f, 0.038f, 0.46f);
            addMode(b, (int) (SR * 0.012f), 1320f, 1360f, 0.004f, 0.024f, 0.24f);
        } else {
            addMode(b, 0, 640f, 640f, 0.001f, 0.0035f, 0.38f);
            addMode(b, 0, 1880f, 1880f, 0.001f, 0.0038f, 0.52f);
        }
    }

    private static void synthWood(float[] b, int role) {
        if (role == ROLE_FOCUS) {
            addMode(b, 0, 170f, 145f, 0.0015f, 0.0048f, 0.34f);
            addMode(b, 0, 385f, 382f, 0.0018f, 0.0115f, 0.68f);
            addMode(b, 0, 1060f, 1055f, 0.0012f, 0.0042f, 0.22f);
        } else if (role == ROLE_SELECT) {
            addMode(b, 0, 330f, 330f, 0.002f, 0.032f, 0.55f);
            addMode(b, 0, 909f, 909f, 0.0015f, 0.010f, 0.18f);
            int delay = (int) (SR * 0.026f);
            addMode(b, delay, 495f, 495f, 0.0025f, 0.038f, 0.58f);
            addMode(b, delay, 1364f, 1364f, 0.0015f, 0.011f, 0.16f);
        } else if (role == ROLE_BACK) {
            addMode(b, 0, 392f, 385f, 0.002f, 0.020f, 0.48f);
            int delay = (int) (SR * 0.022f);
            addMode(b, delay, 262f, 255f, 0.0025f, 0.028f, 0.58f);
        } else if (role == ROLE_BLADE) {
            addMode(b, 0, 349f, 349f, 0.0025f, 0.026f, 0.46f);
            addMode(b, (int) (SR * 0.020f), 440f, 440f, 0.0025f, 0.034f, 0.52f);
        } else {
            addMode(b, 0, 680f, 660f, 0.001f, 0.0042f, 0.64f);
            addMode(b, 0, 1860f, 1840f, 0.001f, 0.0020f, 0.18f);
        }
    }

    private static void synthPulse(float[] b, int role) {
        if (role == ROLE_FOCUS) {
            addMode(b, 0, 285f, 108f, 0.0018f, 0.0135f, 0.76f);
            addMode(b, 0, 760f, 520f, 0.0012f, 0.0034f, 0.18f);
        } else if (role == ROLE_SELECT) {
            addMode(b, 0, 146f, 146f, 0.005f, 0.045f, 0.56f);
            addMode(b, 0, 293f, 293f, 0.004f, 0.038f, 0.42f);
            addMode(b, (int) (SR * 0.018f), 440f, 440f, 0.005f, 0.036f, 0.34f);
        } else if (role == ROLE_BACK) {
            addMode(b, 0, 215f, 96f, 0.0025f, 0.030f, 0.72f);
        } else if (role == ROLE_BLADE) {
            addMode(b, 0, 145f, 255f, 0.006f, 0.038f, 0.58f);
            addMode(b, (int) (SR * 0.014f), 510f, 520f, 0.005f, 0.026f, 0.24f);
        } else {
            addMode(b, 0, 320f, 140f, 0.001f, 0.0046f, 0.72f);
        }
    }

    private static void synthDew(float[] b, int role) {
        if (role == ROLE_FOCUS) {
            addMode(b, 0, 560f, 890f, 0.0022f, 0.0115f, 0.68f);
        } else if (role == ROLE_SELECT) {
            addMode(b, 0, 540f, 790f, 0.0025f, 0.022f, 0.48f);
            addMode(b, (int) (SR * 0.028f), 810f, 1170f, 0.0025f, 0.030f, 0.56f);
        } else if (role == ROLE_BACK) {
            addMode(b, 0, 740f, 465f, 0.0025f, 0.025f, 0.62f);
        } else if (role == ROLE_BLADE) {
            addMode(b, 0, 490f, 740f, 0.004f, 0.032f, 0.52f);
            addMode(b, (int) (SR * 0.016f), 980f, 1080f, 0.004f, 0.024f, 0.22f);
        } else {
            addMode(b, 0, 790f, 1080f, 0.0012f, 0.0042f, 0.62f);
        }
    }

    private static void addMode(float[] out, int startSample, float fStart, float fEnd,
                                float attackSec, float tauSec, float amp) {
        double phase = 0.0;
        int len = out.length - startSample;
        if (len <= 0) return;
        for (int i = 0; i < len; i++) {
            float t = (float) i / SR;
            // Smooth exponential frequency glide over ~16ms
            float glide = 1f - (float) Math.exp(-t / 0.014f);
            float freq = fStart + (fEnd - fStart) * glide;
            phase += (2.0 * Math.PI * freq) / SR;
            float att = attackSec <= 0f ? 1f : Math.min(1f, t / attackSec);
            float dec = (float) Math.exp(-t / tauSec);
            out[startSample + i] += (float) Math.sin(phase) * att * dec * amp;
        }
    }

    private static void writeWav(File file, short[] pcm) throws IOException {
        int dataBytes = pcm.length * 2;
        int totalDataLen = dataBytes + 36;
        byte[] out = new byte[44 + dataBytes];

        // RIFF/WAVE header (44.1 kHz, mono, 16-bit PCM)
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        putIntLE(out, 4, totalDataLen);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        putIntLE(out, 16, 16);
        putShortLE(out, 20, (short) 1);   // PCM
        putShortLE(out, 22, (short) 1);   // Mono
        putIntLE(out, 24, SR);
        putIntLE(out, 28, SR * 2);        // Byte rate
        putShortLE(out, 32, (short) 2);   // Block align
        putShortLE(out, 34, (short) 16);  // Bits per sample
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        putIntLE(out, 40, dataBytes);

        int idx = 44;
        for (short s : pcm) {
            out[idx++] = (byte) (s & 0xFF);
            out[idx++] = (byte) ((s >>> 8) & 0xFF);
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(out);
            fos.flush();
        }
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    private static void putShortLE(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }
}
