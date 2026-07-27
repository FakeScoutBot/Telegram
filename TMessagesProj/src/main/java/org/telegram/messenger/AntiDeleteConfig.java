package org.telegram.messenger;

import android.content.SharedPreferences;

/**
 * User-facing toggles for the anti-delete (deleted message recovery) feature.
 * Mirrors the flags described in AyuGram's implementation, trimmed to what
 * Phase 1 (capture engine) actually needs. Kept as simple static state + its
 * own SharedPreferences file, same shape as SharedConfig.
 */
public class AntiDeleteConfig {

    private static final String PREFS_NAME = "antidelete";

    public static boolean saveDeletedMessages;
    public static boolean saveForBots;

    private static boolean loaded;

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        SharedPreferences prefs = getPreferences();
        saveDeletedMessages = prefs.getBoolean("saveDeletedMessages", false);
        saveForBots = prefs.getBoolean("saveForBots", false);
        loaded = true;
    }

    public static void setSaveDeletedMessages(boolean value) {
        load();
        saveDeletedMessages = value;
        getPreferences().edit().putBoolean("saveDeletedMessages", value).apply();
    }

    public static void setSaveForBots(boolean value) {
        load();
        saveForBots = value;
        getPreferences().edit().putBoolean("saveForBots", value).apply();
    }
}
