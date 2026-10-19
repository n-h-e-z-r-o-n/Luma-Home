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
    AppCatalog(Context context) { this.context = context; pm = context.getPackageManager(); }
    List<App> all() {
        LinkedHashMap<String, App> found = new LinkedHashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo result : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = result.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || found.containsKey(pkg)) continue;
                boolean tvApp=Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category);
                int flags=result.activityInfo.applicationInfo.flags;
                boolean system=(flags&(android.content.pm.ApplicationInfo.FLAG_SYSTEM|android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
                found.put(pkg, new App(pkg, result.activityInfo.applicationInfo.loadLabel(pm).toString(),!tvApp&&system));
            }
        }
        List<App> apps = new ArrayList<>(found.values());
        java.util.Collections.sort(apps,(a,b) -> a.originalName.compareToIgnoreCase(b.originalName));
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
