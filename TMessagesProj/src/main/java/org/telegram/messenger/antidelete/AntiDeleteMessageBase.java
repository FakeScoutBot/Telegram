package org.telegram.messenger.antidelete;

import androidx.room.ColumnInfo;

/**
 * Abstract base holding every field needed to reconstruct a TLRPC.Message after
 * the original has been purged. Room doesn't support entity inheritance across
 * separate tables the way a plain ORM might, so DeletedMessage below re-declares
 * these as its own @Entity fields; this class exists purely to document the
 * shared shape in one place (kept in sync manually with DeletedMessage).
 */
public abstract class AntiDeleteMessageBase {
    public long dialogId;
    public long peerId;
    public long fromId;
    public long topicId;
    public long groupedId;
    public long fwdFromId;
    public long replyPeerId;

    public int messageId;
    public int date;
    public int editDate;
    public int flags;
    public int views;
    public int replyMessageId;
    public int replyTopId;
    public int replyFlags;

    public boolean replyForumTopic;

    @ColumnInfo(typeAffinity = ColumnInfo.TEXT)
    public String text;
    public String mimeType;
    public String mediaPath;
    public String hqThumbPath;
    public String postAuthor;
    public String fwdName;
    public String fwdPostAuthor;

    public byte[] textEntities;
    public byte[] replySerialized;
    public byte[] replyMarkupSerialized;
    public byte[] documentSerialized;
    public byte[] documentAttributesSerialized;
    public byte[] thumbsSerialized;

    public int documentType;
    public int fwdFlags;
    public int fwdDate;

    public int entityCreateDate;

    public long userId;
}
