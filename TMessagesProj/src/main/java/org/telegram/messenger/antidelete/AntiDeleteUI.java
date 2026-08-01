package org.telegram.messenger.antidelete;

public class AntiDeleteUI {

    public static CharSequence getDeletedIcon() {
        AntiDeleteConfig.ensureLoaded();
        switch (AntiDeleteConfig.deletedIcon) {
            case 0:
                return "";
            case 2:
                return "✖";
            case 3:
                return "🙈";
            case 1:
            default:
                return "🗑";
        }
    }

    public static int getDeletedIconWidth() {
        return 14;
    }
}
