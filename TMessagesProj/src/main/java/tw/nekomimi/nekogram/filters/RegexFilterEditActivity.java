package tw.nekomimi.nekogram.filters;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tw.nekomimi.nekogram.utils.LocaleUtil;

public class RegexFilterEditActivity extends BaseFragment {

    private static final int HELP_BUTTON = 1;
    private static final int DONE_BUTTON = 2;

    private final int filterIdx;
    private final AyuFilter.FilterModel filterModel;
    private final long targetDialogId;
    private final int chatFilterIdx;
    private final String prefillText;
    private final boolean canSelectSharedTarget;

    private boolean enabled;
    private boolean caseInsensitive;
    private boolean reversed;
    private boolean addToSharedFilters;

    private EditTextBoldCursor editField;
    private OutlineTextContainerView editFieldContainer;
    private View doneButton;
    private TextView errorTextView;

    private CheckBoxCell enabledCell;
    private CheckBoxCell caseInsensitiveCell;
    private CheckBoxCell reversedCell;
    private CheckBoxCell addToSharedFiltersCell;

    public RegexFilterEditActivity() {
        filterIdx = -1;
        filterModel = null;
        enabled = true;
        caseInsensitive = true;
        reversed = false;
        targetDialogId = 0L;
        chatFilterIdx = -1;
        prefillText = null;
        canSelectSharedTarget = false;
        addToSharedFilters = false;
    }

    public RegexFilterEditActivity(long dialogId) {
        filterIdx = -1;
        filterModel = null;
        enabled = true;
        caseInsensitive = true;
        reversed = false;
        targetDialogId = dialogId;
        chatFilterIdx = -1;
        prefillText = null;
        canSelectSharedTarget = false;
        addToSharedFilters = false;
    }

    public RegexFilterEditActivity(long dialogId, String prefillText) {
        filterIdx = -1;
        filterModel = null;
        enabled = true;
        caseInsensitive = true;
        reversed = false;
        targetDialogId = dialogId;
        chatFilterIdx = -1;
        this.prefillText = prefillText;
        canSelectSharedTarget = true;
        addToSharedFilters = false;
    }

    public RegexFilterEditActivity(long dialogId, int chatFilterIdx) {
        this.filterIdx = -1;
        this.targetDialogId = dialogId;
        this.chatFilterIdx = chatFilterIdx;
        this.filterModel = AyuFilter.getChatFiltersForDialog(dialogId).size() > chatFilterIdx && chatFilterIdx >= 0 ? AyuFilter.getChatFiltersForDialog(dialogId).get(chatFilterIdx) : null;
        this.enabled = this.filterModel == null || this.filterModel.enabled;
        this.caseInsensitive = this.filterModel == null || this.filterModel.caseInsensitive;
        this.reversed = this.filterModel != null && this.filterModel.reversed;
        this.prefillText = null;
        this.canSelectSharedTarget = false;
        this.addToSharedFilters = false;
    }

    public RegexFilterEditActivity(int filterIdx) {
        this.filterIdx = filterIdx;
        this.filterModel = AyuFilter.getRegexFilters().get(filterIdx);
        this.enabled = filterModel.enabled;
        this.caseInsensitive = filterModel.caseInsensitive;
        this.reversed = filterModel.reversed;
        this.targetDialogId = 0L;
        this.chatFilterIdx = -1;
        this.prefillText = null;
        this.canSelectSharedTarget = false;
        this.addToSharedFilters = false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View createView(Context context) {
        boolean isEdit = filterIdx != -1 || chatFilterIdx != -1;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(isEdit ? R.string.RegexFiltersEdit : R.string.RegexFiltersAdd));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == HELP_BUTTON) {
                    showHelpDialog();
                } else if (id == DONE_BUTTON) {
                    save();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(HELP_BUTTON, R.drawable.msg_help_14);
        doneButton = menu.addItemWithWidth(DONE_BUTTON, R.drawable.ic_ab_done, dp(56));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        container.setOnTouchListener((v, event) -> true);
        fragmentView = container;

        editFieldContainer = new OutlineTextContainerView(context, getResourceProvider());
        editFieldContainer.setText(getString(R.string.RegexFiltersPattern));
        container.addView(editFieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 24, 24, 24, 0));

