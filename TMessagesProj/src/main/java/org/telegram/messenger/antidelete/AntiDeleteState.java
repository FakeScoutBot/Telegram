package org.telegram.messenger.antidelete;

import androidx.collection.LongSparseArray;

import java.util.ArrayList;

/**
 * In-memory-only (NOT persisted) tracker for the one genuinely special case in this
 * feature: the user long-pressing an already-rendered ghost/anti-delete bubble and
 * choosing "Delete" a second time, meaning "purge my local recovery copy" rather than
 * "capture this as a newly-deleted message".
 *
 * Call permitDeleteMessage(dialogId, messageId) at the UI action site — immediately
 * before invoking MessagesController.deleteMessages(...) — but ONLY when the message
 * being deleted is itself already rendered with messageObject.messageOwner.antiDeleted
 * == true. For a normal, still-live message, do not call this.
 *
 * Cleared on process death; that's fine, since a fresh process has no ghost bubbles
 * rendered yet either.
 */
public class AntiDeleteState {

    private static final LongSparseArray<ArrayList<Integer>> messageDeletePermitted = new LongSparseArray<>();

    private AntiDeleteState() {
    }

    public static synchronized void permitDeleteMessage(long dialogId, int messageId) {
        ArrayList<Integer> list = messageDeletePermitted.get(dialogId);
        if (list == null) {
            list = new ArrayList<>();
            messageDeletePermitted.put(dialogId, list);
        }
        if (!list.contains(messageId)) {
            list.add(messageId);
        }
    }

    public static synchronized boolean isDeleteMessagePermitted(long dialogId, int messageId) {
        ArrayList<Integer> list = messageDeletePermitted.get(dialogId);
        return list != null && list.contains(messageId);
    }

    /**
     * Call once the deletion has actually gone through, to keep this list from
     * growing unbounded over a long session.
     */
    public static synchronized void messageDeleted(long dialogId, int messageId) {
        ArrayList<Integer> list = messageDeletePermitted.get(dialogId);
        if (list != null) {
            list.remove(Integer.valueOf(messageId));
            if (list.isEmpty()) {
                messageDeletePermitted.remove(dialogId);
            }
        }
    }
}
