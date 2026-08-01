package org.telegram.messenger.antidelete;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class AntiDeleteHook {

    public static final int NO_CHANGE = -100;

    public static int inject(int currentAccount,
                             ArrayList<MessageObject> targetList,
                             TLRPC.messages_Messages messagesRes,
                             int count,
                             long dialogId,
                             long topicId,
                             int mode,
                             boolean isCache) {
        AntiDeleteConfig.ensureLoaded();
        if (!needHook(dialogId, topicId) || !AntiDeleteConfig.saveDeletedMessageFor(currentAccount, dialogId)) {
            return NO_CHANGE;
        }
        if (mode != 0 && mode != 8) {
            return NO_CHANGE;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return NO_CHANGE;
        }

        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        for (int i = 0; i < targetList.size(); i++) {
            int id = targetList.get(i).getId();
            minId = Math.min(minId, id);
            maxId = Math.max(maxId, id);
        }
        if (minId == Integer.MAX_VALUE) {
            minId = Integer.MIN_VALUE;
            maxId = Integer.MAX_VALUE;
        }

        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        AntiDeleteController controller = AntiDeleteController.getInstance(currentAccount);
        List<AntiDeleteDatabase.DeletedMessageFull> deleted = topicId != 0
                ? controller.getDao().getMessagesForTopic(userId, dialogId, topicId, minId, maxId)
                : controller.getDao().getMessagesTopicless(userId, dialogId, minId, maxId);

        if (deleted == null || deleted.isEmpty()) {
            return NO_CHANGE;
        }

        HashSet<Integer> existingIds = new HashSet<>();
        for (int i = 0; i < targetList.size(); i++) {
            existingIds.add(targetList.get(i).getId());
        }

        ArrayList<MessageObject> toAdd = new ArrayList<>();
        for (int i = 0; i < deleted.size(); i++) {
            AntiDeleteDatabase.DeletedMessageFull full = deleted.get(i);
            if (full == null || full.message == null || existingIds.contains(full.message.messageId)) {
                continue;
            }
            TLRPC.Message restored = controller.getMapper().reconstruct(full);
            restored.dialog_id = dialogId;
            MessageObject mo = new MessageObject(currentAccount, restored, false, false);
            toAdd.add(mo);
        }

        if (toAdd.isEmpty()) {
            return NO_CHANGE;
        }

        targetList.addAll(toAdd);
        final boolean ascending = targetList.size() >= 2 && targetList.get(0).getId() < targetList.get(targetList.size() - 1).getId();
        targetList.sort((a, b) -> compare(a, b, ascending));

        if (messagesRes != null) {
            for (int i = 0; i < toAdd.size(); i++) {
                messagesRes.messages.add(toAdd.get(i).messageOwner);
            }
        }

        return count + toAdd.size();
    }

    private static int compare(MessageObject a, MessageObject b, boolean ascending) {
        boolean mixedSign = (a.getId() < 0 && b.getId() > 0) || (a.getId() > 0 && b.getId() < 0);
        int cmp;
        if (mixedSign) {
            cmp = Integer.compare(a.messageOwner.date, b.messageOwner.date);
        } else {
            cmp = Integer.compare(a.getId(), b.getId());
        }
        return ascending ? cmp : -cmp;
    }

    private static boolean needHook(long dialogId, long topicId) {
        return true;
    }
}
