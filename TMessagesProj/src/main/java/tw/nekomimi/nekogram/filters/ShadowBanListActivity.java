package tw.nekomimi.nekogram.filters;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import tw.nekomimi.nekogram.filters.popup.RegexUserFilterPopup;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.FiltersChatCell;

public class ShadowBanListActivity extends BaseNekoSettingsActivity {

    private final HashSet<Long> resolvingCustomFilteredUsers = new HashSet<>();
    private final HashSet<Long> resolvedCustomFilteredUsers = new HashSet<>();
    private final HashMap<Long, String> customFilteredUserDisplayCache = new HashMap<>();
    private ArrayList<Long> shadowBannedPeers = new ArrayList<>();
    private int peersStartRow;
    private int peersEndRow;
    private int emptyRow;

    @Override
    protected void updateRows() {
        super.updateRows();
        shadowBannedPeers = buildShadowBannedPeers();
        peersStartRow = rowCount;
        rowCount += shadowBannedPeers.size();
        peersEndRow = rowCount;
        emptyRow = shadowBannedPeers.isEmpty() ? rowCount++ : -1;
    }

    private ArrayList<Long> buildShadowBannedPeers() {
        ArrayList<Long> merged = new ArrayList<>();
        merged.addAll(AyuFilter.getBlockedChannelsList());
        merged.addAll(AyuFilter.getCustomFilteredUsersList());
        return merged;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        super.onResume();
        invalidateCustomFilteredUsersDisplayState();
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(0, R.drawable.msg_add).setContentDescription(getString(R.string.Add));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 0) {
                    presentFragment(createPickerActivity());
                }
            }
        });
        return view;
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position >= peersStartRow && position < peersEndRow) {
            int idx = position - peersStartRow;
            if (idx < 0 || idx >= shadowBannedPeers.size()) {
                return;
            }
            long dialogId = shadowBannedPeers.get(idx);
            RegexUserFilterPopup.show(this, view, x, y, getResourceProvider(), () -> removeShadowBannedPeer(dialogId));
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position >= peersStartRow && position < peersEndRow) {
            int idx = position - peersStartRow;
            if (idx >= 0 && idx < shadowBannedPeers.size()) {
                long dialogId = shadowBannedPeers.get(idx);
                if (dialogId > 0) {
                    presentFragment(ProfileActivity.of(dialogId));
                    return true;
                }
            }
        }
        return super.onItemLongClick(view, position, x, y);
    }

    private DialogsActivity createPickerActivity() {
        Bundle args = new Bundle();
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_SHADOW_BAN);
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", false);
        args.putBoolean("allowSwitchAccount", true);
        args.putBoolean("checkCanWrite", false);
        DialogsActivity dialogsActivity = new DialogsActivity(args);
        dialogsActivity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                long dialogId = ((MessagesStorage.TopicKey) dids.get(0)).dialogId;
                addShadowBannedPeer(dialogId);
                dialogsActivity.finishFragment();
            }
            return true;
        });
        return dialogsActivity;
    }

    private void addShadowBannedPeer(long dialogId) {
        if (dialogId == 0L) {
            return;
        }
        if (dialogId < 0L) {
            AyuFilter.blockPeer(dialogId);
            refreshRows();
            return;
        }
        long selfUserId = getUserConfig().getClientUserId();
        if (dialogId == selfUserId) {
            return;
        }
        HashSet<Long> idSet = new HashSet<>(AyuFilter.getCustomFilteredUsersList());
        if (!idSet.add(dialogId)) {
            return;
        }
        AyuFilter.setCustomFilteredUsers(new ArrayList<>(idSet));
        TLRPC.User localUser = getMessagesController().getUser(dialogId);
        if (localUser != null) {
            AyuFilter.updateCustomFilteredUserFromLocalUser(localUser);
        }
        refreshRows();
    }

    private void removeShadowBannedPeer(long dialogId) {
        if (dialogId < 0L) {
            AyuFilter.unblockPeer(dialogId);
            refreshRows();
            return;
        }
        ArrayList<Long> userIds = AyuFilter.getCustomFilteredUsersList();
        if (!userIds.remove(dialogId)) {
            return;
        }
        AyuFilter.setCustomFilteredUsers(userIds);
        refreshRows();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshRows() {
        invalidateCustomFilteredUsersDisplayState();
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void invalidateCustomFilteredUsersDisplayState() {
        resolvingCustomFilteredUsers.clear();
        resolvedCustomFilteredUsers.clear();
        customFilteredUserDisplayCache.clear();
    }

    private boolean isCustomFilteredUserUntracked(long userId) {
        return userId <= 0L || !AyuFilter.getCustomFilteredUsersList().contains(userId);
    }

    private void clearCustomFilteredUserDisplayState(long userId) {
        resolvingCustomFilteredUsers.remove(userId);
        resolvedCustomFilteredUsers.remove(userId);
        customFilteredUserDisplayCache.remove(userId);
    }

    private boolean hasUsableUserIdentity(TLRPC.User user) {
        if (user == null || user.id == 0L) {
            return false;
        }
        String displayName = UserObject.getUserName(user);
        if (!TextUtils.isEmpty(displayName) && !TextUtils.isEmpty(displayName.trim())) {
            return true;
        }
        return !TextUtils.isEmpty(UserObject.getPublicUsername(user));
    }

    private String formatResolvedUserTitle(TLRPC.User user) {
        if (user == null || user.id == 0L) {
            return null;
        }
        String displayName = UserObject.getUserName(user);
        if (!TextUtils.isEmpty(displayName)) {
            displayName = displayName.trim();
        }
        if (TextUtils.isEmpty(displayName)) {
            String username = UserObject.getPublicUsername(user);
            if (!TextUtils.isEmpty(username)) {
                displayName = "@" + username;
            }
        }
        if (TextUtils.isEmpty(displayName)) {
            return String.valueOf(user.id);
        }
        return displayName;
    }

    private String buildFallbackCustomFilteredUserTitle(AyuFilter.CustomFilteredUser userData, long userId) {
        if (userData != null) {
            if (!TextUtils.isEmpty(userData.displayName)) {
                String displayName = userData.displayName.trim();
                if (!TextUtils.isEmpty(displayName)) {
                    return displayName;
                }
            }
            String username = userData.username;
            if (!TextUtils.isEmpty(username)) {
                return "@" + username;
            }
        }
        return String.valueOf(userId);
    }

    private String getPeerRowSubtitle(long dialogId) {
        if (dialogId > 0) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null && user.bot) {
                return getString(R.string.RegexFiltersShadowBannedBot);
            }
            return getString(R.string.RegexFiltersShadowBannedUser);
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat)) {
            return getString(R.string.RegexFiltersShadowBannedChannel);
        }
        return getString(R.string.RegexFiltersShadowBannedGroup);
    }

    private String getPeerRowTitle(long dialogId) {
        if (dialogId > 0) {
            return getCustomFilteredUserRowTitle(dialogId);
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat != null && !TextUtils.isEmpty(chat.title)) {
            return chat.title;
        }
        return String.valueOf(dialogId);
    }

    private boolean cacheResolvedCustomFilteredUser(long userId, TLRPC.User user, boolean notifyRow) {
        if (user == null || user.id != userId) {
            return false;
        }
        AyuFilter.updateCustomFilteredUserFromLocalUser(user);
        if (!hasUsableUserIdentity(user)) {
            return false;
        }
        String title = formatResolvedUserTitle(user);
        if (TextUtils.isEmpty(title)) {
            return false;
        }
        resolvingCustomFilteredUsers.remove(userId);
        resolvedCustomFilteredUsers.add(userId);
        customFilteredUserDisplayCache.put(userId, title);
        if (notifyRow) {
            notifyCustomFilteredUserRowChanged(userId);
        }
        return true;
    }

    private String getCustomFilteredUserRowTitle(long userId) {
        TLRPC.User localUser = getMessagesController().getUser(userId);
        if (cacheResolvedCustomFilteredUser(userId, localUser, false)) {
            return customFilteredUserDisplayCache.get(userId);
        }
        String cached = customFilteredUserDisplayCache.get(userId);
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        AyuFilter.CustomFilteredUser userData = AyuFilter.getCustomFilteredUser(userId);
        String fallback = buildFallbackCustomFilteredUserTitle(userData, userId);
        customFilteredUserDisplayCache.put(userId, fallback);
        return fallback;
    }

    private void ensureCustomFilteredUserResolved(long userId) {
        if (userId <= 0L || isCustomFilteredUserUntracked(userId)) {
            return;
        }
        TLRPC.User localUser = getMessagesController().getUser(userId);
        if (cacheResolvedCustomFilteredUser(userId, localUser, false)) {
            return;
        }
        if (resolvedCustomFilteredUsers.contains(userId) || resolvingCustomFilteredUsers.contains(userId)) {
            return;
        }
        resolvingCustomFilteredUsers.add(userId);
        resolveCustomFilteredUserFromLocalDb(userId);
    }

    private void resolveCustomFilteredUserFromLocalDb(long userId) {
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.User storageUser = getMessagesStorage().getUserSync(userId);
            AndroidUtilities.runOnUIThread(() -> {
                if (isCustomFilteredUserUntracked(userId)) {
                    clearCustomFilteredUserDisplayState(userId);
                    return;
                }
                if (resolvedCustomFilteredUsers.contains(userId) && !resolvingCustomFilteredUsers.contains(userId)) {
                    return;
                }
                if (cacheResolvedCustomFilteredUser(userId, storageUser, true)) {
                    getMessagesController().putUser(storageUser, true);
                    return;
                }
                if (storageUser != null) {
                    getMessagesController().putUser(storageUser, true);
                    AyuFilter.updateCustomFilteredUserFromLocalUser(storageUser);
                }
                resolveCustomFilteredUserByUsername(userId);
            });
        });
    }

    private void resolveCustomFilteredUserByUsername(long userId) {
        AyuFilter.CustomFilteredUser userData = AyuFilter.getCustomFilteredUser(userId);
        String username = userData != null ? userData.username : null;
        if (TextUtils.isEmpty(username)) {
            resolveCustomFilteredUserById(userId);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        int reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isCustomFilteredUserUntracked(userId)) {
                clearCustomFilteredUserDisplayState(userId);
                return;
            }
            if (resolvedCustomFilteredUsers.contains(userId) && !resolvingCustomFilteredUsers.contains(userId)) {
                return;
            }
            if (response instanceof TLRPC.TL_contacts_resolvedPeer resolvedPeer) {
                getMessagesController().putUsers(resolvedPeer.users, false);
                getMessagesController().putChats(resolvedPeer.chats, false);
                getMessagesStorage().putUsersAndChats(resolvedPeer.users, resolvedPeer.chats, true, true);
                boolean matched = resolvedPeer.peer instanceof TLRPC.TL_peerUser && resolvedPeer.peer.user_id == userId;
                if (matched) {
                    TLRPC.User resolvedUser = getMessagesController().getUser(userId);
                    if (resolvedUser == null && resolvedPeer.users != null) {
                        for (TLRPC.User user : resolvedPeer.users) {
                            if (user != null && user.id == userId) {
                                resolvedUser = user;
                                break;
                            }
                        }
                    }
                    if (cacheResolvedCustomFilteredUser(userId, resolvedUser, true)) {
                        return;
                    }
                }
            }
            resolveCustomFilteredUserById(userId);
        }));
        getConnectionsManager().bindRequestToGuid(reqId, classGuid);
    }

    @SuppressWarnings("rawtypes")
    private void resolveCustomFilteredUserById(long userId) {
        AyuFilter.CustomFilteredUser userData = AyuFilter.getCustomFilteredUser(userId);
        if (userData == null || userData.accessHash == 0L) {
            onCustomFilteredUserResolveFailed(userId);
            return;
        }
        TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = userId;
        inputUser.access_hash = userData.accessHash;
        req.id.add(inputUser);
        int reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isCustomFilteredUserUntracked(userId)) {
                clearCustomFilteredUserDisplayState(userId);
                return;
            }
            if (resolvedCustomFilteredUsers.contains(userId) && !resolvingCustomFilteredUsers.contains(userId)) {
                return;
            }
            if (error == null && response instanceof Vector vector) {
                ArrayList<TLRPC.User> users = new ArrayList<>();
                for (Object object : vector.objects) {
                    if (object instanceof TLRPC.User user) {
                        users.add(user);
                    }
                }
                if (!users.isEmpty()) {
                    getMessagesController().putUsers(users, false);
                    getMessagesStorage().putUsersAndChats(users, null, true, true);
                    TLRPC.User resolvedUser = null;
                    for (TLRPC.User user : users) {
                        if (user != null && user.id == userId) {
                            resolvedUser = user;
                            break;
                        }
                    }
                    if (resolvedUser == null) {
                        resolvedUser = getMessagesController().getUser(userId);
                    }
                    if (cacheResolvedCustomFilteredUser(userId, resolvedUser, true)) {
                        return;
                    }
                }
            }
            TLRPC.User localUser = getMessagesController().getUser(userId);
            if (cacheResolvedCustomFilteredUser(userId, localUser, true)) {
                return;
            }
            onCustomFilteredUserResolveFailed(userId);
        }));
        getConnectionsManager().bindRequestToGuid(reqId, classGuid);
    }

    private void onCustomFilteredUserResolveFailed(long userId) {
        if (isCustomFilteredUserUntracked(userId)) {
            clearCustomFilteredUserDisplayState(userId);
            return;
        }
        resolvingCustomFilteredUsers.remove(userId);
        resolvedCustomFilteredUsers.add(userId);
        AyuFilter.CustomFilteredUser userData = AyuFilter.getCustomFilteredUser(userId);
        customFilteredUserDisplayCache.put(userId, buildFallbackCustomFilteredUserTitle(userData, userId));
        notifyCustomFilteredUserRowChanged(userId);
    }

    private void notifyCustomFilteredUserRowChanged(long userId) {
        if (listAdapter == null) {
            return;
        }
        int index = shadowBannedPeers.indexOf(userId);
        if (index < 0) {
            return;
        }
        int position = peersStartRow + index;
        if (position >= 0 && position < rowCount) {
            listAdapter.notifyItemChanged(position);
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.ShadowBan);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_ACCOUNT) {
                FiltersChatCell chatCell = new FiltersChatCell(mContext);
                chatCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                chatCell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(chatCell);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload) {
            switch (holder.getItemViewType()) {
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    infoCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    if (position == emptyRow) {
                        infoCell.setText(getString(R.string.RegexFiltersShadowBanEmpty));
                    }
                    break;
                case TYPE_ACCOUNT:
                    if (position >= peersStartRow && position < peersEndRow) {
                        int idx = position - peersStartRow;
                        if (idx >= 0 && idx < shadowBannedPeers.size()) {
                            long dialogId = shadowBannedPeers.get(idx);
                            boolean needDivider = position + 1 < peersEndRow;
                            FiltersChatCell chatCell = (FiltersChatCell) holder.itemView;
                            chatCell.setShadowBannedPeer(dialogId, getPeerRowTitle(dialogId), getPeerRowSubtitle(dialogId), needDivider);
                            if (dialogId > 0) {
                                ensureCustomFilteredUserResolved(dialogId);
                            }
                        }
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == emptyRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_ACCOUNT;
        }
    }
}
