package org.telegram.ui;

import android.content.Context;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AntiDeleteController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Read-only viewer for messages captured by AntiDeleteController for one dialog.
 *
 * Modeled on ChannelAdminLogActivity's approach of reusing the real ChatMessageCell
 * for rendering (so entries look like actual chat bubbles instead of a bespoke
 * list cell) -- but deliberately much leaner than that 4500-line screen: no chat
 * wallpaper/background, no bottom overlay panel, no media/photo-viewer wiring,
 * and no search action-bar item yet. AntiDeleteController.getMessagesForScroll
 * already accepts a search query, so adding a search UI later is cheap; it's
 * left out of this pass to keep the diff reviewable.
 */
public class DeletedMessagesActivity extends BaseFragment {

    private static final int PAGE_SIZE = 25;

    private final long dialogId;

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter listAdapter;
    private TextView emptyView;

    private final ArrayList<MessageObject> items = new ArrayList<>();
    private String lastDateKey;
    private boolean loading;
    private boolean endReached;

    public DeletedMessagesActivity(long dialogId) {
        super();
        this.dialogId = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        loadMore();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Deleted Messages");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        listAdapter = new ListAdapter(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText("Nothing here yet. Messages someone else deletes in this chat will show up here.");
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        emptyView.setGravity(Gravity.CENTER);
        int pad = AndroidUtilities.dp(32);
        emptyView.setPadding(pad, pad, pad, pad);
        emptyView.setVisibility(View.GONE);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        listView.setOnItemLongClickListener((view, position) -> {
            if (view instanceof ChatMessageCell) {
                showDeleteConfirm(((ChatMessageCell) view).getMessageObject());
                return true;
            }
            return false;
        });

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (!loading && !endReached && layoutManager.findLastVisibleItemPosition() >= items.size() - 5) {
                    loadMore();
                }
            }
        });

        return fragmentView;
    }

    private void loadMore() {
        if (loading || endReached) {
            return;
        }
        loading = true;
        // offset is by message count, not counting the date-header rows we insert
        // client-side, since those aren't in the underlying store.
        int offset = 0;
        for (MessageObject messageObject : items) {
            if (messageObject.type != MessageObject.TYPE_DATE) {
                offset++;
            }
        }
        AntiDeleteController.getInstance(currentAccount).getMessagesForScroll(dialogId, offset, PAGE_SIZE, null, messages -> {
            loading = false;
            if (messages.isEmpty()) {
                endReached = true;
            }
            appendWithDateHeaders(messages);
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            if (emptyView != null) {
                emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void appendWithDateHeaders(ArrayList<MessageObject> messages) {
        for (MessageObject messageObject : messages) {
            String dateKey = LocaleController.formatDateChat(messageObject.messageOwner.date);
            if (!dateKey.equals(lastDateKey)) {
                // same technique MessageObject.createDateArray uses for admin-log date
                // separators -- a synthetic TL_message wrapped as a MessageObject.
                TLRPC.TL_message dateMsg = new TLRPC.TL_message();
                dateMsg.message = dateKey;
                dateMsg.id = 0;
                dateMsg.date = messageObject.messageOwner.date;
                MessageObject dateObj = new MessageObject(currentAccount, dateMsg, false, false);
                dateObj.type = MessageObject.TYPE_DATE;
                dateObj.contentType = 1;
                dateObj.isDateObject = true;
                items.add(dateObj);
                lastDateKey = dateKey;
            }
            items.add(messageObject);
        }
    }

    private void showDeleteConfirm(MessageObject messageObject) {
        if (messageObject == null || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Delete entry");
        builder.setMessage("Remove this message from the deleted-messages log? This can't be undone.");
        builder.setPositiveButton("Delete", (dialog, which) -> AntiDeleteController.getInstance(currentAccount).deleteDeletedMessage(dialogId, messageObject.getId(), () -> {
            items.remove(messageObject);
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            if (emptyView != null) {
                emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            }
        }));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type == MessageObject.TYPE_DATE ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                view = new ChatActionCell(mContext);
            } else {
                ChatMessageCell cell = new ChatMessageCell(mContext, currentAccount);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                    @Override
                    public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) {
                        if (url instanceof URLSpan) {
                            Browser.openUrl(getParentActivity(), ((URLSpan) url).getURL());
                        }
                    }

                    @Override
                    public void didLongPress(ChatMessageCell cell, float x, float y) {
                        showDeleteConfirm(cell.getMessageObject());
                    }
                });
                view = cell;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            MessageObject messageObject = items.get(position);
            if (holder.itemView instanceof ChatActionCell) {
                ((ChatActionCell) holder.itemView).setMessageObject(messageObject);
            } else if (holder.itemView instanceof ChatMessageCell) {
                boolean topNear = position > 0 && items.get(position - 1).type != MessageObject.TYPE_DATE;
                boolean bottomNear = position < items.size() - 1 && items.get(position + 1).type != MessageObject.TYPE_DATE;
                ((ChatMessageCell) holder.itemView).setMessageObject(messageObject, null, bottomNear, topNear, false);
            }
        }

       // @Override
        //public boolean isEnabled(RecyclerView.ViewHolder holder) {
           // return false;
        //}
    }
}
