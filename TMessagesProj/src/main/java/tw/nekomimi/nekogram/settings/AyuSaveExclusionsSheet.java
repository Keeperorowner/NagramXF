package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuSaveExclusions;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Picker for one save-exclusion list: whole chat categories on top, then every chat with a checkbox
 * that adds or removes it from the list. The search field filters the chats by title.
 */
public class AyuSaveExclusionsSheet extends BottomSheet {

    private static final int TYPE_CATEGORY = 0;
    private static final int TYPE_CHAT = 1;
    private static final int TYPE_INFO = 2;

    private final boolean always;
    private final Runnable onChanged;
    private final int currentAccount = UserConfig.selectedAccount;

    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<TLRPC.Dialog> allDialogs = new ArrayList<>();
    private final Adapter adapter;

    private static final class Entry {
        final int type;
        final int flag;
        final long dialogId;
        final CharSequence title;

        Entry(int type, int flag, long dialogId, CharSequence title) {
            this.type = type;
            this.flag = flag;
            this.dialogId = dialogId;
            this.title = title;
        }
    }

    public AyuSaveExclusionsSheet(Context context, boolean always, Runnable onChanged) {
        super(context, false, null);
        this.always = always;
        this.onChanged = onChanged;
        setApplyBottomPadding(false);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

        android.widget.TextView title = new android.widget.TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setText(getString(always ? R.string.AyuSaveExclusionsAlwaysList : R.string.AyuSaveExclusionsNeverList));
        title.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(16), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        EditText search = new EditText(context);
        search.setHint(getString(R.string.Search));
        search.setSingleLine();
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        search.setBackground(null);
        search.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        search.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        search.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(8), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                rebuild(s == null ? "" : s.toString());
            }
        });
        container.addView(search, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        RecyclerListView list = new RecyclerListView(context, resourcesProvider);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter = new Adapter());
        list.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        container.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        containerView = container;
        loadDialogs();
        rebuild("");
    }

    private void loadDialogs() {
        allDialogs.clear();
        ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(currentAccount).getDialogs(0);
        if (dialogs != null) {
            allDialogs.addAll(dialogs);
        }
    }

    private void rebuild(String query) {
        entries.clear();
        entries.add(new Entry(TYPE_INFO, 0, 0, getString(R.string.AyuSaveExclusionsPickInfo)));
        for (int flag : new int[]{
                AyuSaveExclusions.FLAG_BOT_CHAT,
                AyuSaveExclusions.FLAG_BOT_USER,
                AyuSaveExclusions.FLAG_USER,
                AyuSaveExclusions.FLAG_GROUPS,
                AyuSaveExclusions.FLAG_CHANNELS,
                AyuSaveExclusions.FLAG_SECRET}) {
            entries.add(new Entry(TYPE_CATEGORY, flag, 0, categoryTitle(flag)));
        }
        final String lower = query == null ? "" : query.trim().toLowerCase();
        for (TLRPC.Dialog dialog : allDialogs) {
            CharSequence name = dialogTitle(dialog.id);
            if (name == null || name.length() == 0) {
                continue;
            }
            if (!TextUtils.isEmpty(lower) && !name.toString().toLowerCase().contains(lower)) {
                continue;
            }
            entries.add(new Entry(TYPE_CHAT, 0, dialog.id, name));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private CharSequence categoryTitle(int flag) {
        switch (flag) {
            case AyuSaveExclusions.FLAG_BOT_CHAT:
                return getString(R.string.AyuSaveExclusionsBotChats);
            case AyuSaveExclusions.FLAG_BOT_USER:
                return getString(R.string.AyuSaveExclusionsBotUsers);
            case AyuSaveExclusions.FLAG_USER:
                return getString(R.string.AyuSaveExclusionsUsers);
            case AyuSaveExclusions.FLAG_GROUPS:
                return getString(R.string.AyuSaveExclusionsGroups);
            case AyuSaveExclusions.FLAG_CHANNELS:
                return getString(R.string.AyuSaveExclusionsChannels);
            default:
                return getString(R.string.AyuSaveExclusionsSecret);
        }
    }

    private CharSequence dialogTitle(long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat chat = MessagesController.getInstance(currentAccount).getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            return chat != null ? getString(R.string.AyuSaveExclusionsSecret) : null;
        }
        if (dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : null;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        return chat != null ? chat.title : null;
    }

    private void toggleChat(long dialogId) {
        Set<String> ids = AyuSaveExclusions.getIds(always);
        boolean inList = ids.contains(String.valueOf(dialogId));
        AyuSaveExclusions.setChatSavingType(dialogId,
                inList ? AyuSaveExclusions.TYPE_DEFAULT : (always ? AyuSaveExclusions.TYPE_ALWAYS : AyuSaveExclusions.TYPE_NEVER));
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void toggleCategory(int flag) {
        AyuSaveExclusions.setFlag(always, flag, (AyuSaveExclusions.getFlags(always) & flag) == 0);
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return entries.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_INFO) {
                view = new TextInfoPrivacyCell(getContext());
            } else {
                view = new TextCheckCell(getContext(), resourcesProvider);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Entry entry = entries.get(position);
            if (entry.type == TYPE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(entry.title);
                return;
            }
            TextCheckCell cell = (TextCheckCell) holder.itemView;
            if (entry.type == TYPE_CATEGORY) {
                cell.setTextAndCheck(entry.title, (AyuSaveExclusions.getFlags(always) & entry.flag) != 0, true);
                cell.setOnClickListener(v -> {
                    toggleCategory(entry.flag);
                    notifyItemChanged(position);
                });
            } else {
                boolean checked = AyuSaveExclusions.getIds(always).contains(String.valueOf(entry.dialogId));
                cell.setTextAndCheck(entry.title, checked, true);
                cell.setOnClickListener(v -> {
                    toggleChat(entry.dialogId);
                    notifyItemChanged(position);
                });
            }
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
