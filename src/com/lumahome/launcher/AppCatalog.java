package com.lumahome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class AppCatalog {
    static final class Artwork {
        final Drawable drawable; final boolean banner;
        Artwork(Drawable drawable,boolean banner) {this.drawable=drawable;this.banner=banner;}
    }
    static final class App {
        final String pkg, originalName;
        final boolean utility;
        App(String pkg, String name, boolean utility) { this.pkg = pkg; this.originalName = name;this.utility=utility; }
    }
    private final Context context;
    private final PackageManager pm;
    private final android.content.SharedPreferences labelCachePrefs;
    private final java.util.Map<String, String> memoryLabelCache = new java.util.concurrent.ConcurrentHashMap<>();

    AppCatalog(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.labelCachePrefs = context.getSharedPreferences("app_label_cache", Context.MODE_PRIVATE);
    }

    void invalidate(String pkg) {
        if (pkg != null) {
            memoryLabelCache.remove(pkg);
            labelCachePrefs.edit().remove(pkg).apply();
        }
    }

    List<App> all() {
        LinkedHashMap<String, App> found = new LinkedHashMap<>();
        android.content.SharedPreferences.Editor editor = null;
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo result : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = result.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || found.containsKey(pkg)) continue;
                boolean tvApp = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category);
                int flags = result.activityInfo.applicationInfo.flags;
                boolean system = (flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;

                String label = memoryLabelCache.get(pkg);
                if (label == null) {
                    label = labelCachePrefs.getString(pkg, null);
                    if (label != null) {
                        memoryLabelCache.put(pkg, label);
                    }
                }
                if (label == null) {
                    label = result.activityInfo.applicationInfo.loadLabel(pm).toString();
                    memoryLabelCache.put(pkg, label);
                    if (editor == null) editor = labelCachePrefs.edit();
                    editor.putString(pkg, label);
                }
                found.put(pkg, new App(pkg, label, !tvApp && system));
            }
        }
        if (editor != null) editor.apply();
        List<App> apps = new ArrayList<>(found.values());
        java.util.Collections.sort(apps, (a, b) -> a.originalName.compareToIgnoreCase(b.originalName));
        return apps;
    }
    Intent launch(String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent == null ? pm.getLaunchIntentForPackage(pkg) : intent;
    }
    Artwork artwork(String pkg, boolean iconOnly) {
        try {
            if (!iconOnly) {
                Intent launch = launch(pkg);
                Drawable banner = launch == null ? null : pm.getActivityBanner(launch);
                if (banner == null) banner = pm.getApplicationBanner(pkg);
                if (banner != null) return new Artwork(banner,true);
            }
            return new Artwork(pm.getApplicationIcon(pkg),false);
        } catch (Exception e) { return new Artwork(null,false); }
    }
}
