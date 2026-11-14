package com.lumahome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Emulates the Liquid Glass 2.0 optical pipeline (QWEA0/Liquid-Glass-Android):
 * - Rounded-rect SDF + numerical gradient normal
 * - Inverse-power bevel profile (flat clear interior, compressed mirror-like rim)
 * - RGB chromatic dispersion along the SDF normal
 * - Vibrancy saturation curve (boosts muted tones while protecting highlights)
 * - Normal-driven dual-lobe specular rim (30 deg / 210 deg) with inner lit glow
 * - High-performance cached extraction & fast flat-interior bypass for 60fps TV rendering
 */
final class LiquidGlassRenderer {
    private LiquidGlassRenderer() {}

    private static Bitmap sActiveBackdrop;
    private static Bitmap sOverlayBackdrop;
    private static Bitmap sFrostedOverlayBackdrop;
    private static Bitmap sFrostedSourceRef;
    private static int sExtractedColor = 0xFF1E3A5F;
    private static Bitmap sExtractedSourceRef;
    private static Bitmap sCachedBackdrop;
    private static int[] sCachedSrcPixels;
    private static int[] sDstPixels;

    static synchronized void setActiveBackdrop(Bitmap backdrop) {
        sActiveBackdrop = backdrop;
        sCachedBackdrop = null;
    }

    static synchronized Bitmap getActiveBackdrop() {
        return sActiveBackdrop;
    }

    static synchronized void setOverlayBackdrop(Bitmap backdrop) {
        sOverlayBackdrop = backdrop;
        sFrostedSourceRef = null;
        sExtractedSourceRef = null;
        sCachedBackdrop = null;
    }

    static synchronized Bitmap getOverlayBackdrop() {
        return sOverlayBackdrop;
    }

