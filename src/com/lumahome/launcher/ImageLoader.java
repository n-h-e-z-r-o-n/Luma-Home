package com.lumahome.launcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded decoding; slow storage and network work never runs on the UI thread. */
final class ImageLoader {
    interface Imported { void done(String path, String error); }
    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private volatile boolean closed;

    private static int calculateCacheSize() {
        long max = Runtime.getRuntime().maxMemory();
        return (int) Math.min(32L * 1024 * 1024, Math.max(12L * 1024 * 1024, max / 8));
    }

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(calculateCacheSize()) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    ImageLoader(Context context) { this.context = context.getApplicationContext(); }

    void show(ImageView view, String uri, int width, int height) {
        String key = uri + ":" + width + "x" + height;
        view.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        workers.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = loadBitmap(Uri.parse(uri), width, height);
                if (bitmap != null) cache.put(key, bitmap);
            } catch (Throwable ignored) { }
            final Bitmap result = bitmap;
            if (!closed) ui.post(() -> { if (!closed && key.equals(view.getTag()) && result != null) view.setImageBitmap(result); });
        });
    }

    void importImage(Uri uri, String name, int width, int height, Imported callback) {
        workers.execute(() -> {
            String path = null, error = null;
            File temp = null;
            try {
                Bitmap bitmap = loadBitmap(uri, width, height);
                if (bitmap == null) throw new IllegalArgumentException("This file is not a supported image.");
                File directory = new File(context.getFilesDir(), "images");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot save image.");
                File file = new File(directory, name + "-" + System.currentTimeMillis() + ".jpg");
                temp = file;
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw new IllegalStateException("Cannot save image.");
                }
                bitmap.recycle();
                path = file.toURI().toString();
            } catch (Exception | OutOfMemoryError e) {
                if (temp != null) temp.delete();
                error = "Could not import this image. Choose a smaller JPEG or PNG (under 12 MB).";
            }
            final String saved = path, failure = error;
            if (!closed) ui.post(() -> { if (!closed) callback.done(saved, failure); });
        });
    }

    private Bitmap loadBitmap(Uri uri, int width, int height) throws Exception {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            File f = new File(uri.getPath());
            if (f.exists()) return decodeFileDirect(f.getAbsolutePath(), width, height);
        }
        if ("content".equals(scheme) || "android.resource".equals(scheme)) {
            try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null) {
                    FileDescriptor fd = afd.getFileDescriptor();
                    if (fd != null && fd.valid()) {
                        return decodeFdDirect(fd, width, height);
                    }
                }
            } catch (Throwable ignored) {}
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                return decode(readLimited(stream), width, height);
            }
        }
        try (InputStream stream = open(uri)) {
            byte[] bytes = readLimited(stream);
            return decode(bytes, width, height);
        }
    }

    private static Bitmap decodeFileDirect(String path, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int sample = 1;
        while ((options.outWidth / (sample * 2)) >= width && (options.outHeight / (sample * 2)) >= height) {
            sample *= 2;
        }
        options.inSampleSize = Math.max(1, sample);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        return scaleIfNeeded(bitmap, width, height);
    }

    private static Bitmap decodeFdDirect(FileDescriptor fd, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int sample = 1;
        while ((options.outWidth / (sample * 2)) >= width && (options.outHeight / (sample * 2)) >= height) {
            sample *= 2;
        }
        options.inSampleSize = Math.max(1, sample);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options);
        return scaleIfNeeded(bitmap, width, height);
    }

    private InputStream open(Uri uri) throws Exception {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) return new FileInputStream(new File(uri.getPath()));
        if ("content".equals(scheme) || "android.resource".equals(scheme)) return context.getContentResolver().openInputStream(uri);
        if (!"https".equals(scheme)) throw new IllegalArgumentException("Unsupported image source");
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(3500); connection.setReadTimeout(3500);
        connection.setInstanceFollowRedirects(false);
        if (connection.getResponseCode() != 200) { connection.disconnect(); throw new IllegalArgumentException("Image unavailable"); }
        return new java.io.FilterInputStream(connection.getInputStream()) {
            @Override public void close() throws java.io.IOException { try { super.close(); } finally { connection.disconnect(); } }
        };
    }

    private static byte[] readLimited(InputStream stream) throws Exception {
        if (stream == null) throw new IllegalArgumentException("No image stream");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > 12 * 1024 * 1024) throw new IllegalArgumentException("Image too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static Bitmap decode(byte[] bytes, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int sample = 1;
        while ((options.outWidth / (sample * 2)) >= width && (options.outHeight / (sample * 2)) >= height) {
            sample *= 2;
        }
        options.inSampleSize = Math.max(1, sample);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return scaleIfNeeded(bitmap, width, height);
    }

    private static Bitmap scaleIfNeeded(Bitmap bitmap, int width, int height) {
        if (bitmap == null) return null;
        float factor = Math.min(1f, Math.min((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight()));
        if (factor < 0.95f) {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * factor)), Math.max(1, Math.round(bitmap.getHeight() * factor)), true);
            if (small != bitmap) bitmap.recycle();
            return small;
        }
        return bitmap;
    }

    void close() { closed = true; workers.shutdownNow(); cache.evictAll(); }
}