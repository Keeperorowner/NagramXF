package com.exteragram.messenger.plugins.ui.components;

import android.app.Activity;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StrikethroughSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Stories.recorder.HintView2;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import tw.nekomimi.nekogram.TextViewEffects;
import tw.nekomimi.nekogram.helpers.MessageHelper;

public class InstallPluginBottomSheet extends BottomSheet {
    private HintView2 currentHint;
    private boolean enableAfterInstallation;

    public InstallPluginBottomSheet(final BaseFragment fragment, final PluginsController.PluginValidationResult validationResult, final PluginInstallParams params) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        Activity activity = fragment.getParentActivity();
        fixNavigationBar();

        final Plugin existingPlugin = validationResult != null && validationResult.plugin != null ? PluginsController.getInstance().plugins.get(validationResult.plugin.getId()) : null;
        final boolean updating = existingPlugin != null;

        FrameLayout root = new FrameLayout(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (validationResult.plugin.getPack() != null && validationResult.plugin.getIndex() >= 0) {
            BackupImageView imageView = new BackupImageView(activity);
            imageView.setRoundRadius(AndroidUtilities.dp(12));
            imageView.getImageReceiver().setAutoRepeat(1);
            imageView.getImageReceiver().setAutoRepeatCount(1);
            content.addView(imageView, LayoutHelper.createLinear(78, 78, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));
            MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImageByIndex(imageView, validationResult.plugin.getPack(), validationResult.plugin.getIndex(), "150_150");
        } else {
            RLottieImageView imageView = new RLottieImageView(activity);
            imageView.setScaleType(ImageView.ScaleType.CENTER);
            imageView.setImageResource(R.drawable.msg_settings);
            imageView.setColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
            imageView.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(78), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
            content.addView(imageView, LayoutHelper.createLinear(78, 78, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));
        }

        TextView titleView = new TextView(activity);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTextSize(1, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(validationResult.plugin.getName());
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 40, 16, 40, 0));

