package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;

import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Role;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;

public class EditRoleActivity extends BaseFragment {

    private static Role currentRole;
    private EditTextBoldCursor nameField;
    private EditTextBoldCursor promptField;
    private OutlineTextContainerView nameFieldContainer;
    private OutlineTextContainerView promptFieldContainer;
    private View doneButton;

    public EditRoleActivity() {
    }

    public EditRoleActivity(Role role) {
        currentRole = role;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(currentRole != null ? R.string.AIChatEditRole : R.string.AIChatNewRole));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    saveRole();
                }
            }
        });

        doneButton = actionBar.createMenu().addItemWithWidth(1, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneButton.setContentDescription(LocaleController.getString(R.string.Done));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        fragmentView = layout;

        nameFieldContainer = new OutlineTextContainerView(context);
        nameFieldContainer.setText(LocaleController.getString(R.string.AIChatRoleName));
        nameField = createEditText(context, 1);
        nameField.setOnFocusChangeListener((v, hasFocus) -> nameFieldContainer.animateSelection(hasFocus ? 1.0f : 0.0f));
        nameFieldContainer.addView(nameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 0));
        nameFieldContainer.attachEditText(nameField);
        layout.addView(nameFieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 24, 24, 0));

        promptFieldContainer = new OutlineTextContainerView(context);
        promptFieldContainer.setText(LocaleController.getString(R.string.AIChatRolePrompt));
        promptField = createEditText(context, 8);
        promptField.setOnFocusChangeListener((v, hasFocus) -> promptFieldContainer.animateSelection(hasFocus ? 1.0f : 0.0f));
        promptFieldContainer.addView(promptField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 0));
        promptFieldContainer.attachEditText(promptField);
        layout.addView(promptFieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 24, 24, 0));

        if (currentRole != null) {
            nameField.setText(currentRole.getName());
            promptField.setText(currentRole.getPrompt());
        }

        return fragmentView;
    }

    private EditTextBoldCursor createEditText(Context context, int maxLines) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(1, 16);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setBackground(null);
        editText.setMaxLines(maxLines);
        if (maxLines == 1) {
            editText.setSingleLine(true);
        }
        editText.setInputType(maxLines > 1 ? 147457 : 1);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        editText.setCursorWidth(1.5f);
        editText.setGravity(LocaleController.isRTL ? 5 : 3);
        int dp = AndroidUtilities.dp(16);
        editText.setPadding(0, dp, 0, dp);
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setMinHeight(AndroidUtilities.dp(36));
        return editText;
    }

    private void saveRole() {
        String name = nameField.getText().toString().trim();
        String prompt = promptField.getText().toString().trim();
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(prompt)) {
            AndroidUtilities.shakeView(TextUtils.isEmpty(name) ? nameFieldContainer : promptFieldContainer);
            return;
        }

        Role newRole = new Role(name, prompt);
        if (currentRole != null) {
            AiController.getInstance().updateRole(currentRole, newRole);
        } else {
            AiController.getInstance().addRole(newRole);
        }
        finishFragment();
    }

    @Override
    public void onFragmentDestroy() {
        currentRole = null;
        super.onFragmentDestroy();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen) {
            nameField.requestFocus();
            AndroidUtilities.showKeyboard(nameField);
        }
    }
}
