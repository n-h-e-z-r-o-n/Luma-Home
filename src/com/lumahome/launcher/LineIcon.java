package com.lumahome.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

// Tiny white outline icons on a 24x24 grid, so the app needs no image resources.
// Each icon is a list of ops: 0=moveTo x y, 1=lineTo x y, 2=close, 3=circle cx cy r.
final class LineIcon extends Drawable {
    static final int[] PLAY = {0, 60, 45, 1, 60, 195, 1, 190, 120, 2};
    static final int[] HDMI = {0, 30, 90, 1, 210, 90, 1, 210, 130, 1, 185, 160, 1, 55, 160, 1, 30, 130, 2,
            0, 80, 120, 1, 160, 120};
    static final int[] SETTINGS = {0, 40, 70, 1, 140, 70, 0, 180, 70, 1, 200, 70, 0, 40, 170, 1, 60, 170,
            0, 100, 170, 1, 200, 170, 3, 160, 70, 20, 3, 80, 170, 20};
    static final int[] HEART = {0, 120, 84, 1, 96, 52, 1, 64, 52, 1, 38, 78, 1, 38, 114, 1, 68, 154, 1, 120, 204, 1, 172, 154, 1, 202, 114, 1, 202, 78, 1, 176, 52, 1, 144, 52, 2};
    static final int[] GEAR = {3, 120, 120, 28, 0, 179, 101, 1, 207, 105, 1, 207, 135, 1, 179, 139, 1,
            166, 161, 1, 177, 187, 1, 150, 203, 1, 133, 181, 1, 107, 181, 1, 90, 203, 1, 63, 187, 1,
            74, 161, 1, 61, 139, 1, 33, 135, 1, 33, 105, 1, 61, 101, 1, 74, 79, 1, 63, 53, 1,
            90, 37, 1, 107, 59, 1, 133, 59, 1, 150, 37, 1, 177, 53, 1, 166, 79, 2};
    static final int[] PLUS = {0, 120, 50, 1, 120, 190, 0, 50, 120, 1, 190, 120};
    static final int[] PALETTE = {3, 120, 120, 85, 3, 92, 95, 10, 3, 145, 92, 10, 3, 152, 142, 10};
    static final int[] BANNER = {0, 32, 56, 1, 208, 56, 1, 208, 184, 1, 32, 184, 2, 0, 102, 94, 1, 102, 146, 1, 148, 120, 2};
    static final int[] SPARKLES = {0, 40, 56, 1, 108, 56, 1, 108, 110, 1, 40, 110, 2,
            0, 132, 56, 1, 200, 56, 1, 200, 110, 1, 132, 110, 2,
            0, 40, 132, 1, 200, 132, 1, 200, 184, 1, 40, 184, 2};
    static final int[] CLOCK = {3, 120, 120, 84, 0, 120, 76, 1, 120, 122, 1, 154, 142};
    static final int[] HOME = {0, 36, 114, 1, 120, 42, 1, 204, 114, 0, 62, 98, 1, 62, 194, 1, 178, 194, 1, 178, 98};
    static final int[] INFO = {3, 120, 120, 86, 0, 120, 112, 1, 120, 164, 3, 120, 80, 6};
    static final int[] WIFI = {0, 34, 96, 1, 82, 62, 1, 158, 62, 1, 206, 96,
            0, 66, 132, 1, 98, 108, 1, 142, 108, 1, 174, 132,
            3, 120, 172, 12};
    static final int[] DISPLAY = {0, 32, 52, 1, 208, 52, 1, 208, 164, 1, 32, 164, 2,
            0, 84, 196, 1, 156, 196, 0, 120, 164, 1, 120, 196};
    static final int[] SOUND = {0, 48, 96, 1, 88, 96, 1, 140, 54, 1, 140, 186, 1, 88, 144, 1, 48, 144, 2,
            0, 172, 88, 1, 192, 120, 1, 172, 152};

    private final int[] ops;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    LineIcon(int[] ops) {
        this(ops, 0xEBFFFFFF);
    }

    LineIcon(int[] ops, int color) {
        this.ops = ops;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        if (ops == null) return;
        float s = getBounds().width() / 240f; // ops are in tenths of the 24-unit grid
        paint.setStrokeWidth(16 * s);
        path.reset();
        for (int i = 0; i < ops.length; ) {
            switch (ops[i]) {
                case 0: path.moveTo(ops[i + 1] * s, ops[i + 2] * s); i += 3; break;
                case 1: path.lineTo(ops[i + 1] * s, ops[i + 2] * s); i += 3; break;
                case 2: path.close(); i += 1; break;
                default: path.addCircle(ops[i + 1] * s, ops[i + 2] * s, ops[i + 3] * s, Path.Direction.CW); i += 4;
            }
        }
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.drawPath(path, paint);
        c.restore();
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 44; }
    @Override public int getIntrinsicHeight() { return 44; }
}
