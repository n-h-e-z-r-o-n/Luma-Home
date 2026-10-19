package com.lumahome.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class LauncherStore {
    final SharedPreferences prefs;
    LauncherStore(Context context) { prefs = context.getSharedPreferences("luma_preferences", Context.MODE_PRIVATE); }
    boolean flag(String key) { return prefs.getBoolean(key, false); }
    boolean flag(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }
    void setFlag(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    String value(String key, String fallback) { return prefs.getString(key, fallback); }
    void set(String key, String value) { prefs.edit().putString(key, value).apply(); }
    int number(String key, int fallback) { return prefs.getInt(key, fallback); }
    void setNumber(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    List<String> list(String key) {
        String raw = value(key, "");
        return raw.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(raw.split(",")));
    }
    void list(String key, List<String> values) { set(key, android.text.TextUtils.join(",", values)); }
    void recent(String pkg) {
        if (!flag("recents")) return;
        List<String> list = list("recent_apps"); list.remove(pkg); list.add(0, pkg);
        while (list.size() > 12) list.remove(list.size() - 1);
        list("recent_apps", list);
    }
    boolean recommendations(String pkg) { return flag("recommendations." + pkg); }
    void recommendations(String pkg, boolean enabled) { setFlag("recommendations." + pkg, enabled); }
}
