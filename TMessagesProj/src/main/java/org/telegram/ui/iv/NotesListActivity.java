package org.telegram.ui.iv;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Fully local notes list. Everything here reads/writes NotesStorage only -
 * there is no account, no server call, no message sending involved.
 */
public class NotesListActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private EmptyTextProgressView emptyView;
    private final ArrayList<NotesStorage.NoteMeta> notes = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Notes");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    presentFragment(new NoteEditActivity());
                }
            }
        });
        actionBar.createMenu().addItem(1, R.drawable.filled_fab_compose_32);

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        emptyView = new EmptyTextProgressView(context, null, getResourceProvider());
        emptyView.setText("No notes yet. Tap the pencil to write your first one.");
        emptyView.showTextView();
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);

        listView = new RecyclerListView(context);
        listView.setEmptyView(emptyView);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= notes.size()) return;
            presentFragment(new NoteReadActivity(notes.get(position).id));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= notes.size()) return false;
            final NotesStorage.NoteMeta note = notes.get(position);
            ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> {
                    new AlertDialog.Builder(getContext(), getResourceProvider())
                        .setTitle(note.title)
                        .setMessage("Delete this note? This can't be undone.")
                        .setPositiveButton(LocaleController.getString(R.string.Delete), (di, w) -> {
                            NotesStorage.deleteNote(note.id);
                            reloadNotes();
                        })
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .makeRed(AlertDialog.BUTTON_POSITIVE)
                        .show();
                })
                .show();
            return true;
        });

        reloadNotes();

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadNotes();
    }

    private void reloadNotes() {
        notes.clear();
        notes.addAll(NotesStorage.listNotes());
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private static class NoteCell extends FrameLayout {
        private final TextView titleView;
        private final TextView previewView;
        private final TextView dateView;

        public NoteCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 16, 10, 16, 10));

            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            column.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleView = new TextView(context);
            titleView.setTextSize(16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            topRow.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            dateView = new TextView(context);
            dateView.setTextSize(12);
            dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            topRow.addView(dateView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            previewView = new TextView(context);
            previewView.setTextSize(14);
            previewView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            previewView.setMaxLines(2);
            previewView.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0, 0));
        }

        public void setNote(NotesStorage.NoteMeta note) {
            titleView.setText(TextUtils.isEmpty(note.title) ? "Untitled note" : note.title);
            previewView.setText(note.preview);
            previewView.setVisibility(TextUtils.isEmpty(note.preview) ? GONE : VISIBLE);
            dateView.setText(LocaleController.formatShortDate(note.updatedAt / 1000L));
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        public ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return notes.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new NoteCell(context));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((NoteCell) holder.itemView).setNote(notes.get(position));
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        return themeDescriptions;
    }
}
