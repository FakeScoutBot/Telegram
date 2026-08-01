package org.telegram.messenger.antidelete;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.List;

/**
 * Called from MessagesController.processLoadedMessages(), right after the
 * existing reply-linking loop and before the final runOnUIThread dispatch.
 * Injects any locally-saved deleted messages that fall inside the just-loaded
 * [minId, maxId] page into `objects` (the ArrayList<MessageObject> that becomes
 * chatActivity.messages), so they render as ghost bubbles.
 *
 * Sentinel: returns -100 to mean "did nothing, caller should keep using its
 * own `count`"; any other value is the caller's new count.
 */
public class AntiDeleteHook {

    public static final int NO_OP = -100;

    public static int inject(int currentAccount, List<MessageObject> objects, long dialogId, long topicId,
                              boolean isTopic, int mode) {
        if (!needHook(currentAccount, dialogId, topicId, mode, isTopic)) {
            return NO_OP;
        }

        boolean isEncryptedChat = DialogObject.isEncryptedDialog(dialogId);
        if (isEncryptedChat && !AntiDeleteConfig.getInstance(currentAccount).saveInSecretChats) {
            return NO_OP;
        }

        // The window actually loaded is exactly the id range of what's in
        // `objects` right now -- deriving it here instead of threading extra
        // min/max-id params through processLoadedMessages avoids duplicating
        // that method's own (more complex) pagination-direction bookkeeping,
        // and is always correct for "don't inject messages outside this page".
        int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
        for (MessageObject mo : objects) {
            int id = mo.getId();
            if (id <= 0) {
                continue; // locally-pending sends carry no page-boundary meaning
            }
            if (id < minId) minId = id;
            if (id > maxId) maxId = id;
        }
        if (minId > maxId) {
            return NO_OP; // page had nothing with a real server id (e.g. all-pending)
        }

        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        DeletedMessageDao dao = AntiDeleteDatabase.getInstance(currentAccount).deletedMessageDao();

        List<DeletedMessageFull> deleted = isTopic
            ? dao.getMessagesForTopic(userId, dialogId, topicId, minId, maxId)
            : dao.getMessagesTopicless(userId, dialogId, minId, maxId);

        if (deleted.isEmpty()) {
            return NO_OP;
        }

        java.util.ArrayList<MessageObject> toInsert = new java.util.ArrayList<>(deleted.size());
        for (DeletedMessageFull full : deleted) {
            TLRPC.TL_message reconstructed = AntiDeleteMapper.reconstruct(currentAccount, full);
            MessageObject mo = new MessageObject(currentAccount, reconstructed, true, false);
            toInsert.add(mo);
        }

        // Regular (non-secret) chat history in this codebase loads newest-first
        // by default (descending); secret chats load oldest-first. If this
        // fork's pagination differs for a particular load_type, pass the real
        // direction through instead of this fixed assumption.
        boolean effectiveAscending = isEncryptedChat;

        merge(toInsert, objects, isEncryptedChat, effectiveAscending);

        resolveReplyChains(currentAccount, objects, dialogId);

        return objects.size();
    }

    /**
     * Skip-conditions mirroring Ayugram's needHook(): don't inject into
     * scheduled/quick-reply pseudo-loads, comment threads, or non-default modes.
     * `mode == 0` is ChatActivity.MODE_DEFAULT; adjust the saved-messages mode
     * constant to match this fork's actual ChatActivity.MODE_* values if you
     * want ghosts in Saved Messages too.
     */
    private static boolean needHook(int currentAccount, long dialogId, long topicId, int mode, boolean isTopic) {
        if (mode != 0) {
            return false;
        }
        return AntiDeleteConfig.getInstance(currentAccount).shouldSaveDeletedMessagesFor(dialogId, false);
    }

    /**
     * Faithful port of the merge algorithm described in the spec (reconstructed
     * from Ayugram's decompiled merge()). Sorts by message id, falling back to
     * date only when comparing a locally-pending (negative id) message against
     * a server-confirmed (positive id) one -- negative ids are arbitrary
     * per-client counters with no chronological meaning relative to real ids.
     */
    static void merge(List<MessageObject> deletedToInsert, List<MessageObject> targetList, boolean isEncryptedChat, boolean effectiveAscending) {
        for (MessageObject toInsert : deletedToInsert) {
            if (targetList.isEmpty()) {
                targetList.add(toInsert);
                continue;
            }
            boolean inserted = false;
            for (int i = 0; i < targetList.size(); i++) {
                MessageObject cursor = targetList.get(i);
                boolean idSignMismatch = !isEncryptedChat &&
                    ((toInsert.getId() < 0 && cursor.getId() > 0) || (toInsert.getId() > 0 && cursor.getId() < 0));
                boolean shouldInsertHere = idSignMismatch
                    ? (effectiveAscending ? toInsert.messageOwner.date < cursor.messageOwner.date
                                          : toInsert.messageOwner.date > cursor.messageOwner.date)
                    : (effectiveAscending ? toInsert.getId() < cursor.getId()
                                          : toInsert.getId() > cursor.getId());
                if (shouldInsertHere) {
                    targetList.add(i, toInsert);
                    inserted = true;
                    break;
                }
            }
            if (!inserted) {
                MessageObject boundary = targetList.get(effectiveAscending ? targetList.size() - 1 : 0);
                boolean idSignMismatch = !isEncryptedChat &&
                    ((toInsert.getId() < 0 && boundary.getId() > 0) || (toInsert.getId() > 0 && boundary.getId() < 0));
                boolean appendAtEnd = idSignMismatch
                    ? (effectiveAscending ? toInsert.messageOwner.date >= boundary.messageOwner.date
                                          : toInsert.messageOwner.date <= boundary.messageOwner.date)
                    : (effectiveAscending ? toInsert.getId() >= boundary.getId()
                                          : toInsert.getId() <= boundary.getId());
                if (appendAtEnd) {
                    targetList.add(toInsert);
                } else {
                    targetList.add(0, toInsert);
                }
            }
        }
    }

    /**
     * For any message whose reply_to is set but replyMessage isn't resolved
     * (including a ghost replying to another ghost), try: (a) already in the
     * merged list, (b) our own deleted-messages table. This intentionally does
     * NOT fall back to MessagesController.dialogMessagesByIds / MessagesStorage
     * here to keep this hook self-contained to the antidelete package; wiring
     * those two extra fallbacks in is a small additional call from
     * MessagesController's own hook site, which already has both in scope.
     */
    private static void resolveReplyChains(int currentAccount, List<MessageObject> objects, long dialogId) {
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        DeletedMessageDao dao = AntiDeleteDatabase.getInstance(currentAccount).deletedMessageDao();
        LongSparseArray<MessageObject> byId = new LongSparseArray<>();
        for (MessageObject mo : objects) {
            byId.put(mo.getId(), mo);
        }
        for (MessageObject mo : objects) {
            if (mo.messageOwner.reply_to == null || mo.replyMessageObject != null) {
                continue;
            }
            int replyToId = mo.messageOwner.reply_to.reply_to_msg_id;
            MessageObject resolved = byId.get(replyToId);
            if (resolved == null) {
                DeletedMessageFull full = dao.getMessage(userId, dialogId, replyToId);
                if (full != null) {
                    TLRPC.TL_message reconstructed = AntiDeleteMapper.reconstruct(currentAccount, full);
                    resolved = new MessageObject(currentAccount, reconstructed, true, false);
                }
            }
            if (resolved != null) {
                mo.replyMessageObject = resolved;
                mo.messageOwner.replyMessage = resolved.messageOwner;
            }
        }
    }
}
