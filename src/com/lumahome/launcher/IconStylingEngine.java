package com.lumahome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.CornerPathEffect;
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
import android.util.Log;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Cached material rendering for app icons. Flattened tile artwork fills the chosen mask without stretching;
 * only explicit Android foreground layers are eligible for glyph recoloring.
 * Material backgrounds, optional effects and optical sizing share one output shape.
 */
public final class IconStylingEngine {

    private static final String TAG = "LumaIconEngine";
    private static final int SOLID_ALPHA = 160;

    public static final class Config {
        public final String style;       // "original", "minimalist", "monochrome", "paper", "glass", "dark", "flat", "outlined"
        public final String color;       // "white", "black", "accent", "original"
        public final String shape;       // "circle", "squircle", "rounded", "square"
        public final int sizePct;        // 85, 100, 115, 130
        public final String background;  // "glass", "solid_dark", "transparent", "gradient"
        public final String effects;     // "shadow", "border", "depth", "none"
        public final int accentColor;

        public Config(String style, String color, String shape, int sizePct, String background, String effects, int accentColor) {
            this.style = style != null ? style : "brand";
            this.color = color != null ? color : "original";
            this.shape = shape != null ? shape : "circle";
            this.sizePct = sizePct > 0 ? sizePct : 100;
            this.background = background != null ? background : "auto";
            this.effects = effects != null ? effects : "shadow";
            this.accentColor = accentColor;
        }

        public String cacheKey(String pkg, int targetSize) {
            return pkg + ":" + targetSize + ":" + style + ":" + color + ":" + shape + ":" + sizePct + ":" + background + ":" + effects + ":" + accentColor;
        }
    }

    /** Result of glyph extraction for one app icon. Style-independent, so it is cached separately. */
    private static final class Glyph {
        Bitmap bitmap;
        boolean ok;
        String method;
        String reason = "";
        float fill;          // ink density inside the glyph's own bounding box
        float inkFrac;       // ink relative to the analysed source area
        float seamDensity;   // share of glyph pixels that sit on a hard colour boundary
        float structure;     // share of glyph pixels that differ clearly from the dominant tone
        float holes;         // enclosed transparent area (counters, cut-outs) relative to bbox
        int tones;           // quantised colours needed to cover 85% of the glyph
        int parts;           // significant connected components
        int domLum = 255;    // dominant luminance of the glyph
        int lumSpread;       // luminance spread around the dominant tone

        Glyph(String method) { this.method = method; }

        Glyph fail(String why) { ok = false; reason = why; return this; }

        String describe() {
            return String.format(Locale.US, "%s %s fill=%.2f ink=%.3f seams=%.3f struct=%.2f holes=%.2f tones=%d parts=%d",
                    method, ok ? "OK" : "FALLBACK(" + reason + ")", fill, inkFrac, seamDensity, structure, holes, tones, parts);
        }
    }

    private static final LruCache<String, Drawable> sCache = new LruCache<String, Drawable>(8 * 1024) {
        @Override protected int sizeOf(String key, Drawable value) {
            return value instanceof BitmapDrawable ? Math.max(1, ((BitmapDrawable)value).getBitmap().getByteCount()/1024) : 1;
        }
    };

    // Extracted glyphs are style-independent: switching styles re-uses them instead of re-analysing icons.
    private static final LruCache<String, Glyph> sGlyphCache = new LruCache<String, Glyph>(6 * 1024) {
        @Override
        protected int sizeOf(String key, Glyph g) {
            return g.bitmap != null ? Math.max(1, g.bitmap.getByteCount() / 1024) : 1;
        }
    };

    public static void clearCache() {
        sCache.evictAll();
        sGlyphCache.evictAll();
    }

    public static Drawable getStyledIcon(Context context, String pkg, Drawable original, int width, int height, Config config) {
        if (original == null) return null;
        int targetSize = Math.max(16, Math.min(width > 0 ? width : 160, height > 0 ? height : 160));
        long revision=0;
        if(pkg!=null)try { revision=context.getPackageManager().getPackageInfo(pkg,0).lastUpdateTime; }catch(Exception ignored){}
        String key = config.cacheKey(pkg != null ? pkg : "sample", targetSize)+":"+revision;
        if(original instanceof BitmapDrawable)key+=":"+((BitmapDrawable)original).getBitmap().getGenerationId();
        Drawable cached = sCache.get(key);
        if (cached != null) return cached.getConstantState().newDrawable(context.getResources()).mutate();

        try {
            // Brand and Original never infer or redraw a flattened app logo.
            Glyph glyph=("brand".equals(config.style)||"original".equals(config.style))?null:obtainGlyph((pkg==null?"sample":pkg)+":"+revision,original,targetSize);
            Bitmap rendered=(glyph!=null&&glyph.ok)?renderStyledBitmap(glyph,targetSize,config):renderOriginalFallback(original,targetSize,config);
            if (rendered != null) {
                Drawable result = new BitmapDrawable(context.getResources(), rendered);
                sCache.put(key, result);
                return result;
            }
        } catch (Throwable t) {
            // Graceful fallback to original drawable on unexpected memory or render error
            Log.w(TAG, "styling failed for " + pkg, t);
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

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    private static Path shapePath(RectF bounds, float radius, int targetSize) {
        Path path = new Path();
        if (radius >= targetSize / 2f - 0.5f) {
            path.addOval(bounds, Path.Direction.CW);
        } else {
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        }
        return path;
    }

    /** Aspect-preserving artwork clipped to the selected shape (optionally colour-filtered). */
    private static Bitmap renderShapedArtwork(Bitmap fullOriginal, int targetSize, Config config, ColorFilter filter) {
        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        RectF bounds = new RectF(0, 0, targetSize, targetSize);
        float radius = getShapeCornerRadius(config.shape, targetSize);
        Path path = shapePath(bounds, radius, targetSize);

        renderBackground(canvas,path,bounds,radius,config);
        canvas.save();canvas.clipPath(path);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setColorFilter(filter);
        float scale=Math.max(.65f,Math.min(1.35f,config.sizePct/100f));
        // Full tile artwork fills the mask for every package, regardless of its colors.
        // Transparent standalone symbols retain optical sizing instead of being cropped.
        boolean tileArtwork = hasArtworkBacking(fullOriginal);
        float available=targetSize*Math.min(.94f,.76f*scale);
        float factor=tileArtwork
                ? Math.max(targetSize/(float)fullOriginal.getWidth(),targetSize/(float)fullOriginal.getHeight())*Math.max(1f,scale)
                : Math.min(available/fullOriginal.getWidth(),available/fullOriginal.getHeight());
        float w=fullOriginal.getWidth()*factor,h=fullOriginal.getHeight()*factor;
        if(!tileArtwork && "circle".equals(config.shape)){
            float diagonal=(float)Math.hypot(w,h),limit=targetSize*.93f;
            if(diagonal>limit){w*=limit/diagonal;h*=limit/diagonal;}
        }
        RectF destination=new RectF((targetSize-w)/2f,(targetSize-h)/2f,(targetSize+w)/2f,(targetSize+h)/2f);
        if("shadow".equals(config.effects)||"depth".equals(config.effects)){
            Paint shadow=new Paint(paint);shadow.setColorFilter(new PorterDuffColorFilter(0x55000000,PorterDuff.Mode.SRC_IN));
            RectF offset=new RectF(destination);offset.offset(0,targetSize*.018f);canvas.drawBitmap(fullOriginal,null,offset,shadow);
        }
        canvas.drawBitmap(fullOriginal,null,destination,paint);canvas.restore();
        if("paper".equals(config.style)||"depth".equals(config.effects))renderMaterialEdge(canvas,bounds,radius,targetSize);
        return output;
    }

    /** Detect a complete artwork tile by alpha coverage, never by package or backing color. */
    private static boolean hasArtworkBacking(Bitmap artwork) {
        int opaque=0,total=0;
        for(int y=0;y<32;y++) for(int x=0;x<32;x++) {
            int c=artwork.getPixel(Math.min(artwork.getWidth()-1,(int)((x+.5f)*artwork.getWidth()/32)),
                    Math.min(artwork.getHeight()-1,(int)((y+.5f)*artwork.getHeight()/32)));
            if(Color.alpha(c)>=240)opaque++;
            total++;
        }
        return opaque >= total*.85f;
    }

    /**
     * Used when no trustworthy glyph exists: keep the real logo intact rather than produce a broken
     * silhouette. Monochrome can safely desaturate (that never alters shapes); other styles keep colour.
     */
    private static Bitmap renderOriginalFallback(Drawable original, int targetSize, Config config) {
        Bitmap full = renderFullOriginalBitmap(original);
        if (full == null) return null;
        ColorFilter filter = null;
        if ("monochrome".equals(config.style)) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0f);
            filter = new ColorMatrixColorFilter(cm);
        }
        Bitmap out = renderShapedArtwork(full, targetSize, config, filter);
        if ("border".equals(config.effects) || "glass".equals(config.style) || "outlined".equals(config.style) || "depth".equals(config.effects)) {
            RectF bounds = new RectF(0, 0, targetSize, targetSize);
            float radius = getShapeCornerRadius(config.shape, targetSize);
            renderBorderRim(new Canvas(out), shapePath(bounds, radius, targetSize), bounds, radius, config);
        }
        return out;
    }

