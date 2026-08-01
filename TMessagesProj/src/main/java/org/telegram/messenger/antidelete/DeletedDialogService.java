package org.telegram.messenger.antidelete;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional polish (spec section 10a). NOT wired into the dialogs-list UI by
 * this module, since that requires a hook in DialogsActivity/DialogCell (or
 * wherever this fork resolves a dialog's "last message" preview), which is
 * outside the "touch only MessagesController/ChatMessageCell(/MessageObject/
 * TLRPC)" scope the rest of this feature was constrained to. What's here is
 * the data side: an in-memory fallback map, kept fresh after every delete
 * batch, that the dialogs adapter *could* consult when a dialog's real
 * top_message doesn't resolve to anything -- e.g.
 *
 *   MessageObject fallback = DeletedDialogService.getInstance(account)
 *       .getLastMessage(dialogId);
 *
 * inside whatever method currently computes a DialogCell's preview text, as
 * one more additive, clearly-commented insertion, same as the other hooks.
 */
public class DeletedDialogService {

    private static final DeletedDialogService[] instances = new DeletedDialogService[UserConfig.MAX_ACCOUNT_COUNT];

    public static DeletedDialogService getInstance(int account) {
        DeletedDialogService local = instances[account];
        if (local == null) {
            synchronized (DeletedDialogService.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new DeletedDialogService(account);
                }
            }
        }
        return local;
    }

    private final int currentAccount;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LongSparseArray<MessageObject> lastMessages = new LongSparseArray<>();

    private DeletedDialogService(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public MessageObject getLastMessage(long dialogId) {
        synchronized (lastMessages) {
            return lastMessages.get(dialogId);
        }
    }

    /** Called from AntiDeleteMessageDeleteWrapper.commit() after a delete batch. */
    public void refreshPreviews() {
        executor.execute(() -> {
            long userId = UserConfig.getInstance(currentAccount).getClientUserId();
            DeletedMessageDao dao = AntiDeleteDatabase.getInstance(currentAccount).deletedMessageDao();
            List<Long> dialogIds = dao.getDialogsWithDeletedMessages(userId);
            List<DeletedMessage> all = dao.getAllForAccount(userId);
            for (long dialogId : dialogIds) {
                DeletedMessage newest = null;
                for (DeletedMessage m : all) {
                    if (m.dialogId == dialogId && (newest == null || m.date > newest.date)) {
                        newest = m;
                    }
                }
                if (newest == null) {
                    continue;
                }
                DeletedMessageFull full = dao.getMessage(userId, dialogId, newest.messageId);
                if (full == null) {
                    continue;
                }
                TLRPC.TL_message reconstructed = AntiDeleteMapper.reconstruct(currentAccount, full);
                MessageObject mo = new MessageObject(currentAccount, reconstructed, false, false);
                synchronized (lastMessages) {
                    lastMessages.put(dialogId, mo);
                }
            }
        });
    }
}
