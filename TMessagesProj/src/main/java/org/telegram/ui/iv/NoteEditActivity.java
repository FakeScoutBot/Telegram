package org.telegram.ui.iv;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSelectionHelper;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import static org.telegram.messenger.LocaleController.getString;

/**
 * Local-only rich note editor. Hosts the same RichEditorListView / RichEditorToolbar
 * used for composing rich Telegram messages, but there is no send/upload path at all -
 * "Save" just serializes the current blocks and writes them to NotesStorage on disk.
 * No MessagesController, SendMessagesHelper, or FileLoader network calls are involved.
 */
public class NoteEditActivity extends BaseFragment {

    private static final int[] STYLE_FLAGS = {
        RichTextStyle.BOLD, RichTextStyle.ITALIC, RichTextStyle.UNDERLINE, RichTextStyle.STRIKE,
        RichTextStyle.MONO, RichTextStyle.SUBSCRIPT, RichTextStyle.SUPERSCRIPT
    };

    private final long noteId;

    private FrameLayout container;
    private RichEditorListView listView;
    private RichEditorToolbar toolbar;
    private ItemOptions menu;
    private RichCommandSuggestions commandSuggestions;

    public NoteEditActivity() {
        this(0);
    }

    public NoteEditActivity(long noteId) {
        super();
        this.noteId = noteId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        container = new FrameLayout(context);
        setHasOwnBackground(true);
        container.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        container.setFocusable(true);
        container.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 26) {
            container.setDefaultFocusHighlightEnabled(false);
        }

