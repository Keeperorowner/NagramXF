package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.ui.MarkdownPreview;
import com.exteragram.messenger.ai.ui.components.HistoryMessageCell;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/**
 * Saved conversation history: a switch for keeping history, one entry per message that expands on
 * tap, and a button that clears everything. Long pressing an entry copies or removes its turn.
 */
public class AiHistoryActivity extends BasePreferencesActivity {

    private static final int SAVE_HISTORY = 1;
    private static final int CLEAR_HISTORY = 2;

    private final ArrayList<Entry> entries = new ArrayList<>();
    private int lastEntryId = 100;

    private static final class Entry {
        final int id;
        final Message message;
        CharSequence content;
        boolean expanded;

        Entry(int id, Message message) {
            this.id = id;
            this.message = message;
            this.content = message.content();
        }
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public CharSequence getTitle() {
        return LocaleController.getString(R.string.AIChatSavedConversations);
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        setHeaderTitle(null);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateHeaderTitle();
            }
        });
        updateHeaderTitle();
        return view;
    }

    private void setHeaderTitle(CharSequence title) {
        if (simpleActionBar != null) {
            simpleActionBar.setTitle(title);
        } else {
            actionBar.setTitle(title);
        }
    }

    private void updateHeaderTitle() {
        if (listView == null || listView.layoutManager == null) {
            return;
        }
        final boolean topVisible = listView.layoutManager.findFirstVisibleItemPosition() <= 0;
        setHeaderTitle(topVisible ? null : getTitle());
    }

    @Override
    public boolean onFragmentCreate() {
        final boolean result = super.onFragmentCreate();
        final ArrayList<Message> history = AiConfig.getConversationHistory();
        for (int i = 0; i < history.size(); i++) {
            entries.add(new Entry(lastEntryId++, history.get(i)));
        }
        formatContents();
        return result;
    }

    private void formatContents() {
        if (entries.isEmpty()) {
            return;
        }
        final ArrayList<Entry> snapshot = new ArrayList<>(entries);
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<CharSequence> formatted = new ArrayList<>(snapshot.size());
            for (Entry entry : snapshot) {
                formatted.add(MarkdownPreview.format(entry.message.content()));
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (getContext() == null) {
                    return;
                }
                for (int i = 0; i < snapshot.size() && i < formatted.size(); i++) {
                    snapshot.get(i).content = formatted.get(i);
                }
                if (listView != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asRippleCheck(SAVE_HISTORY, LocaleController.getString(R.string.AIChatMessageHistory)).setChecked(AiConfig.saveHistoryConfig.Bool()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.AIChatHistoryInfo)));
        if (entries.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.formatPluralString("messages", entries.size())));
        for (Entry entry : entries) {
            items.add(HistoryMessageCell.Factory.asHistoryCell(entry.id, entry.message, entry.content, entry.expanded));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(CLEAR_HISTORY, R.drawable.msg_delete, LocaleController.getString(R.string.ClearHistory)).red());
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == SAVE_HISTORY) {
            final boolean enabled = AiConfig.saveHistoryConfig.toggleConfigBool();
            AiConfig.syncFields();
            item.setChecked(enabled);
            refreshRows();
            return;
        }
        if (item.id == CLEAR_HISTORY) {
            AiController.clearHistory(this, getResourceProvider(), true, () -> {
                entries.clear();
                refreshRows();
            });
            return;
        }
        final Entry entry = findEntry(item.id);
        if (entry != null) {
            entry.expanded = !entry.expanded;
            refreshRows();
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof Message)) {
            return false;
        }
        final Message message = (Message) item.object;
        final ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
            if (AndroidUtilities.addToClipboard(message.content())) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
        });
        options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> deleteTurn(item.id));
        options.setGravity(LocaleController.isRTL ? 3 : 5);
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.show();
        return true;
    }

    private void refreshRows() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private Entry findEntry(int id) {
        for (Entry entry : entries) {
            if (entry.id == id) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Removes a whole exchange when the message belongs to a user and assistant pair, otherwise
     * removes the single entry, then stores the remaining messages back.
     */
    private void deleteTurn(int id) {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            return;
        }
        int from = "assistant".equals(entries.get(index).message.role()) ? index - 1 : index;
        int to = from + 1;
        if (from < 0 || to >= entries.size()
                || "assistant".equals(entries.get(from).message.role())
                || !"assistant".equals(entries.get(to).message.role())) {
            from = index;
            to = index;
        }
        for (int i = to; i >= from; i--) {
            entries.remove(i);
        }
        final ArrayList<Message> remaining = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            remaining.add(entry.message);
        }
        AiConfig.saveConversationHistory(remaining);
        refreshRows();
    }
}
