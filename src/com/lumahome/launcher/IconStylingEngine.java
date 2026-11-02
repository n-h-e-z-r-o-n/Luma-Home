package com.lumahome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

public final class IconStylingEngine {

    public static final class Config {
        public final String style;       // "original", "minimalist", "monochrome", "paper", "glass", "dark", "flat", "outlined"
        public final String color;       // "white", "black", "accent", "original"
        public final String shape;       // "circle", "squircle", "rounded", "square"
        public final int sizePct;        // 85, 100, 115, 130
        public final String background;  // "glass", "solid_dark", "transparent", "gradient"
        public final String effects;     // "shadow", "border", "depth", "none"
        public final int accentColor;

        public Config(String style, String color, String shape, int sizePct, String background, String effects, int accentColor) {
            this.style = style != null ? style : "original";
            this.color = color != null ? color : "white";
            this.shape = shape != null ? shape : "circle";
            this.sizePct = sizePct > 0 ? sizePct : 100;
            this.background = background != null ? background : "glass";
            this.effects = effects != null ? effects : "shadow";
            this.accentColor = accentColor;
        }

        public String cacheKey(String pkg, int targetSize) {
            return pkg + ":" + targetSize + ":" + style + ":" + color + ":" + shape + ":" + sizePct + ":" + background + ":" + effects + ":" + accentColor;
        }
    }

    private static final LruCache<String, Drawable> sCache = new LruCache<>(150);

    public static void clearCache() {
        sCache.evictAll();
    }

    public static Drawable getStyledIcon(Context context, String pkg, Drawable original, int width, int height, Config config) {
        if (original == null) return null;
        int targetSize = Math.max(16, Math.min(width > 0 ? width : 160, height > 0 ? height : 160));
        String key = config.cacheKey(pkg != null ? pkg : "sample", targetSize);
        Drawable cached = sCache.get(key);
        if (cached != null) return cached;

        try {
            // If Original style and default circle shape & 100% size:
            // Return 100% unaltered, unsegmented authentic original icon (exact original launcher behavior)
            if ("original".equals(config.style) && "circle".equals(config.shape) && config.sizePct == 100) {
                Bitmap fullOriginal = renderFullOriginalBitmap(original);
                if (fullOriginal != null) {
                    Drawable result = new BitmapDrawable(context.getResources(), fullOriginal);
                    sCache.put(key, result);
                    return result;
                }
                return original;
            }

            // If Original style with custom shape (e.g. Squircle) or custom size:
            // Preserve the complete authentic icon artwork intact without any glyph stripping or recoloring
            if ("original".equals(config.style)) {
                Bitmap fullOriginal = renderFullOriginalBitmap(original);
                if (fullOriginal != null) {
                    Bitmap shaped = renderOriginalWithCustomShape(fullOriginal, targetSize, config);
                    if (shaped != null) {
                        Drawable result = new BitmapDrawable(context.getResources(), shaped);
                        sCache.put(key, result);
                        return result;
                    }
                }
                return original;
            }

            // Customized styles (Minimalist, Monochrome, Paper-like, Glass, Dark, Flat, Outlined):
            Bitmap rendered = renderStyledBitmap(original, targetSize, config);
            if (rendered != null) {
                Drawable result = new BitmapDrawable(context.getResources(), rendered);
                sCache.put(key, result);
                return result;
            }
        } catch (Throwable t) {
            // Graceful fallback to original drawable on unexpected memory or render error
        }
        return original;
    }

