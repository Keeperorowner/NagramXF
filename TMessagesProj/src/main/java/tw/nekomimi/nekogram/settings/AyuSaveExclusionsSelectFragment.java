package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.FragmentSpansContainer;
import org.telegram.ui.Components.GroupCreateSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.StickerEmptyView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Picker for one save-exclusion list.
 *
 * <p>Picked chat kinds are shown as removable chips above the search field, the list offers the
 * remaining kinds and every chat, and the choice is written only when the done button is pressed.
 */
@SuppressLint("RtlHardcoded")
public class AyuSaveExclusionsSelectFragment extends BaseFragment implements View.OnClickListener {

    private static final int[] CATEGORY_FLAGS = {
            AyuSaveExclusions.FLAG_USER,
            AyuSaveExclusions.FLAG_SECRET,
            AyuSaveExclusions.FLAG_BOT_USER,
            AyuSaveExclusions.FLAG_PRIVATE_GROUP,
            AyuSaveExclusions.FLAG_PUBLIC_GROUP,
            AyuSaveExclusions.FLAG_PRIVATE_CHANNEL,
            AyuSaveExclusions.FLAG_PUBLIC_CHANNEL,
            AyuSaveExclusions.FLAG_BOT_CHAT,
    };

    private static final int[] CATEGORY_AVATARS = {
            AvatarDrawable.AVATAR_TYPE_FILTER_CONTACTS,
            AvatarDrawable.AVATAR_TYPE_ANONYMOUS,
            AvatarDrawable.AVATAR_TYPE_FILTER_BOTS,
            AvatarDrawable.AVATAR_TYPE_FILTER_GROUPS,
            AvatarDrawable.AVATAR_TYPE_FILTER_GROUPS,
            AvatarDrawable.AVATAR_TYPE_FILTER_CHANNELS,
            AvatarDrawable.AVATAR_TYPE_FILTER_CHANNELS,
            AvatarDrawable.AVATAR_TYPE_FILTER_BOTS,
    };

