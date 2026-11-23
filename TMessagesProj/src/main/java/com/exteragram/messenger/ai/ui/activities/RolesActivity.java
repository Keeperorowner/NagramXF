package com.exteragram.messenger.ai.ui.activities;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.ui.components.RoleCell;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AIEditorAlert;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Role picker: the suggested roles on top, the roles the user created below. Tapping the radio
 * selects a role, tapping the row opens the preview or the editor.
 */
public class RolesActivity extends BasePreferencesActivity {

    private static final int MENU_ADD = 0;
    private static final int ROLE_NAME_MAX_LENGTH = 64;
    private static final int ROLE_PROMPT_MAX_LENGTH = 1024;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.AIChatRoles);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        setHeaderTitle(null);
        listView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                updateHeaderTitle();
            }
        });
        updateHeaderTitle();

        simpleActionBar.createMenu().addItem(MENU_ADD, R.drawable.msg_add);
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ADD) {
                    showRoleEditor(null);
                }
            }
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRows();
    }

    private void refreshRows() {
        AiController.getInstance().loadRoles();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
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
        boolean topVisible = listView.layoutManager.findFirstVisibleItemPosition() <= 0;
        setHeaderTitle(topVisible ? null : getTitle());
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.AIChatRolesInfo), "exteraGramPlaceholders", "🎭"));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AIChatSuggestedRoles)));

        List<Role> suggestedRoles = AiController.getInstance().getSuggestedRoles();
        for (int i = 0; i < suggestedRoles.size(); i++) {
            Role role = suggestedRoles.get(i);
            items.add(RoleCell.Factory.asRoleCell(role, v -> selectRole(role)));
        }
        items.add(UItem.asShadow(null));

        List<Role> customRoles = AiController.getInstance().getRoles();
        if (customRoles.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.AIChatCustomRoles)));
        for (int i = 0; i < customRoles.size(); i++) {
            Role role = customRoles.get(i);
            items.add(RoleCell.Factory.asRoleCell(role, v -> selectRole(role)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        Object object = item.object;
        if (!(object instanceof Role)) {
            return;
        }
        Role role = (Role) object;
        if (role.isSuggestion()) {
            showRolePreview(role);
        } else {
            showRoleEditor(role);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        Object object = item.object;
        if (!(object instanceof Role)) {
            return false;
        }
        Role role = (Role) object;
        if (role.isSuggestion()) {
            return false;
        }
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> showRoleEditor(role))
                .add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
                    if (AndroidUtilities.addToClipboard(role.getName() + "\n" + role.getPrompt())) {
                        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                    }
                })
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDeleteRole(role))
                .setScrimViewBackground(listView.getClipBackground(view))
                .setGravity(LocaleController.isRTL ? 3 : 5)
                .show();
        return true;
    }

    private void selectRole(Role role) {
        if (role.isSelected()) {
            return;
        }
        AiConfig.setSelectedRole(role);
        refreshRows();
    }

    /** Suggested roles can only be inspected; the editor stays reserved for the user's own roles. */
    private void showRolePreview(Role role) {
        if (getParentActivity() == null) {
            return;
        }
        new AIEditorAlert.CreateAiStyleAlert(getParentActivity(), getResourceProvider())
                .setLocalStylePreview(role.getName(), role.getPrompt(), role.getEmojiId(), ROLE_NAME_MAX_LENGTH, ROLE_PROMPT_MAX_LENGTH)
                .show();
    }

    private void showRoleEditor(Role role) {
        if (getParentActivity() == null) {
            return;
        }
        final boolean isEditing = role != null;
        new AIEditorAlert.CreateAiStyleAlert(getParentActivity(), getResourceProvider())
                .setLocalStyle(
                        isEditing ? role.getName() : "",
                        isEditing ? role.getPrompt() : "",
                        isEditing ? role.getEmojiId() : 0L,
                        isEditing,
                        ROLE_NAME_MAX_LENGTH,
                        ROLE_PROMPT_MAX_LENGTH,
                        (name, prompt, emojiId) -> saveRole(role, name, prompt, emojiId != null ? emojiId : 0L)
                )
                .show();
    }

    private boolean saveRole(Role role, String name, String prompt, long emojiId) {
        Role updated = new Role(name, prompt).setEmojiId(emojiId);
        boolean selected = role != null && role.isSelected();
        boolean saved;
        if (role != null && !role.isSuggestion()) {
            saved = AiController.getInstance().updateRole(role, updated);
        } else {
            saved = AiController.getInstance().addRole(updated);
        }
        if (saved) {
            if (selected) {
                AiConfig.setSelectedRole(updated);
            }
            refreshRows();
        }
        return saved;
    }

    private void confirmDeleteRole(Role role) {
        Activity parentActivity = getParentActivity();
        if (parentActivity == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(parentActivity)
                .setTitle(LocaleController.getString(R.string.Delete))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.AIChatDeleteRoleInfo, role.getName())))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, which) -> deleteRole(role))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteRole(Role role) {
        boolean wasSelected = role.isSelected();
        if (AiController.getInstance().removeRole(role)) {
            if (wasSelected) {
                AiConfig.setSelectedRole(AiController.getInstance().getSuggestedRoles().get(0));
            }
            refreshRows();
        }
    }
}
