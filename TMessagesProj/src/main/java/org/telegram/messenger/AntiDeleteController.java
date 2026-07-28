package org.telegram.messenger;

import android.text.TextUtils;
import android.util.SparseArray;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;

/**
 * Captures a full snapshot of a message right before it's deleted, unless the
 * deletion was something the local user just did themselves (delete-for-me,
 * delete-for-everyone, clear history, etc).
 *
 * Storage is a separate SQLite database file (antidelete.db), not the main
 * cache4.db, so none of MessagesStorage's schema/migration logic (LAST_DB_VERSION,
 * updateDbToLastVersion) needs to change for this feature to exist.
 */
public class AntiDeleteController extends BaseController {

    private static volatile AntiDeleteController[] Instance = new AntiDeleteController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static AntiDeleteController getInstance(int num) {
        AntiDeleteController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new AntiDeleteController(num);
                }
            }
        }
        return localInstance;
    }

    private SQLiteDatabase database;
    private DispatchQueue queue;
    private boolean triedToOpen;

    // in-memory only, per section 5 of the design: bridges the gap between a
    // user-initiated delete call and the shared storage-layer delete path, so
    // that path can tell "we did this" apart from "someone else did this".
    private final LongSparseArray<HashSet<Integer>> messageDeletePermitted = new LongSparseArray<>();

    private AntiDeleteController(int num) {
        super(num);
    }

    // ----------------- self-delete permit bridge -----------------

    public void permitDeleteMessage(long dialogId, int messageId) {
        HashSet<Integer> set = messageDeletePermitted.get(dialogId);
        if (set == null) {
            messageDeletePermitted.put(dialogId, set = new HashSet<>());
        }
        set.add(messageId);
    }

    public void permitDeleteMessages(long dialogId, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        HashSet<Integer> set = messageDeletePermitted.get(dialogId);
        if (set == null) {
            messageDeletePermitted.put(dialogId, set = new HashSet<>());
        }
        set.addAll(messageIds);
    }

    /**
     * Checks whether this message's deletion was permitted by the local user.
     * Non-destructive -- more than one call site needs to observe the same
     * entry (MessagesStorage's archive-on-delete check, and ChatActivity's
     * live in-place update), so nobody consumes it just by looking. Call
     * clearDeletePermits once the deletion this bridges is actually done with.
     */
    public boolean isDeleteMessagePermitted(long dialogId, int messageId) {
        HashSet<Integer> set = messageDeletePermitted.get(dialogId);
        return set != null && set.contains(messageId);
    }

    /**
     * Releases permit entries once every call site that needed to see them has.
     * Call this once, from wherever the local delete request this bridges
     * actually finishes (deleteMessages), not from either of the peek sites.
     */
    public void clearDeletePermits(long dialogId, ArrayList<Integer> messageIds) {
        HashSet<Integer> set = messageDeletePermitted.get(dialogId);
        if (set == null || messageIds == null) {
            return;
        }
        set.removeAll(messageIds);
        if (set.isEmpty()) {
            messageDeletePermitted.remove(dialogId);
        }
    }

    // ----------------- gating -----------------

    public boolean shouldSaveDeletedMessage(long dialogId) {
        AntiDeleteConfig.load();
        if (!AntiDeleteConfig.saveDeletedMessages) {
            return false;
        }
        if (!AntiDeleteConfig.saveForBots) {
            TLRPC.User user = getMessagesController().getUser(Math.abs(dialogId));
            if (user != null && user.bot) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this dialog has any locally-archived deleted messages -- used to
     * exempt it from the "no real messages left, drop it from the dialog list"
     * cleanup even though the underlying real history is empty (e.g. right
     * after clearing it).
     */
    public void hasDeletedMessages(long dialogId, Utilities.Callback<Boolean> callback) {
        getQueue().postRunnable(() -> {
            ensureOpen();
            boolean exists = false;
            if (database != null) {
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized("SELECT 1 FROM deleted_messages WHERE uid = ? LIMIT 1", dialogId);
                    exists = cursor.next();
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
            boolean result = exists;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    // ----------------- inline history merge -----------------

    /**
     * Fills any gaps in the live history with locally-archived deleted messages
     * so they render inline at their original spot instead of only being
     * visible in the separate "Deleted Messages" viewer.
     *
     * Called from ChatActivity (didReceivedNotification_messagesDidLoad), not
     * MessagesController -- it needs liveMessages (ChatActivity's messagesDict)
     * to dedup against every page loaded this session, not just the one that
     * just arrived, mirroring AyuGram's AyuHistoryHook (which does the same
     * against chatActivity.messagesDict rather than a single page's array).
     *
     * Runs on the UI thread as a result (ChatActivity's notification handler
     * is UI-thread), so the blocking DB reads below (getMinAndMaxArchivedSync,
     * getDeletedMessagesInRangeSync) briefly block it. Both are single indexed
     * lookups against deleted_messages' (uid, mid) primary key, so this should
     * stay well under jank-visible latency for a personal-scale archive -- but
     * it's a real trade-off, not a free lunch, and worth knowing about if this
     * ever needs to run against a much larger archive.
     */
    public void mergeDeletedMessagesIntoHistory(long dialogId, ArrayList<MessageObject> objects, int load_type, SparseArray<MessageObject>[] liveMessages) {
        if (!shouldSaveDeletedMessage(dialogId)) {
            return;
        }
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        HashSet<Integer> existingIds = new HashSet<>();
        if (liveMessages != null) {
            for (SparseArray<MessageObject> dict : liveMessages) {
                if (dict != null) {
                    for (int a = 0, N = dict.size(); a < N; a++) {
                        existingIds.add(dict.keyAt(a));
                    }
                }
            }
        }
        for (int a = 0, N = objects.size(); a < N; a++) {
            int id = objects.get(a).getId();
            existingIds.add(id);
            if (id > 0) {
                if (id < minId) {
                    minId = id;
                }
                if (id > maxId) {
                    maxId = id;
                }
            }
        }
        if (minId > maxId) {
            // Nothing real came back in this page (e.g. the only message here was
            // deleted and nothing's been sent since) -- anchor on the true bounds of
            // what's actually archived for this dialog (not a guessed window), capped
            // at Telegram's own authoritative "latest message" pointer (dialog.top_message,
            // which stays correct even after that very message gets deleted), instead of
            // extrapolating a window that can overlap what an adjacent page already covers.
            int[] archiveBounds = getMinAndMaxArchivedSync(dialogId);
            if (archiveBounds == null) {
                return;
            }
            minId = archiveBounds[0];
            maxId = archiveBounds[1];
            TLRPC.Dialog dialog = getMessagesController().getDialog(dialogId);
            if (dialog != null && dialog.top_message > 0 && dialog.top_message < maxId) {
                maxId = dialog.top_message;
            }
        }
        ArrayList<MessageObject> deletedMessages = getDeletedMessagesInRangeSync(dialogId, minId, maxId, existingIds);
        if (deletedMessages.isEmpty()) {
            return;
        }
        boolean descending = !objects.isEmpty() ? objects.get(0).getId() >= objects.get(objects.size() - 1).getId() : load_type != MessagesController.LOAD_FORWARD;
        for (int a = 0, N = deletedMessages.size(); a < N; a++) {
            MessageObject deletedMessageObject = deletedMessages.get(a);
            int insertId = deletedMessageObject.getId();
            int index = objects.size();
            for (int b = 0, M = objects.size(); b < M; b++) {
                int id = objects.get(b).getId();
                if (descending ? id < insertId : id > insertId) {
                    index = b;
                    break;
                }
            }
            objects.add(index, deletedMessageObject);
        }
    }

    /**
     * True min/max mid of everything archived for this dialog (not a guessed
     * window) -- mirrors AyuGram's AyuLocalDatabaseUtils.getMinAndMaxForDialog.
     * Returns null if nothing is archived for this dialog at all. Blocking read,
     * same threading note as getDeletedMessagesInRangeSync below.
     */
    private int[] getMinAndMaxArchivedSync(long dialogId) {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        int[] holder = new int[]{0, 0};
        getQueue().postRunnable(() -> {
            ensureOpen();
            if (database != null) {
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized("SELECT MIN(mid), MAX(mid) FROM deleted_messages WHERE uid = ?", dialogId);
                    if (cursor.next()) {
                        holder[0] = cursor.intValue(0);
                        holder[1] = cursor.intValue(1);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
            countDownLatch.countDown();
        });
        try {
            countDownLatch.await();
        } catch (Exception e) {
            FileLog.e(e);
        }
        // SELECT MIN/MAX with no matching rows still returns one row of NULLs,
        // which this SQLite wrapper reads back as 0 -- mids are always positive,
        // so holder[0] > 0 is a safe "found nothing" check either way.
        return holder[0] > 0 ? holder : null;
    }

    /**
     * Blocking read (see class javadoc on threading above) of every locally-archived
     * deleted message in [minId, maxId] for this dialog, minus anything already present
     * in the freshly-loaded page (existingIds), resolved into ready-to-render
     * MessageObjects with antiDeleted set. Same users/chats resolution approach as
     * getMessagesForScroll below, just scoped to an ID range instead of a scroll offset.
     */
    private ArrayList<MessageObject> getDeletedMessagesInRangeSync(long dialogId, int minId, int maxId, HashSet<Integer> existingIds) {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        ArrayList<TLRPC.Message>[] holder = new ArrayList[]{new ArrayList<>()};
        getQueue().postRunnable(() -> {
            ensureOpen();
            if (database != null) {
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized("SELECT data FROM deleted_messages WHERE uid = ? AND mid >= ? AND mid <= ?", dialogId, minId, maxId);
                    ArrayList<TLRPC.Message> messages = new ArrayList<>();
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            data.reuse();
                            if (message != null && !existingIds.contains(message.id)) {
                                messages.add(message);
                            }
                        }
                    }
                    holder[0] = messages;
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
            countDownLatch.countDown();
        });
        try {
            countDownLatch.await();
        } catch (Exception e) {
            FileLog.e(e);
        }
        ArrayList<TLRPC.Message> messages = holder[0];
        ArrayList<MessageObject> result = new ArrayList<>();
        if (messages.isEmpty()) {
            return result;
        }
        ArrayList<Long> usersToLoad = new ArrayList<>();
        ArrayList<Long> chatsToLoad = new ArrayList<>();
        for (int a = 0, N = messages.size(); a < N; a++) {
            MessagesStorage.addUsersAndChatsFromMessage(messages.get(a), usersToLoad, chatsToLoad, null);
        }
        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        try {
            if (!usersToLoad.isEmpty()) {
                getMessagesStorage().getUsersInternal(usersToLoad, users);
            }
            if (!chatsToLoad.isEmpty()) {
                getMessagesStorage().getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        LongSparseArray<TLRPC.User> usersDict = new LongSparseArray<>();
        for (int a = 0, N = users.size(); a < N; a++) {
            usersDict.put(users.get(a).id, users.get(a));
        }
        LongSparseArray<TLRPC.Chat> chatsDict = new LongSparseArray<>();
        for (int a = 0, N = chats.size(); a < N; a++) {
            chatsDict.put(chats.get(a).id, chats.get(a));
        }
        if (!users.isEmpty()) {
            getMessagesController().putUsers(users, true);
        }
        if (!chats.isEmpty()) {
            getMessagesController().putChats(chats, true);
        }
        for (int a = 0, N = messages.size(); a < N; a++) {
            TLRPC.Message message = messages.get(a);
            message.antiDeleted = true;
            try {
                result.add(new MessageObject(currentAccount, message, usersDict, chatsDict, false, true));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return result;
    }

    // ----------------- storage -----------------

    private synchronized DispatchQueue getQueue() {
        if (queue == null) {
            queue = new DispatchQueue("antiDeleteQueue_" + currentAccount);
        }
        return queue;
    }

    /**
     * Must be called from getQueue()'s thread only.
     */
    private void ensureOpen() {
        if (database != null || triedToOpen) {
            return;
        }
        triedToOpen = true;
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        File dbFile = new File(filesDir, "antidelete.db");
        try {
            SQLiteDatabase db = new SQLiteDatabase(dbFile.getPath());
            db.executeFast("CREATE TABLE IF NOT EXISTS deleted_messages(uid INTEGER, mid INTEGER, date INTEGER, data BLOB, PRIMARY KEY(uid, mid));").stepThis().dispose();
            database = db;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Called from MessagesStorage right before a message row is removed from
     * messages_v2, with the same serialized data blob already read from that
     * row -- no re-serialization. Safe to call unconditionally per deleted
     * message; this method does its own gating and gracefully no-ops (reusing
     * the buffer) when the feature is off, the sender is a bot with saving
     * disabled for bots, or this exact deletion was self-initiated.
     *
     * Must be called from MessagesStorage's storage thread, same as every
     * other read of `data` in that loop.
     */
    public void saveDeletedMessage(long dialogId, int messageId, NativeByteBuffer data) {
    if (data == null) {
        return;
    }
    if (!shouldSaveDeletedMessage(dialogId) || isDeleteMessagePermitted(dialogId, messageId)) {
        data.reuse();
        return;
    }
    // data is backed by the caller's cursor row and won't survive past this call
    // (MessagesStorage disposes that cursor right after this loop), so copy it now,
    // synchronously, before handing the actual DB write off to our own queue.
    NativeByteBuffer tempCopy = null;
    try {
        tempCopy = new NativeByteBuffer(data.limit());
        tempCopy.writeBytes(data);
        tempCopy.position(0);
    } catch (Exception e) {
        FileLog.e(e);
    } finally {
        data.reuse();
    }
    if (tempCopy == null) {
        return;
    }
    final NativeByteBuffer copy = tempCopy;
    int date = getConnectionsManager().getCurrentTime();
    getQueue().postRunnable(() -> {
        ensureOpen();
        if (database == null) {
            copy.reuse();
            return;
        }
        try {
            SQLitePreparedStatement state = database.executeFast("INSERT OR IGNORE INTO deleted_messages VALUES(?, ?, ?, ?)");
            state.bindLong(1, dialogId);
            state.bindInteger(2, messageId);
            state.bindInteger(3, date);
            state.bindByteBuffer(4, copy);
            state.step();
            state.dispose();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            copy.reuse();
        }
    });
}

    /**
     * Phase 2/3 will build a log screen on top of this; left here since the
     * read side is trivial once the write side exists.
     */
    /**
     * Loads a page of deleted messages for the viewer screen, already resolved into
     * ready-to-render MessageObjects (users/chats/reply resolved, date-header entries
     * NOT included -- the adapter builds those, same as MessageObject.createDateArray
     * does for admin log entries), newest first.
     *
     * We don't have a separate searchable text column (unlike AyuGram's schema --
     * see the note on saveDeletedMessage), so a non-empty searchQuery scans this
     * dialog's rows and filters after deserializing rather than in SQL. Fine for a
     * personal log; not meant for huge volumes.
     */
    public void getMessagesForScroll(long dialogId, int offset, int count, String searchQuery, Utilities.Callback<ArrayList<MessageObject>> callback) {
        getQueue().postRunnable(() -> {
            ensureOpen();
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            if (database != null) {
                SQLiteCursor cursor = null;
                try {
                    if (TextUtils.isEmpty(searchQuery)) {
                        cursor = database.queryFinalized("SELECT data FROM deleted_messages WHERE uid = ? ORDER BY date DESC LIMIT ? OFFSET ?", dialogId, count, offset);
                    } else {
                        cursor = database.queryFinalized("SELECT data FROM deleted_messages WHERE uid = ? ORDER BY date DESC", dialogId);
                    }
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            data.reuse();
                            if (message != null) {
                                messages.add(message);
                            }
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }

            if (!TextUtils.isEmpty(searchQuery)) {
                String query = searchQuery.toLowerCase();
                ArrayList<TLRPC.Message> filtered = new ArrayList<>();
                for (TLRPC.Message message : messages) {
                    if (message.message != null && message.message.toLowerCase().contains(query)) {
                        filtered.add(message);
                    }
                }
                int from = Math.min(offset, filtered.size());
                int to = Math.min(offset + count, filtered.size());
                messages = new ArrayList<>(filtered.subList(from, to));
            }

            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            for (TLRPC.Message message : messages) {
                MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
            }
            ArrayList<TLRPC.User> users = new ArrayList<>();
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            try {
                if (!usersToLoad.isEmpty()) {
                    getMessagesStorage().getUsersInternal(usersToLoad, users);
                }
                if (!chatsToLoad.isEmpty()) {
                    getMessagesStorage().getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            LongSparseArray<TLRPC.User> usersDict = new LongSparseArray<>();
            for (TLRPC.User user : users) {
                usersDict.put(user.id, user);
            }
            LongSparseArray<TLRPC.Chat> chatsDict = new LongSparseArray<>();
            for (TLRPC.Chat chat : chats) {
                chatsDict.put(chat.id, chat);
            }

            ArrayList<MessageObject> result = new ArrayList<>();
            for (TLRPC.Message message : messages) {
                MessageObject messageObject = new MessageObject(currentAccount, message, usersDict, chatsDict, false, true);
                // reuse the existing transient "deleted" flag (stock Telegram already
                // uses this to mean "gone, don't treat as live") instead of adding a
                // new one -- the viewer cell rendering doesn't currently branch on it,
                // but it's a correct, honest marker for anything that inspects it later.
                messageObject.deleted = true;
                if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0) {
                    TLRPC.Message replyMessage = getMessagesStorage().getMessage(dialogId, message.reply_to.reply_to_msg_id);
                    if (replyMessage != null) {
                        messageObject.replyMessageObject = new MessageObject(currentAccount, replyMessage, usersDict, chatsDict, false, true);
                    }
                }
                result.add(messageObject);
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    /**
     * Permanently removes one row from the log (the viewer screen's per-row delete
     * action). This is unrelated to permitDeleteMessage -- that's about not logging
     * a deletion in the first place; this is the user pruning an already-logged entry.
     */
    public void deleteDeletedMessage(long dialogId, int messageId, Runnable onDone) {
        getQueue().postRunnable(() -> {
            ensureOpen();
            if (database != null) {
                try {
                    SQLitePreparedStatement state = database.executeFast("DELETE FROM deleted_messages WHERE uid = ? AND mid = ?");
                    state.bindLong(1, dialogId);
                    state.bindInteger(2, messageId);
                    state.step();
                    state.dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }
}
