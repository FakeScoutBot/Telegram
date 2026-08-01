package org.telegram.messenger.antidelete;

import android.util.LongSparseArray;

import java.util.ArrayList;

public class AntiDeleteState {

    private static final LongSparseArray<ArrayList<Integer>> messageDeletePermitted = new LongSparseArray<>();

    public static void permitDeleteMessage(long dialogId, int messageId) {
        synchronized (messageDeletePermitted) {
            ArrayList<Integer> list = messageDeletePermitted.get(dialogId);
            if (list == null) {
                list = new ArrayList<>();
                messageDeletePermitted.put(dialogId, list);
            }
            if (!list.contains(messageId)) {
                list.add(messageId);
            }
        }
    }

    public static boolean isDeleteMessagePermitted(long dialogId, int messageId) {
        synchronized (messageDeletePermitted) {
            ArrayList<Integer> list = messageDeletePermitted.get(dialogId);
            return list != null && list.contains(messageId);
        }
    }

    public static void messageDeleted(long dialogId, int messageId) {
        synchronized (messageDeletePermitted) {
            ArrayList<Integer> list = messageDeletePermitted.get(dialogId);
            if (list != null) {
                list.remove((Integer) messageId);
                if (list.isEmpty()) {
                    messageDeletePermitted.remove(dialogId);
                }
            }
        }
    }
}
