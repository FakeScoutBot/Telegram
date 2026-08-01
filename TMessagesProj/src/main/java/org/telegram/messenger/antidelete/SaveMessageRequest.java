package org.telegram.messenger.antidelete;

import org.telegram.tgnet.TLRPC;

public class SaveMessageRequest {
    public final int currentAccount;
    public final TLRPC.Message message;
    public final long dialogId;
    public final long topicId;
    public final int messageId;
    /** When WE noticed the delete -- not the original message date. */
    public final int requestCatchTime;

    public SaveMessageRequest(int currentAccount, TLRPC.Message message, long dialogId, long topicId) {
        this.currentAccount = currentAccount;
        this.message = message;
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = message != null ? message.id : 0;
        this.requestCatchTime = (int) (System.currentTimeMillis() / 1000);
    }
}
