package com.lumahome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.nio.charset.StandardCharsets;

/**
 * Self-contained ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level M, Versions 1..6).
 * Zero external dependencies. Renders crisp, high-contrast QR bitmaps for TV scanning.
 */
final class QrCode {
    // Version 1..6 parameters for ECC Level M (format ECC bits = 0b00):
    // {totalCodewords, ecCodewordsPerBlock, numBlocks}
    private static final int[][] VERSION_M = {
            null,
            {26, 10, 1},   // V1: 16 data bytes -> max 14 UTF-8 bytes
            {44, 16, 1},   // V2: 28 data bytes -> max 26 UTF-8 bytes
            {70, 26, 1},   // V3: 44 data bytes -> max 42 UTF-8 bytes
            {100, 18, 2},  // V4: 64 data bytes -> max 62 UTF-8 bytes
            {134, 24, 2},  // V5: 86 data bytes -> max 84 UTF-8 bytes
            {172, 16, 4}   // V6: 108 data bytes -> max 106 UTF-8 bytes
    };

    private QrCode() {}

    static boolean[][] encodeMatrix(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int ver = 1;
        while (ver <= 6) {
            int dataCap = VERSION_M[ver][0] - VERSION_M[ver][1] * VERSION_M[ver][2];
            if (payload.length + 2 <= dataCap) break;
            ver++;
        }
        if (ver > 6) throw new IllegalArgumentException("QR text too long: " + payload.length);

        int totalCw = VERSION_M[ver][0];
        int ecPerBlock = VERSION_M[ver][1];
        int numBlocks = VERSION_M[ver][2];
        int totalDataCw = totalCw - ecPerBlock * numBlocks;

        // 1. Build bit stream: Byte mode (0100) + 8-bit length + payload + terminator + pad bytes
        byte[] dataCw = new byte[totalDataCw];
        int bitLen = totalDataCw * 8;
        int bitPos = 0;
        bitPos = appendBits(dataCw, bitPos, 0b0100, 4);
        bitPos = appendBits(dataCw, bitPos, payload.length, 8);
        for (byte b : payload) {
            bitPos = appendBits(dataCw, bitPos, b & 0xFF, 8);
        }
        int term = Math.min(4, bitLen - bitPos);
        bitPos = appendBits(dataCw, bitPos, 0, term);
        while ((bitPos & 7) != 0) bitPos++;
        int padByte = 0xEC;
        for (int i = bitPos >>> 3; i < totalDataCw; i++) {
            dataCw[i] = (byte) padByte;
            padByte = (padByte == 0xEC) ? 0x11 : 0xEC;
        }

        // 2. Split into blocks, compute Reed-Solomon EC, and interleave
        int blockDataLen = totalDataCw / numBlocks;
        byte[][] dataBlocks = new byte[numBlocks][blockDataLen];
        byte[][] ecBlocks = new byte[numBlocks][ecPerBlock];
        byte[] rsGen = rsGeneratorPoly(ecPerBlock);
        for (int b = 0; b < numBlocks; b++) {
            System.arraycopy(dataCw, b * blockDataLen, dataBlocks[b], 0, blockDataLen);
            ecBlocks[b] = rsComputeRemainder(dataBlocks[b], rsGen);
        }
        byte[] allCw = new byte[totalCw];
        int idx = 0;
        for (int i = 0; i < blockDataLen; i++) {
            for (int b = 0; b < numBlocks; b++) allCw[idx++] = dataBlocks[b][i];
        }
        for (int i = 0; i < ecPerBlock; i++) {
            for (int b = 0; b < numBlocks; b++) allCw[idx++] = ecBlocks[b][i];
        }

        // 3. Construct base matrix and function patterns
        int size = ver * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunc = new boolean[size][size];

        drawFinderPattern(modules, isFunc, 3, 3);
        drawFinderPattern(modules, isFunc, size - 4, 3);
        drawFinderPattern(modules, isFunc, 3, size - 4);

        // Timing patterns
        for (int i = 8; i < size - 8; i++) {
            boolean dark = (i % 2 == 0);
            setFunc(modules, isFunc, 6, i, dark);
            setFunc(modules, isFunc, i, 6, dark);
        }

        // Alignment pattern (Versions 2..6 have single non-overlapping center at (size-7, size-7))
        if (ver >= 2) {
            drawAlignmentPattern(modules, isFunc, size - 7, size - 7);
        }

        // Reserve format information areas + dark module
        drawFormatBits(modules, isFunc, 0);

        // 4. Zig-zag data placement
        int bitIdx = 0;
        int totalBits = allCw.length * 8;
        boolean upward = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int v = 0; v < size; v++) {
                int r = upward ? (size - 1 - v) : v;
                for (int j = 0; j < 2; j++) {
                    int c = right - j;
                    if (!isFunc[r][c]) {
                        boolean dark = false;
                        if (bitIdx < totalBits) {
                            dark = (((allCw[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) != 0);
                            bitIdx++;
                        }
                        modules[r][c] = dark;
                    }
                }
            }
            upward = !upward;
        }

        // 5. Evaluate all 8 masks and apply the lowest-penalty mask
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] trial = copyMatrix(modules);
            applyMask(trial, isFunc, mask);
            drawFormatBits(trial, null, mask);
            int pen = penaltyScore(trial);
            if (pen < bestPenalty) {
                bestPenalty = pen;
                bestMask = mask;
            }
        }
        applyMask(modules, isFunc, bestMask);
        drawFormatBits(modules, isFunc, bestMask);
        return modules;
    }

    static Bitmap encodeBitmap(String text, int targetPx, int quietModules, int darkColor, int lightColor) {
        boolean[][] matrix = encodeMatrix(text);
        int n = matrix.length;
        int q = Math.max(2, quietModules);
        int total = n + q * 2;
        int bmpSize = Math.max(total * 4, targetPx);
        Bitmap bmp = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setStyle(Paint.Style.FILL);
        p.setColor(lightColor);
        cv.drawRect(0, 0, bmpSize, bmpSize, p);

        float cell = (float) bmpSize / total;
        p.setColor(darkColor);
        RectF rect = new RectF();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (matrix[r][c]) {
                    float left = (c + q) * cell;
                    float top = (r + q) * cell;
                    // Slight 0.35px overlap avoids hairline anti-aliasing seams between adjacent dark modules
                    rect.set(left - 0.2f, top - 0.2f, left + cell + 0.2f, top + cell + 0.2f);
                    cv.drawRect(rect, p);
                }
            }
        }
        return bmp;
    }

    private static int appendBits(byte[] out, int bitPos, int val, int len) {
        for (int i = len - 1; i >= 0; i--) {
            if (((val >>> i) & 1) != 0) {
                out[bitPos >>> 3] |= (byte) (1 << (7 - (bitPos & 7)));
            }
            bitPos++;
        }
        return bitPos;
    }

    private static void setFunc(boolean[][] m, boolean[][] fn, int r, int c, boolean dark) {
        m[r][c] = dark;
        if (fn != null) fn[r][c] = true;
    }

    private static void drawFinderPattern(boolean[][] m, boolean[][] fn, int cr, int cc) {
        int size = m.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int r = cr + dy, c = cc + dx;
                if (r >= 0 && r < size && c >= 0 && c < size) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    setFunc(m, fn, r, c, dist != 2 && dist != 4);
                }
            }
        }
    }

    private static void drawAlignmentPattern(boolean[][] m, boolean[][] fn, int cr, int cc) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunc(m, fn, cr + dy, cc + dx, dist != 1);
            }
        }
    }

    private static void drawFormatBits(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        // ECC Level M format indicator = 0b00
        int data5 = (0b00 << 3) | mask;
        int rem = data5 << 10;
        for (int i = 14; i >= 10; i--) {
            if (((rem >>> i) & 1) != 0) rem ^= (0x537 << (i - 10));
        }
        int bits = ((data5 << 10) | rem) ^ 0x5412;

        // Copy 1: around top-left finder
        for (int i = 0; i <= 5; i++) setFunc(m, fn, i, 8, bit(bits, i));
        setFunc(m, fn, 7, 8, bit(bits, 6));
        setFunc(m, fn, 8, 8, bit(bits, 7));
        setFunc(m, fn, 8, 7, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunc(m, fn, 8, 14 - i, bit(bits, i));

        // Copy 2: under top-right finder & beside bottom-left finder
        for (int i = 0; i < 8; i++) setFunc(m, fn, 8, size - 1 - i, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunc(m, fn, size - 15 + i, 8, bit(bits, i));
        setFunc(m, fn, size - 8, 8, true); // Dark module
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        int n = m.length;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (fn[r][c]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (r + c) % 2 == 0; break;
                    case 1: invert = r % 2 == 0; break;
                    case 2: invert = c % 3 == 0; break;
                    case 3: invert = (r + c) % 3 == 0; break;
                    case 4: invert = (r / 2 + c / 3) % 2 == 0; break;
                    case 5: invert = (r * c) % 2 + (r * c) % 3 == 0; break;
                    case 6: invert = ((r * c) % 2 + (r * c) % 3) % 2 == 0; break;
                    default: invert = ((r + c) % 2 + (r * c) % 3) % 2 == 0; break;
                }
                if (invert) m[r][c] = !m[r][c];
            }
        }
    }

    private static boolean[][] copyMatrix(boolean[][] src) {
        int n = src.length;
        boolean[][] dst = new boolean[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(src[i], 0, dst[i], 0, n);
        return dst;
    }

    private static int penaltyScore(boolean[][] m) {
        int n = m.length;
        int score = 0;
        // N1: 5+ consecutive same-color runs horizontally & vertically
        for (int r = 0; r < n; r++) {
            int runH = 1, runV = 1;
            for (int c = 1; c < n; c++) {
                if (m[r][c] == m[r][c - 1]) {
                    runH++;
                    if (runH == 5) score += 3; else if (runH > 5) score++;
                } else runH = 1;
                if (m[c][r] == m[c - 1][r]) {
                    runV++;
                    if (runV == 5) score += 3; else if (runV > 5) score++;
                } else runV = 1;
            }
        }
        // N2: 2x2 same-color blocks
        for (int r = 0; r < n - 1; r++) {
            for (int c = 0; c < n - 1; c++) {
                boolean v = m[r][c];
                if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) score += 3;
            }
        }
        // N4: dark module balance
        int dark = 0;
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) if (m[r][c]) dark++;
        int total = n * n;
        int k = Math.abs(dark * 20 - total * 10) / total;
        score += k * 10;
        return score;
    }

    private static byte[] rsGeneratorPoly(int degree) {
        byte[] poly = new byte[degree];
        poly[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                poly[j] = (byte) (gfMul(poly[j] & 0xFF, root) ^ (j + 1 < degree ? (poly[j + 1] & 0xFF) : 0));
            }
            root = gfMul(root, 0x02);
        }
        return poly;
    }

    private static byte[] rsComputeRemainder(byte[] data, byte[] gen) {
        byte[] rem = new byte[gen.length];
        for (byte b : data) {
            int factor = (b ^ rem[0]) & 0xFF;
            System.arraycopy(rem, 1, rem, 0, rem.length - 1);
            rem[rem.length - 1] = 0;
            for (int j = 0; j < gen.length; j++) {
                rem[j] ^= (byte) gfMul(gen[j] & 0xFF, factor);
            }
        }
        return rem;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }
}
