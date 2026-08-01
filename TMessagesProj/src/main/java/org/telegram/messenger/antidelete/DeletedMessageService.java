package org.telegram.messenger.antidelete;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles the actual Room writes for capturing a deleted message, off the
 * calling thread (deleteMessages() is called from multiple threads including
 * the UI thread, so this must never do DB/file IO synchronously there).
 */
public class DeletedMessageService {

    private static final DeletedMessageService[] instances = new DeletedMessageService[UserConfig.MAX_ACCOUNT_COUNT];

    public static DeletedMessageService getInstance(int account) {
        DeletedMessageService local = instances[account];
        if (local == null) {
            synchronized (DeletedMessageService.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new DeletedMessageService(account);
                }
            }
        }
        return local;
    }

    private final int currentAccount;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private DeletedMessageService(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public void onMessageDeleted(SaveMessageRequest req) {
        if (!validateForSave(req)) {
            return;
        }
        executor.execute(() -> {
            try {
                DeletedMessageDao dao = AntiDeleteDatabase.getInstance(currentAccount).deletedMessageDao();
                long userId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (dao.exists(userId, req.dialogId, req.topicId, req.messageId)) {
                    return; // idempotency guard -- e.g. a re-delivered delete update
                }
                saveDeletedMessage(req, dao, userId);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void saveDeletedMessage(SaveMessageRequest req, DeletedMessageDao dao, long userId) {
        DeletedMessage entity = AntiDeleteMapper.map(req);
        long insertedId = dao.insert(entity);

        if (req.message.reactions != null && req.message.reactions.results != null) {
            for (TLRPC.ReactionCount rc : req.message.reactions.results) {
                dao.insertReaction(toReactionEntity(insertedId, rc));
            }
        }

        DeletedDialog dd = new DeletedDialog();
        dd.userId = userId;
        dd.dialogId = req.dialogId;
        dd.lastDeletedMessageId = req.messageId;
        dd.lastDeletedDate = req.message.date;
        dao.upsertDeletedDialog(dd);
    }

    private DeletedMessageReaction toReactionEntity(long deletedMessageId, TLRPC.ReactionCount rc) {
        DeletedMessageReaction r = new DeletedMessageReaction();
        r.deletedMessageId = deletedMessageId;
        r.count = rc.count;
        r.selfSelected = rc.chosen;
        if (rc.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            r.isCustom = true;
            r.documentId = ((TLRPC.TL_reactionCustomEmoji) rc.reaction).document_id;
        } else if (rc.reaction instanceof TLRPC.TL_reactionPaid) {
            r.isPaid = true;
        } else if (rc.reaction instanceof TLRPC.TL_reactionEmoji) {
            r.emoticon = ((TLRPC.TL_reactionEmoji) rc.reaction).emoticon;
        }
        return r;
    }

    /**
     * Deletes our saved copy (Room row + the locally-copied media file), used
     * when the user re-deletes an already-rendered ghost bubble (see
     * AntiDeleteState / section 6.2).
     */
    public void deleteMessage(long dialogId, int messageId) {
        executor.execute(() -> {
            try {
                DeletedMessageDao dao = AntiDeleteDatabase.getInstance(currentAccount).deletedMessageDao();
                long userId = UserConfig.getInstance(currentAccount).getClientUserId();
                DeletedMessageFull full = dao.getMessage(userId, dialogId, messageId);
                if (full != null && full.message.mediaPath != null) {
                    File f = new File(full.message.mediaPath);
                    if (f.exists()) {
                        f.delete();
                    }
                }
                dao.delete(userId, dialogId, messageId);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Rejects: null messages, still-sending local messages (negative id +
     * send_state == 1), service/empty messages, and any per-chat exclusion.
     */
    private boolean validateForSave(SaveMessageRequest req) {
        TLRPC.Message m = req.message;
        if (m == null) {
            return false;
        }
        if (m instanceof TLRPC.TL_messageService || m instanceof TLRPC.TL_messageEmpty) {
            return false;
        }
        if (m.id < 0 && m.send_state == 1) {
            return false; // still sending, not a real deletion of committed content
        }
        boolean isEncrypted = org.telegram.messenger.DialogObject.isEncryptedDialog(req.dialogId);
        return AntiDeleteConfig.getInstance(currentAccount).shouldSaveDeletedMessagesFor(req.dialogId, isEncrypted);
    }
}