    private static Bitmap renderStyledBitmap(Glyph glyph, int targetSize, Config config) {
        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        RectF bounds = new RectF(0, 0, targetSize, targetSize);
        float radius = getShapeCornerRadius(config.shape, targetSize);

        // Clip canvas to the user-selected icon shape
        Path shapePath = shapePath(bounds, radius, targetSize);

        // Step 1: Render background plate if not transparent
        renderBackground(canvas, shapePath, bounds, radius, config);

        // Step 2: Transform glyph according to chosen style and color
        Bitmap styledGlyph = transformGlyphArtwork(glyph, config);

        // Step 3: Position and scale glyph by visual weight (ink area), not by bounding box
        if (styledGlyph != null) {
            canvas.save();
            canvas.clipPath(shapePath);

            RectF box = glyphBox(glyph, styledGlyph.getWidth(), styledGlyph.getHeight(), targetSize, config.shape, config.sizePct);
            Rect dst = new Rect(Math.round(box.left), Math.round(box.top), Math.round(box.right), Math.round(box.bottom));

            // Optional drop shadow effect under the glyph
            if (("shadow".equals(config.effects)||"depth".equals(config.effects)) && !"flat".equals(config.style)) {
                Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                shadowPaint.setColorFilter(new PorterDuffColorFilter(0x40000000, PorterDuff.Mode.SRC_IN));
                int off = Math.max(1, targetSize / 50);
                Rect shadowDst = new Rect(dst.left, dst.top + off, dst.right, dst.bottom + off);
                canvas.drawBitmap(styledGlyph, null, shadowDst, shadowPaint);
            }

            Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(styledGlyph, null, dst, glyphPaint);
            canvas.restore();
        }

        if("depth".equals(config.effects))renderMaterialEdge(canvas,bounds,radius,targetSize);
        // Step 4: Render subtle specular rim / border if requested or for glass style
        if ("border".equals(config.effects) || "glass".equals(config.style) || "outlined".equals(config.style) || "depth".equals(config.effects)) {
            renderBorderRim(canvas, shapePath, bounds, radius, config);
        }

        return output;
    }

    /**
     * Sizes a glyph so that every icon carries similar visual weight: dense/solid glyphs are drawn a
     * little smaller, thin/airy glyphs a little larger, and nothing touches the curved edge of the shape.
     */
    private static RectF glyphBox(Glyph glyph, int gw, int gh, int size, String shape, int sizePct) {
        float aspect = gw / (float) Math.max(1, gh);
        float zoom=Math.max(.65f,Math.min(1.35f,sizePct/100f));
        float base = size * 0.62f;
        float w, h;
        if (aspect >= 1f) { w = base; h = base / aspect; } else { h = base; w = base * aspect; }

        float area = Math.max(0.02f, glyph.fill) * w * h / (size * (float) size);
        float k = (float) Math.sqrt(0.20f / Math.max(0.01f, area));
        k = Math.max(0.86f, Math.min(1.14f, k));
        w *= k*zoom;
        h *= k*zoom;

        float maxDim = size * 0.90f;
        float m = Math.max(w, h);
        if (m > maxDim) { w *= maxDim / m; h *= maxDim / m; }

        float diagCap = "circle".equals(shape) ? 0.90f : "squircle".equals(shape) ? 0.96f : 1.30f;
        float diag = (float) Math.hypot(w, h);
        if (diag > diagCap * size) { float f = diagCap * size / diag; w *= f; h *= f; }

        float left = (size - w) / 2f, top = (size - h) / 2f;
        return new RectF(left, top, left + w, top + h);
    }

