package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuSaveExclusions;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Picker for one save-exclusion list.
 *
 * <p>Picked chat kinds are shown as removable chips above the search field; the list offers the
 * remaining kinds, then every chat with a checkmark that adds or removes it from the list. Nothing
 * is written until the done button is pressed.
 */
@SuppressLint("RtlHardcoded")
public class AyuSaveExclusionsSelectFragment extends BaseFragment {

    private static final int[] CATEGORY_FLAGS = {
            AyuSaveExclusions.FLAG_USER,
            AyuSaveExclusions.FLAG_BOT_USER,
            AyuSaveExclusions.FLAG_BOT_CHAT,
            AyuSaveExclusions.FLAG_PRIVATE_GROUP,
            AyuSaveExclusions.FLAG_PUBLIC_GROUP,
            AyuSaveExclusions.FLAG_PRIVATE_CHANNEL,
            AyuSaveExclusions.FLAG_PUBLIC_CHANNEL,
            AyuSaveExclusions.FLAG_SECRET,
    };

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CATEGORY = 1;
    private static final int TYPE_CHAT = 2;

    private final boolean always;
    private final Runnable onChanged;
    private final int currentAccount = UserConfig.selectedAccount;

    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
    private final HashSet<String> ids = new HashSet<>();
    private final HashSet<String> initialIds = new HashSet<>();
    private int flags;
    private int initialFlags;
    private String query = "";

    private RecyclerListView listView;
    private ListAdapter adapter;
    private FragmentSearchField searchField;
    private LinearLayout chipsContainer;
    private HorizontalScrollView chipsScroll;

    private static final class Item {
        final int type;
        final int flag;
        final long dialogId;
        final CharSequence title;

        Item(int type, int flag, long dialogId, CharSequence title) {
            this.type = type;
            this.flag = flag;
            this.dialogId = dialogId;
            this.title = title;
        }
    }

