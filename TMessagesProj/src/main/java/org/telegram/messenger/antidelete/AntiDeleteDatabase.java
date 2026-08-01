package org.telegram.messenger.antidelete;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.LongSparseArray;

import java.util.ArrayList;
import java.util.List;

public class AntiDeleteDatabase extends SQLiteOpenHelper {

    public static class AntiDeleteMessageBase {
        public long dialogId, peerId, fromId, topicId, groupedId, fwdFromId, replyPeerId;
        public int messageId, date, editDate, flags, views, replyMessageId, replyTopId, replyFlags;
        public boolean replyForumTopic;
        public String text, mimeType, mediaPath, hqThumbPath, postAuthor, fwdName, fwdPostAuthor;
        public byte[] textEntities;
        public byte[] replySerialized;
        public byte[] replyMarkupSerialized;
        public byte[] documentSerialized;
        public byte[] documentAttributesSerialized;
        public byte[] thumbsSerialized;
        public byte[] messageSerialized;
        public int documentType, fwdFlags, fwdDate;
        public int entityCreateDate;
        public long userId;
    }

    public static class DeletedMessage extends AntiDeleteMessageBase {
        public long fakeId;
    }

    public static class DeletedMessageReaction {
        public long deletedMessageId;
        public long fakeReactionId;
        public String emoticon;
        public long documentId;
        public boolean isCustom, isPaid, selfSelected;
        public int count;
    }

    public static class DeletedMessageFull {
        public DeletedMessage message;
        public List<DeletedMessageReaction> reactions = new ArrayList<>();
    }

    private static final int VERSION = 1;
    private static final LongSparseArray<AntiDeleteDatabase> INSTANCES = new LongSparseArray<>();

    public static synchronized AntiDeleteDatabase getInstance(Context context, int accountId) {
        AntiDeleteDatabase db = INSTANCES.get(accountId);
        if (db == null) {
            db = new AntiDeleteDatabase(context.getApplicationContext(), accountId);
            INSTANCES.put(accountId, db);
        }
        return db;
    }

