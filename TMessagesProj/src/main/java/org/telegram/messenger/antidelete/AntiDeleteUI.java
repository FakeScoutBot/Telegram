package org.telegram.messenger.antidelete;

import android.text.SpannableStringBuilder;

import org.telegram.messenger.R;
import org.telegram.ui.Components.ColoredImageSpan;

/**
 * Builds the small icon shown next to the timestamp on a ghost/anti-delete
 * bubble, per AntiDeleteConfig.deletedIcon.
 *
 * Icon assets: msg_delete and msg_cancel already exist in this fork's
 * drawable set and are reused here (msg_delete for ICON_TRASH, msg_cancel for
 * ICON_CROSS). There is no dedicated "eye, crossed out" asset in this fork's
 * resources -- ICON_EYE_CROSSED falls back to msg_mute (a closed-eye-adjacent
 * "hidden" glyph) as a placeholder; swap in a real eye-crossed drawable if you
 * want that option to look right rather than approximate.
 */
public class AntiDeleteUI {

    private AntiDeleteUI() {
    }

    public static CharSequence getDeletedIcon(int currentAccount) {
        int iconStyle = AntiDeleteConfig.getInstance(currentAccount).deletedIcon;
        int drawableRes;
        switch (iconStyle) {
            case AntiDeleteConfig.ICON_TRASH:
                drawableRes = R.drawable.msg_delete;
                break;
            case AntiDeleteConfig.ICON_CROSS:
                drawableRes = R.drawable.msg_cancel;
                break;
            case AntiDeleteConfig.ICON_EYE_CROSSED:
                drawableRes = R.drawable.msg_mute; // see class doc: placeholder
                break;
            case AntiDeleteConfig.ICON_NONE:
            default:
                return "";
        }
        SpannableStringBuilder sb = new SpannableStringBuilder("d");
        ColoredImageSpan span = new ColoredImageSpan(drawableRes);
        int colorIndex = AntiDeleteConfig.getInstance(currentAccount).deletedIconColor;
        if (colorIndex != 0) {
            // 0 == theme default -- leave ColoredImageSpan's own paint-color
            // behavior in place; a real palette lookup for colorIndex > 0
            // belongs wherever this fork keeps its user-selectable accent
            // palette (Theme.keys_avatar_background or similar), not invented
            // here.
        }
        sb.setSpan(span, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    public static int getDeletedIconWidth() {
        return org.telegram.messenger.AndroidUtilities.dp(14);
    }
}
