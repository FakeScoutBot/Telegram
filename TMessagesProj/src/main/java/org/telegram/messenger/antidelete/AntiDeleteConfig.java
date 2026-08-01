package org.telegram.messenger.antidelete;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.HashSet;
import java.util.Set;

public class AntiDeleteConfig {

    private static final String PREFS = "anti_delete";
    private static final String KEY_SAVE = "save_deleted_messages";
    private static final String KEY_SEMI = "semi_transparent_deleted_messages";
    private static final String KEY_ICON = "deleted_icon";
    private static final String KEY_ICON_COLOR = "deleted_icon_color";

    public static volatile boolean saveDeletedMessages = true;
    public static volatile boolean semiTransparentDeletedMessages = true;
    public static volatile int deletedIcon = 1;
    public static volatile int deletedIconColor = 0;

    private static volatile boolean loaded;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static void ensureLoaded() {
        if (loaded || ApplicationLoader.applicationContext == null) {
            return;
        }
        synchronized (AntiDeleteConfig.class) {
            if (loaded) {
                return;
            }
            SharedPreferences p = prefs();
            saveDeletedMessages = p.getBoolean(KEY_SAVE, true);
            semiTransparentDeletedMessages = p.getBoolean(KEY_SEMI, true);
            deletedIcon = p.getInt(KEY_ICON, 1);
            deletedIconColor = p.getInt(KEY_ICON_COLOR, 0);
            loaded = true;
        }
    }

    public static boolean saveDeletedMessageFor(int accountId, long dialogId) {
        ensureLoaded();
        if (!saveDeletedMessages) {
            return false;
        }
        Set<String> excluded = prefs().getStringSet("excluded_dialogs_" + accountId, new HashSet<>());
        return !excluded.contains(Long.toString(dialogId));
    }
}