    private static final int[] CATEGORY_COLORS = {5, 4, 2, 3, 1, 2, 5, 4};

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
    private final HashMap<Integer, GroupCreateSpan> categorySpans = new HashMap<>();
    private int flags;
    private int initialFlags;
    private String query = "";
    private GroupCreateSpan currentDeletingSpan;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private FragmentSearchField searchField;
    private FragmentSpansContainer spansContainer;
    private StickerEmptyView emptyView;
    private View headerDivider;

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
        // A recreated view builds new spans, so drop references to the previous container.
        categorySpans.clear();
        currentDeletingSpan = null;
        spansContainer = null;
        headerDivider = null;
        query = "";
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
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        searchField = new FragmentSearchField(context, resourceProvider);
        searchField.setHintText(getString(R.string.Search), false);
        searchField.setCloseButtonVisible(false);
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
        column.addView(searchField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, DialogsActivity.SEARCH_FIELD_HEIGHT));

        spansContainer = new FragmentSpansContainer(context, currentAccount) {
            @Override
            public void removeSpan(GroupCreateSpan span) {
                super.removeSpan(span);
                updateSpansVisibility();
            }

            @Override
            public void addSpan(GroupCreateSpan span) {
                super.addSpan(span);
                updateSpansVisibility();
            }
        };
        spansContainer.setVisibility(View.GONE);
        column.addView(spansContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        headerDivider = new View(context);
        headerDivider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourceProvider));
        column.addView(headerDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        FrameLayout listContainer = new FrameLayout(context);
        column.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context, resourceProvider);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(72));
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new StickerEmptyView(context, new FlickerLoadingView(context), StickerEmptyView.STICKER_TYPE_SEARCH, resourceProvider);
        emptyView.title.setText(getString(R.string.NoResult));
        emptyView.showProgress(false, false);
        emptyView.setVisibility(View.GONE);
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        FragmentFloatingButton doneButton = new FragmentFloatingButton(context, resourceProvider);
        doneButton.imageView.setImageResource(R.drawable.floating_check);
        doneButton.setContentDescription(getString(R.string.Done));
        doneButton.setOnClickListener(v -> onDonePressed());
        root.addView(doneButton, FragmentFloatingButton.createDefaultLayoutParams());

        syncSpans();
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
        if (!TextUtils.isEmpty(query)) {
            query = "";
            if (searchField != null) {
                searchField.editText.setText("");
                searchField.editText.clearFocus();
                AndroidUtilities.hideKeyboard(searchField.editText);
            }
            rebuild();
            return true;
        }
        if (flags != initialFlags || !ids.equals(initialIds)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
            builder.setMessage(getString(R.string.DiscardChanges));
            builder.setPositiveButton(getString(R.string.Discard), (dialog, which) -> finishFragment());
            builder.setNegativeButton(getString(R.string.Cancel), null);
            showDialog(builder.create());
            return true;
        }
        return super.onBackPressed(invoked);
    }

    private void updateSpansVisibility() {
        if (spansContainer == null) {
            return;
        }
        boolean hasSpans = !categorySpans.isEmpty();
        spansContainer.setVisibility(hasSpans ? View.VISIBLE : View.GONE);
        if (headerDivider != null) {
            headerDivider.setVisibility(hasSpans ? View.VISIBLE : View.GONE);
        }
    }

    private void syncSpans() {
        if (spansContainer == null || getParentActivity() == null) {
            return;
        }
        for (int i = 0; i < CATEGORY_FLAGS.length; i++) {
            int flag = CATEGORY_FLAGS[i];
            boolean selected = (flags & flag) != 0;
            GroupCreateSpan span = categorySpans.get(flag);
            if (selected && span == null) {
                span = GroupCreateSpan.createCategorySpan(getParentActivity(), categoryTitle(flag),
                        CATEGORY_AVATARS[i], Theme.keys_avatar_background[CATEGORY_COLORS[i]], resourceProvider);
                span.setOnClickListener(this);
                categorySpans.put(flag, span);
                spansContainer.addSpan(span);
            } else if (!selected && span != null) {
                categorySpans.remove(flag);
                spansContainer.removeSpan(span);
            }
        }
        spansContainer.endAnimation();
        updateSpansVisibility();
    }

    @Override
    public void onClick(View v) {
        if (!(v instanceof GroupCreateSpan)) {
            return;
        }
        GroupCreateSpan span = (GroupCreateSpan) v;
        if (span.isDeleting()) {
            currentDeletingSpan = null;
            Integer flag = findFlag(span);
            if (flag != null) {
                categorySpans.remove(flag);
                flags &= ~flag;
            }
            spansContainer.removeSpan(span);
            rebuild();
        } else {
            if (currentDeletingSpan != null) {
                currentDeletingSpan.cancelDeleteAnimation();
            }
            currentDeletingSpan = span;
            span.startDeleteAnimation();
        }
    }

    private Integer findFlag(GroupCreateSpan span) {
        for (HashMap.Entry<Integer, GroupCreateSpan> entry : categorySpans.entrySet()) {
            if (entry.getValue() == span) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void toggleCategory(int flag) {
        flags ^= flag;
        syncSpans();
        rebuild();
    }

    private void rebuild() {
        items.clear();
        final String lower = query == null ? "" : query.trim().toLowerCase();
        final boolean searching = !TextUtils.isEmpty(lower);
        int chatCount = 0;
        if (!searching) {
            items.add(new Item(TYPE_HEADER, 0, 0, getString(R.string.AyuSaveExclusionsCategories)));
            for (int flag : CATEGORY_FLAGS) {
                items.add(new Item(TYPE_CATEGORY, flag, 0, categoryTitle(flag)));
            }
        }
        for (TLRPC.Dialog dialog : dialogs) {
            CharSequence name = dialogTitle(dialog.id);
            if (name == null || name.length() == 0) {
                continue;
            }
            if (searching && !name.toString().toLowerCase().contains(lower)) {
                continue;
            }
            if (!searching && chatCount == 0) {
                items.add(new Item(TYPE_HEADER, 0, 0, getString(R.string.Chats)));
            }
            chatCount++;
            items.add(new Item(TYPE_CHAT, 0, dialog.id, name));
        }

        if (emptyView != null) {
            boolean empty = searching && chatCount == 0;
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            listView.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
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
            if (chat == null) {
                return null;
            }
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(chat.admin_id);
            CharSequence name = user != null ? UserObject.getUserName(user) : getString(R.string.AyuSaveExclusionsSecret);
            return withSecretLock(name);
        }
        if (dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : null;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        return chat != null ? chat.title : null;
    }

    /** Prefixes a lock icon, matching how secret chats are marked in chat lists. */
    private CharSequence withSecretLock(CharSequence text) {
        Context context = getParentActivity();
        if (context == null) {
            return text;
        }
        try {
            SpannableStringBuilder builder = new SpannableStringBuilder("  ");
            builder.append(text);
            Drawable lock = context.getResources().getDrawable(R.drawable.baseline_lock_14);
            if (lock != null) {
                lock = lock.mutate();
                lock.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider), PorterDuff.Mode.SRC_IN));
                lock.setBounds(0, 0, lock.getIntrinsicWidth(), lock.getIntrinsicHeight());
                builder.setSpan(new ImageSpan(lock, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return builder;
        } catch (Exception e) {
            return text;
        }
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
            } else {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                if (item.type == TYPE_CATEGORY) {
                    cell.setTextAndCheck(item.title, (flags & item.flag) != 0, true);
                    cell.setOnClickListener(v -> toggleCategory(item.flag));
                } else {
                    cell.setTextAndCheck(item.title, ids.contains(String.valueOf(item.dialogId)), true);
                    cell.setOnClickListener(v -> toggleChat(item.dialogId));
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