    static synchronized Bitmap getFrostedOverlayBackdrop() {
        Bitmap src = sOverlayBackdrop != null ? sOverlayBackdrop : sActiveBackdrop;
        if (src == null) return null;
        if (sFrostedOverlayBackdrop != null && sFrostedSourceRef == src) {
            return sFrostedOverlayBackdrop;
        }
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 4 || h <= 4) return src;
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int radius = 14;
        int div = radius * 2 + 1;
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                int row = y * w, rSum = 0, gSum = 0, bSum = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = px[row + Math.max(0, Math.min(w - 1, i))];
                    rSum += (c >> 16) & 0xFF;
                    gSum += (c >> 8) & 0xFF;
                    bSum += c & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    tmp[row + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                    int cOut = px[row + Math.max(0, x - radius)];
                    int cIn = px[row + Math.min(w - 1, x + radius + 1)];
                    rSum += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                    gSum += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                    bSum += (cIn & 0xFF) - (cOut & 0xFF);
                }
            }
            for (int x = 0; x < w; x++) {
                int rSum = 0, gSum = 0, bSum = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = tmp[Math.max(0, Math.min(h - 1, i)) * w + x];
                    rSum += (c >> 16) & 0xFF;
                    gSum += (c >> 8) & 0xFF;
                    bSum += c & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    px[y * w + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                    int cOut = tmp[Math.max(0, y - radius) * w + x];
                    int cIn = tmp[Math.min(h - 1, y + radius + 1) * w + x];
                    rSum += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                    gSum += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                    bSum += (cIn & 0xFF) - (cOut & 0xFF);
                }
            }
        }
        if (sFrostedOverlayBackdrop == null
                || sFrostedOverlayBackdrop.getWidth() != w
                || sFrostedOverlayBackdrop.getHeight() != h) {
            sFrostedOverlayBackdrop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        sFrostedOverlayBackdrop.setPixels(px, 0, w, 0, 0, w, h);
        sFrostedSourceRef = src;
        return sFrostedOverlayBackdrop;
    }

    static synchronized int getExtractedOverlayColor() {
        Bitmap src = sOverlayBackdrop != null ? sOverlayBackdrop : sActiveBackdrop;
        if (src == null) return 0xFF1E3A5F;
        if (sExtractedSourceRef == src) return sExtractedColor;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = getBackdropPixels(src);
        if (px == null) return 0xFF1E3A5F;

        double rWeighted = 0, gWeighted = 0, bWeighted = 0, totalW = 0;
        double rAvg = 0, gAvg = 0, bAvg = 0;
        int count = 0;
        int xStart = w / 3;
        for (int y = h / 10; y < h * 9 / 10; y += 6) {
            int row = y * w;
            for (int x = xStart; x < w - 8; x += 6) {
                int c = px[row + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                rAvg += r;
                gAvg += g;
                bAvg += b;
                count++;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int sat = max - min;
                double lum = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0;
                double weight = (0.15 + (sat / 255.0) * 2.2) * (1.0 - Math.abs(lum - 0.52) * 1.1);
                if (weight > 0.05) {
                    rWeighted += r * weight;
                    gWeighted += g * weight;
                    bWeighted += b * weight;
                    totalW += weight;
                }
            }
        }
        int rf, gf, bf;
        if (totalW > 1.0) {
            rf = (int) Math.round(rWeighted / totalW);
            gf = (int) Math.round(gWeighted / totalW);
            bf = (int) Math.round(bWeighted / totalW);
        } else if (count > 0) {
            rf = (int) Math.round(rAvg / count);
            gf = (int) Math.round(gAvg / count);
            bf = (int) Math.round(bAvg / count);
        } else {
            rf = 30; gf = 58; bf = 95;
        }
        // Boost saturation slightly and clamp brightness for a rich, readable glass tint
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(rf, gf, bf, hsv);
        hsv[1] = Math.min(0.85f, Math.max(0.28f, hsv[1] * 1.30f));
        hsv[2] = Math.min(0.62f, Math.max(0.24f, hsv[2]));
        sExtractedColor = android.graphics.Color.HSVToColor(hsv);
        sExtractedSourceRef = src;
        return sExtractedColor;
    }

    static synchronized void invalidateBackdropCache() {
        sCachedBackdrop = null;
    }

    private static synchronized int[] getBackdropPixels(Bitmap backdrop) {
        if (backdrop == null) return null;
        int w = backdrop.getWidth(), h = backdrop.getHeight();
        if (sCachedBackdrop != backdrop || sCachedSrcPixels == null || sCachedSrcPixels.length != w * h) {
            sCachedBackdrop = backdrop;
            sCachedSrcPixels = new int[w * h];
            backdrop.getPixels(sCachedSrcPixels, 0, w, 0, 0, w, h);
        }
        return sCachedSrcPixels;
    }

    private static synchronized int[] getDstBuffer(int size) {
        if (sDstPixels == null || sDstPixels.length < size) {
            sDstPixels = new int[size];
        }
        return sDstPixels;
    }

    static Bitmap renderRefractedBevelRing(
            Bitmap backdrop,
            int screenW,
            int screenH,
            int locX,
            int locY,
            float viewW,
            float viewH,
            float cornerRadiusPx,
            float bevelWidthPx,
            float refractionHeightPx,
            float dispersionStrength,
            float satFactor,
            float specStrength,
            int tintColor,
            float tintAlpha,
            Bitmap reuse) {
        return renderRefractedInternal(
                backdrop, screenW, screenH, locX, locY, viewW, viewH,
                cornerRadiusPx, bevelWidthPx, refractionHeightPx,
                dispersionStrength, satFactor, specStrength,
                tintColor, tintAlpha, true, reuse);
    }

    static Bitmap renderRefractedPatch(
            Bitmap backdrop,
            int screenW,
            int screenH,
            int locX,
            int locY,
            float viewW,
            float viewH,
            float cornerRadiusPx,
            float bevelWidthPx,
            float refractionHeightPx,
            float dispersionStrength,
            float satFactor,
            float specStrength,
            int tintColor,
            float tintAlpha,
            Bitmap reuse) {
        return renderRefractedInternal(
                backdrop, screenW, screenH, locX, locY, viewW, viewH,
                cornerRadiusPx, bevelWidthPx, refractionHeightPx,
                dispersionStrength, satFactor, specStrength,
                tintColor, tintAlpha, false, reuse);
    }

    private static Bitmap renderRefractedInternal(
            Bitmap backdrop,
            int screenW,
            int screenH,
            int locX,
            int locY,
            float viewW,
            float viewH,
            float cornerRadiusPx,
            float bevelWidthPx,
            float refractionHeightPx,
            float dispersionStrength,
            float satFactor,
            float specStrength,
            int tintColor,
            float tintAlpha,
            boolean bevelRingOnly,
            Bitmap reuse) {
        if (backdrop == null || screenW <= 0 || screenH <= 0 || viewW <= 1f || viewH <= 1f) {
            return null;
        }
        int srcW = backdrop.getWidth();
        int srcH = backdrop.getHeight();
        if (srcW <= 1 || srcH <= 1) return null;

        int[] srcPixels = getBackdropPixels(backdrop);
        if (srcPixels == null) return null;

        float scale = Math.min(0.5f, Math.min(960f / viewW, 540f / viewH));
        int bw = Math.max(16, Math.round(viewW * scale));
        int bh = Math.max(16, Math.round(viewH * scale));

        Bitmap out = (reuse != null && reuse.getWidth() == bw && reuse.getHeight() == bh)
                ? reuse
                : Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);

        int[] dstPixels = getDstBuffer(bw * bh);

        float halfW = bw * 0.5f;
        float halfH = bh * 0.5f;
        float shortSide = Math.min(bw, bh);
        float r = Math.min(Math.min(halfW, halfH), Math.max(0f, cornerRadiusPx * scale));

        // Clamped bevel, refraction, and rim glow band
        float bevel = Math.max(2f, Math.min(shortSide * 0.45f, bevelWidthPx * scale));
        float refrPx = Math.min(shortSide * 0.55f, refractionHeightPx * scale);
        float bandW = Math.max(1.5f, Math.min(shortSide * 0.18f, bevel * 0.30f));

        // Inverse-power bevel constant (falloff = 2.0 -> gB = 5^-2 = 0.04)
        float gB = 0.04f;
        float invOneMinusGB = 1f / (1f - gB);

        // 30-degree top-left light source (-0.866, -0.5)
        float negLx = -0.8660254f;
        float negLy = -0.5f;

        float mapScaleX = (float) srcW / screenW;
        float mapScaleY = (float) srcH / screenH;
        float invScale = 1f / scale;

        float tr = ((tintColor >> 16) & 0xFF) / 255f;
        float tg = ((tintColor >> 8) & 0xFF) / 255f;
        float tb = (tintColor & 0xFF) / 255f;

        float[] sampleRgb = new float[3];

        for (int py = 0; py < bh; py++) {
            float cy = (py + 0.5f) - halfH;
            int rowOffset = py * bw;
            for (int px = 0; px < bw; px++) {
                float cx = (px + 0.5f) - halfW;

                float d = sdRoundedBox(cx, cy, halfW, halfH, r);
                float cov = clamp01(0.5f - d / 1.5f);
                if (cov <= 0.004f || (bevelRingOnly && -d >= bevel)) {
                    dstPixels[rowOffset + px] = 0;
                    continue;
                }

                float fr, fg, fb;
                float spec = 0f;

                if (-d >= bevel) {
                    // Fast crystal-clear interior bypass: direct 1:1 backdrop sampling without numerical gradients
                    float fx = (locX + (px + 0.5f) * invScale) * mapScaleX;
                    float fy = (locY + (py + 0.5f) * invScale) * mapScaleY;
                    samplePixelRgb(srcPixels, srcW, srcH, fx, fy, sampleRgb);
                    fr = sampleRgb[0];
                    fg = sampleRgb[1];
                    fb = sampleRgb[2];
                } else {
                    // Curved refractive bevel rim: numerical SDF gradient -> outward normal
                    float gx = sdRoundedBox(cx + 1f, cy, halfW, halfH, r)
                             - sdRoundedBox(cx - 1f, cy, halfW, halfH, r);
                    float gy = sdRoundedBox(cx, cy + 1f, halfW, halfH, r)
                             - sdRoundedBox(cx, cy - 1f, halfW, halfH, r);
                    float nLen = (float) Math.sqrt(gx * gx + gy * gy);
                    float nx = nLen > 1e-4f ? gx / nLen : 0f;
                    float ny = nLen > 1e-4f ? gy / nLen : -1f;

                    // Inverse-power bevel profile
                    float t = clamp01(-d / bevel);
                    if (bevelRingOnly) {
                        float ringFade = 1f - t * t * (3f - 2f * t);
                        cov *= ringFade;
                        if (cov <= 0.004f) {
                            dstPixels[rowOffset + px] = 0;
                            continue;
                        }
                    }
                    float onePlus4t = 1f + 4f * t;
                    float slope = (1f / (onePlus4t * onePlus4t) - gB) * invOneMinusGB;
                    if (slope < 0f) slope = 0f;

                    // Inward refraction (-1) + RGB chromatic dispersion along normal
                    float baseOff = -slope * refrPx;
                    float ox = nx * baseOff;
                    float oy = ny * baseOff;

                    float dispScale = dispersionStrength * slope;
                    float baseX = px + 0.5f;
                    float baseY = py + 0.5f;

                    float rX = (locX + (baseX + ox * (1f - dispScale)) * invScale) * mapScaleX;
                    float rY = (locY + (baseY + oy * (1f - dispScale)) * invScale) * mapScaleY;
                    float gX = (locX + (baseX + ox) * invScale) * mapScaleX;
                    float gY = (locY + (baseY + oy) * invScale) * mapScaleY;
                    float bX = (locX + (baseX + ox * (1f + dispScale)) * invScale) * mapScaleX;
                    float bY = (locY + (baseY + oy * (1f + dispScale)) * invScale) * mapScaleY;

                    fr = sampleChannel(srcPixels, srcW, srcH, rX, rY, 16);
                    fg = sampleChannel(srcPixels, srcW, srcH, gX, gY, 8);
                    fb = sampleChannel(srcPixels, srcW, srcH, bX, bY, 0);

                    // Optical glass meniscus rim (subtle internal reflection so bevel never renders dark ink)
                    float meniscus = slope * (specStrength > 0.001f ? (0.16f * specStrength) : 0.06f);
                    fr = clamp01(fr + meniscus);
                    fg = clamp01(fg + meniscus * 1.05f);
                    fb = clamp01(fb + meniscus * 1.16f);

                    // Normal-driven dual-lobe specular rim
                    if (specStrength > 0.001f) {
                        float facing = nx * negLx + ny * negLy;
                        float lobeF = facing > 0f ? (float) Math.pow(facing, 4.5) : 0f;
                        float lobeB = facing < 0f ? (float) Math.pow(-facing, 4.5) : 0f;

                        float rimLine = clamp01(1f - Math.abs(-d - 0.8f) / 1.25f);
                        float glowIn = clamp01((-d - 0.8f) / 1.6f);
                        float glow = glowIn * (float) Math.pow(clamp01(1f - (-d - 2.0f) / bandW), 1.5);
                        spec = ((lobeF + lobeB) * rimLine + lobeF * glow * 0.42f) * specStrength;
                    }
                }

                // Vibrancy saturation curve
                float lum = 0.2126f * fr + 0.7152f * fg + 0.0722f * fb;
                if (satFactor > 1f) {
                    float satNow = Math.max(fr, Math.max(fg, fb)) - Math.min(fr, Math.min(fg, fb));
                    float room = 1f - smoothstep(0.20f, 0.85f, satNow);
                    float hl = 1f - smoothstep(0.75f, 0.98f, lum);
                    float amount = 1f + (satFactor - 1f) * (0.3f + 0.7f * room * hl);
                    fr = clamp01(lum + (fr - lum) * amount);
                    fg = clamp01(lum + (fg - lum) * amount);
                    fb = clamp01(lum + (fb - lum) * amount);
                }

                // Glass tint
                if (tintAlpha > 0.002f) {
                    fr += (tr - fr) * tintAlpha;
                    fg += (tg - fg) * tintAlpha;
                    fb += (tb - fb) * tintAlpha;
                }

                if (spec > 0.001f) {
                    fr = clamp01(fr + spec);
                    fg = clamp01(fg + spec);
                    fb = clamp01(fb + spec);
                }

                int ia = clampByte(Math.round(cov * 255f));
                int ir = clampByte(Math.round(fr * 255f));
                int ig = clampByte(Math.round(fg * 255f));
                int ib = clampByte(Math.round(fb * 255f));
                dstPixels[rowOffset + px] = (ia << 24) | (ir << 16) | (ig << 8) | ib;
            }
        }

        out.setPixels(dstPixels, 0, bw, 0, 0, bw, bh);
        return out;
    }

    private static float sdRoundedBox(float px, float py, float bx, float by, float r) {
        float qx = Math.abs(px) - bx + r;
        float qy = Math.abs(py) - by + r;
        float ax = qx > 0f ? qx : 0f;
        float ay = qy > 0f ? qy : 0f;
        float out = (float) Math.sqrt(ax * ax + ay * ay);
        float in = Math.min(Math.max(qx, qy), 0f);
        return out + in - r;
    }

    private static void samplePixelRgb(int[] px, int w, int h, float fx, float fy, float[] rgb) {
        float cx = Math.max(0f, Math.min(w - 1.001f, fx));
        float cy = Math.max(0f, Math.min(h - 1.001f, fy));
        int x0 = (int) cx;
        int y0 = (int) cy;
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        float tx = cx - x0;
        float ty = cy - y0;

        int row0 = y0 * w;
        int row1 = y1 * w;
        int c00 = px[row0 + x0];
        int c10 = px[row0 + x1];
        int c01 = px[row1 + x0];
        int c11 = px[row1 + x1];

        float w00 = (1f - tx) * (1f - ty);
        float w10 = tx * (1f - ty);
        float w01 = (1f - tx) * ty;
        float w11 = tx * ty;

        float r = ((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10 + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11;
        float g = ((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10 + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11;
        float b = (c00 & 0xFF) * w00 + (c10 & 0xFF) * w10 + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11;

        rgb[0] = r / 255f;
        rgb[1] = g / 255f;
        rgb[2] = b / 255f;
    }

    private static float sampleChannel(int[] px, int w, int h, float fx, float fy, int shift) {
        float cx = Math.max(0f, Math.min(w - 1.001f, fx));
        float cy = Math.max(0f, Math.min(h - 1.001f, fy));
        int x0 = (int) cx;
        int y0 = (int) cy;
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        float tx = cx - x0;
        float ty = cy - y0;

        float c00 = (px[y0 * w + x0] >> shift) & 0xFF;
        float c10 = (px[y0 * w + x1] >> shift) & 0xFF;
        float c01 = (px[y1 * w + x0] >> shift) & 0xFF;
        float c11 = (px[y1 * w + x1] >> shift) & 0xFF;

        float v = c00 * (1f - tx) * (1f - ty)
                + c10 * tx * (1f - ty)
                + c01 * (1f - tx) * ty
                + c11 * tx * ty;
        return v / 255f;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static void drawPatch(Canvas c, Bitmap patch, RectF dst, Paint p) {
        if (patch == null || dst.width() <= 0 || dst.height() <= 0) return;
        p.setFilterBitmap(true);
        c.drawBitmap(patch, null, dst, p);
    }
}