    public static Bitmap renderFullOriginalBitmap(Drawable original) {
        if (original == null) return null;
        try {
            int w = Math.max(1, original.getIntrinsicWidth());
            int h = Math.max(1, original.getIntrinsicHeight());
            float scale = Math.min(1f, 512f / Math.max(w, h));
            w = Math.max(1, Math.round(w * scale));
            h = Math.max(1, Math.round(h * scale));
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Rect bounds = new Rect(original.getBounds());
            try {
                original.setBounds(0, 0, w, h);
                original.draw(new Canvas(bitmap));
            } finally {
                original.setBounds(bounds);
            }
            // Trim transparent borders only - NEVER strip backgrounds or segment glyphs
            return trimTransparent(bitmap);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap renderOriginalWithCustomShape(Bitmap fullOriginal, int targetSize, Config config) {
        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        RectF bounds = new RectF(0, 0, targetSize, targetSize);
        float radius = getShapeCornerRadius(config.shape, targetSize);

        Path shapePath = new Path();
        if (radius >= targetSize / 2f - 0.5f) {
            shapePath.addOval(bounds, Path.Direction.CW);
        } else {
            shapePath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        }

        canvas.save();
        canvas.clipPath(shapePath);

        float scaleFactor = (config.sizePct / 100f);
        int gw = Math.round(targetSize * scaleFactor);
        int gh = Math.round(targetSize * scaleFactor);

        int left = (targetSize - gw) / 2;
        int top = (targetSize - gh) / 2;
        Rect dst = new Rect(left, top, left + gw, top + gh);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(fullOriginal, null, dst, paint);
        canvas.restore();

        return output;
    }

    private static Bitmap renderStyledBitmap(Drawable original, int targetSize, Config config) {
        // Step 1: Extract base glyph / subject from original drawable
        Bitmap rawGlyph = extractGlyphBitmap(original, targetSize);
        if (rawGlyph == null) return null;

        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        RectF bounds = new RectF(0, 0, targetSize, targetSize);
        float radius = getShapeCornerRadius(config.shape, targetSize);

        // Clip canvas to the user-selected icon shape
        Path shapePath = new Path();
        if (radius >= targetSize / 2f - 0.5f) {
            shapePath.addOval(bounds, Path.Direction.CW);
        } else {
            shapePath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        }

        // Step 2: Render background plate if not transparent
        renderBackground(canvas, shapePath, bounds, radius, config);

        // Step 3: Transform glyph according to chosen style and color
        Bitmap styledGlyph = transformGlyphArtwork(rawGlyph, config);

        // Step 4: Position, scale, and render styled glyph
        if (styledGlyph != null) {
            canvas.save();
            canvas.clipPath(shapePath);

            float baseScale = "original".equals(config.style) ? 1.0f : 0.60f;
            float scaleFactor = baseScale * (config.sizePct / 100f);
            int gw = Math.round(targetSize * scaleFactor);
            int gh = Math.round(targetSize * scaleFactor);

            // Maintain glyph aspect ratio
            float aspect = (float) styledGlyph.getWidth() / Math.max(1, styledGlyph.getHeight());
            if (aspect > 1f) {
                gh = Math.round(gw / aspect);
            } else if (aspect < 1f) {
                gw = Math.round(gh * aspect);
            }

            int left = (targetSize - gw) / 2;
            int top = (targetSize - gh) / 2;
            Rect dst = new Rect(left, top, left + gw, top + gh);

            // Optional drop shadow effect under the glyph
            if (!"none".equals(config.effects) && !"flat".equals(config.style)) {
                Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                shadowPaint.setColorFilter(new PorterDuffColorFilter(0x40000000, PorterDuff.Mode.SRC_IN));
                Rect shadowDst = new Rect(left, top + Math.max(1, targetSize / 50), left + gw, top + gh + Math.max(1, targetSize / 50));
                canvas.drawBitmap(styledGlyph, null, shadowDst, shadowPaint);
            }

            Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(styledGlyph, null, dst, glyphPaint);
            canvas.restore();
        }

        // Step 5: Render subtle specular rim / border if requested or for glass style
        if ("border".equals(config.effects) || "glass".equals(config.style) || "depth".equals(config.effects)) {
            renderBorderRim(canvas, shapePath, bounds, radius, config);
        }

        return output;
    }

    private static void renderBackground(Canvas canvas, Path shapePath, RectF bounds, float radius, Config config) {
        if ("transparent".equals(config.background)) {
            return;
        }

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.save();
        canvas.clipPath(shapePath);

        if ("solid_dark".equals(config.background)) {
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(0xFF151922);
            canvas.drawRect(bounds, bgPaint);
        } else if ("gradient".equals(config.background)) {
            int topCol = 0xFF1C2230;
            int botCol = (config.accentColor & 0x00FFFFFF) | 0x4D000000;
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, topCol, botCol, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, bgPaint);
        } else {
            // Frosted glass (default)
            bgPaint.setStyle(Paint.Style.FILL);
            int baseTint = (config.accentColor & 0x00FFFFFF) | 0x1E000000;
            bgPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, 0x24FFFFFF, baseTint, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, bgPaint);

            // Specular upper sheen (top 45%)
            RectF sheen = new RectF(bounds.left, bounds.top, bounds.right, bounds.top + bounds.height() * 0.45f);
            bgPaint.setShader(new LinearGradient(0, sheen.top, 0, sheen.bottom, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(sheen, bgPaint);
        }

        canvas.restore();
    }

    private static void renderBorderRim(Canvas canvas, Path shapePath, RectF bounds, float radius, Config config) {
        Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1.2f, bounds.width() / 100f));
        float inset = rimPaint.getStrokeWidth() / 2f;
        RectF strokeBounds = new RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);