        listView = new RichEditorListView(context, currentAccount, getResourceProvider(), new RichEditorListView.Delegate() {
            @Override public ItemOptions makeMenu(View anchor) { return menu = ItemOptions.makeOptions(NoteEditActivity.this, anchor); }
            @Override public void onSelectionChanged() { updateFormattingButtons(); updateToolbarBlockType(); }
            @Override public void onContentChanged() { updateSaveEnabled(); }
            @Override public void onHistoryChanged() { updateHistoryButtons(); }
            @Override public void onOpenAttachRequest(int a, int b) {
                BulletinFactory.of(NoteEditActivity.this).createSimpleBulletin(R.raw.chats_infotip, "Notes don't support media yet - text formatting only for now.").show();
            }
            @Override public void onOpenLocationRequest(BlockRow row) {
                BulletinFactory.of(NoteEditActivity.this).createSimpleBulletin(R.raw.chats_infotip, "Notes don't support locations yet.").show();
            }
            @Override public void onSlashSuggest(RichTextCell cell, String query) {
                if (commandSuggestions == null) {
                    commandSuggestions = new RichCommandSuggestions(anchor -> menu = ItemOptions.makeOptions(NoteEditActivity.this, anchor), getResourceProvider());
                }
                commandSuggestions.update(cell, query);
            }
            @Override public void onListScrolled(int dy) {}
            @Override public void onListLayoutUpdated() {}
            @Override public void makeEditTextFocusable(RichEditText et, boolean showKeyboard) {}
            @Override public void onReorderStart() { if (toolbar != null) toolbar.onReorderStart(); }
            @Override public boolean onReorderMove(float screenX, float screenY) { return toolbar != null && toolbar.onReorderMove(screenX, screenY); }
            @Override public void onReorderEnd() { if (toolbar != null) toolbar.onReorderEnd(); }
        });
        listView.setAllowTapAboveContent(false);
        container.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.FILL));
        container.addView(listView.getOverlayView(), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.FILL));

        if (noteId > 0) {
            TL_iv.RichMessage existing = NotesStorage.loadNote(noteId);
            if (existing != null) {
                listView.loadRichMessage(existing);
            } else {
                listView.seedEmptyArticle();
            }
        } else {
            listView.seedEmptyArticle();
        }
        listView.resetHistoryBaseline();

        toolbar = new RichEditorToolbar(context, toolbarDelegate);
        toolbar.setBackVisible(true);
        toolbar.setSendEditing(true);
        container.addView(toolbar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.FILL));

        updateHistoryButtons();
        updateToolbarBlockType();
        updateSaveEnabled();

        fragmentView = container;
        return fragmentView;
    }

    private final RichEditorToolbar.Delegate toolbarDelegate = new RichEditorToolbar.Delegate() {
        @Override public Theme.ResourcesProvider getResourcesProvider() { return NoteEditActivity.this.getResourceProvider(); }
        @Override public void onBack() { attemptClose(); }
        @Override public void onUndo() { listView.undo(); }
        @Override public void onRedo() { listView.redo(); }
        @Override public void onEmoji() {}
        @Override public void onAi() {}
        @Override public void onAttach() {
            BulletinFactory.of(NoteEditActivity.this).createSimpleBulletin(R.raw.chats_infotip, "Notes don't support media yet - text formatting only for now.").show();
        }
        @Override public void onSend() { saveNote(); }
        @Override public boolean onSendLongClick(View anchor) { return false; }
        @Override public void onBlockButton(int flag, View anchor) { onBlockButtonClicked(flag, anchor); }
        @Override public void onFormatting(int styleFlag) { listView.onFormattingClicked(styleFlag); }
        @Override public void onLink() { listView.onLinkClicked(); }
        @Override public void onDate() {}
        @Override public void onMath() { listView.onMathClicked(); }
        @Override public void onQuote() { listView.toggleQuoteOnSelection(); updateFormattingButtons(); }
        @Override public void onAiStyle() {}
    };

    private void onBlockButtonClicked(int flag, View v) {
        final BlockRow row = listView.findFocusedRow();
        switch (flag) {
            case RichEditorToolbar.BLOCK_TEXT:
                showTextTypeMenu(row, v);
                break;
            case RichEditorToolbar.BLOCK_LIST:
                showListMenu(row, v);
                break;
            case RichEditorToolbar.BLOCK_TABLE:
                listView.addBlock(RichTextCell.newEmptyTable(2, 2));
                break;
            case RichEditorToolbar.BLOCK_MATH: {
                final TL_iv.pageBlockMath math = row != null && row.block instanceof TL_iv.pageBlockMath ? (TL_iv.pageBlockMath) row.block : null;
                ChatAttachAlertRichLayout.showEditLatexSheet(getContext(), math == null || TextUtils.isEmpty(math.source) ? "" : math.source, source -> {
                    if (math != null) {
                        math.source = source;
                        listView.adapter.update(false);
                    } else {
                        final TL_iv.pageBlockMath newMath = new TL_iv.pageBlockMath();
                        newMath.source = source;
                        listView.addBlock(newMath);
                    }
                }, getResourceProvider());
                break;
            }
        }
    }

    private void showTextTypeMenu(BlockRow row, View v) {
        if (menu != null) menu.dismiss();
        final ItemOptions o = ItemOptions.makeOptions(this, v, true).dontFocus();
        final ItemOptions headers = o.makeSwipeback();

        headers.add(R.drawable.ic_ab_back, getString(R.string.Back), () -> o.closeSwipeback());
        headers.addGap();
        addHeadingItem(headers, row, new TL_iv.pageBlockHeading1(), R.drawable.iv_h1, getString(R.string.ArticleHeading1), SharedConfig.fontSize + 2);
        addHeadingItem(headers, row, new TL_iv.pageBlockHeading2(), R.drawable.iv_h2, getString(R.string.ArticleHeading2), SharedConfig.fontSize + 1);
        addHeadingItem(headers, row, new TL_iv.pageBlockHeading3(), R.drawable.iv_h3, getString(R.string.ArticleHeading3), SharedConfig.fontSize);
        addHeadingItem(headers, row, new TL_iv.pageBlockHeading4(), R.drawable.iv_h4, getString(R.string.ArticleHeading4), SharedConfig.fontSize - 1);
        addHeadingItem(headers, row, new TL_iv.pageBlockHeading5(), R.drawable.iv_h5, getString(R.string.ArticleHeading5), SharedConfig.fontSize - 2);
        addHeadingItem(headers, row, new TL_iv.pageBlockHeading6(), R.drawable.iv_h6, getString(R.string.ArticleHeading6), SharedConfig.fontSize - 3);

        o.addChecked(row != null && RichEditorListView.isHeading(row.block), R.drawable.iv_h1, getString(R.string.ArticleHeading), () -> o.openSwipeback(headers));
        o.addChecked(row != null && row.block instanceof TL_iv.pageBlockParagraph, R.drawable.iv_text, getString(R.string.ArticleText), () -> listView.turnIntoKeepList(row, new TL_iv.pageBlockParagraph()));
        o.addChecked(row != null && row.block instanceof TL_iv.pageBlockBlockquote, R.drawable.iv_quote, getString(R.string.ArticleQuote), () -> listView.turnInto(row, RichEditorListView.newBlockquote(), 0, 0, false, false));
        o.addChecked(row != null && row.block instanceof TL_iv.pageBlockPullquote, R.drawable.iv_pullquote, getString(R.string.ArticlePullquote), () -> listView.turnInto(row, RichEditorListView.newPullquote(), 0, 0, false, false));
        o.addChecked(row != null && row.block instanceof TL_iv.pageBlockPreformatted, R.drawable.iv_code, getString(R.string.ArticleCode), () -> listView.turnIntoKeepList(row, new TL_iv.pageBlockPreformatted()));
        menu = o.show();
    }

    private void addHeadingItem(ItemOptions headers, BlockRow row, TL_iv.PageBlock block, int icon, String label, int textSize) {
        headers.addChecked(row != null && row.block.getClass() == block.getClass(), icon, label, () -> { listView.turnIntoKeepList(row, block); });
        headers.getLast().textView.setTypeface(AndroidUtilities.getTypeface("fonts/mw_bold.ttf"));
        headers.getLast().textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize);
    }

    private void showListMenu(BlockRow row, View v) {
        if (menu != null) menu.dismiss();
        final ItemOptions o = ItemOptions.makeOptions(this, v).dontFocus();
        o
            .addChecked(row == null || !row.isInList(), R.drawable.field_carret_empty, getString(R.string.ArticleNone), () -> listView.turnIntoList(row, 0))
            .addChecked(row != null && row.isInList() && !row.isChecklist() && !row.isOrdered(), R.drawable.iv_list, getString(R.string.ArticleListBulleted), () -> listView.turnIntoList(row, 1))
            .addChecked(row != null && row.isInList() && !row.isChecklist() && row.isOrdered(), R.drawable.iv_ordered_list, getString(R.string.ArticleListNumbered), () -> listView.turnIntoList(row, 2))
            .addChecked(row != null && row.isInList() && row.isChecklist() && !row.isOrdered(), R.drawable.iv_todo, getString(R.string.ArticleListTodo), () -> listView.turnIntoList(row, 3))
            .addChecked(row != null && row.block instanceof TL_iv.pageBlockDetails, R.drawable.iv_details, getString(R.string.ArticleToggleBlock), listView::insertDetails);
        final boolean canIndent = listView.canIndentSelection();
        final boolean canOutdent = listView.canOutdentSelection();
        if (canIndent || canOutdent) {
            o.addGap();
            if (canIndent) o.add(R.drawable.iv_list_tab, getString(R.string.ArticleIndent), () -> { listView.indentSelection(false); o.dismiss(); });
            if (canOutdent) o.add(R.drawable.iv_list_untab, getString(R.string.ArticleOutdent), () -> { listView.indentSelection(true); o.dismiss(); });
        }
        menu = o.forceTop(true).show();
    }

    private void updateFormattingButtons() {
        final TextSelectionHelper.ArticleTextSelectionHelper textSelectionHelper = listView.getTextSelectionHelper();
        if (toolbar == null || textSelectionHelper == null || !textSelectionHelper.isInSelectionMode()) return;
        toolbar.setQuoteState(listView.isSelectionQuoted());
        final int sCell = textSelectionHelper.getStartCell();
        final int eCell = textSelectionHelper.getEndCell();
        final int sOff = textSelectionHelper.getStartOffset();
        final int eOff = textSelectionHelper.getEndOffset();
        final boolean valid = sCell >= 0 && eCell >= 0 && eCell >= sCell && eCell < listView.itemRows.size();
        int mask = 0;
        if (valid) {
            for (final int flag : STYLE_FLAGS) {
                if (listView.isStyleFullyApplied(flag, sCell, sOff, eCell, eOff)) mask |= flag;
            }
        }
        toolbar.setFormattingState(mask,
            valid && listView.isLinkApplied(sCell, sOff, eCell, eOff),
            valid && listView.isDateApplied(sCell, sOff, eCell, eOff),
            valid && sCell == eCell,
            !listView.isSelectionAllHeadings());
    }

    private void updateToolbarBlockType() {
        if (toolbar == null) return;
        final BlockRow row;
        final TextSelectionHelper.ArticleTextSelectionHelper textSelectionHelper = listView.getTextSelectionHelper();
        if (textSelectionHelper != null && textSelectionHelper.isInSelectionMode()) {
            final int sCell = textSelectionHelper.getStartCell();
            final int eCell = textSelectionHelper.getEndCell();
            row = sCell == eCell ? listView.rowForCell(sCell) : null;
        } else {
            row = listView.findFocusedRow();
        }
        int type;
        if (listView.findFocusedTableCell() != null) {
            type = RichEditorToolbar.BLOCK_TABLE;
        } else if (row == null) {
            type = 0;
        } else if (row.isChecklist() || row.isInList() || row.isOrdered() || row.block instanceof TL_iv.pageBlockDetails) {
            type = RichEditorToolbar.BLOCK_LIST;
        } else if (row.block instanceof TL_iv.pageBlockTable) {
            type = RichEditorToolbar.BLOCK_TABLE;
        } else if (row.block instanceof TL_iv.pageBlockMath) {
            type = RichEditorToolbar.BLOCK_MATH;
        } else if (RichEditorListView.isHeading(row.block)
            || row.block instanceof TL_iv.pageBlockParagraph
            || row.block instanceof TL_iv.pageBlockPreformatted
            || row.block instanceof TL_iv.pageBlockBlockquote
            || row.block instanceof TL_iv.pageBlockPullquote) {
            type = RichEditorToolbar.BLOCK_TEXT;
        } else {
            type = 0;
        }
        int icon = 0;
        if (row != null) {
            if (type == RichEditorToolbar.BLOCK_TEXT) {
                if (row.block instanceof TL_iv.pageBlockHeading1) icon = R.drawable.iv_h1;
                else if (row.block instanceof TL_iv.pageBlockHeading2) icon = R.drawable.iv_h2;
                else if (row.block instanceof TL_iv.pageBlockHeading3) icon = R.drawable.iv_h3;
                else if (row.block instanceof TL_iv.pageBlockHeading4) icon = R.drawable.iv_h4;
                else if (row.block instanceof TL_iv.pageBlockHeading5) icon = R.drawable.iv_h5;
                else if (row.block instanceof TL_iv.pageBlockHeading6) icon = R.drawable.iv_h6;
                else if (row.block instanceof TL_iv.pageBlockPreformatted) icon = R.drawable.iv_code;
                else if (row.block instanceof TL_iv.pageBlockBlockquote) icon = R.drawable.iv_quote;
                else if (row.block instanceof TL_iv.pageBlockPullquote) icon = R.drawable.iv_pullquote;
            } else if (type == RichEditorToolbar.BLOCK_LIST) {
                if (row.isChecklist()) icon = R.drawable.iv_todo;
                else if (row.isOrdered()) icon = R.drawable.iv_ordered_list;
            }
        }
        toolbar.setSelectedBlockType(type, icon);
    }

    private void updateHistoryButtons() {
        if (toolbar != null) {
            toolbar.setHistoryEnabled(listView.canUndo(), listView.canRedo());
        }
    }

    private void updateSaveEnabled() {
        if (toolbar != null) {
            toolbar.setSendEnabled(listView.hasAnyText());
        }
    }

    private void saveNote() {
        if (!listView.hasAnyText()) return;
        final ArrayList<TL_iv.PageBlock> blocks = listView.flattenRowsToBlocks();
        final TL_iv.RichMessage richMessage = new TL_iv.RichMessage();
        richMessage.blocks = blocks;
        String title = "";
        String preview = "";
        for (TL_iv.PageBlock b : blocks) {
            final String text = RichTextCell.readPlainText(b);
            if (TextUtils.isEmpty(text)) continue;
            if (TextUtils.isEmpty(title)) {
                title = text.length() > 60 ? text.substring(0, 60) : text;
            } else if (TextUtils.isEmpty(preview)) {
                preview = text.length() > 120 ? text.substring(0, 120) : text;
                break;
            }
        }
        if (TextUtils.isEmpty(title)) title = "Untitled note";
        try {
            NotesStorage.saveNote(noteId, richMessage, title, preview);
        } catch (Exception e) {
            FileLog.e(e);
        }
        finishFragment();
    }

    private void attemptClose() {
        if (listView.deselectIfAny()) return;
        if (listView.hasAnyText()) {
            new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(getString(R.string.ArticleSaveDraftTitle))
                .setMessage(getString(R.string.ArticleSaveDraftMessage))
                .setNegativeButton(getString(R.string.Delete), (di, w) -> finishFragment())
                .setPositiveButton(getString(R.string.Save), (di, w) -> saveNote())
                .makeRed(AlertDialog.BUTTON_NEGATIVE)
                .show();
            return;
        }
        finishFragment();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (listView != null && listView.deselectIfAny()) return false;
        attemptClose();
        return false;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        if (listView != null && listView.textSelectionHelper.isInSelectionMode()) return false;
        return super.isSwipeBackEnabled(event);
    }
}
