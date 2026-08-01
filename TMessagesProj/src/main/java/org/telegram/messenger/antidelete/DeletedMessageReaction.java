package org.telegram.messenger.antidelete;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "deleted_message_reactions",
    foreignKeys = @ForeignKey(
        entity = DeletedMessage.class,
        parentColumns = "fakeId",
        childColumns = "deletedMessageId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {@Index("deletedMessageId")}
)
public class DeletedMessageReaction {

    @PrimaryKey(autoGenerate = true)
    public long fakeReactionId;

    public long deletedMessageId;

    public String emoticon;
    public long documentId; // for custom emoji reactions

    public boolean isCustom;
    public boolean isPaid;
    public boolean selfSelected;

    public int count;
}
