package com.exteragram.messenger.ai.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public class GenerateFromMessageBottomSheet extends BottomSheet {

    private final EditTextBoldCursor promptField;
    private final OutlineTextContainerView promptFieldContainer;
    private boolean useHistory;
    private boolean includeImage;
    BaseFragment parentFragment;

    public GenerateFromMessageBottomSheet(BaseFragment fragment, Context context, Utilities.Callback<GenerationData> callback, boolean showHistory) {
        this("", null, fragment, context, callback, showHistory);
    }

    public GenerateFromMessageBottomSheet(String text, String imagePath, BaseFragment fragment, Context context, Utilities.Callback<GenerationData> callback) {
        this(text, imagePath, fragment, context, callback, AiConfig.saveHistory);
    }

    public GenerateFromMessageBottomSheet(String text, String imagePath, BaseFragment fragment, Context context, Utilities.Callback<GenerationData> callback, boolean showHistory) {
        super(context, true, fragment.getResourceProvider());
        fixNavigationBar();
        this.smoothKeyboardAnimationEnabled = true;
        this.parentFragment = fragment;
        this.useHistory = AiConfig.saveHistory;
        this.includeImage = AiController.canSendImage(imagePath);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 51));

        FrameLayout fieldFrame = new FrameLayout(context);
        fieldFrame.setClipChildren(false);
        linearLayout.addView(fieldFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

        promptFieldContainer = new OutlineTextContainerView(context, this.resourcesProvider);
        promptFieldContainer.setText(LocaleController.getString(R.string.AIChatPromptHint));
        fieldFrame.addView(promptFieldContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1, 0, 0, 0, 0));

        promptField = new EditTextBoldCursor(context);
        promptField.setTextSize(1, 16);
        promptField.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        promptField.setBackground(null);
        promptField.setMaxLines(8);
        if (!TextUtils.isEmpty(text)) {
            promptField.setText(text);
        }
        promptField.setInputType(147457);
        promptField.setImeOptions(268435456);
        promptField.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        promptField.setCursorWidth(1.5f);
        promptField.setGravity(LocaleController.isRTL ? 5 : 3);
        promptField.setOnFocusChangeListener((v, hasFocus) -> promptFieldContainer.animateSelection(hasFocus ? 1.0f : 0.0f));
        promptField.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((event.getAction() & 0xFF) == MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        promptField.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        promptField.setCursorSize(AndroidUtilities.dp(20));
        promptFieldContainer.addView(promptField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 16, 0, 16));
        promptFieldContainer.attachEditText(promptField);

        if ((AiConfig.saveHistory && showHistory) || includeImage) {
            LinearLayout checkboxRow = new LinearLayout(context);
            checkboxRow.setOrientation(LinearLayout.HORIZONTAL);
            checkboxRow.setGravity(3);

            if (AiConfig.saveHistory && showHistory) {
                checkboxRow.addView(createCheckbox(context, LocaleController.getString(R.string.AIChatMessageHistory), useHistory, checked -> useHistory = checked),
                        LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }

            if (includeImage) {
                if (AiConfig.saveHistory && showHistory) {
                    View spacer = new View(context);
                    checkboxRow.addView(spacer, LayoutHelper.createLinear(8, LayoutHelper.MATCH_PARENT));
                }
                checkboxRow.addView(createCheckbox(context, LocaleController.getString(R.string.AttachPhoto), includeImage, checked -> includeImage = checked),
                        LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }

            linearLayout.addView(checkboxRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1, 0, 16, 0, 0));
        }

        ButtonWithCounterView button = new ButtonWithCounterView(context, this.resourcesProvider);
        button.setText(LocaleController.getString(R.string.AIChatProceed), false);
        button.setOnClickListener(v -> {
            String promptText = promptField.getText().toString();
            if (TextUtils.isEmpty(promptText)) {
                if (!includeImage) {
                    AndroidUtilities.shakeViewSpring(promptFieldContainer);
                    v.performHapticFeedback(0);
                    return;
                }
                promptText = LocaleController.getString(R.string.AttachPhoto);
            }
            dismiss();
            String finalImagePath = includeImage ? imagePath : null;
            callback.run(new GenerationData(promptText, useHistory, finalImagePath));
        });
        linearLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 16, 0, 16));

        setCustomView(scrollView);
        setTitle(LocaleController.getString(R.string.AIChatGenerate), true);
    }

    private LinearLayout createCheckbox(Context context, String text, boolean initialChecked, Utilities.Callback<Boolean> onChanged) {
        CheckBox2 checkBox = new CheckBox2(context, 21, this.resourcesProvider);
        checkBox.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
        checkBox.setDrawUnchecked(true);
        checkBox.setChecked(initialChecked, false);
        checkBox.setDrawBackgroundAsArc(10);

        TextView label = new TextView(context);
        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setTextSize(1, 14);
        label.setText(text);

        FrameLayout checkFrame = new FrameLayout(context);
        checkFrame.addView(checkBox, LayoutHelper.createFrame(21, 21, 17, 0, 0, 0, 0));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
        row.addView(checkFrame, LayoutHelper.createLinear(24, 24, 16, 0, 0, 6, 0));
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 16));
        row.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked(), true);
            onChanged.run(checkBox.isChecked());
        });
        ScaleStateListAnimator.apply(row, 0.05f, 1.2f);
        row.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 6, 6));
        return row;
    }

    public static class GenerationData {
        private final String prompt;
        private final boolean useHistory;
        private final String imagePath;

        public GenerationData(String prompt, boolean useHistory, String imagePath) {
            this.prompt = prompt;
            this.useHistory = useHistory;
            this.imagePath = imagePath;
        }

        public String prompt() {
            return prompt;
        }

        public boolean useHistory() {
            return useHistory;
        }

        public String imagePath() {
            return imagePath;
        }
    }
}
