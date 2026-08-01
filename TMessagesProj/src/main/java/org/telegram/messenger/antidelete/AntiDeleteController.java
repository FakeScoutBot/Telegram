package org.telegram.messenger.antidelete;

import org.telegram.messenger.UserConfig;

/**
 * Thin per-account facade the MessagesController hook talks to. Exists mainly
 * to provide wrapDelete(), which batches a whole deleteMessages() call into a
 * single "refresh dialog-list previews" pass instead of one per message.
 */
public class AntiDeleteController {

    private static final AntiDeleteController[] instances = new AntiDeleteController[UserConfig.MAX_ACCOUNT_COUNT];

    public static AntiDeleteController getInstance(int account) {
        AntiDeleteController local = instances[account];
        if (local == null) {
            synchronized (AntiDeleteController.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new AntiDeleteController(account);
                }
            }
        }
        return local;
    }

    private final int currentAccount;

    private AntiDeleteController(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public void onMessageDeleted(SaveMessageRequest req) {
        DeletedMessageService.getInstance(currentAccount).onMessageDeleted(req);
    }

    public AntiDeleteMessageDeleteWrapper wrapDelete() {
        return new AntiDeleteMessageDeleteWrapper(currentAccount);
    }
}