        int topRim = "glass".equals(config.style) ? 0x55FFFFFF : 0x3AFFFFFF;
        int botRim = 0x12FFFFFF;
        rimPaint.setShader(new LinearGradient(0, strokeBounds.top, 0, strokeBounds.bottom, topRim, botRim, Shader.TileMode.CLAMP));

        if (radius >= bounds.width() / 2f - 0.5f) {
            canvas.drawOval(strokeBounds, rimPaint);
        } else {
            float innerRad = Math.max(0, radius - inset);
            canvas.drawRoundRect(strokeBounds, innerRad, innerRad, rimPaint);
        }
    }

    public static float getShapeCornerRadius(String shape, float size) {
        if ("squircle".equals(shape)) return size * 0.38f;
        if ("rounded".equals(shape)) return size * 0.22f;
        if ("square".equals(shape)) return size * 0.08f;
        return size / 2f; // circle
    }

    private static Bitmap extractGlyphBitmap(Drawable original, int targetSize) {
        try {
            int w = Math.max(1, original.getIntrinsicWidth());
            int h = Math.max(1, original.getIntrinsicHeight());
            float scale = Math.min(1f, 512f / Math.max(w, h));
            int rw = Math.max(targetSize, Math.round(w * scale));
            int rh = Math.max(targetSize, Math.round(h * scale));

            Drawable source = original;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && original instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) original;
                Drawable fg = adaptive.getForeground();
                if (fg != null) source = fg;
            }

            Bitmap bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Rect bounds = new Rect(source.getBounds());
            source.setBounds(0, 0, rw, rh);
            source.draw(canvas);
            source.setBounds(bounds);

            // Trim transparent outer padding
            Bitmap trimmed = trimTransparent(bitmap);

            // If the icon has an outer opaque container box/disc with an inner glyph, segment it
            return isolateGlyphFromContainer(trimmed);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap trimTransparent(Bitmap bitmap) {
        if (bitmap == null) return null;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int left = w, top = h, right = -1, bottom = -1;
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((pixels[y * w + x] >>> 24) > 20) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right >= left && bottom >= top) {
            return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1);
        }
        return bitmap;
    }

    private static Bitmap isolateGlyphFromContainer(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w < 16 || h < 16) return src;

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // Sample perimeter pixels to see if the icon has a solid container plate
        int opaquePerimeter = 0;
        int totalPerimeter = 0;
        long rSum = 0, gSum = 0, bSum = 0;

        for (int x = 0; x < w; x++) {
            int c1 = pixels[x];
            int c2 = pixels[(h - 1) * w + x];
            if ((c1 >>> 24) > 40) { opaquePerimeter++; rSum += (c1 >> 16) & 0xFF; gSum += (c1 >> 8) & 0xFF; bSum += c1 & 0xFF; }
            if ((c2 >>> 24) > 40) { opaquePerimeter++; rSum += (c2 >> 16) & 0xFF; gSum += (c2 >> 8) & 0xFF; bSum += c2 & 0xFF; }
            totalPerimeter += 2;
        }
        for (int y = 1; y < h - 1; y++) {
            int c1 = pixels[y * w];
            int c2 = pixels[y * w + (w - 1)];
            if ((c1 >>> 24) > 40) { opaquePerimeter++; rSum += (c1 >> 16) & 0xFF; gSum += (c1 >> 8) & 0xFF; bSum += c1 & 0xFF; }
            if ((c2 >>> 24) > 40) { opaquePerimeter++; rSum += (c2 >> 16) & 0xFF; gSum += (c2 >> 8) & 0xFF; bSum += c2 & 0xFF; }
            totalPerimeter += 2;
        }

        // If perimeter is mostly opaque, we likely have an outer container plate (e.g. Downloader orange circle)
        if (opaquePerimeter > totalPerimeter * 0.70f) {
            int avgR = (int) (rSum / Math.max(1, opaquePerimeter));
            int avgG = (int) (gSum / Math.max(1, opaquePerimeter));
            int avgB = (int) (bSum / Math.max(1, opaquePerimeter));

            // Count inner pixels with significant color or luminance delta
            int distinctPixels = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = pixels[y * w + x];
                    if ((c >>> 24) > 40) {
                        int dr = ((c >> 16) & 0xFF) - avgR;
                        int dg = ((c >> 8) & 0xFF) - avgG;
                        int db = (c & 0xFF) - avgB;
                        double dist = Math.sqrt(2 * dr * dr + 4 * dg * dg + 3 * db * db);
                        if (dist > 45) distinctPixels++;
                    }
                }
            }

            // If there is a distinct inner glyph (between 3% and 85% of area)
            if (distinctPixels > (w * h * 0.03f) && distinctPixels < (w * h * 0.85f)) {
                int[] isolated = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * w + x;
                        int c = pixels[idx];
                        int a = (c >>> 24);
                        if (a > 40) {
                            int dr = ((c >> 16) & 0xFF) - avgR;
                            int dg = ((c >> 8) & 0xFF) - avgG;
                            int db = (c & 0xFF) - avgB;
                            double dist = Math.sqrt(2 * dr * dr + 4 * dg * dg + 3 * db * db);
                            if (dist > 45) {
                                float alphaWeight = Math.min(1f, (float)((dist - 40) / 20f));
                                isolated[idx] = ((int)(a * alphaWeight) << 24) | (c & 0x00FFFFFF);
                            } else {
                                isolated[idx] = 0;
                            }
                        } else {
                            isolated[idx] = 0;
                        }
                    }
                }
                Bitmap isoBmp = Bitmap.createBitmap(isolated, w, h, Bitmap.Config.ARGB_8888);
                return trimTransparent(isoBmp);
            }
        }
        return src;
    }

    private static Bitmap transformGlyphArtwork(Bitmap src, Config config) {
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        int targetColor;
        if ("black".equals(config.color)) {
            targetColor = 0xFF141720;
        } else if ("accent".equals(config.color)) {
            targetColor = config.accentColor;
        } else {
            targetColor = 0xFFFFFFFF; // Pure White (default)
        }

        int tr = (targetColor >> 16) & 0xFF;
        int tg = (targetColor >> 8) & 0xFF;
        int tb = targetColor & 0xFF;

        int[] result = new int[w * h];

        // Analyze luminance spread
        int minLum = 255, maxLum = 0;
        for (int c : pixels) {
            if ((c >>> 24) > 20) {
                int lum = Math.round(0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF));
                minLum = Math.min(minLum, lum);
                maxLum = Math.max(maxLum, lum);
            }
        }
        int lumRange = Math.max(1, maxLum - minLum);

        String style = config.style;

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = (c >>> 24);
            if (a < 20) {
                result[i] = 0;
                continue;
            }

            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int lum = Math.round(0.299f * r + 0.587f * g + 0.114f * b);

            if ("monochrome".equals(style)) {
                // High-contrast perceptual monochrome
                float cFactor = 1.35f;
                int boosted = Math.max(0, Math.min(255, Math.round((lum - 128f) * cFactor + 128f)));
                if ("accent".equals(config.color)) {
                    int outR = Math.round(boosted * tr / 255f);
                    int outG = Math.round(boosted * tg / 255f);
                    int outB = Math.round(boosted * tb / 255f);
                    result[i] = (a << 24) | (outR << 16) | (outG << 8) | outB;
                } else if ("black".equals(config.color)) {
                    int dark = Math.round(boosted * 0.35f + 25f);
                    result[i] = (a << 24) | (dark << 16) | (dark << 8) | dark;
                } else {
                    result[i] = (a << 24) | (boosted << 16) | (boosted << 8) | boosted;
                }
            } else if ("paper".equals(style)) {
                // Tactile papercraft: chalky matte paper tone
                float norm = (float)(lum - minLum) / lumRange;
                int paperR = Math.round(245f + 8f * norm);
                int paperG = Math.round(243f + 8f * norm);
                int paperB = Math.round(239f + 8f * norm);
                int outA = Math.round(a * (0.80f + 0.20f * norm));
                result[i] = (outA << 24) | (paperR << 16) | (paperG << 8) | paperB;
            } else if ("glass".equals(style)) {
                // Frosted glass glyph
                float norm = (float)(lum - minLum) / lumRange;
                int outA = Math.round(a * (0.45f + 0.55f * norm));
                result[i] = (outA << 24) | (tr << 16) | (tg << 8) | tb;
            } else if ("dark".equals(style)) {
                // Stealth dark: luminous off-white/silver glyph
                float norm = (float)(lum - minLum) / lumRange;
                int silverR = Math.round(200f + 45f * norm);
                int silverG = Math.round(208f + 42f * norm);
                int silverB = Math.round(222f + 33f * norm);
                result[i] = (a << 24) | (silverR << 16) | (silverG << 8) | silverB;
            } else if ("flat".equals(style)) {
                // Bold solid flat vector
                float norm = (float)(lum - minLum) / lumRange;
                int outA = norm >= 0.35f ? a : Math.round(a * 0.40f);
                result[i] = (outA << 24) | (tr << 16) | (tg << 8) | tb;
            } else if ("outlined".equals(style)) {
                // Wireframe outline with translucent interior
                result[i] = (Math.round(a * 0.65f) << 24) | (tr << 16) | (tg << 8) | tb;
            } else {
                // "minimalist" (Default styled):
                // If icon has high internal contrast (e.g. YouTube play triangle on tube, or Play Store facets):
                // Preserve internal depth by mapping luminance to opacity levels
                if (lumRange > 30) {
                    float norm = (float)(lum - minLum) / lumRange;
                    int outA = Math.round(a * (0.35f + 0.65f * norm));
                    result[i] = (outA << 24) | (tr << 16) | (tg << 8) | tb;
                } else {
                    result[i] = (a << 24) | (tr << 16) | (tg << 8) | tb;
                }
            }
        }

        Bitmap transformed = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888);

        // For outlined style, apply outline edge filter
        if ("outlined".equals(style)) {
            return applyOutlineFilter(transformed, targetColor);
        }
        // For paper style, apply directional embossing
        if ("paper".equals(style)) {
            return applyPaperEmboss(transformed);
        }

        return transformed;
    }

    private static Bitmap applyOutlineFilter(Bitmap src, int strokeColor) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        // Faint 15% fill
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        fillPaint.setAlpha(38);
        canvas.drawBitmap(src, 0, 0, fillPaint);

        // Edge detection stroke
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] edgePixels = new int[w * h];

        int radius = Math.max(1, w / 80);
        for (int y = radius; y < h - radius; y++) {
            for (int x = radius; x < w - radius; x++) {
                int aCenter = (pixels[y * w + x] >>> 24);
                if (aCenter > 30) {
                    boolean isEdge = false;
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dx = -radius; dx <= radius; dx++) {
                            if ((pixels[(y + dy) * w + (x + dx)] >>> 24) < 20) {
                                isEdge = true;
                                break;
                            }
                        }
                        if (isEdge) break;
                    }
                    if (isEdge) {
                        edgePixels[y * w + x] = strokeColor;
                    }
                }
            }
        }
        Bitmap edgeBmp = Bitmap.createBitmap(edgePixels, w, h, Bitmap.Config.ARGB_8888);
        canvas.drawBitmap(edgeBmp, 0, 0, null);
        return out;
    }

    private static Bitmap applyPaperEmboss(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        // Ambient contact shadow (offset +1.5px, +1.5px)
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        shadow.setColorFilter(new PorterDuffColorFilter(0x40101520, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 1.5f, 1.5f, shadow);

        // Bevel highlight (offset -1.2px, -1.2px)
        Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        highlight.setColorFilter(new PorterDuffColorFilter(0x50FFFFFF, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, -1.2f, -1.2f, highlight);

        // Main paper glyph
        canvas.drawBitmap(src, 0, 0, null);
        return out;
    }
}
