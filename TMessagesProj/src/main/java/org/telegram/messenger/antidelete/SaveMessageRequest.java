package org.telegram.messenger.antidelete;

import org.telegram.tgnet.TLRPC;

public class SaveMessageRequest {
    public final int accountId;
    public final TLRPC.Message message;
    public final long dialogId;
    public final long topicId;
    public final int messageId;
    public final int requestCatchTime;

    public SaveMessageRequest(int accountId, TLRPC.Message message, long dialogId, long topicId) {
        this.accountId = accountId;
        this.message = message;
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = message != null ? message.id : 0;
        this.requestCatchTime = (int) (System.currentTimeMillis() / 1000L);
    }
}
