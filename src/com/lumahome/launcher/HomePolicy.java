package com.lumahome.launcher;

/** The same gates govern the banner and the selected-app recommendation row. */
final class HomePolicy {
    static boolean recommendations(boolean master, boolean appEnabled, boolean hasContent) {
        return master && appEnabled && hasContent;
    }

    static boolean banner(boolean enabled, String source, boolean master, boolean appEnabled,
                          boolean hasContent, boolean hasArtwork) {
        return enabled && source != null && !source.isEmpty() && hasArtwork
                && recommendations(master, appEnabled, hasContent);
    }
}
