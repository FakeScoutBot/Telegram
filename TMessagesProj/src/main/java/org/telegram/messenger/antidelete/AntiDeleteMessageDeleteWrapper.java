package org.telegram.messenger.antidelete;

public class AntiDeleteMessageDeleteWrapper {

    private final int currentAccount;
    private boolean didDeleteAnything;

    AntiDeleteMessageDeleteWrapper(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    /** "Genuinely remove our saved copy" branch -- see AntiDeleteState. */
    public void deleteMessage(long dialogId, int messageId) {
        DeletedMessageService.getInstance(currentAccount).deleteMessage(dialogId, messageId);
        AntiDeleteState.messageDeleted(dialogId, messageId);
        didDeleteAnything = true;
    }

    public void commit() {
        if (didDeleteAnything) {
            DeletedDialogService.getInstance(currentAccount).refreshPreviews();
        }
    }
}
