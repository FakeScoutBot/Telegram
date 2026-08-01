package org.telegram.messenger.antidelete;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room row for one captured deleted message. Field shape mirrors
 * AntiDeleteMessageBase (kept manually in sync since Room requires each
 * @Entity to own its columns directly rather than inheriting across tables).
 */
@Entity(
    tableName = "deleted_messages",
    indices = {
        @Index(value = {"userId", "dialogId", "messageId"}, unique = true),
        @Index(value = {"userId", "dialogId", "topicId", "messageId"})
    }
)
public class DeletedMessage extends AntiDeleteMessageBase {

    @PrimaryKey(autoGenerate = true)
    public long fakeId;
}
