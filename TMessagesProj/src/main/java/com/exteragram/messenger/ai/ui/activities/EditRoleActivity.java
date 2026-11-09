package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Role;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SectionsScrollView;

public class EditRoleActivity extends BaseFragment {

    private static Role currentRole;
    private EditTextBoldCursor nameField;
    private EditTextBoldCursor promptField;
    private OutlineTextContainerView nameFieldContainer;
    private OutlineTextContainerView promptFieldContainer;
    private View doneButton;
    private SectionsScrollView scrollView;

    public EditRoleActivity() {
    }

    public EditRoleActivity(Role role) {
        currentRole = role;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(currentRole != null ? R.string.AIChatEditRole : R.string.AIChatNewRole));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    saveRole();
                }
            }
        });

        SimpleActionBarMenu menu = simpleActionBar.createMenu();
        doneButton = menu.addItemWithWidth(1, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneButton.setContentDescription(LocaleController.getString(R.string.Done));

        FrameLayout root = new FrameLayout(context);
        fragmentView = root;

        SectionsScrollView.SectionsLinearLayout layout = new SectionsScrollView.SectionsLinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        scrollView = new SectionsScrollView(context, layout, getResourceProvider());
        scrollView.setDrawBackground(true);
        scrollView.setFillViewport(true);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        scrollView.addView(layout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        nameFieldContainer = new OutlineTextContainerView(context);
        nameFieldContainer.setText(LocaleController.getString(R.string.AIChatRoleName));
        // the fields draw their own outline: no section card behind them
        nameFieldContainer.setTag(RecyclerListView.TAG_NOT_SECTION);
        nameField = createEditText(context, 1);
        nameField.setOnFocusChangeListener((v, hasFocus) -> nameFieldContainer.animateSelection(hasFocus ? 1.0f : 0.0f));
        nameFieldContainer.addView(nameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 0));
        nameFieldContainer.attachEditText(nameField);
        layout.addView(nameFieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 24, 24, 0));

        promptFieldContainer = new OutlineTextContainerView(context);
        promptFieldContainer.setText(LocaleController.getString(R.string.AIChatRolePrompt));
        promptFieldContainer.setTag(RecyclerListView.TAG_NOT_SECTION);
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

    /** The scrollable form the glass header samples. */
    @Override
    protected SectionsScrollView getScrollViewForSimpleGlass() {
        return scrollView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
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