    public AyuSaveExclusionsSelectFragment(boolean always, Runnable onChanged) {
        super();
        this.always = always;
        this.onChanged = onChanged;
        ids.addAll(AyuSaveExclusions.getIds(always));
        initialIds.addAll(ids);
        flags = AyuSaveExclusions.getFlags(always);
        initialFlags = flags;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(always ? R.string.AyuSaveExclusionsAlwaysList : R.string.AyuSaveExclusionsNeverList));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    onBackPressed(true);
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipToPadding(false);
        chipsScroll.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(8), AndroidUtilities.dp(13), AndroidUtilities.dp(8));
        chipsContainer = new LinearLayout(context);
        chipsContainer.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chipsContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        chipsScroll.setVisibility(View.GONE);
        content.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        searchField = new FragmentSearchField(context, resourceProvider);
        searchField.setHintText(getString(R.string.Search), false);
        searchField.editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString();
                rebuild();
            }
        });
        content.addView(searchField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        listView = new RecyclerListView(context, resourceProvider);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(72));
        listView.setClipToPadding(false);
        content.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        FragmentFloatingButton doneButton = new FragmentFloatingButton(context, resourceProvider);
        doneButton.imageView.setImageResource(R.drawable.floating_check);
        doneButton.setContentDescription(getString(R.string.Done));
        doneButton.setOnClickListener(v -> onDonePressed());
        root.addView(doneButton, FragmentFloatingButton.createDefaultLayoutParams());

        loadDialogs();
        rebuild();

        fragmentView = root;
        return root;
    }

    private void loadDialogs() {
        dialogs.clear();
        ArrayList<TLRPC.Dialog> folderDialogs = MessagesController.getInstance(currentAccount).getDialogs(0);
        if (folderDialogs != null) {
            dialogs.addAll(folderDialogs);
        }
    }

    private void onDonePressed() {
        AyuSaveExclusions.setIds(always, new HashSet<>(ids));
        AyuSaveExclusions.setFlags(always, flags);
        if (onChanged != null) {
            onChanged.run();
        }
        finishFragment();
    }

    private boolean hasChanges() {
        return flags != initialFlags || !ids.equals(initialIds);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (hasChanges()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
            builder.setMessage(getString(R.string.DiscardChanges));
            builder.setPositiveButton(getString(R.string.Discard), (dialog, which) -> finishFragment());
            builder.setNegativeButton(getString(R.string.Cancel), null);
            showDialog(builder.create());
            return true;
        }
        return super.onBackPressed(invoked);
    }

    private void rebuild() {
        items.clear();
        boolean hasFreeCategory = false;
        for (int flag : CATEGORY_FLAGS) {
            if ((flags & flag) == 0) {
                hasFreeCategory = true;
                break;
            }
        }
        if (hasFreeCategory) {
            items.add(new Item(TYPE_HEADER, 0, 0, getString(R.string.AyuSaveExclusionsCategories)));
            for (int flag : CATEGORY_FLAGS) {
                if ((flags & flag) == 0) {
                    items.add(new Item(TYPE_CATEGORY, flag, 0, categoryTitle(flag)));
                }
            }
        }
        items.add(new Item(TYPE_HEADER, 0, 0, getString(R.string.Chats)));
        final String lower = query == null ? "" : query.trim().toLowerCase();
        for (TLRPC.Dialog dialog : dialogs) {
            CharSequence name = dialogTitle(dialog.id);
            if (name == null || name.length() == 0) {
                continue;
            }
            if (!TextUtils.isEmpty(lower) && !name.toString().toLowerCase().contains(lower)) {
                continue;
            }
            items.add(new Item(TYPE_CHAT, 0, dialog.id, name));
        }
        updateChips();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateChips() {
        if (chipsContainer == null) {
            return;
        }
        chipsContainer.removeAllViews();
        int count = 0;
        for (int flag : CATEGORY_FLAGS) {
            if ((flags & flag) == 0) {
                continue;
            }
            View chip = createChip(flag);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, AndroidUtilities.dp(32));
            if (count > 0) {
                params.leftMargin = AndroidUtilities.dp(6);
            }
            chipsContainer.addView(chip, params);
            count++;
        }
        chipsScroll.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    private View createChip(int flag) {
        Context context = getParentActivity();
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(8), 0);
        chip.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16),
                Theme.getColor(Theme.key_groupcreate_spanBackground, resourceProvider)));
        TextView text = new TextView(context);
        text.setTextSize(14);
        text.setTextColor(Theme.getColor(Theme.key_groupcreate_spanText, resourceProvider));
        text.setText(categoryTitle(flag));
        chip.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.delete);
        close.setColorFilter(Theme.getColor(Theme.key_groupcreate_spanDelete, resourceProvider));
        chip.addView(close, LayoutHelper.createLinear(14, 14, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        chip.setOnClickListener(v -> {
            flags &= ~flag;
            rebuild();
        });
        return chip;
    }

    private CharSequence categoryTitle(int flag) {
        switch (flag) {
            case AyuSaveExclusions.FLAG_USER:
                return getString(R.string.AyuSaveExclusionsUsers);
            case AyuSaveExclusions.FLAG_BOT_USER:
                return getString(R.string.AyuSaveExclusionsBotUsers);
            case AyuSaveExclusions.FLAG_BOT_CHAT:
                return getString(R.string.AyuSaveExclusionsBotsInGroups);
            case AyuSaveExclusions.FLAG_PRIVATE_GROUP:
                return getString(R.string.AyuSaveExclusionsPrivateGroups);
            case AyuSaveExclusions.FLAG_PUBLIC_GROUP:
                return getString(R.string.AyuSaveExclusionsPublicGroups);
            case AyuSaveExclusions.FLAG_PRIVATE_CHANNEL:
                return getString(R.string.AyuSaveExclusionsPrivateChannels);
            case AyuSaveExclusions.FLAG_PUBLIC_CHANNEL:
                return getString(R.string.AyuSaveExclusionsPublicChannels);
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
        String key = String.valueOf(dialogId);
        if (!ids.remove(key)) {
            ids.add(key);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new GraySectionCell(mContext, resourceProvider);
            } else if (viewType == TYPE_CATEGORY) {
                view = new TextSettingsCell(mContext, resourceProvider);
            } else {
                view = new TextCheckCell(mContext, resourceProvider);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            if (item.type == TYPE_HEADER) {
                ((GraySectionCell) holder.itemView).setText(item.title);
            } else if (item.type == TYPE_CATEGORY) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setText(item.title, true);
                cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
                cell.setOnClickListener(v -> {
                    flags |= item.flag;
                    rebuild();
                });
            } else {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck(item.title, ids.contains(String.valueOf(item.dialogId)), true);
                cell.setOnClickListener(v -> toggleChat(item.dialogId));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