    private static void renderBackground(Canvas canvas,Path shapePath,RectF bounds,float radius,Config config){
        if("transparent".equals(config.background)||("original".equals(config.style)&&"auto".equals(config.background)))return;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);canvas.save();canvas.clipPath(shapePath);
        String style=config.style;int top=0xFF202A32,bottom=0xFF131B22;
        if("paper".equals(style)){top=0xFF514A41;bottom=0xFF39342F;}
        else if("dark".equals(style)){top=0xFF14181F;bottom=0xFF06090E;}
        else if("flat".equals(style)){top=bottom=0xFF000000|(config.accentColor&0xFFFFFF);}
        else if("outlined".equals(style)){top=bottom=0x12000000;}
        else if("minimalist".equals(style)){top=bottom=0xFF202428;}
        else if("monochrome".equals(style)){top=0xFF363636;bottom=0xFF181818;}
        else if("glass".equals(style)){top=0x65FFFFFF;bottom=0x35000000|(config.accentColor&0xFFFFFF);}
        if("black".equals(config.color)&&"auto".equals(config.background)){top=0xFFE4E7EB;bottom=0xFFC9CFD6;}
        if("solid_dark".equals(config.background)){top=bottom=0xFF141920;}
        else if("gradient".equals(config.background)){top=0xFF202A38;bottom=0xB0000000|(config.accentColor&0xFFFFFF);}
        else if("glass".equals(config.background)){top=0x44FFFFFF;bottom=0x26000000|(config.accentColor&0xFFFFFF);}
        p.setShader(new LinearGradient(0,bounds.top,0,bounds.bottom,top,bottom,Shader.TileMode.CLAMP));canvas.drawRect(bounds,p);p.setShader(null);
        if("paper".equals(style)){
            p.setColor(0x0CFFFFFF);p.setStrokeWidth(Math.max(1,bounds.width()/220f));
            for(float y=3;y<bounds.height();y+=Math.max(3,bounds.height()/72f))canvas.drawLine(0,y,bounds.width(),y,p);
        }
        if("glass".equals(style)){
            p.setShader(new LinearGradient(0,0,bounds.width(),bounds.height(),new int[]{0x55FFFFFF,0x08FFFFFF,0x00FFFFFF},new float[]{0,.45f,1},Shader.TileMode.CLAMP));canvas.drawRect(bounds,p);
        }
        canvas.restore();
    }
    private static void renderMaterialEdge(Canvas canvas,RectF bounds,float radius,int size){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,size*.012f));
        p.setShader(new LinearGradient(0,0,0,size,0x60FFFFFF,0x70000000,Shader.TileMode.CLAMP));
        RectF inner=new RectF(bounds);inner.inset(p.getStrokeWidth(),p.getStrokeWidth());
        canvas.drawRoundRect(inner,Math.max(0,radius-p.getStrokeWidth()),Math.max(0,radius-p.getStrokeWidth()),p);
    }

    private static void renderBorderRim(Canvas canvas, Path shapePath, RectF bounds, float radius, Config config) {
        Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1.2f, bounds.width() / 100f));
        float inset = rimPaint.getStrokeWidth() / 2f;
        RectF strokeBounds = new RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);

        int topRim = "outlined".equals(config.style)?0xDDFFFFFF:"glass".equals(config.style) ? 0x88FFFFFF : 0x3AFFFFFF;
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

    // ---------------------------------------------------------------------------------------------
    // Glyph extraction
    // ---------------------------------------------------------------------------------------------

    private static Glyph obtainGlyph(String pkg, Drawable original, int targetSize) {
        int analysis = Math.max(128, Math.min(256, targetSize));
        String name = pkg != null ? pkg : "sample";
        String key = name + "@" + analysis + ":" + original.getIntrinsicWidth() + "x" + original.getIntrinsicHeight();
        Glyph cached = sGlyphCache.get(key);
        if (cached != null) return cached;

        Glyph glyph=new Glyph("complete-artwork").fail("flattened artwork preserved");
        if(Build.VERSION.SDK_INT>=26&&original instanceof AdaptiveIconDrawable){
            Drawable foreground=((AdaptiveIconDrawable)original).getForeground();
            Bitmap raw=foreground==null?null:rasterize(foreground,analysis);
            if(raw!=null){
                Bitmap trimmed=trimTransparent(raw);
                if(trimmed!=null){
                    Glyph separated=curatedGlyph(trimmed,"android foreground");
                    if(separated!=null){
                        long weight=0,luminance=0;
                        java.util.HashSet<Integer> tones=new java.util.HashSet<>();
                        for(int y=0;y<trimmed.getHeight();y++)for(int x=0;x<trimmed.getWidth();x++){
                            int pixel=trimmed.getPixel(x,y),alpha=pixel>>>24;
                            weight+=alpha;luminance+=(long)alpha*lum((pixel>>16)&255,(pixel>>8)&255,pixel&255);
                            if(alpha>160)tones.add(((pixel>>20)&15)<<8|((pixel>>12)&15)<<4|((pixel>>4)&15));
                        }
                        separated.domLum=weight==0?128:(int)(luminance/weight);separated.lumSpread=64;
                        separated.method="android-foreground";
                        if(tones.size()<=24)glyph=separated;else glyph.fail("illustrated foreground preserved");
                    }
                }
            }
        }
        if (!glyph.ok) glyph.bitmap = null; // failed glyphs are never drawn; don't keep their pixels
        Log.d(TAG, name + " -> " + glyph.describe());
        sGlyphCache.put(key, glyph);
        return glyph;
    }

    private static Glyph extractGlyph(Drawable original, int size) {
        Glyph fgResult = null;
        // Adaptive icons: the foreground layer *is* the logo; the background layer is the plate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && original instanceof AdaptiveIconDrawable) {
            Drawable fg = ((AdaptiveIconDrawable) original).getForeground();
            if (fg != null) {
                Bitmap fgBmp = rasterize(fg, size);
                if (fgBmp != null) {
                    fgResult = analyze(fgBmp, "adaptive-fg", true, 0);
                    if (fgResult.ok) return fgResult;
                }
            }
        }
        Bitmap full = rasterize(original, size);
        if (full == null) {
            return fgResult != null ? fgResult : new Glyph("full-icon").fail("could not rasterize");
        }
        return analyze(full, "full-icon", false, 0);
    }

    private static Bitmap rasterize(Drawable d, int size) {
        try {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            int w = size, h = size;
            if (iw > 0 && ih > 0) {
                if (iw >= ih) h = Math.max(1, Math.round(size * ih / (float) iw));
                else w = Math.max(1, Math.round(size * iw / (float) ih));
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Rect old = new Rect(d.getBounds());
            try {
                d.setBounds(0, 0, w, h);
                d.draw(new Canvas(bmp));
            } finally {
                d.setBounds(old);
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Separates a glyph from its plate.
     *
     * @param preferWhole treat the whole image as the glyph first (adaptive foregrounds)
     * @param depth       0 for the first pass; 1 when peeling an inner plate
     */
    private static Glyph analyze(Bitmap src, String method, boolean preferWhole, int depth) {
        Bitmap trimmed = trimTransparent(src);
        int w = trimmed.getWidth(), h = trimmed.getHeight();
        if (w < 12 || h < 12) return new Glyph(method).fail("too small");
        int n = w * h;
        int[] px = new int[n];
        trimmed.getPixels(px, 0, w, 0, 0, w, h);

        Glyph whole = null;
        if (preferWhole) {
            whole = finalizeGlyph(px.clone(), w, h, null, method + "/whole", n);
            if (whole.ok) return whole;
        }

        // A plate is a solid shape (disc, squircle, square) filling most of its bounding box.
        boolean[] solid = new boolean[n];
        int solidCount = 0;
        for (int i = 0; i < n; i++) {
            if ((px[i] >>> 24) >= SOLID_ALPHA) { solid[i] = true; solidCount++; }
        }
        boolean[] band = null;
        float[] model = null;
        if (solidCount >= n * 0.62f) {
            // Sample the plate along a ring just inside its real edge (works for circles, unlike the bitmap border).
            int k = Math.max(2, Math.round(Math.min(w, h) * 0.045f));
            boolean[] core = erode(solid, w, h, k);
            band = new boolean[n];
            for (int i = 0; i < n; i++) band[i] = solid[i] && !core[i];
            model = fitContainer(px, band, w, h);
        }

        Glyph split = null;
        if (model != null) {
            int[] seg = segment(px, w, h, model);
            split = finalizeGlyph(seg, w, h, band, method + "/plate", n);
            boolean plateLike = split.bitmap != null && split.fill + split.holes > 0.72f;
            if (plateLike && depth == 0) {
                // The separated shape is itself a plate (e.g. a coloured disc on a white tile): peel once more.
                Glyph inner = analyze(split.bitmap, method + "/inner", false, 1);
                if (inner.ok) return inner;
            }
            if (split.ok) return split;
        }
        if (!preferWhole) {
            whole = finalizeGlyph(px, w, h, null, method + "/whole", n);
            if (whole.ok) return whole;
        }
        if (split != null && split.bitmap != null) return split;
        return whole != null ? whole : new Glyph(method).fail("no glyph");
    }

    /**
     * Fits the plate colour as a linear gradient (c = a + b*x + c*y per channel) from ring samples,
     * so gradient plates are removed as cleanly as flat ones. Returns null when there is no uniform plate.
     * Layout: {aR,bR,cR, aG,bG,cG, aB,bB,cB, rms}.
     */
    private static float[] fitContainer(int[] px, boolean[] band, int w, int h) {
        int n = w * h;
        int[] count = new int[4096];
        long[] sr = new long[4096], sg = new long[4096], sb = new long[4096];
        int bandCount = 0;
        for (int i = 0; i < n; i++) {
            if (!band[i]) continue;
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int bucket = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            count[bucket]++; sr[bucket] += r; sg[bucket] += g; sb[bucket] += b;
            bandCount++;
        }
        if (bandCount < 40) return null;
        int best = 0;
        for (int i = 1; i < 4096; i++) if (count[i] > count[best]) best = i;
        float cr = sr[best] / (float) count[best], cg = sg[best] / (float) count[best], cb = sb[best] / (float) count[best];

        // Pass 1: samples close to the dominant ring colour.
        float[] model = fitGradient(px, band, w, h, null, cr, cg, cb, 40f, bandCount, 0.25f);
        if (model == null) return null;
        // Pass 2: re-select against the fitted gradient (captures the full sweep of gradient plates).
        model = fitGradient(px, band, w, h, model, 0, 0, 0, 36f, bandCount, 0.60f);
        if (model == null || model[9] > 30f) return null;
        return model;
    }

    private static float[] fitGradient(int[] px, boolean[] band, int w, int h, float[] prev,
                                       float cr, float cg, float cb, float tol, int bandCount, float minFrac) {
        double s1 = 0, sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        double[] sv = new double[3], sxv = new double[3], syv = new double[3];
        int selected = 0;
        float fx = 2f / Math.max(1, w - 1), fy = 2f / Math.max(1, h - 1);
        for (int y = 0; y < h; y++) {
            float ny = y * fy - 1f;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!band[i]) continue;
                float nx = x * fx - 1f;
                int c = px[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                float er = cr, eg = cg, eb = cb;
                if (prev != null) {
                    er = prev[0] + prev[1] * nx + prev[2] * ny;
                    eg = prev[3] + prev[4] * nx + prev[5] * ny;
                    eb = prev[6] + prev[7] * nx + prev[8] * ny;
                }
                if (colorDist(r, g, b, er, eg, eb) > tol) continue;
                selected++;
                s1 += 1; sx += nx; sy += ny; sxx += nx * nx; sxy += nx * ny; syy += ny * ny;
                sv[0] += r; sv[1] += g; sv[2] += b;
                sxv[0] += nx * r; sxv[1] += nx * g; sxv[2] += nx * b;
                syv[0] += ny * r; syv[1] += ny * g; syv[2] += ny * b;
            }
        }
        if (selected < 30 || selected < bandCount * minFrac) return null;

        float[] m = new float[10];
        double det = s1 * (sxx * syy - sxy * sxy) - sx * (sx * syy - sxy * sy) + sy * (sx * sxy - sxx * sy);
        for (int ch = 0; ch < 3; ch++) {
            double a, bx, by;
            if (Math.abs(det) < 1e-6) {
                a = sv[ch] / s1; bx = 0; by = 0;
            } else {
                a = (sv[ch] * (sxx * syy - sxy * sxy) - sx * (sxv[ch] * syy - sxy * syv[ch]) + sy * (sxv[ch] * sxy - sxx * syv[ch])) / det;
                bx = (s1 * (sxv[ch] * syy - sxy * syv[ch]) - sv[ch] * (sx * syy - sxy * sy) + sy * (sx * syv[ch] - sxv[ch] * sy)) / det;
                by = (s1 * (sxx * syv[ch] - sxv[ch] * sxy) - sx * (sx * syv[ch] - sxv[ch] * sy) + sv[ch] * (sx * sxy - sxx * sy)) / det;
            }
            m[ch * 3] = (float) a; m[ch * 3 + 1] = (float) bx; m[ch * 3 + 2] = (float) by;
        }

        // Residual of the selected samples against the fit.
        double err = 0;
        int cnt = 0;
        for (int y = 0; y < h; y++) {
            float ny = y * fy - 1f;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!band[i]) continue;
                float nx = x * fx - 1f;
                int c = px[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                float pr = m[0] + m[1] * nx + m[2] * ny, pg = m[3] + m[4] * nx + m[5] * ny, pb = m[6] + m[7] * nx + m[8] * ny;
                float d = colorDist(r, g, b, pr, pg, pb);
                if (d > tol) continue;
                err += d * d;
                cnt++;
            }
        }
        m[9] = cnt > 0 ? (float) Math.sqrt(err / cnt) : 99f;
        return m;
    }

    /** Removes the plate with a soft, gradient-aware threshold and decontaminates anti-aliased glyph edges. */
    private static int[] segment(int[] px, int w, int h, float[] m) {
        int n = w * h;
        int[] out = new int[n];
        float tol = Math.min(70f, Math.max(24f, m[9] * 2.5f));
        float soft = 28f;
        float fx = 2f / Math.max(1, w - 1), fy = 2f / Math.max(1, h - 1);
        for (int y = 0; y < h; y++) {
            float ny = y * fy - 1f;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = px[i];
                int a = c >>> 24;
                if (a <= 20) continue;
                float nx = x * fx - 1f;
                float pr = m[0] + m[1] * nx + m[2] * ny, pg = m[3] + m[4] * nx + m[5] * ny, pb = m[6] + m[7] * nx + m[8] * ny;
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                float t = smoothstep(tol, tol + soft, colorDist(r, g, b, pr, pg, pb));
                if (t <= 0f) continue;
                if (t < 1f && t >= 0.2f) {
                    // Pixel = t*glyph + (1-t)*plate  ->  recover the glyph colour (removes coloured fringes).
                    r = clamp255((r - (1f - t) * pr) / t);
                    g = clamp255((g - (1f - t) * pg) / t);
                    b = clamp255((b - (1f - t) * pb) / t);
                }
                int oa = Math.round(a * t);
                out[i] = (oa << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    /**
     * Cleans the separated pixels (specks, plate outlines, baked shadows), trims, measures and validates.
     */
    private static Glyph finalizeGlyph(int[] px, int w, int h, boolean[] band, String method, int sourceArea) {
        Glyph glyph = new Glyph(method);
        int n = w * h;

        // 1. Connected components of clearly visible pixels.
        int[] labels = new int[n];
        int[] stack = new int[n];
        ArrayList<int[]> comps = new ArrayList<>();
        comps.add(null); // label 0 = none
        int totalArea = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] != 0 || (px[i] >>> 24) <= 60) continue;
            int label = comps.size();
            int[] st = {0, 0, w, h, -1, -1, 0}; // area, alphaSum, minX, minY, maxX, maxY, touchesRing
            int sp = 0;
            stack[sp++] = i;
            labels[i] = label;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % w, y = p / w;
                st[0]++;
                st[1] += px[p] >>> 24;
                if (x < st[2]) st[2] = x;
                if (y < st[3]) st[3] = y;
                if (x > st[4]) st[4] = x;
                if (y > st[5]) st[5] = y;
                if (band != null && band[p]) st[6] = 1;
                if (x > 0) { int q = p - 1; if (labels[q] == 0 && (px[q] >>> 24) > 60) { labels[q] = label; stack[sp++] = q; } }
                if (x < w - 1) { int q = p + 1; if (labels[q] == 0 && (px[q] >>> 24) > 60) { labels[q] = label; stack[sp++] = q; } }
                if (y > 0) { int q = p - w; if (labels[q] == 0 && (px[q] >>> 24) > 60) { labels[q] = label; stack[sp++] = q; } }
                if (y < h - 1) { int q = p + w; if (labels[q] == 0 && (px[q] >>> 24) > 60) { labels[q] = label; stack[sp++] = q; } }
            }
            comps.add(st);
            totalArea += st[0];
        }
        if (totalArea == 0) return glyph.fail("nothing left after separation");

        int minArea = Math.max(6, Math.round(totalArea * 0.004f));
        boolean[] keep = new boolean[comps.size()];
        int keptArea = 0;
        for (int l = 1; l < comps.size(); l++) {
            int[] st = comps.get(l);
            boolean k = st[0] >= minArea;
            if (k && band != null && st[6] == 1) {
                int bw = st[4] - st[2] + 1, bh = st[5] - st[3] + 1;
                boolean ring = bw >= w * 0.85f && bh >= h * 0.85f && st[0] < bw * bh * 0.30f; // plate outline
                boolean faint = st[1] / (float) st[0] < 100f;                                  // baked shadow / glow
                if (ring || faint) k = false;
            }
            keep[l] = k;
            if (k) keptArea += st[0];
        }
        if (keptArea == 0) return glyph.fail("only specks or plate edges left");
        int significant = 0;
        for (int l = 1; l < comps.size(); l++) {
            if (keep[l] && comps.get(l)[0] >= keptArea * 0.015f) significant++;
        }
        for (int i = 0; i < n; i++) {
            int l = labels[i];
            if (l > 0) {
                if (!keep[l]) px[i] = 0;
            } else if (px[i] != 0) {
                // Faint pixels survive only as anti-aliasing next to a kept shape.
                int x = i % w, y = i / w;
                boolean near = (x > 0 && keep[labels[i - 1]]) || (x < w - 1 && keep[labels[i + 1]])
                        || (y > 0 && keep[labels[i - w]]) || (y < h - 1 && keep[labels[i + w]]);
                if (!near) px[i] = 0;
            }
        }
        glyph.parts = significant;

        // 2. Trim to the glyph.
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 20) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < left) return glyph.fail("nothing left after cleanup");
        int gw = right - left + 1, gh = bottom - top + 1;
        int[] gp = new int[gw * gh];
        for (int y = 0; y < gh; y++) System.arraycopy(px, (top + y) * w + left, gp, y * gw, gw);
        glyph.bitmap = Bitmap.createBitmap(gp, gw, gh, Bitmap.Config.ARGB_8888);

        // 3. Measurements.
        long inkSum = 0;
        int strong = 0;
        int[] lumHist = new int[32];
        int[] toneHist = new int[4096];
        for (int c : gp) {
            int a = c >>> 24;
            inkSum += a;
            if (a >= SOLID_ALPHA) {
                strong++;
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                lumHist[lum(r, g, b) >> 3]++;
                toneHist[((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4)]++;
            }
        }
        glyph.inkFrac = inkSum / 255f / sourceArea;
        glyph.fill = inkSum / 255f / (gw * (float) gh);
        glyph.holes = enclosedHoleFraction(gp, gw, gh);
        if (strong < 20) return glyph.fail("glyph too faint");

        int domBin = 0;
        for (int b = 1; b < 32; b++) if (lumHist[b] > lumHist[domBin]) domBin = b;
        glyph.domLum = domBin * 8 + 4;
        int lo = Math.round(strong * 0.03f), hi = Math.round(strong * 0.97f);
        int acc = 0, p3 = -1, p97 = -1;
        for (int b = 0; b < 32; b++) {
            acc += lumHist[b];
            if (p3 < 0 && acc > lo) p3 = b * 8 + 4;
            if (p97 < 0 && acc > hi) p97 = b * 8 + 4;
        }
        if (p3 < 0) p3 = glyph.domLum;
        if (p97 < 0) p97 = glyph.domLum;
        glyph.lumSpread = Math.max(glyph.domLum - p3, p97 - glyph.domLum);

        int structured = 0;
        for (int c : gp) {
            if ((c >>> 24) < SOLID_ALPHA) continue;
            if (Math.abs(lum((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF) - glyph.domLum) > 45) structured++;
        }
        glyph.structure = structured / (float) strong;

        int nz = 0;
        for (int v : toneHist) if (v > 0) nz++;
        int[] tones = new int[nz];
        nz = 0;
        for (int v : toneHist) if (v > 0) tones[nz++] = v;
        Arrays.sort(tones);
        int need = Math.round(strong * 0.85f), covered = 0, toneCount = 0;
        for (int j = tones.length - 1; j >= 0 && covered < need; j--) { covered += tones[j]; toneCount++; }
        glyph.tones = toneCount;

        boolean[] seams = computeSeams(gp, gw, gh, false);
        int seamCount = 0;
        for (boolean s : seams) if (s) seamCount++;
        glyph.seamDensity = seamCount / (float) strong;

        // 4. Validation: is this still a recognisable glyph?
        boolean plate = glyph.fill + glyph.holes > 0.72f;
        if (glyph.inkFrac < 0.012f) return glyph.fail("too little left after separation");
        if (glyph.seamDensity > 0.14f) return glyph.fail("detailed illustration");
        if (glyph.parts > 14) return glyph.fail("fragmented artwork");
        if (plate && glyph.structure < 0.06f && glyph.holes < 0.03f) return glyph.fail("featureless block");
        if (plate && band == null && glyph.tones > 6 && glyph.holes < 0.03f) return glyph.fail("full-frame artwork");
        if (glyph.tones > 48 && glyph.seamDensity > 0.07f) return glyph.fail("photographic colours");

        glyph.ok = true;
        // 5. Stroke-weight normalisation so hairline glyphs don't vanish next to bold ones.
        Bitmap normalized = normalizeStroke(gp, gw, gh);
        if (normalized != null) glyph.bitmap = normalized;
        return glyph;
    }

    private static Bitmap normalizeStroke(int[] gp, int w, int h) {
        int strong = 0, edge = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if ((gp[i] >>> 24) < SOLID_ALPHA) continue;
                strong++;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || (gp[i - 1] >>> 24) <= 60 || (gp[i + 1] >>> 24) <= 60
                        || (gp[i - w] >>> 24) <= 60 || (gp[i + w] >>> 24) <= 60) edge++;
            }
        }
        if (edge == 0) return null;
        int maxDim = Math.max(w, h);
        float rel = (2f * strong / edge) / maxDim;
        int r = rel < 0.03f && maxDim >= 160 ? 2 : rel < 0.05f ? 1 : 0;
        if (r == 0) return null;

        int pw = w + 2 * r, ph = h + 2 * r;
        int[] padded = new int[pw * ph];
        for (int y = 0; y < h; y++) System.arraycopy(gp, y * w, padded, (y + r) * pw + r, w);
        int[] tmp = new int[pw * ph], out = new int[pw * ph];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int best = padded[y * pw + x];
                for (int dx = -r; dx <= r; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= pw) continue;
                    int c = padded[y * pw + xx];
                    if ((c >>> 24) > (best >>> 24)) best = c;
                }
                tmp[y * pw + x] = best;
            }
        }
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int best = tmp[y * pw + x];
                for (int dy = -r; dy <= r; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= ph) continue;
                    int c = tmp[yy * pw + x];
                    if ((c >>> 24) > (best >>> 24)) best = c;
                }
                out[y * pw + x] = best;
            }
        }
        return Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888);
    }

    /** Transparent area fully enclosed by the glyph (letter counters, cut-out triangles), relative to bbox. */
    private static float enclosedHoleFraction(int[] gp, int w, int h) {
        int n = w * h;
        boolean[] outside = new boolean[n];
        int[] stack = new int[n];
        int sp = 0;
        for (int x = 0; x < w; x++) {
            int a = x, b = (h - 1) * w + x;
            if ((gp[a] >>> 24) <= 60 && !outside[a]) { outside[a] = true; stack[sp++] = a; }
            if ((gp[b] >>> 24) <= 60 && !outside[b]) { outside[b] = true; stack[sp++] = b; }
        }
        for (int y = 0; y < h; y++) {
            int a = y * w, b = y * w + w - 1;
            if ((gp[a] >>> 24) <= 60 && !outside[a]) { outside[a] = true; stack[sp++] = a; }
            if ((gp[b] >>> 24) <= 60 && !outside[b]) { outside[b] = true; stack[sp++] = b; }
        }
        while (sp > 0) {
            int p = stack[--sp];
            int x = p % w, y = p / w;
            if (x > 0) { int q = p - 1; if (!outside[q] && (gp[q] >>> 24) <= 60) { outside[q] = true; stack[sp++] = q; } }
            if (x < w - 1) { int q = p + 1; if (!outside[q] && (gp[q] >>> 24) <= 60) { outside[q] = true; stack[sp++] = q; } }
            if (y > 0) { int q = p - w; if (!outside[q] && (gp[q] >>> 24) <= 60) { outside[q] = true; stack[sp++] = q; } }
            if (y < h - 1) { int q = p + w; if (!outside[q] && (gp[q] >>> 24) <= 60) { outside[q] = true; stack[sp++] = q; } }
        }
        int holes = 0;
        for (int i = 0; i < n; i++) if (!outside[i] && (gp[i] >>> 24) <= 60) holes++;
        return holes / (float) n;
    }

    /** Hard colour boundaries inside the glyph (e.g. between the facets of a multi-colour logo). */
    private static boolean[] computeSeams(int[] px, int w, int h, boolean wide) {
        boolean[] s = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = px[i];
                if ((c >>> 24) < SOLID_ALPHA) continue;
                if (x + 1 < w) {
                    int c2 = px[i + 1];
                    if ((c2 >>> 24) >= SOLID_ALPHA && colorDist(c, c2) > 60f) { s[i] = true; if (wide) s[i + 1] = true; }
                }
                if (y + 1 < h) {
                    int c2 = px[i + w];
                    if ((c2 >>> 24) >= SOLID_ALPHA && colorDist(c, c2) > 60f) { s[i] = true; if (wide) s[i + w] = true; }
                }
            }
        }
        return s;
    }

    private static boolean[] erode(boolean[] m, int w, int h, int k) {
        int n = w * h;
        boolean[] hz = new boolean[n];
        int[] run = new int[Math.max(w, h)];
        for (int y = 0; y < h; y++) {
            int r = 0;
            for (int x = 0; x < w; x++) { r = m[y * w + x] ? r + 1 : 0; run[x] = r; }
            r = 0;
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                r = m[i] ? r + 1 : 0;
                hz[i] = run[x] > k && r > k;
            }
        }
        boolean[] out = new boolean[n];
        for (int x = 0; x < w; x++) {
            int r = 0;
            for (int y = 0; y < h; y++) { r = hz[y * w + x] ? r + 1 : 0; run[y] = r; }
            r = 0;
            for (int y = h - 1; y >= 0; y--) {
                int i = y * w + x;
                r = hz[i] ? r + 1 : 0;
                out[i] = run[y] > k && r > k;
            }
        }
        return out;
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

    // ---------------------------------------------------------------------------------------------
    // Curated glyphs (used only when automatic extraction is rejected)
    // ---------------------------------------------------------------------------------------------

    private static String curatedKind(String pkg) {
        if (pkg == null) return null;
        if (pkg.equals("com.google.android.apps.youtube.music") || pkg.startsWith("com.google.android.youtube.tvmusic")) return "ytmusic";
        if (pkg.startsWith("com.google.android.youtube")) return "youtube";
        if (pkg.equals("com.android.vending")) return "play";
        if (pkg.equals("com.android.settings") || pkg.endsWith(".tv.settings")) return "settings";
        if (pkg.startsWith("com.netflix.")) return "netflix";
        if (pkg.startsWith("com.spotify.")) return "spotify";
        if (pkg.startsWith("com.plexapp.")) return "plex";
        if (pkg.contains("filemanager") || pkg.contains("filecommander") || pkg.equals("com.google.android.apps.nbu.files")
                || pkg.equals("com.android.documentsui")) return "folder";
        return null;
    }

    private static Bitmap renderCurated(String pkg, int size) {
        String kind = curatedKind(pkg);
        if (kind == null) return null;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float s = size;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFFFFFF);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        Paint clearStroke = new Paint(clear);
        clearStroke.setStyle(Paint.Style.STROKE);
        clearStroke.setStrokeCap(Paint.Cap.ROUND);

        switch (kind) {
            case "youtube": {
                c.drawRoundRect(new RectF(s * 0.06f, s * 0.19f, s * 0.94f, s * 0.81f), s * 0.17f, s * 0.17f, fill);
                Path t = new Path();
                t.moveTo(s * 0.41f, s * 0.35f); t.lineTo(s * 0.66f, s * 0.50f); t.lineTo(s * 0.41f, s * 0.65f); t.close();
                c.drawPath(t, clear);
                break;
            }
            case "ytmusic": {
                c.drawCircle(s / 2f, s / 2f, s * 0.44f, fill);
                c.drawCircle(s / 2f, s / 2f, s * 0.36f, clear);
                Path t = new Path();
                t.moveTo(s * 0.42f, s * 0.33f); t.lineTo(s * 0.68f, s * 0.50f); t.lineTo(s * 0.42f, s * 0.67f); t.close();
                c.drawPath(t, fill);
                break;
            }
            case "play": {
                Paint p = new Paint(fill);
                p.setPathEffect(new CornerPathEffect(s * 0.07f));
                Path t = new Path();
                t.moveTo(s * 0.22f, s * 0.10f); t.lineTo(s * 0.86f, s * 0.50f); t.lineTo(s * 0.22f, s * 0.90f); t.close();
                c.drawPath(t, p);
                clearStroke.setStrokeWidth(s * 0.035f);
                c.drawLine(s * 0.22f, s * 0.10f, s * 0.62f, s * 0.65f, clearStroke);
                c.drawLine(s * 0.22f, s * 0.90f, s * 0.62f, s * 0.35f, clearStroke);
                break;
            }
            case "settings": {
                float cx = s / 2f, cy = s / 2f;
                c.drawCircle(cx, cy, s * 0.31f, fill);
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45f, cx, cy);
                    c.drawRoundRect(new RectF(cx - s * 0.075f, cy - s * 0.45f, cx + s * 0.075f, cy - s * 0.24f), s * 0.03f, s * 0.03f, fill);
                    c.restore();
                }
                c.drawCircle(cx, cy, s * 0.13f, clear);
                break;
            }
            case "netflix": {
                c.drawRect(s * 0.24f, s * 0.10f, s * 0.40f, s * 0.90f, fill);
                c.drawRect(s * 0.60f, s * 0.10f, s * 0.76f, s * 0.90f, fill);
                Path d = new Path();
                d.moveTo(s * 0.24f, s * 0.10f); d.lineTo(s * 0.40f, s * 0.10f); d.lineTo(s * 0.76f, s * 0.90f); d.lineTo(s * 0.60f, s * 0.90f); d.close();
                c.drawPath(d, fill);
                break;
            }
            case "spotify": {
                c.drawCircle(s / 2f, s / 2f, s * 0.46f, fill);
                float[][] arcs = {
                        {0.22f, 0.38f, 0.50f, 0.24f, 0.78f, 0.44f, 0.075f},
                        {0.26f, 0.53f, 0.50f, 0.42f, 0.73f, 0.58f, 0.062f},
                        {0.29f, 0.67f, 0.50f, 0.58f, 0.68f, 0.71f, 0.050f}};
                for (float[] a : arcs) {
                    Path p = new Path();
                    p.moveTo(s * a[0], s * a[1]);
                    p.quadTo(s * a[2], s * a[3], s * a[4], s * a[5]);
                    clearStroke.setStrokeWidth(s * a[6]);
                    c.drawPath(p, clearStroke);
                }
                break;
            }
            case "plex": {
                Path p = new Path();
                p.moveTo(s * 0.30f, s * 0.15f); p.lineTo(s * 0.52f, s * 0.15f); p.lineTo(s * 0.76f, s * 0.50f);
                p.lineTo(s * 0.52f, s * 0.85f); p.lineTo(s * 0.30f, s * 0.85f); p.lineTo(s * 0.54f, s * 0.50f); p.close();
                c.drawPath(p, fill);
                break;
            }
            case "folder": {
                c.drawRoundRect(new RectF(s * 0.08f, s * 0.18f, s * 0.46f, s * 0.36f), s * 0.05f, s * 0.05f, fill);
                c.drawRoundRect(new RectF(s * 0.08f, s * 0.28f, s * 0.92f, s * 0.82f), s * 0.07f, s * 0.07f, fill);
                clearStroke.setStrokeCap(Paint.Cap.BUTT);
                clearStroke.setStrokeWidth(s * 0.03f);
                c.drawLine(s * 0.08f, s * 0.39f, s * 0.92f, s * 0.39f, clearStroke);
                break;
            }
            default:
                return null;
        }
        return bmp;
    }

    private static Glyph curatedGlyph(Bitmap bmp, String extractionReason) {
        Bitmap t = trimTransparent(bmp);
        int w = t.getWidth(), h = t.getHeight();
        int[] px = new int[w * h];
        t.getPixels(px, 0, w, 0, 0, w, h);
        long ink = 0;
        for (int c : px) ink += c >>> 24;
        if (ink == 0) return null;
        Glyph g = new Glyph("curated (extraction: " + extractionReason + ")");
        g.bitmap = t;
        g.fill = ink / 255f / (w * (float) h);
        g.inkFrac = g.fill;
        g.domLum = 255;
        g.lumSpread = 0;
        g.tones = 1;
        g.parts = 1;
        g.ok = true;
        return g;
    }

    // ---------------------------------------------------------------------------------------------
    // Styling
    // ---------------------------------------------------------------------------------------------

    private static Bitmap transformGlyphArtwork(Glyph glyph, Config config) {
        Bitmap src = glyph.bitmap;
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

        String style = config.style;
        int[] result = new int[w * h];

        // Tone-relative mapping: the glyph's dominant tone keeps full weight; secondary tones become
        // lighter "detail" so the logo's inner shapes stay readable without fading the whole glyph.
        float dom = glyph.domLum;
        float spread = Math.max(40f, glyph.lumSpread);
        boolean[] seams = "monochrome".equals(style) ? null : computeSeams(pixels, w, h, Math.max(w, h) >= 150);
        boolean invertMono = glyph.domLum < 80; // dark logos would disappear on dark plates

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
            int lum = lum(r, g, b);
            float detail = Math.min(1f, Math.abs(lum - dom) / spread);
            boolean seam = seams != null && seams[i];

            if ("original".equals(config.color)&&!"monochrome".equals(style)) {
                result[i]=c;
            } else if ("monochrome".equals(style)) {
                // High-contrast perceptual monochrome
                int base = invertMono ? 255 - lum : lum;
                int boosted = clamp255((base - 128f) * 1.35f + 128f);
                if ("accent".equals(config.color)) {
                    result[i] = (a << 24) | (Math.round(boosted * tr / 255f) << 16) | (Math.round(boosted * tg / 255f) << 8) | Math.round(boosted * tb / 255f);
                } else if ("black".equals(config.color)) {
                    int dark = Math.round(boosted * 0.35f + 25f);
                    result[i] = (a << 24) | (dark << 16) | (dark << 8) | dark;
                } else {
                    result[i] = (a << 24) | (boosted << 16) | (boosted << 8) | boosted;
                }
            } else if ("paper".equals(style)) {
                // Tactile papercraft: chalky matte paper; secondary tones read as folds
                float tone = 250f - 30f * detail;
                int outA = Math.round(a * (1f - 0.40f * detail) * (seam ? 0.2f : 1f));
                result[i] = (outA << 24) | (clamp255(tr*tone/255f) << 16) | (clamp255(tg*(tone-2f)/255f) << 8) | clamp255(tb*(tone-7f)/255f);
            } else if ("glass".equals(style)) {
                // Frosted glass glyph
                int outA = Math.round(a * (0.92f - 0.50f * detail) * (seam ? 0.2f : 1f));
                result[i] = (outA << 24) | (tr << 16) | (tg << 8) | tb;
            } else if ("dark".equals(style)) {
                // Stealth dark: luminous silver glyph; detail slightly dimmer
                float k = 1f - 0.32f * detail;
                int outA = Math.round(a * (seam ? 0.2f : 1f));
                result[i] = (outA << 24) | (clamp255(tr * k) << 16) | (clamp255(tg * k) << 8) | clamp255(tb * k);
            } else if ("flat".equals(style)) {
                // Bold solid flat vector; strongly different tones become cut-outs
                int outA = Math.round((detail > 0.5f ? a * 0.25f : a) * (seam ? 0.15f : 1f));
                result[i] = (outA << 24) | (tr << 16) | (tg << 8) | tb;
            } else if ("outlined".equals(style)) {
                // Wireframe outline with translucent interior; seams become inner outlines
                int outA = seam ? 0 : Math.round(a * 0.65f);
                result[i] = (outA << 24) | (tr << 16) | (tg << 8) | tb;
            } else {
                // "minimalist" (Default styled)
                int outA = Math.round(a * (1f - 0.68f * detail) * (seam ? 0.15f : 1f));
                result[i] = (outA << 24) | (tr << 16) | (tg << 8) | tb;
            }
        }

        Bitmap transformed = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888);

        // For outlined style, apply outline edge filter
        if ("outlined".equals(style)) {
            return applyOutlineFilter(transformed, targetColor,"original".equals(config.color));
        }
        // For paper style, apply directional embossing
        if ("paper".equals(style)) {
            return applyPaperEmboss(transformed);
        }

        return transformed;
    }

    private static Bitmap applyOutlineFilter(Bitmap src, int strokeColor,boolean preserveColor) {
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
                        edgePixels[y * w + x] = preserveColor?(0xFF000000|(pixels[y*w+x]&0xFFFFFF)):strokeColor;
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

    // ---------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------

    private static int lum(int r, int g, int b) {
        return Math.round(0.299f * r + 0.587f * g + 0.114f * b);
    }

    /** Perceptually weighted RGB distance on a 0..255 scale. */
    private static float colorDist(int r, int g, int b, float r2, float g2, float b2) {
        float dr = r - r2, dg = g - g2, db = b - b2;
        return (float) Math.sqrt((2f * dr * dr + 4f * dg * dg + 3f * db * db) / 9f);
    }

    private static float colorDist(int c1, int c2) {
        return colorDist((c1 >> 16) & 0xFF, (c1 >> 8) & 0xFF, c1 & 0xFF, (c2 >> 16) & 0xFF, (c2 >> 8) & 0xFF, c2 & 0xFF);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
