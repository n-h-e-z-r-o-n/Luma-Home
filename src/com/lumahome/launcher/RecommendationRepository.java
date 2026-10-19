package com.lumahome.launcher;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads only recommendations exposed to this ordinary launcher by the TV provider. */
final class RecommendationRepository {
    static final class Item {
        final String source, title, description, artwork, action;
        String preview="";
        Item(String source, String title, String description, String artwork, String action) {
            this.source=source; this.title=title; this.description=description; this.artwork=artwork; this.action=action;
        }
        Intent intent() {
            try {
                Intent intent = Intent.parseUri(action, Intent.URI_INTENT_SCHEME);
                if (intent.getComponent() != null && !source.equals(intent.getComponent().getPackageName())) return null;
                String actionName = intent.getAction();
                if (actionName != null && !Intent.ACTION_VIEW.equals(actionName) && !Intent.ACTION_MAIN.equals(actionName)) return null;
                intent.setSelector(null); intent.setPackage(source); intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.getAction() == null) intent.setAction(Intent.ACTION_VIEW);
                return intent;
            } catch (Exception e) { return null; }
        }
    }
    static final class Feed {
        final List<Item> items;
        final String status;
        Feed(List<Item> items, String status) { this.items=items; this.status=status; }
        Item featured() { for (Item item : items) if (!item.artwork.isEmpty()) return item; return null; }
    }
    interface Loaded { void ready(Feed feed); }
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Feed> cache = new HashMap<>();
    private volatile boolean closed;
    RecommendationRepository(Context context) { this.context=context.getApplicationContext(); }
    Feed cached(String pkg) { return cache.get(pkg); }
    void clear() { cache.clear(); }
    void load(String pkg, Loaded callback) {
        Feed known = cache.get(pkg);
        if (known != null) { callback.ready(known); return; }
        worker.execute(() -> {
            List<Item> items = new ArrayList<>(); HashSet<String> actions = new HashSet<>();
            boolean blocked = false;
            for (String table : new String[] {"preview_program", "watch_next_program"}) {
                try (Cursor cursor = context.getContentResolver().query(Uri.parse("content://android.media.tv/"+table),
                        null, null, null, null)) {
                    if (cursor == null) continue;
                    int checked=0;
                    while (cursor.moveToNext() && checked++ < 400 && items.size() < 8) {
                        if (!pkg.equals(column(cursor,"package_name"))) continue;
                        String title = column(cursor,"title"), action=column(cursor,"intent_uri");
                        if (title.isEmpty() || action.isEmpty() || !actions.add(action)) continue;
                        String art=column(cursor,"poster_art_uri"); if (art.isEmpty()) art=column(cursor,"thumbnail_uri");
                        String description=column(cursor,"short_description");if(description.isEmpty())description=column(cursor,"long_description");
                        Item item = new Item(pkg,title,description,art,action);
                        item.preview=column(cursor,"preview_video_uri");
                        if (item.intent()!=null) items.add(item);
                    }
                } catch (SecurityException e) { blocked=true; }
                catch (Exception ignored) { }
            }
            Feed feed = new Feed(items, !items.isEmpty() ? "Ready · " + items.size() + " shared titles" : blocked
                    ? "This TV restricts recommendation access. The clock remains available."
                    : "No shared recommendations are available from this app on this TV.");
            if (!closed) ui.post(() -> { if (!closed) { cache.put(pkg,feed); callback.ready(feed); } });
        });
    }
    private static String column(Cursor c,String name) {int index=c.getColumnIndex(name);return index<0?"":value(c,index);}
    private static String value(Cursor c,int column) { return c.isNull(column) ? "" : c.getString(column); }
    void close() { closed=true; worker.shutdownNow(); }
}
