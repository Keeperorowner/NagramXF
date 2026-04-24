package tw.nekomimi.nekogram.filters;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.HashMap;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.FiltersChatCell;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.HttpClient;
import xyz.nextalone.nagram.NaConfig;

public class RegexFiltersSettingActivity extends BaseNekoSettingsActivity {

    private static class DialogFilterItem {
        long dialogId;
        int chatFiltersCount;
        int excludedSharedCount;
    }

    private int filtersOptionHeaderRow;
    private int regexFiltersEnabledRow;
    private int regexFiltersEnableInChatsRow;
    private int regexFiltersMaskMessagesRow;
    private int regexFiltersMaskMessagesInfoRow;
    private int ignoreBlockedRow;
    private int filtersOptionDividerRow;
    private int filtersHeaderRow;
    private int sharedFiltersPageRow;
    private int userFiltersPageRow;
    private int dividerRow;
    private int chatFiltersHeaderRow;
    private int chatFiltersStartRow;
    private int chatFiltersEndRow;

    public RegexFiltersSettingActivity() {
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        filtersOptionHeaderRow = -1;
        regexFiltersEnabledRow = -1;
        regexFiltersEnableInChatsRow = -1;
        regexFiltersMaskMessagesRow = -1;
        regexFiltersMaskMessagesInfoRow = -1;
        ignoreBlockedRow = -1;
        filtersOptionDividerRow = -1;
        filtersHeaderRow = -1;
        sharedFiltersPageRow = -1;
        userFiltersPageRow = -1;
        dividerRow = -1;
        chatFiltersHeaderRow = -1;
        chatFiltersStartRow = -1;
        chatFiltersEndRow = -1;
        filtersOptionHeaderRow = rowCount++;
        regexFiltersEnabledRow = rowCount++;
        regexFiltersEnableInChatsRow = rowCount++;
        ignoreBlockedRow = rowCount++;
        filtersOptionDividerRow = rowCount++;
        regexFiltersMaskMessagesRow = rowCount++;
        regexFiltersMaskMessagesInfoRow = rowCount++;

        filtersHeaderRow = rowCount++;
        sharedFiltersPageRow = rowCount++;
        userFiltersPageRow = rowCount++;
        dividerRow = rowCount++;
        var chatEntries = getDialogFilterItems();
        if (!chatEntries.isEmpty()) {
            chatFiltersHeaderRow = rowCount++;
            chatFiltersStartRow = rowCount;
            rowCount += chatEntries.size();
            chatFiltersEndRow = rowCount;
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        super.onResume();

        updateRows();

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @SuppressWarnings("NewApi")
    @Override
    public View createView(Context context) {
        View v = super.createView(context);

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem menuItem = menu.addItem(0, R.drawable.ic_ab_other);
        menuItem.addSubItem(1, R.drawable.msg_user_search, getString(R.string.SelectChat));
        menuItem.addColoredGap();
        menuItem.addSubItem(2, R.drawable.msg_photo_settings_solar, getString(R.string.RegexFiltersImport));
        menuItem.addSubItem(3, R.drawable.msg_instant_link_solar, getString(R.string.RegexFiltersExport));
        menuItem.addColoredGap();
        ActionBarMenuSubItem clearSub = menuItem.addSubItem(4, R.drawable.msg_clear, getString(R.string.ClearRegexFilters));
        int red = Theme.getColor(Theme.key_text_RedRegular);
        clearSub.setColors(red, red);
        clearSub.setSelectorColor(Theme.multAlpha(red, .12f));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
                if (id == 1) {
                    presentFragment(createSelectChatActivity());
                }
                if (id == 2) {
                    showImportSourceChooser(context);
                } else if (id == 3) {
                    showExportSourceChooser(context);
                } else if (id == 4) {
                    new AlertDialog.Builder(getContext(), getResourceProvider())
                        .setTitle(getString(R.string.ClearRegexFilters))
                        .setMessage(getString(R.string.ClearRegexFiltersAlertMessage))
                        .setNegativeButton(getString(R.string.Cancel), null)
                        .setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
                            AyuFilter.clearAllFilters();
                            refreshRows();
                        })
                        .makeRed(AlertDialog.BUTTON_POSITIVE)
                        .show();
                }
            }
        });
        return v;
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == regexFiltersEnabledRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NaConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(enabled);
            AyuFilter.invalidateFilteredCache();
        } else if (position == regexFiltersEnableInChatsRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NaConfig.INSTANCE.getRegexFiltersEnableInChats().setConfigBool(enabled);
            AyuFilter.invalidateFilteredCache();
        } else if (position == regexFiltersMaskMessagesRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NaConfig.INSTANCE.getRegexFiltersMaskMessages().setConfigBool(enabled);
            AyuFilter.invalidateFilteredCache();
        } else if (position == ignoreBlockedRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NekoConfig.ignoreBlocked.setConfigBool(enabled);
            if (enabled && !NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()) {
                NaConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(true);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            }
            AyuFilter.invalidateFilteredCache();
        } else if (position == sharedFiltersPageRow) {
            presentFragment(new RegexSharedFiltersListActivity());
        } else if (position == userFiltersPageRow) {
            presentFragment(new ShadowBanListActivity());
        } else if (position >= chatFiltersStartRow && position < chatFiltersEndRow) {
            int idx = position - chatFiltersStartRow;
            var chatEntries = getDialogFilterItems();
            if (idx >= 0 && idx < chatEntries.size()) {
                long did = chatEntries.get(idx).dialogId;
                presentFragment(new RegexChatFiltersListActivity(did));
            }
        }
    }

    @NonNull
    private DialogsActivity createSelectChatActivity() {
        Bundle b = new Bundle();
        b.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_REGEX_FILTER);
        b.putBoolean("onlySelect", true);
        b.putBoolean("canSelectTopics", false);
        b.putBoolean("allowSwitchAccount", true);
        b.putBoolean("checkCanWrite", false);
        DialogsActivity dialogsActivity = new DialogsActivity(b);
        dialogsActivity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                long dialogId = ((MessagesStorage.TopicKey) dids.get(0)).dialogId;
                presentFragment(new RegexChatFiltersListActivity(dialogId), true);
            }
            return true;
        });
        return dialogsActivity;
    }

    private int getShadowBanCount() {
        return AyuFilter.getBlockedChannelsList().size() + AyuFilter.getCustomFilteredUsersList().size();
    }

    private String buildExportJson() {
        TransferData data = new TransferData();
        data.version = 2;
        data.filters = new ArrayList<>();
        data.peers = new HashMap<>();
        MessagesController messagesController = getMessagesController();
        for (AyuFilter.FilterModel model : AyuFilter.getRegexFilters()) {
            if (model == null || model.regex == null) {
                continue;
            }
            data.filters.add(buildBackupFilter(model, null));
        }
        for (AyuFilter.ChatFilterEntry entry : checkChatFilters(AyuFilter.getChatFilterEntries())) {
            if (entry == null || entry.filters == null) {
                continue;
            }
            for (AyuFilter.FilterModel model : entry.filters) {
                if (model == null || model.regex == null) {
                    continue;
                }
                data.filters.add(buildBackupFilter(model, entry.dialogId));
            }
            addPeerUsername(data.peers, entry.dialogId, messagesController);
        }
        data.exclusions = new ArrayList<>();
        for (AyuFilter.ExcludedFilterEntry entry : AyuFilter.getExcludedFilterEntries()) {
            if (entry == null || TextUtils.isEmpty(entry.filterId) || entry.dialogId == 0L) {
                continue;
            }
            BackupExclusion exclusion = new BackupExclusion();
            exclusion.dialogId = entry.dialogId;
            exclusion.filterId = entry.filterId;
            data.exclusions.add(exclusion);
            addPeerUsername(data.peers, entry.dialogId, messagesController);
        }
        data.customFilteredUsersData = AyuFilter.getCustomFilteredUsersDataList();
        return new Gson().toJson(data);
    }

    private void showExportSourceChooser(Context context) {
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.FiltersExportTitle))
                .setItems(new CharSequence[]{
                        getString(R.string.FiltersExportClipboard),
                        getString(R.string.FiltersExportURL)
                }, (dialog, which) -> {
                    if (which == 0) {
                        exportToClipboard();
                    } else {
                        exportToUrl();
                    }
                })
                .show();
    }

    private void exportToClipboard() {
        try {
            AndroidUtilities.addToClipboard(buildExportJson());
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersExportError)).show();
        }
    }

    private void exportToUrl() {
        final String json;
        try {
            json = buildExportJson();
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersExportError)).show();
            return;
        }
        Request request = new Request.Builder()
                .url("https://dpaste.com/api/v2/")
                .post(new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("content", json)
                        .addFormDataPart("syntax", "json")
                        .addFormDataPart("title", "NagramXF Filters")
                        .build())
                .build();
        HttpClient.INSTANCE.getInstance().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                AndroidUtilities.runOnUIThread(() ->
                        BulletinFactory.of(RegexFiltersSettingActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.FiltersToastFailPublish))
                                .show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String location;
                try (ResponseBody ignored = response.body()) {
                    location = response.header("Location");
                }
                final String exportUrl = TextUtils.isEmpty(location) ? null : (location.endsWith(".txt") ? location : location + ".txt");
                AndroidUtilities.runOnUIThread(() -> {
                    if (TextUtils.isEmpty(exportUrl)) {
                        BulletinFactory.of(RegexFiltersSettingActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.FiltersToastFailPublish))
                                .show();
                        return;
                    }
                    AndroidUtilities.addToClipboard(exportUrl);
                    BulletinFactory.of(RegexFiltersSettingActivity.this).createCopyLinkBulletin().show();
                });
            }
        });
    }

    private ArrayList<AyuFilter.ChatFilterEntry> checkChatFilters(ArrayList<AyuFilter.ChatFilterEntry> chatEntries) {
        if (chatEntries == null || chatEntries.isEmpty()) return chatEntries;
        ArrayList<AyuFilter.ChatFilterEntry> newEntries = new ArrayList<>();
        for (AyuFilter.ChatFilterEntry entry : chatEntries) {
            if (entry == null) continue;
            if (entry.dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(entry.dialogId);
                if (user != null) {
                    newEntries.add(entry);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-entry.dialogId);
                if (chat != null) {
                    newEntries.add(entry);
                }
            }
        }
        return newEntries;
    }

    private boolean isKnownDialog(long dialogId) {
        if (dialogId > 0) {
            return MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialogId) != null;
        }
        return MessagesController.getInstance(UserConfig.selectedAccount).getChat(-dialogId) != null;
    }

    private void resolveUnknownPeers(HashMap<Long, String> peers) {
        MessagesController messagesController = getMessagesController();
        for (var entry : peers.entrySet()) {
            Long dialogId = entry.getKey();
            String rawUsername = entry.getValue();
            if (dialogId == null || dialogId == 0L || TextUtils.isEmpty(rawUsername)) {
                continue;
            }
            if (isKnownDialog(dialogId)) {
                continue;
            }
            String username = rawUsername.startsWith("@") ? rawUsername.substring(1) : rawUsername;
            if (TextUtils.isEmpty(username)) {
                continue;
            }
            messagesController.getUserNameResolver().resolve(username, peerId -> {
                if (peerId != null && peerId != 0L) {
                    AndroidUtilities.runOnUIThread(this::refreshRows);
                }
            });
        }
    }


    private static class ParsedImport {
        ArrayList<AyuFilter.FilterModel> sharedIncoming;
        ArrayList<AyuFilter.ChatFilterEntry> chatsIncoming;
        ArrayList<AyuFilter.CustomFilteredUser> customFilteredUsersIncoming;
        ArrayList<AyuFilter.ExcludedFilterEntry> exclusionsIncoming;
        HashMap<Long, String> peersIncoming;

        boolean hasAnyData() {
            return (sharedIncoming != null && !sharedIncoming.isEmpty())
                    || (chatsIncoming != null && !chatsIncoming.isEmpty())
                    || (customFilteredUsersIncoming != null && !customFilteredUsersIncoming.isEmpty())
                    || (exclusionsIncoming != null && !exclusionsIncoming.isEmpty());
        }
    }

    private ParsedImport parseImportJson(String rawJson) {
        if (TextUtils.isEmpty(rawJson)) return null;
        String json = rawJson.trim();
        long selfUserId = getUserConfig().getClientUserId();
        ParsedImport out = new ParsedImport();
        try {
            if (json.startsWith("[")) {
                AyuFilter.FilterModel[] arr = new Gson().fromJson(json, AyuFilter.FilterModel[].class);
                if (arr != null) {
                    out.sharedIncoming = new ArrayList<>();
                    for (AyuFilter.FilterModel m : arr) {
                        if (m == null || m.regex == null) continue;
                        m.migrateFromLegacy(0L);
                        out.sharedIncoming.add(m);
                    }
                }
            } else {
                TransferData data = new Gson().fromJson(json, TransferData.class);
                if (data != null) {
                    if (data.filters != null) {
                        out.sharedIncoming = new ArrayList<>();
                        out.chatsIncoming = new ArrayList<>();
                        HashMap<Long, AyuFilter.ChatFilterEntry> chatMap = new HashMap<>();
                        for (BackupFilter bf : data.filters) {
                            if (bf == null || TextUtils.isEmpty(bf.text)) continue;
                            AyuFilter.FilterModel m = new AyuFilter.FilterModel();
                            m.id = bf.id;
                            m.regex = bf.text;
                            m.enabled = bf.enabled;
                            m.caseInsensitive = bf.caseInsensitive;
                            m.reversed = bf.reversed;
                            m.ensureId();
                            if (bf.dialogId == null) {
                                out.sharedIncoming.add(m);
                            } else {
                                long did = bf.dialogId;
                                AyuFilter.ChatFilterEntry entry = chatMap.get(did);
                                if (entry == null) {
                                    entry = new AyuFilter.ChatFilterEntry();
                                    entry.dialogId = did;
                                    entry.filters = new ArrayList<>();
                                    chatMap.put(did, entry);
                                    out.chatsIncoming.add(entry);
                                }
                                entry.filters.add(m);
                            }
                        }
                    }
                    if (data.exclusions != null) {
                        out.exclusionsIncoming = new ArrayList<>();
                        for (BackupExclusion be : data.exclusions) {
                            if (be == null || TextUtils.isEmpty(be.filterId)) continue;
                            AyuFilter.ExcludedFilterEntry entry = new AyuFilter.ExcludedFilterEntry();
                            entry.dialogId = be.dialogId;
                            entry.filterId = be.filterId;
                            out.exclusionsIncoming.add(entry);
                        }
                    }
                    if (data.peers != null && !data.peers.isEmpty()) {
                        out.peersIncoming = new HashMap<>(data.peers);
                    }
                    if (data.customFilteredUsersData != null) {
                        out.customFilteredUsersIncoming = new ArrayList<>();
                        for (AyuFilter.CustomFilteredUser user : data.customFilteredUsersData) {
                            if (user == null || user.id <= 0L || user.id == selfUserId) {
                                continue;
                            }
                            AyuFilter.CustomFilteredUser normalized = new AyuFilter.CustomFilteredUser();
                            normalized.id = user.id;
                            normalized.accessHash = user.accessHash;
                            normalized.username = user.username;
                            if (!TextUtils.isEmpty(user.displayName)) {
                                String displayName = user.displayName.trim();
                                if (!TextUtils.isEmpty(displayName)) {
                                    normalized.displayName = displayName;
                                }
                            }
                            out.customFilteredUsersIncoming.add(normalized);
                        }
                    } else if (data.customFilteredUsers != null) {
                        out.customFilteredUsersIncoming = new ArrayList<>();
                        for (Long userId : data.customFilteredUsers) {
                            if (userId == null || userId <= 0L || userId == selfUserId) {
                                continue;
                            }
                            AyuFilter.CustomFilteredUser normalized = new AyuFilter.CustomFilteredUser();
                            normalized.id = userId;
                            out.customFilteredUsersIncoming.add(normalized);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        return out.hasAnyData() ? out : null;
    }

    private FiltersImportBottomSheet.Summary buildImportSummary(ParsedImport parsed) {
        FiltersImportBottomSheet.Summary s = new FiltersImportBottomSheet.Summary();
        if (parsed == null) return s;
        if (parsed.sharedIncoming != null && !parsed.sharedIncoming.isEmpty()) {
            ArrayList<AyuFilter.FilterModel> currentShared = AyuFilter.getRegexFilters();
            for (AyuFilter.FilterModel in : parsed.sharedIncoming) {
                boolean exactDuplicate = false;
                boolean needsUpdate = false;
                for (AyuFilter.FilterModel ex : currentShared) {
                    if (ex != null && ex.regex != null
                            && ex.regex.equals(in.regex)
                            && ex.caseInsensitive == in.caseInsensitive
                            && ex.reversed == in.reversed) {
                        if (ex.enabled == in.enabled) {
                            exactDuplicate = true;
                        } else {
                            needsUpdate = true;
                        }
                        break;
                    }
                }
                if (exactDuplicate) {
                } else if (needsUpdate) {
                    s.updatedFilters++;
                } else {
                    s.newFilters++;
                }
            }
        }
        if (parsed.chatsIncoming != null) {
            ArrayList<AyuFilter.ChatFilterEntry> currentChats = checkChatFilters(AyuFilter.getChatFilterEntries());
            for (AyuFilter.ChatFilterEntry inEntry : parsed.chatsIncoming) {
                if (inEntry == null || inEntry.filters == null) continue;
                AyuFilter.ChatFilterEntry existingEntry = null;
                for (AyuFilter.ChatFilterEntry exEntry : currentChats) {
                    if (exEntry != null && exEntry.dialogId == inEntry.dialogId) {
                        existingEntry = exEntry;
                        break;
                    }
                }
                for (AyuFilter.FilterModel in : inEntry.filters) {
                    boolean exactDuplicate = false;
                    boolean needsUpdate = false;
                    if (existingEntry != null && existingEntry.filters != null) {
                        for (AyuFilter.FilterModel ex : existingEntry.filters) {
                            if (ex != null && ex.regex != null
                                    && ex.regex.equals(in.regex)
                                    && ex.caseInsensitive == in.caseInsensitive
                                    && ex.reversed == in.reversed) {
                                if (ex.enabled == in.enabled) {
                                    exactDuplicate = true;
                                } else {
                                    needsUpdate = true;
                                }
                                break;
                            }
                        }
                    }
                    if (exactDuplicate) {
                    } else if (needsUpdate) {
                        s.updatedFilters++;
                    } else {
                        s.newChatFilters++;
                    }
                }
            }
        }
        if (parsed.exclusionsIncoming != null) {
            ArrayList<AyuFilter.ExcludedFilterEntry> currentExclusions = AyuFilter.getExcludedFilterEntries();
            for (AyuFilter.ExcludedFilterEntry in : parsed.exclusionsIncoming) {
                boolean exists = false;
                for (AyuFilter.ExcludedFilterEntry ex : currentExclusions) {
                    if (ex != null && ex.dialogId == in.dialogId
                            && ex.filterId != null && ex.filterId.equals(in.filterId)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) s.newExclusions++;
            }
        }
        if (parsed.customFilteredUsersIncoming != null) {
            long selfUserId = getUserConfig().getClientUserId();
            HashMap<Long, AyuFilter.CustomFilteredUser> existing = new HashMap<>();
            for (AyuFilter.CustomFilteredUser u : AyuFilter.getCustomFilteredUsersDataList()) {
                if (u != null && u.id > 0L) existing.put(u.id, u);
            }
            for (AyuFilter.CustomFilteredUser in : parsed.customFilteredUsersIncoming) {
                if (in == null || in.id <= 0L || in.id == selfUserId) continue;
                AyuFilter.CustomFilteredUser ex = existing.get(in.id);
                if (ex == null) {
                    s.newShadowBans++;
                } else if (!TextUtils.equals(ex.username, in.username)
                        || !TextUtils.equals(ex.displayName, in.displayName)
                        || ex.accessHash != in.accessHash) {
                    s.updatedFilters++;
                }
            }
        }
        if (parsed.peersIncoming != null) {
            for (var entry : parsed.peersIncoming.entrySet()) {
                Long did = entry.getKey();
                if (did == null || did == 0L) continue;
                if (!isKnownDialog(did)) s.peersToResolve++;
            }
        }
        return s;
    }

    private void applyImport(ParsedImport parsed) {
        if (parsed == null) return;
        long selfUserId = getUserConfig().getClientUserId();
        if (parsed.sharedIncoming != null && !parsed.sharedIncoming.isEmpty()) {
            ArrayList<AyuFilter.FilterModel> currentShared = AyuFilter.getRegexFilters();
            for (AyuFilter.FilterModel in : parsed.sharedIncoming) {
                boolean found = false;
                for (int i = 0; i < currentShared.size(); i++) {
                    AyuFilter.FilterModel ex = currentShared.get(i);
                    if (ex != null && ex.regex != null && ex.regex.equals(in.regex) && ex.caseInsensitive == in.caseInsensitive && ex.reversed == in.reversed) {
                        ex.enabled = in.enabled;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    currentShared.add(in);
                }
            }
            AyuFilter.saveFilter(currentShared);
        }
        if (parsed.chatsIncoming != null && !parsed.chatsIncoming.isEmpty()) {
            ArrayList<AyuFilter.ChatFilterEntry> currentChats = checkChatFilters(AyuFilter.getChatFilterEntries());
            for (AyuFilter.ChatFilterEntry inEntry : parsed.chatsIncoming) {
                if (inEntry == null) continue;
                AyuFilter.ChatFilterEntry target = null;
                for (AyuFilter.ChatFilterEntry exEntry : currentChats) {
                    if (exEntry != null && exEntry.dialogId == inEntry.dialogId) {
                        target = exEntry;
                        break;
                    }
                }
                if (target == null) {
                    AyuFilter.ChatFilterEntry newEntry = new AyuFilter.ChatFilterEntry();
                    newEntry.dialogId = inEntry.dialogId;
                    newEntry.filters = new ArrayList<>();
                    currentChats.add(newEntry);
                    target = newEntry;
                }
                if (inEntry.filters != null) {
                    for (AyuFilter.FilterModel in : inEntry.filters) {
                        boolean found = false;
                        if (target.filters == null) target.filters = new ArrayList<>();
                        for (int i = 0; i < target.filters.size(); i++) {
                            AyuFilter.FilterModel ex = target.filters.get(i);
                            if (ex != null && ex.regex != null && ex.regex.equals(in.regex) && ex.caseInsensitive == in.caseInsensitive && ex.reversed == in.reversed) {
                                ex.enabled = in.enabled;
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            target.filters.add(in);
                        }
                    }
                }
            }
            AyuFilter.saveChatFilterEntries(currentChats);
        }
        if (parsed.customFilteredUsersIncoming != null && !parsed.customFilteredUsersIncoming.isEmpty()) {
            HashMap<Long, AyuFilter.CustomFilteredUser> merged = new HashMap<>();
            for (AyuFilter.CustomFilteredUser existing : AyuFilter.getCustomFilteredUsersDataList()) {
                if (existing == null || existing.id <= 0L || existing.id == selfUserId) {
                    continue;
                }
                merged.put(existing.id, existing);
            }
            for (AyuFilter.CustomFilteredUser incoming : parsed.customFilteredUsersIncoming) {
                if (incoming == null || incoming.id <= 0L || incoming.id == selfUserId) {
                    continue;
                }
                AyuFilter.CustomFilteredUser target = merged.get(incoming.id);
                if (target == null) {
                    target = new AyuFilter.CustomFilteredUser();
                    target.id = incoming.id;
                }
                if (incoming.accessHash != 0L) {
                    target.accessHash = incoming.accessHash;
                }
                String username = incoming.username;
                if (!TextUtils.isEmpty(username)) {
                    target.username = username;
                }
                if (!TextUtils.isEmpty(incoming.displayName)) {
                    String displayName = incoming.displayName.trim();
                    if (!TextUtils.isEmpty(displayName)) {
                        target.displayName = displayName;
                    }
                }
                merged.put(target.id, target);
            }
            AyuFilter.setCustomFilteredUsersData(new ArrayList<>(merged.values()));
        }
        if (parsed.exclusionsIncoming != null && !parsed.exclusionsIncoming.isEmpty()) {
            for (AyuFilter.ExcludedFilterEntry entry : parsed.exclusionsIncoming) {
                if (entry == null || TextUtils.isEmpty(entry.filterId) || entry.dialogId == 0L) {
                    continue;
                }
                AyuFilter.setSharedFilterExcluded(entry.dialogId, entry.filterId, true);
            }
        }
        if (parsed.peersIncoming != null && !parsed.peersIncoming.isEmpty()) {
            resolveUnknownPeers(parsed.peersIncoming);
        }
    }

    private void showImportPreview(ParsedImport parsed) {
        if (parsed == null || !parsed.hasAnyData()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportNoChanges)).show();
            return;
        }
        FiltersImportBottomSheet.Summary summary = buildImportSummary(parsed);
        if (summary.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportNoChanges)).show();
            return;
        }
        new FiltersImportBottomSheet(this, summary, () -> {
            try {
                applyImport(parsed);
                refreshRows();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.RegexFiltersImportSuccess)).show();
            } catch (Exception e) {
                FileLog.e(e);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
            }
        }).show();
    }

    private void showImportSourceChooser(Context context) {
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.RegexFiltersImportSourceTitle))
                .setItems(new CharSequence[]{
                        getString(R.string.RegexFiltersImportFromClipboard),
                        getString(R.string.RegexFiltersImportFromURL)
                }, (dialog, which) -> {
                    if (which == 0) {
                        importFromClipboard(context);
                    } else {
                        importFromUrl(context);
                    }
                })
                .show();
    }

    private void importFromClipboard(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        if (text == null || TextUtils.isEmpty(text.toString().trim())) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
            return;
        }
        ParsedImport parsed = parseImportJson(text.toString());
        if (parsed == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
            return;
        }
        showImportPreview(parsed);
    }

    private void importFromUrl(Context context) {
        FrameLayout container = new FrameLayout(context);
        EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setTextSize(16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        editText.setHint(getString(R.string.RegexFiltersImportUrlHint));
        editText.setSingleLine(true);
        editText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence clipboardText = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            clipboardText = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        String initialLink = clipboardText != null ? clipboardText.toString().trim() : "";
        String lastLink = NaConfig.INSTANCE.getRegexFiltersLastImportLink().String();
        if (!initialLink.contains(".txt") && !initialLink.contains("github") && !initialLink.contains("bin") && !initialLink.contains("paste")) {
            initialLink = lastLink;
        }
        if (!TextUtils.isEmpty(initialLink)) {
            editText.setText(initialLink);
            editText.setSelection(editText.getText().length());
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = AndroidUtilities.dp(24);
        lp.rightMargin = AndroidUtilities.dp(24);
        lp.topMargin = AndroidUtilities.dp(6);
        container.addView(editText, lp);

        AlertDialog alert = new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.RegexFiltersImportUrlTitle))
                .setView(container)
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.RegexFiltersImportUrlAction), (dialog, which) -> {
                    String url = editText.getText() != null ? editText.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(url)) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed)).show();
                        return;
                    }
                    fetchImportFromUrl(url);
                })
                .create();
        alert.show();
        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    private void fetchImportFromUrl(String url) {
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, getResourceProvider());
        progress.setCanCancel(false);
        progress.show();
        Request request;
        try {
            request = new Request.Builder().url(url).get().build();
        } catch (IllegalArgumentException e) {
            progress.dismiss();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed)).show();
            return;
        }
        HttpClient.INSTANCE.getInstance().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    BulletinFactory.of(RegexFiltersSettingActivity.this)
                            .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed))
                            .show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String body = null;
                try (ResponseBody rb = response.body()) {
                    if (response.isSuccessful() && rb != null) {
                        body = rb.string();
                    }
                } catch (IOException ignored) {
                }
                final String finalBody = body;
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    if (TextUtils.isEmpty(finalBody)) {
                        BulletinFactory.of(RegexFiltersSettingActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed))
                                .show();
                        return;
                    }
                    ParsedImport parsed = parseImportJson(finalBody);
                    if (parsed == null) {
                        BulletinFactory.of(RegexFiltersSettingActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError))
                                .show();
                        return;
                    }
                    NaConfig.INSTANCE.getRegexFiltersLastImportLink().setConfigString(url);
                    showImportPreview(parsed);
                });
            }
        });
    }


    private ArrayList<DialogFilterItem> getDialogFilterItems() {
        HashMap<Long, DialogFilterItem> map = new HashMap<>();
        ArrayList<AyuFilter.ChatFilterEntry> chatEntries = checkChatFilters(AyuFilter.getChatFilterEntries());
        if (chatEntries != null) {
            for (AyuFilter.ChatFilterEntry entry : chatEntries) {
                if (entry == null || !isKnownDialog(entry.dialogId)) {
                    continue;
                }
                DialogFilterItem item = map.computeIfAbsent(entry.dialogId, did -> {
                    DialogFilterItem newItem = new DialogFilterItem();
                    newItem.dialogId = did;
                    return newItem;
                });
                item.chatFiltersCount = entry.filters != null ? entry.filters.size() : 0;
            }
        }
        for (AyuFilter.ExcludedFilterEntry entry : AyuFilter.getExcludedFilterEntries()) {
            if (entry == null || !isKnownDialog(entry.dialogId)) {
                continue;
            }
            DialogFilterItem item = map.computeIfAbsent(entry.dialogId, did -> {
                DialogFilterItem newItem = new DialogFilterItem();
                newItem.dialogId = did;
                return newItem;
            });
            item.excludedSharedCount++;
        }
        ArrayList<DialogFilterItem> items = new ArrayList<>(map.values());
        items.sort((o1, o2) -> Long.compare(o1.dialogId, o2.dialogId));
        return items;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshRows() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        return super.onItemLongClick(view, position, x, y);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.RegexFilters);
    }

    private static BackupFilter buildBackupFilter(AyuFilter.FilterModel model, Long dialogId) {
        BackupFilter bf = new BackupFilter();
        bf.id = model.id;
        bf.text = model.regex;
        bf.dialogId = dialogId;
        bf.enabled = model.enabled;
        bf.caseInsensitive = model.caseInsensitive;
        bf.reversed = model.reversed;
        return bf;
    }

    private static void addPeerUsername(HashMap<Long, String> peers, long dialogId, MessagesController messagesController) {
        if (peers == null || dialogId == 0L || peers.containsKey(dialogId)) return;
        TLObject peer = dialogId > 0
                ? messagesController.getUser(dialogId)
                : messagesController.getChat(-dialogId);
        if (peer == null) return;
        String username = DialogObject.getPublicUsername(peer);
        if (!TextUtils.isEmpty(username)) {
            peers.put(dialogId, "@" + username);
        }
    }

    private static class TransferData {
        public Integer version;
        public ArrayList<BackupFilter> filters;
        public ArrayList<BackupExclusion> exclusions;
        public HashMap<Long, String> peers;
        public ArrayList<AyuFilter.CustomFilteredUser> customFilteredUsersData;
        public ArrayList<Long> customFilteredUsers;
    }

    private static class BackupFilter {
        public String id;
        public String text;
        public Long dialogId;
        public boolean enabled = true;
        public boolean caseInsensitive;
        public boolean reversed;
    }

    private static class BackupExclusion {
        public long dialogId;
        public String filterId;
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
                case TYPE_SHADOW:
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_CHECK:
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    if (position == regexFiltersEnabledRow) {
                        textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersEnabled), NaConfig.INSTANCE.getRegexFiltersEnabled().Bool(), true);
                    } else if (position == regexFiltersEnableInChatsRow) {
                        textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersEnableInChats), NaConfig.INSTANCE.getRegexFiltersEnableInChats().Bool(), true);
                    } else if (position == regexFiltersMaskMessagesRow) {
                        textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersMaskMessagesShort), NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool(), false);
                    } else if (position == ignoreBlockedRow) {
                        textCheckCell.setTextAndCheck(getString(R.string.IgnoreBlocked), NekoConfig.ignoreBlocked.Bool(), false);
                    }
                    break;
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    infoCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    if (position == regexFiltersMaskMessagesInfoRow) {
                        infoCell.setText(getString(R.string.RegexFiltersMaskMessagesAbout));
                    }
                    break;
                case TYPE_TEXT:
                    break;
                case TYPE_SETTINGS:
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    if (position == sharedFiltersPageRow) {
                        settingsCell.setTextAndValue(getString(R.string.RegexFiltersSharedHeader), String.valueOf(AyuFilter.getRegexFilters().size()), true);
                    } else if (position == userFiltersPageRow) {
                        settingsCell.setTextAndValue(getString(R.string.ShadowBan), String.valueOf(getShadowBanCount()), false);
                    }
                    break;
                case TYPE_ACCOUNT:
                    if (position >= chatFiltersStartRow && position < chatFiltersEndRow) {
                        int idx = position - chatFiltersStartRow;
                        var chatEntries = getDialogFilterItems();
                        if (idx >= 0 && idx < chatEntries.size()) {
                            var entry = chatEntries.get(idx);
                            long did = entry.dialogId;
                            FiltersChatCell chatCell = (FiltersChatCell) holder.itemView;
                            chatCell.setDialog(did, entry.chatFiltersCount, entry.excludedSharedCount);
                        }
                    }
                    break;
                case TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == filtersOptionHeaderRow) {
                        headerCell.setText(getString(R.string.General));
                    } else if (position == filtersHeaderRow) {
                        headerCell.setText(getString(R.string.RegexFiltersGlobalHeader));
                    } else if (position == chatFiltersHeaderRow) {
                        headerCell.setText(getString(R.string.RegexFiltersChatHeader));
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == filtersOptionDividerRow || position == dividerRow) {
                return TYPE_SHADOW;
            } else if (position == regexFiltersMaskMessagesInfoRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == filtersHeaderRow || position == filtersOptionHeaderRow || position == chatFiltersHeaderRow) {
                return TYPE_HEADER;
            } else if (position == sharedFiltersPageRow || position == userFiltersPageRow) {
                return TYPE_SETTINGS;
            } else if (position >= chatFiltersStartRow && position < chatFiltersEndRow) {
                return TYPE_ACCOUNT;
            }
            return TYPE_CHECK;
        }
    }
}
