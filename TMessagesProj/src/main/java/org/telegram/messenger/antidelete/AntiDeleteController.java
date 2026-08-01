package org.telegram.messenger.antidelete;

import android.util.SparseArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;

public class AntiDeleteController {

    public static class AntiDeleteMessageDeleteWrapper {
        private final AntiDeleteController controller;
        private boolean changed;

        private AntiDeleteMessageDeleteWrapper(AntiDeleteController controller) {
            this.controller = controller;
        }

        public void deleteMessage(long dialogId, int messageId) {
            changed = true;
            controller.deleteMessage(dialogId, messageId);
        }

        public void commit() {
            if (changed) {
                controller.refreshDeletedDialogs();
            }
        }
    }

    private static final SparseArray<AntiDeleteController> INSTANCES = new SparseArray<>();

    public static synchronized AntiDeleteController getInstance(int accountId) {
        AntiDeleteController c = INSTANCES.get(accountId);
        if (c == null) {
            c = new AntiDeleteController(accountId);
            INSTANCES.put(accountId, c);
        }
        return c;
    }

    private final int accountId;
    private final long userId;
    private final DispatchQueue queue;
    private final AntiDeleteDatabase dao;
    private final AntiDeleteMapper mapper = new AntiDeleteMapper();

    private AntiDeleteController(int accountId) {
        this.accountId = accountId;
        this.userId = UserConfig.getInstance(accountId).getClientUserId();
        this.queue = new DispatchQueue("anti_delete_queue_" + accountId);
        this.dao = AntiDeleteDatabase.getInstance(ApplicationLoader.applicationContext, accountId);
    }

    public AntiDeleteMessageDeleteWrapper wrapDelete() {
        return new AntiDeleteMessageDeleteWrapper(this);
    }

    public void onMessageDeleted(SaveMessageRequest req) {
        if (!validateForSave(req)) {
            return;
        }
        queue.postRunnable(() -> {
            if (dao.exists(userId, req.dialogId, req.topicId, req.messageId)) {
                return;
            }
            saveDeletedMessage(req);
        });
    }

    public void deleteMessage(long dialogId, int messageId) {
        queue.postRunnable(() -> {
            AntiDeleteDatabase.DeletedMessageFull existing = dao.getMessage(userId, dialogId, messageId);
            dao.delete(userId, dialogId, messageId);
            if (existing != null && existing.message != null && existing.message.mediaPath != null) {
                try {
                    File file = new File(existing.message.mediaPath);
                    if (file.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            AntiDeleteState.messageDeleted(dialogId, messageId);
        });
    }

    public AntiDeleteDatabase getDao() {
        return dao;
    }

    public AntiDeleteMapper getMapper() {
        return mapper;
    }

    private boolean validateForSave(SaveMessageRequest req) {
        if (req == null || req.message == null) {
            return false;
        }
        if (!AntiDeleteConfig.saveDeletedMessageFor(accountId, req.dialogId)) {
            return false;
        }
        if (req.message.id < 0 && req.message.send_state == 1) {
            return false;
        }
        if (req.message instanceof TLRPC.TL_messageService || req.message instanceof TLRPC.TL_messageEmpty) {
            return false;
        }
        return true;
    }

    private void saveDeletedMessage(SaveMessageRequest req) {
        AntiDeleteDatabase.DeletedMessage entity = new AntiDeleteDatabase.DeletedMessage();
        mapper.map(req, entity);
        mapper.mapMedia(req, entity, true);
        long insertedId = dao.insert(entity);
        if (insertedId <= 0) {
            return;
        }
        dao.markDialogHasDeleted(userId, req.dialogId, req.requestCatchTime);
        if (req.message.reactions != null && req.message.reactions.results != null) {
            for (int i = 0; i < req.message.reactions.results.size(); i++) {
                TLRPC.ReactionCount rc = req.message.reactions.results.get(i);
                dao.insertReaction(mapper.toReactionEntity(insertedId, rc));
            }
        }
    }

    private void refreshDeletedDialogs() {
        // reserved for dialogs preview refresh hook
    }

    public MessageObject tryLoadMessageObject(long dialogId, long topicId, int messageId) {
        AntiDeleteDatabase.DeletedMessageFull full = topicId == 0 ? dao.getMessage(userId, dialogId, messageId) : dao.getMessage(userId, dialogId, messageId);
        if (full == null || full.message == null) {
            return null;
        }
        TLRPC.Message restored = mapper.reconstruct(full);
        restored.dialog_id = dialogId;
        return new MessageObject(accountId, restored, false, false);
    }
}
