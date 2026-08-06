package org.telegram.ui.iv;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Plain, local, read-only view of a note - styled like a notes app page, not a chat
 * bubble. Renders the note's blocks with Telegram's real formatting spans (bold,
 * italic, mono, quotes, code highlighting, lists) via RichMessageConvert, so the
 * content looks exactly as formatted, without pulling in any chat-message UI.
 * Tapping the pencil opens the same rich editor used to write it.
 */
public class NoteReadActivity extends BaseFragment {

    private final long noteId;
    private TextView textView;

    public NoteReadActivity(long noteId) {
        super();
        this.noteId = noteId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Note");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    presentFragment(new NoteEditActivity(noteId));
                }
            }
        });
        actionBar.createMenu().addItem(1, R.drawable.msg_edit);

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        ScrollView scrollView = new ScrollView(context);
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        textView = new TextView(context);
        textView.setTextSize(16);
        textView.setLineSpacing(AndroidUtilities.dp(4), 1f);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextIsSelectable(true);
        textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        scrollView.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        loadContent();

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadContent();
    }

    private void loadContent() {
        final TL_iv.RichMessage richMessage = NotesStorage.loadNote(noteId);
        if (richMessage == null || richMessage.blocks == null || richMessage.blocks.isEmpty()) {
            textView.setText("This note is empty.");
            actionBar.setTitle("Note");
            return;
        }
        final CharSequence rendered = RichMessageConvert.blocksToCharSequence(richMessage.blocks);
        textView.setText(TextUtils.isEmpty(rendered) ? "This note is empty." : rendered);

        String title = RichTextCell.readPlainText(richMessage.blocks.get(0));
        if (TextUtils.isEmpty(title)) {
            title = "Note";
        } else if (title.length() > 40) {
            title = title.substring(0, 40);
        }
        actionBar.setTitle(title);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        return themeDescriptions;
    }
}