    private AntiDeleteDatabase(Context context, int accountId) {
        super(context, "anti_delete_" + accountId + ".db", null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_messages (" +
                "fake_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id INTEGER, dialog_id INTEGER, peer_id INTEGER, from_id INTEGER, topic_id INTEGER," +
                "grouped_id INTEGER, fwd_from_id INTEGER, reply_peer_id INTEGER," +
                "message_id INTEGER, date INTEGER, edit_date INTEGER, flags INTEGER, views INTEGER," +
                "reply_message_id INTEGER, reply_top_id INTEGER, reply_flags INTEGER, reply_forum_topic INTEGER," +
                "text TEXT, mime_type TEXT, media_path TEXT, hq_thumb_path TEXT, post_author TEXT, fwd_name TEXT, fwd_post_author TEXT," +
                "text_entities BLOB, reply_serialized BLOB, reply_markup_serialized BLOB, document_serialized BLOB," +
                "document_attributes_serialized BLOB, thumbs_serialized BLOB, message_serialized BLOB," +
                "document_type INTEGER, fwd_flags INTEGER, fwd_date INTEGER, entity_create_date INTEGER)"
        );
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS deleted_messages_unique ON deleted_messages(user_id, dialog_id, topic_id, message_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS deleted_messages_range ON deleted_messages(user_id, dialog_id, message_id)");

        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_message_reactions (" +
                "fake_reaction_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "deleted_message_id INTEGER, emoticon TEXT, document_id INTEGER," +
                "is_custom INTEGER, is_paid INTEGER, self_selected INTEGER, count INTEGER)"
        );

        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_dialogs (" +
                "user_id INTEGER, dialog_id INTEGER, last_deleted_date INTEGER," +
                "PRIMARY KEY(user_id, dialog_id))"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public long insert(DeletedMessage m) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = toValues(m);
        return db.insertWithOnConflict("deleted_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void insertReaction(DeletedMessageReaction r) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("deleted_message_id", r.deletedMessageId);
        values.put("emoticon", r.emoticon);
        values.put("document_id", r.documentId);
        values.put("is_custom", r.isCustom ? 1 : 0);
        values.put("is_paid", r.isPaid ? 1 : 0);
        values.put("self_selected", r.selfSelected ? 1 : 0);
        values.put("count", r.count);
        db.insert("deleted_message_reactions", null, values);
    }

    public boolean exists(long userId, long dialogId, long topicId, int messageId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT 1 FROM deleted_messages WHERE user_id=? AND dialog_id=? AND topic_id=? AND message_id=? LIMIT 1",
                    args(userId, dialogId, topicId, messageId));
            return c.moveToFirst();
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public DeletedMessageFull getMessage(long userId, long dialogId, int messageId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT * FROM deleted_messages WHERE user_id=? AND dialog_id=? AND message_id=? ORDER BY fake_id DESC LIMIT 1",
                    args(userId, dialogId, messageId));
            if (!c.moveToFirst()) {
                return null;
            }
            DeletedMessage msg = fromCursor(c);
            DeletedMessageFull full = new DeletedMessageFull();
            full.message = msg;
            full.reactions = getReactions(msg.fakeId);
            return full;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public List<DeletedMessageFull> getMessagesTopicless(long userId, long dialogId, int minId, int maxId) {
        return getMessagesForWhere(userId, dialogId, "topic_id=0", null, minId, maxId);
    }

    public List<DeletedMessageFull> getMessagesForTopic(long userId, long dialogId, long topicId, int minId, int maxId) {
        return getMessagesForWhere(userId, dialogId, "topic_id=?", new String[]{Long.toString(topicId)}, minId, maxId);
    }

    public void delete(long userId, long dialogId, int messageId) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT fake_id FROM deleted_messages WHERE user_id=? AND dialog_id=? AND message_id=?",
                    args(userId, dialogId, messageId));
            ArrayList<String> ids = new ArrayList<>();
            while (c.moveToNext()) {
                ids.add(Long.toString(c.getLong(0)));
            }
            if (!ids.isEmpty()) {
                db.delete("deleted_message_reactions", "deleted_message_id IN(" + TextUtils.join(",", ids) + ")", null);
            }
            db.delete("deleted_messages", "user_id=? AND dialog_id=? AND message_id=?", args(userId, dialogId, messageId));
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public void clearForDialog(long userId, long dialogId, Long beforeDate) {
        SQLiteDatabase db = getWritableDatabase();
        String where = "user_id=? AND dialog_id=?";
        ArrayList<String> argsList = new ArrayList<>();
        argsList.add(Long.toString(userId));
        argsList.add(Long.toString(dialogId));
        if (beforeDate != null) {
            where += " AND entity_create_date<?";
            argsList.add(Long.toString(beforeDate));
        }
        db.delete("deleted_message_reactions", "deleted_message_id IN(SELECT fake_id FROM deleted_messages WHERE " + where + ")", argsList.toArray(new String[0]));
        db.delete("deleted_messages", where, argsList.toArray(new String[0]));
        db.delete("deleted_dialogs", "user_id=? AND dialog_id=?", args(userId, dialogId));
    }

    public void markDialogHasDeleted(long userId, long dialogId, int date) {
        ContentValues values = new ContentValues();
        values.put("user_id", userId);
        values.put("dialog_id", dialogId);
        values.put("last_deleted_date", date);
        getWritableDatabase().insertWithOnConflict("deleted_dialogs", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private List<DeletedMessageFull> getMessagesForWhere(long userId, long dialogId, String topicWhere, String[] topicArgs, int minId, int maxId) {
        ArrayList<DeletedMessageFull> result = new ArrayList<>();
        Cursor c = null;
        try {
            String sql = "SELECT * FROM deleted_messages WHERE user_id=? AND dialog_id=? AND message_id>=? AND message_id<=? AND " + topicWhere;
            ArrayList<String> args = new ArrayList<>();
            args.add(Long.toString(userId));
            args.add(Long.toString(dialogId));
            args.add(Integer.toString(minId));
            args.add(Integer.toString(maxId));
            if (topicArgs != null) {
                for (String s : topicArgs) {
                    args.add(s);
                }
            }
            c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            while (c.moveToNext()) {
                DeletedMessage msg = fromCursor(c);
                DeletedMessageFull full = new DeletedMessageFull();
                full.message = msg;
                full.reactions = getReactions(msg.fakeId);
                result.add(full);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return result;
    }

    private List<DeletedMessageReaction> getReactions(long deletedMessageId) {
        ArrayList<DeletedMessageReaction> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT fake_reaction_id, deleted_message_id, emoticon, document_id, is_custom, is_paid, self_selected, count FROM deleted_message_reactions WHERE deleted_message_id=?",
                    new String[]{Long.toString(deletedMessageId)});
            while (c.moveToNext()) {
                DeletedMessageReaction reaction = new DeletedMessageReaction();
                reaction.fakeReactionId = c.getLong(0);
                reaction.deletedMessageId = c.getLong(1);
                reaction.emoticon = c.getString(2);
                reaction.documentId = c.getLong(3);
                reaction.isCustom = c.getInt(4) == 1;
                reaction.isPaid = c.getInt(5) == 1;
                reaction.selfSelected = c.getInt(6) == 1;
                reaction.count = c.getInt(7);
                result.add(reaction);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return result;
    }

    private ContentValues toValues(DeletedMessage m) {
        ContentValues values = new ContentValues();
        values.put("user_id", m.userId);
        values.put("dialog_id", m.dialogId);
        values.put("peer_id", m.peerId);
        values.put("from_id", m.fromId);
        values.put("topic_id", m.topicId);
        values.put("grouped_id", m.groupedId);
        values.put("fwd_from_id", m.fwdFromId);
        values.put("reply_peer_id", m.replyPeerId);
        values.put("message_id", m.messageId);
        values.put("date", m.date);
        values.put("edit_date", m.editDate);
        values.put("flags", m.flags);
        values.put("views", m.views);
        values.put("reply_message_id", m.replyMessageId);
        values.put("reply_top_id", m.replyTopId);
        values.put("reply_flags", m.replyFlags);
        values.put("reply_forum_topic", m.replyForumTopic ? 1 : 0);
        values.put("text", m.text);
        values.put("mime_type", m.mimeType);
        values.put("media_path", m.mediaPath);
        values.put("hq_thumb_path", m.hqThumbPath);
        values.put("post_author", m.postAuthor);
        values.put("fwd_name", m.fwdName);
        values.put("fwd_post_author", m.fwdPostAuthor);
        values.put("text_entities", m.textEntities);
        values.put("reply_serialized", m.replySerialized);
        values.put("reply_markup_serialized", m.replyMarkupSerialized);
        values.put("document_serialized", m.documentSerialized);
        values.put("document_attributes_serialized", m.documentAttributesSerialized);
        values.put("thumbs_serialized", m.thumbsSerialized);
        values.put("message_serialized", m.messageSerialized);
        values.put("document_type", m.documentType);
        values.put("fwd_flags", m.fwdFlags);
        values.put("fwd_date", m.fwdDate);
        values.put("entity_create_date", m.entityCreateDate);
        return values;
    }

    private DeletedMessage fromCursor(Cursor c) {
        DeletedMessage m = new DeletedMessage();
        m.fakeId = c.getLong(c.getColumnIndexOrThrow("fake_id"));
        m.userId = c.getLong(c.getColumnIndexOrThrow("user_id"));
        m.dialogId = c.getLong(c.getColumnIndexOrThrow("dialog_id"));
        m.peerId = c.getLong(c.getColumnIndexOrThrow("peer_id"));
        m.fromId = c.getLong(c.getColumnIndexOrThrow("from_id"));
        m.topicId = c.getLong(c.getColumnIndexOrThrow("topic_id"));
        m.groupedId = c.getLong(c.getColumnIndexOrThrow("grouped_id"));
        m.fwdFromId = c.getLong(c.getColumnIndexOrThrow("fwd_from_id"));
        m.replyPeerId = c.getLong(c.getColumnIndexOrThrow("reply_peer_id"));
        m.messageId = c.getInt(c.getColumnIndexOrThrow("message_id"));
        m.date = c.getInt(c.getColumnIndexOrThrow("date"));
        m.editDate = c.getInt(c.getColumnIndexOrThrow("edit_date"));
        m.flags = c.getInt(c.getColumnIndexOrThrow("flags"));
        m.views = c.getInt(c.getColumnIndexOrThrow("views"));
        m.replyMessageId = c.getInt(c.getColumnIndexOrThrow("reply_message_id"));
        m.replyTopId = c.getInt(c.getColumnIndexOrThrow("reply_top_id"));
        m.replyFlags = c.getInt(c.getColumnIndexOrThrow("reply_flags"));
        m.replyForumTopic = c.getInt(c.getColumnIndexOrThrow("reply_forum_topic")) == 1;
        m.text = c.getString(c.getColumnIndexOrThrow("text"));
        m.mimeType = c.getString(c.getColumnIndexOrThrow("mime_type"));
        m.mediaPath = c.getString(c.getColumnIndexOrThrow("media_path"));
        m.hqThumbPath = c.getString(c.getColumnIndexOrThrow("hq_thumb_path"));
        m.postAuthor = c.getString(c.getColumnIndexOrThrow("post_author"));
        m.fwdName = c.getString(c.getColumnIndexOrThrow("fwd_name"));
        m.fwdPostAuthor = c.getString(c.getColumnIndexOrThrow("fwd_post_author"));
        m.textEntities = c.getBlob(c.getColumnIndexOrThrow("text_entities"));
        m.replySerialized = c.getBlob(c.getColumnIndexOrThrow("reply_serialized"));
        m.replyMarkupSerialized = c.getBlob(c.getColumnIndexOrThrow("reply_markup_serialized"));
        m.documentSerialized = c.getBlob(c.getColumnIndexOrThrow("document_serialized"));
        m.documentAttributesSerialized = c.getBlob(c.getColumnIndexOrThrow("document_attributes_serialized"));
        m.thumbsSerialized = c.getBlob(c.getColumnIndexOrThrow("thumbs_serialized"));
        m.messageSerialized = c.getBlob(c.getColumnIndexOrThrow("message_serialized"));
        m.documentType = c.getInt(c.getColumnIndexOrThrow("document_type"));
        m.fwdFlags = c.getInt(c.getColumnIndexOrThrow("fwd_flags"));
        m.fwdDate = c.getInt(c.getColumnIndexOrThrow("fwd_date"));
        m.entityCreateDate = c.getInt(c.getColumnIndexOrThrow("entity_create_date"));
        return m;
    }

    private String[] args(long... values) {
        String[] args = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            args[i] = Long.toString(values[i]);
        }
        return args;
    }
}