        TextViewEffects subtitleView = new TextViewEffects(activity, resourcesProvider);
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        subtitleView.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink, resourcesProvider));
        subtitleView.setTextSize(1, 14);
        SpannableStringBuilder subtitle = new SpannableStringBuilder(LocaleController.getString(R.string.PluginVersion)).append(" ");
        int strikeStart = subtitle.length();
        if (updating && existingPlugin != null) {
            subtitle.append(existingPlugin.getVersion()).append(" → ").append(validationResult.plugin.getVersion());
            subtitle.setSpan(new StrikethroughSpan(), strikeStart, strikeStart + existingPlugin.getVersion().length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            subtitle.append(validationResult.plugin.getVersion());
        }
        subtitle.append(" • ").append(LocaleUtils.formatWithUsernames(validationResult.plugin.getAuthor(), fragment, new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        }));
        subtitleView.setText(subtitle);
        content.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 4, 21, 0));

        final LinearLayout sourceBadge = new LinearLayout(activity);
        sourceBadge.setOrientation(LinearLayout.HORIZONTAL);
        sourceBadge.setGravity(Gravity.CENTER);
        final int sourceColor = Theme.getColor(params.trusted ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_text_RedRegular, resourcesProvider);
        sourceBadge.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), AndroidUtilities.multiplyAlphaComponent(sourceColor, 26f / 255f)));
        sourceBadge.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
        ScaleStateListAnimator.apply(sourceBadge, 0.05f, 1.5f);
        ImageView sourceIcon = new ImageView(activity);
        sourceIcon.setImageResource(params.trusted ? R.drawable.msg2_policy : R.drawable.filled_unknown);
        sourceIcon.setColorFilter(sourceColor);
        sourceBadge.addView(sourceIcon, LayoutHelper.createLinear(14, 14, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        TextView sourceText = new TextView(activity);
        sourceText.setTextColor(sourceColor);
        sourceText.setTextSize(1, 13);
        sourceText.setText(LocaleController.getString(params.trusted ? R.string.PluginSourceTrusted : R.string.PluginSourceUnknown));
        sourceBadge.addView(sourceText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        sourceBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSourceHint(sourceBadge, params.trusted);
            }
        });
        content.addView(sourceBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        TextViewEffects descriptionView = new TextViewEffects(activity, resourcesProvider);
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        descriptionView.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink, resourcesProvider));
        descriptionView.setTextSize(1, 15);
        descriptionView.setText(LocaleUtils.fullyFormatText(validationResult.plugin.getDescription(), fragment, new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        }));
        content.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 28, 21, 0));

        if (validationResult.plugin.getRequirements() != null && !validationResult.plugin.getRequirements().isEmpty()) {
            PluginRequirementsView requirementsView = new PluginRequirementsView(activity, resourcesProvider);
            requirementsView.setRequirements(validationResult.plugin.getRequirements());
            content.addView(requirementsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 12, 21, 0));
        }

        ButtonWithCounterView installButton = new ButtonWithCounterView(activity, true, resourcesProvider);
        installButton.setText(LocaleController.getString(updating ? R.string.UpdatePlugin : R.string.InstallPlugin), false);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (installButton.isLoading()) {
                    return;
                }
                PluginsController.PluginsEngine engine = PluginsController.engines.get(PluginsConstants.PYTHON);
                if (!(engine instanceof PythonPluginsEngine)) {
                    return;
                }
                final AtomicBoolean cancelled = new AtomicBoolean(false);
                final AlertDialog progressDialog = new AlertDialog.Builder(activity, resourcesProvider)
                        .setTitle(LocaleController.getString(updating ? R.string.UpdatePlugin : R.string.InstallPlugin))
                        .setMessage(LocaleController.getString(R.string.PluginDependencyPreparing))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> cancelled.set(true))
                        .setOnCancelListener(dialog -> cancelled.set(true))
                        .create();
                progressDialog.setCanceledOnTouchOutside(false);
                installButton.setLoading(true);
                installButton.setSubText(LocaleController.getString(R.string.PluginDependencyPreparing), false);
                setCanDismissWithSwipe(false);
                setCanDismissWithTouchOutside(false);
                progressDialog.show();
                ((PythonPluginsEngine) engine).loadPluginFromFile(params.filePath, validationResult.plugin, new com.exteragram.messenger.plugins.pip.PipController.InstallerDelegate() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void onProgress(String text) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!TextUtils.isEmpty(text)) {
                                installButton.setSubText(text, false);
                                progressDialog.setMessage(text);
                            }
                        });
                    }
                }, new Utilities.Callback<String>() {
                    @Override
                    public void run(final String error) {
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                installButton.setLoading(false);
                                installButton.setSubText(null, true);
                                setCanDismissWithSwipe(true);
                                setCanDismissWithTouchOutside(true);
                                if (progressDialog.isShowing()) {
                                    progressDialog.dismiss();
                                }
                                if (error == null) {
                                    dismiss();
                                    if (enableAfterInstallation && !ExteraConfig.pluginsSafeMode) {
                                        PluginsController.getInstance().setPluginEnabled(validationResult.plugin.getId(), true, new Utilities.Callback<String>() {
                                            @Override
                                            public void run(final String enableError) {
                                                AndroidUtilities.runOnUIThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        if (enableError == null) {
                                                            showSuccessBulletin(fragment, validationResult.plugin, updating);
                                                        } else {
                                                            BulletinFactory.of(fragment)
                                                                    .createSimpleBulletin(
                                                                            R.raw.error,
                                                                            LocaleController.formatString(R.string.PluginInstalledButFailedToEnable, validationResult.plugin.getName()),
                                                                            LocaleUtils.createCopySpan(fragment),
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    if (AndroidUtilities.addToClipboard(enableError)) {
                                                                                        BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                                                                                    }
                                                                                }
                                                                            })
                                                                    .show();
                                                        }
                                                    }
                                                });
                                            }
                                        });
                                    } else {
                                        showSuccessBulletin(fragment, validationResult.plugin, updating);
                                    }
                                } else if (!PythonPluginsEngine.INSTALL_CANCELLED.equals(error)) {
                                    BulletinFactory.of(topBulletinContainer, resourcesProvider)
                                            .createSimpleBulletin(
                                                    R.raw.error,
                                                    LocaleController.formatString(R.string.PluginInstallError, validationResult.plugin.getName()),
                                                    LocaleUtils.createCopySpan(fragment),
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (AndroidUtilities.addToClipboard(error)) {
                                                                BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                                                            }
                                                                }
                                                    })
                                            .show();
                                }
                            }
                        });
                    }
                });
            }
        });
        content.addView(installButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 28, 16, 16));

        if (!validationResult.plugin.isEnabled() && !ExteraConfig.pluginsSafeMode) {
            final CheckBox2 checkBox = new CheckBox2(activity, 21, resourcesProvider);
            checkBox.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
            checkBox.setDrawUnchecked(true);
            checkBox.setChecked(enableAfterInstallation, false);
            checkBox.setDrawBackgroundAsArc(10);
            TextView textView = new TextView(activity);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            textView.setTextSize(1, 14);
            textView.setText(LocaleController.getString(R.string.EnableAfterInstallation));
            FrameLayout checkWrap = new FrameLayout(activity);
            checkWrap.addView(checkBox, LayoutHelper.createFrame(21, 21, Gravity.CENTER));
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 1, AndroidUtilities.dp(8)));
            ScaleStateListAnimator.apply(row, 0.05f, 1.2f);
            row.addView(checkWrap, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
            row.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkBox.setChecked(!checkBox.isChecked(), true);
                    enableAfterInstallation = checkBox.isChecked();
                }
            });
            content.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));
        }

        ImageView openButton = new ImageView(activity);
        openButton.setScaleType(ImageView.ScaleType.CENTER);
        openButton.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.msg_openin).mutate());
        openButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider));
        openButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider), 1, AndroidUtilities.dp(20)));
        ScaleStateListAnimator.apply(openButton, 0.15f, 1.5f);
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File file = new File(params.filePath);
                if (file.exists()) {
                    String name = file.getName();
                    if (PluginFileViewer.getInstance().open(fragment, file, name)) {
                        dismiss();
                    }
                }
            }
        });
        root.addView(openButton, LayoutHelper.createFrame(40, 40, Gravity.END | Gravity.TOP, 0, 16, 16, 0));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(root);
        setCustomView(scrollView);

        if (ExteraConfig.preferences != null && !ExteraConfig.preferences.getBoolean(params.trusted ? "trusted_source_hint" : "unknown_source_hint", false)) {
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    sourceBadge.performClick();
                    ExteraConfig.editor.putBoolean(params.trusted ? "trusted_source_hint" : "unknown_source_hint", true).apply();
                }
            }, 600L);
        }
    }

    private void showSourceHint(final View anchor, boolean trusted) {
        if (container == null || getContext() == null) {
            return;
        }
        if (currentHint != null) {
            currentHint.hide();
            currentHint = null;
        }
        CharSequence text = AndroidUtilities.replaceTags(LocaleController.getString(trusted ? R.string.PluginSourceTrustedInfo : R.string.PluginSourceUnknownInfo));
        final HintView2 hintView = new HintView2(getContext(), 3)
                .setMultilineText(true)
                .setBgColor(Theme.getColor(Theme.key_undo_background, resourcesProvider))
                .setTextColor(Theme.getColor(Theme.key_undo_infoColor, resourcesProvider))
                .setText(text)
                .setTextAlign(Layout.Alignment.ALIGN_CENTER)
                .allowBlur(true)
                .setRounding(12.0f)
                .setRoundingWithCornerEffect(false);
        hintView.setMaxWidthPx(HintView2.cutInFancyHalf(text, hintView.getTextPaint()));
        container.addView(hintView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.TOP | Gravity.FILL_HORIZONTAL, 32, 0, 32, 0));
        currentHint = hintView;
        container.post(new Runnable() {
            @Override
            public void run() {
                int[] anchorPosition = new int[2];
                anchor.getLocationInWindow(anchorPosition);
                int[] containerPosition = new int[2];
                container.getLocationInWindow(containerPosition);
                anchorPosition[0] -= containerPosition[0];
                anchorPosition[1] -= containerPosition[1];
                final float anchorCenterX = anchorPosition[0] + anchor.getMeasuredWidth() / 2.0f;
                final float hintCenterX = hintView.getLeft() + hintView.getMeasuredWidth() / 2.0f;
                hintView.setTranslationY(anchorPosition[1] - hintView.getMeasuredHeight() - AndroidUtilities.dp(6));
                hintView.setJointPx(0.5f, anchorCenterX - hintCenterX);
                hintView.setDuration(5500L);
                hintView.show();
            }
        });
    }

    private void showSuccessBulletin(final BaseFragment fragment, final Plugin plugin, final boolean updating) {
        final BulletinFactory bulletinFactory = BulletinFactory.of(fragment);
        final CharSequence text = LocaleController.formatString(updating ? R.string.PluginUpdated : R.string.PluginInstalled, plugin.getName());
        if (plugin.getPack() == null || plugin.getIndex() < 0) {
            bulletinFactory.createSimpleBulletin(R.raw.contact_check, text).show();
            return;
        }

        final AtomicBoolean shown = new AtomicBoolean(false);
        final Runnable fallback = new Runnable() {
            @Override
            public void run() {
                if (shown.getAndSet(true)) {
                    return;
                }
                bulletinFactory.createSimpleBulletin(R.raw.contact_check, text).show();
            }
        };
        AndroidUtilities.runOnUIThread(fallback, 300L);

        TLRPC.TL_inputStickerSetShortName inputStickerSet = new TLRPC.TL_inputStickerSetShortName();
        inputStickerSet.short_name = plugin.getPack();
        MediaDataController.getInstance(UserConfig.selectedAccount).getStickerSet(inputStickerSet, 0, false, new Utilities.Callback<TLRPC.TL_messages_stickerSet>() {
            @Override
            public void run(TLRPC.TL_messages_stickerSet set) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (shown.get()) {
                            return;
                        }
                        TLRPC.Document document = null;
                        if (set != null && set.documents != null && plugin.getIndex() >= 0 && plugin.getIndex() < set.documents.size()) {
                            document = set.documents.get(plugin.getIndex());
                        }
                        if (document == null || shown.getAndSet(true)) {
                            return;
                        }
                        AndroidUtilities.cancelRunOnUIThread(fallback);
                        bulletinFactory.createSimpleBulletin(document, text).show();
                    }
                });
            }
        });
    }

    @Override
    public void dismiss() {
        if (currentHint != null) {
            currentHint.hide();
            currentHint = null;
        }
        super.dismiss();
    }

    @Override
    protected void onSwipeStarts() {
        if (currentHint != null) {
            currentHint.hide();
            currentHint = null;
        }
    }

    public static class PluginInstallParams {
        public final String filePath;
        public final boolean trusted;

        public PluginInstallParams(String filePath, boolean trusted) {
            this.filePath = filePath;
            this.trusted = trusted;
        }

        public static PluginInstallParams of(MessageObject messageObject) {
            if (messageObject == null) {
                return null;
            }
            boolean trusted = false;
            if (messageObject.isForwarded()) {
                Long forwardedFromId = messageObject.getForwardedFromId();
                if (forwardedFromId != null) {
                    long sourceId = -forwardedFromId;
                    trusted = BadgesController.INSTANCE.isTrusted(sourceId) || BadgesController.INSTANCE.isExtera(sourceId);
                }
            } else if (messageObject.isFromChannel() && !messageObject.isFromChat()) {
                long sourceId = -messageObject.getDialogId();
                trusted = BadgesController.INSTANCE.isTrusted(sourceId) || BadgesController.INSTANCE.isExtera(sourceId);
            }
            return new PluginInstallParams(MessageHelper.getPathToMessage(messageObject), trusted);
        }
    }
}

