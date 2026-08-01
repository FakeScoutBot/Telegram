package org.telegram.messenger.antidelete;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Master configuration for the anti-delete (deleted-message-recovery) feature.
 *
 * Persisted via SharedPreferences, one instance per account, mirroring the pattern
 * used by MessagesController.notificationsPreferences / AppGlobalConfig elsewhere
 * in this codebase. Loaded lazily and cached in memory.
 */
public class AntiDeleteConfig {

    public static final int ICON_NONE = 0;
    public static final int ICON_TRASH = 1;
    public static final int ICON_CROSS = 2;
    public static final int ICON_EYE_CROSSED = 3;

    private static final String PREFS_NAME = "antidelete";

    private static final Object lock = new Object();

    public boolean saveDeletedMessages = true;
    public boolean semiTransparentDeletedMessages = true;
    public int deletedIcon = ICON_TRASH;
    public int deletedIconColor = 0; // 0 = theme default
    public boolean saveInSecretChats = false; // see section 10 of the spec: excluded by default

    private final Set<Long> excludedDialogs = new HashSet<>();
    private final int currentAccount;
    private final SharedPreferences prefs;

    private AntiDeleteConfig(int account) {
        currentAccount = account;
        prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME + account, android.content.Context.MODE_PRIVATE);
        load();
    }

    private static volatile AntiDeleteConfig[] cache = new AntiDeleteConfig[UserConfig.MAX_ACCOUNT_COUNT];

    public static AntiDeleteConfig getInstance(int account) {
        AntiDeleteConfig local = cache[account];
        if (local == null) {
            synchronized (lock) {
                local = cache[account];
                if (local == null) {
                    local = cache[account] = new AntiDeleteConfig(account);
                }
            }
        }
        return local;
    }

    private void load() {
        saveDeletedMessages = prefs.getBoolean("saveDeletedMessages", true);
        semiTransparentDeletedMessages = prefs.getBoolean("semiTransparent", true);
        deletedIcon = prefs.getInt("deletedIcon", ICON_TRASH);
        deletedIconColor = prefs.getInt("deletedIconColor", 0);
        saveInSecretChats = prefs.getBoolean("saveInSecretChats", false);
        excludedDialogs.clear();
        Set<String> raw = prefs.getStringSet("excludedDialogs", null);
        if (raw != null) {
            for (String s : raw) {
                try {
                    excludedDialogs.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    public void save() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("saveDeletedMessages", saveDeletedMessages);
        editor.putBoolean("semiTransparent", semiTransparentDeletedMessages);
        editor.putInt("deletedIcon", deletedIcon);
        editor.putInt("deletedIconColor", deletedIconColor);
        editor.putBoolean("saveInSecretChats", saveInSecretChats);
        Set<String> raw = new HashSet<>();
        for (Long id : excludedDialogs) {
            raw.add(String.valueOf(id));
        }
        editor.putStringSet("excludedDialogs", raw);
        editor.apply();
    }

    public boolean isExcluded(long dialogId) {
        return excludedDialogs.contains(dialogId);
    }

    public void setExcluded(long dialogId, boolean excluded) {
        if (excluded) {
            excludedDialogs.add(dialogId);
        } else {
            excludedDialogs.remove(dialogId);
        }
        save();
    }

    /**
     * ANDs the master switch with the per-dialog exclusion set and the
     * secret-chat opt-out. This is the single method call sites should use
     * instead of reading saveDeletedMessages directly.
     */
    public boolean shouldSaveDeletedMessagesFor(long dialogId, boolean isEncryptedChat) {
        if (!saveDeletedMessages) {
            return false;
        }
        if (isEncryptedChat && !saveInSecretChats) {
            return false;
        }
        return !isExcluded(dialogId);
    }
}