        editField = new EditTextBoldCursor(context);
        editField.setTextSize(1, 18f);
        editField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editField.setBackground(null);
        editField.setImeOptions(6);
        editField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        editField.setCursorSize(dp(20));
        editField.setCursorWidth(1.5f);
        editField.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
        editField.setHighlightColor(Theme.getColor(Theme.key_chat_inTextSelectionHighlight));
        editField.setGravity(Gravity.TOP | (org.telegram.messenger.LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        editField.setPadding(dp(16), dp(16), dp(16), dp(16));
        editField.setOnFocusChangeListener((view, focused) -> editFieldContainer.animateSelection(focused || editField.length() > 0));
        editField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (doneButton != null) {
                    doneButton.setEnabled(!TextUtils.isEmpty(s));
                }
                clearError();
                if (editFieldContainer != null) {
                    editFieldContainer.animateSelection(editField.isFocused() || s.length() > 0, true);
                }
            }
        });
        editFieldContainer.addView(editField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        editFieldContainer.attachEditText(editField);

        if (filterModel != null) {
            editField.setText(filterModel.regex);
        } else if (!TextUtils.isEmpty(prefillText)) {
            editField.setText(prefillText);
        }
        editField.setSelection(editField.length());
        editFieldContainer.animateSelection(editField.length() > 0, false);

        errorTextView = new TextView(context);
        errorTextView.setTextSize(1, 15f);
        errorTextView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        errorTextView.setGravity(org.telegram.messenger.LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        errorTextView.setVisibility(View.GONE);
        container.addView(errorTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, org.telegram.messenger.LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT, 24, 10, 24, 0));

        if (!isEdit && targetDialogId != 0L && canSelectSharedTarget) {
            addToSharedFiltersCell = createOptionCell(context, getString(R.string.RegexFiltersTextSelectionAddtoShared), addToSharedFilters, true, v -> {
                addToSharedFilters = !addToSharedFilters;
                addToSharedFiltersCell.setChecked(addToSharedFilters, true);
            });
            container.addView(addToSharedFiltersCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 12f, 0f, 0f));
        }

        enabledCell = createOptionCell(context, getString(R.string.RegexFiltersFilterEnabled), enabled, true, v -> {
            enabled = !enabled;
            enabledCell.setChecked(enabled, true);
        });
        boolean enabledHasTopGap = addToSharedFiltersCell == null;
        container.addView(enabledCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, enabledHasTopGap ? 12f : 0f, 0f, 0f));

        caseInsensitiveCell = createOptionCell(context, getString(R.string.RegexFiltersCaseInsensitive), caseInsensitive, true, v -> {
            caseInsensitive = !caseInsensitive;
            caseInsensitiveCell.setChecked(caseInsensitive, true);
        });
        container.addView(caseInsensitiveCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        reversedCell = createOptionCell(context, getString(R.string.RegexFiltersReversed), reversed, false, v -> {
            reversed = !reversed;
            reversedCell.setChecked(reversed, true);
        });
        container.addView(reversedCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        doneButton.setEnabled(editField.length() > 0);
        return fragmentView;
    }

    private CheckBoxCell createOptionCell(Context context, CharSequence text, boolean checked, boolean divider, View.OnClickListener listener) {
        CheckBoxCell cell = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, getResourceProvider());
        cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        cell.setText(text, null, checked, divider);
        int leftPadding = dp(LocaleController.isRTL ? 16 : 8);
        int rightPadding = dp(LocaleController.isRTL ? 8 : 16);
        cell.setPadding(leftPadding, 0, rightPadding, 0);
        cell.setOnClickListener(listener);
        return cell;
    }

    private void save() {
        String text = editField == null || editField.getText() == null ? "" : editField.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            showError(getString(R.string.RegexFiltersAddError));
            return;
        }

        try {
            Pattern.compile(text);
        } catch (PatternSyntaxException e) {
            String errorText = e.getMessage();
            if (!TextUtils.isEmpty(errorText)) {
                errorText = errorText.replace(text, "");
            }
            showError(TextUtils.isEmpty(errorText) ? getString(R.string.RegexFiltersAddError) : errorText.trim());
            return;
        }

        persistFilter(text);
        finishFragment();
    }

    private void persistFilter(String text) {
        if (chatFilterIdx != -1 && targetDialogId != 0L) {
            ArrayList<AyuFilter.ChatFilterEntry> entries = new ArrayList<>(AyuFilter.getChatFilterEntries());
            for (AyuFilter.ChatFilterEntry entry : entries) {
                if (entry.dialogId == targetDialogId && entry.filters != null && chatFilterIdx >= 0 && chatFilterIdx < entry.filters.size()) {
                    AyuFilter.FilterModel model = entry.filters.get(chatFilterIdx);
                    applyToModel(model, text);
                    AyuFilter.saveChatFilterEntries(entries);
                    return;
                }
            }
            return;
        }

        if (filterIdx != -1) {
            ArrayList<AyuFilter.FilterModel> filters = new ArrayList<>(AyuFilter.getRegexFilters());
            if (filterIdx >= 0 && filterIdx < filters.size()) {
                applyToModel(filters.get(filterIdx), text);
                AyuFilter.saveFilter(filters);
            }
            return;
        }

        AyuFilter.FilterModel model = new AyuFilter.FilterModel();
        applyToModel(model, text);
        if (targetDialogId != 0L && !(canSelectSharedTarget && addToSharedFilters)) {
            ArrayList<AyuFilter.ChatFilterEntry> entries = new ArrayList<>(AyuFilter.getChatFilterEntries());
            AyuFilter.ChatFilterEntry target = null;
            for (AyuFilter.ChatFilterEntry entry : entries) {
                if (entry.dialogId == targetDialogId) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                target = new AyuFilter.ChatFilterEntry();
                target.dialogId = targetDialogId;
                entries.add(target);
            }
            if (target.filters == null) {
                target.filters = new ArrayList<>();
            }
            target.filters.add(0, model);
            AyuFilter.saveChatFilterEntries(entries);
        } else {
            ArrayList<AyuFilter.FilterModel> filters = new ArrayList<>(AyuFilter.getRegexFilters());
            filters.add(0, model);
            AyuFilter.saveFilter(filters);
        }
    }

    private void applyToModel(AyuFilter.FilterModel model, String text) {
        model.regex = text;
        model.enabled = enabled;
        model.caseInsensitive = caseInsensitive;
        model.reversed = reversed;
    }

    private void showHelpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
        builder.setTitle(getString(R.string.RegexFilters));
        builder.setMessage(LocaleUtil.INSTANCE.htmlToString(getString(R.string.RegexFiltersAddDescription)));
        builder.setPositiveButton(getString(R.string.Open), (dialog, which) -> Browser.openUrl(getParentActivity(), "https://regex101.com"));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void clearError() {
        if (errorTextView != null) {
            errorTextView.setVisibility(View.GONE);
            errorTextView.setText(null);
        }
        if (editFieldContainer != null) {
            editFieldContainer.animateError(0f);
        }
    }

    private void showError(String errorText) {
        if (errorTextView != null) {
            errorTextView.setText(LocaleUtil.INSTANCE.htmlToString("<b>" + errorText + "</b>"));
            errorTextView.setVisibility(View.VISIBLE);
        }
        if (editFieldContainer != null) {
            editFieldContainer.animateError(1f);
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersAddError)).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        boolean animations = preferences.getBoolean("view_animations", true);
        if (!animations) {
            editField.requestFocus();
            AndroidUtilities.showKeyboard(editField);
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen) {
            AndroidUtilities.runOnUIThread(() -> {
                if (editField != null) {
                    editField.requestFocus();
                    AndroidUtilities.showKeyboard(editField);
                }
            }, 200);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(editField, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(editField, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        themeDescriptions.add(new ThemeDescription(errorTextView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_text_RedBold));
        return themeDescriptions;
    }
}
