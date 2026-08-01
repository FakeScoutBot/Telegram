package org.telegram.messenger.antidelete;

import androidx.room.Entity;

/**
 * Remembers, per-dialog, "this chat had deleted messages" so the chats-list
 * "last message" preview can fall back to the newest saved ghost message when
 * the real newest message was deleted (section 10a bonus feature).
 */
@Entity(tableName = "deleted_dialogs", primaryKeys = {"userId", "dialogId"})
public class DeletedDialog {
    public long userId;
    public long dialogId;
    public int lastDeletedMessageId;
    public int lastDeletedDate;
}
