package org.telegram.messenger.antidelete;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

/**
 * One Room database per local account (multi-account support), mirroring how
 * MessagesStorage keeps a separate SQLite file per account elsewhere in this
 * codebase. Kept in a small in-memory cache keyed by account index.
 */
@Database(
    entities = {DeletedMessage.class, DeletedMessageReaction.class, DeletedDialog.class},
    version = 1,
    exportSchema = false
)
public abstract class AntiDeleteDatabase extends RoomDatabase {

    public abstract DeletedMessageDao deletedMessageDao();

    private static volatile AntiDeleteDatabase[] instances = new AntiDeleteDatabase[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object lock = new Object();

    public static AntiDeleteDatabase getInstance(int account) {
        AntiDeleteDatabase local = instances[account];
        if (local == null) {
            synchronized (lock) {
                local = instances[account];
                if (local == null) {
                    Context context = ApplicationLoader.applicationContext;
                    local = instances[account] = Room.databaseBuilder(
                            context,
                            AntiDeleteDatabase.class,
                            "antidelete_" + account + ".db"
                        )
                        // Deleted-message history is a best-effort local cache, not
                        // durable state we need to migrate carefully -- if the schema
                        // ever changes, dropping and recreating is an acceptable cost.
                        .fallbackToDestructiveMigration()
                        .build();
                }
            }
        }
        return local;
    }
}
