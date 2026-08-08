package com.exteragram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.animation.OvershootInterpolator;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.utils.AppUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.TextViewEffects;

import java.io.PrintWriter;
import java.io.StringWriter;

@SuppressLint("ViewConstructor")
public class PluginCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout headerLayout;
    private final LinearLayout textContainer;
    private final BackupImageView imageView;
    private final TextView pluginNameView;
    private final TextViewEffects subtitleView;
    private final TextViewEffects descriptionView;
    private final PluginRequirementsView requirementsLayout;
    private final ImageView shareButton;
    private final ImageView openInButton;
    private final ImageView pinButton;
    private final ImageView settingsButton;
    private final ImageView deleteButton;
    private final Switch checkBox;
    private Plugin plugin;
    private PluginCellDelegate delegate;
    private boolean compact;

    public PluginCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
        setClipChildren(false);
        setClipToPadding(false);

        FrameLayout card = new FrameLayout(context);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        card.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 16, 16, 16, 8));

        headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.VERTICAL);
        content.addView(headerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        imageView = new BackupImageView(context) {
            @Override
            @SuppressLint("DrawAllocation")
            protected void onDraw(Canvas canvas) {
                Path path = new Path();
                float radius = AndroidUtilities.dp(8);
                path.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(path);
                super.onDraw(canvas);
                canvas.restore();
            }
        };
        imageView.setVisibility(GONE);
        imageView.setRoundRadius(AndroidUtilities.dp(8));
        imageView.getImageReceiver().setAutoRepeat(1);
        imageView.getImageReceiver().setAutoRepeatCount(1);
        headerLayout.addView(imageView, LayoutHelper.createLinear(56, 56, Gravity.START, 0, 0, 0, 12));

        textContainer = new LinearLayout(context);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        headerLayout.addView(textContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        pluginNameView = new TextView(context);
        pluginNameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        pluginNameView.setTextSize(1, 18);
        pluginNameView.setTypeface(AndroidUtilities.bold());
        pluginNameView.setEllipsize(TextUtils.TruncateAt.END);
        textContainer.addView(pluginNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new TextViewEffects(context, resourcesProvider);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        subtitleView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        subtitleView.setTextSize(1, 14);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        textContainer.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        checkBox = new Switch(context);
        checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        checkBox.setFocusable(false);
        addView(checkBox, LayoutHelper.createFrame(37, 40, Gravity.TOP | Gravity.END, 0, 16, 24, 0));

        descriptionView = new TextViewEffects(context, resourcesProvider);
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        descriptionView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        descriptionView.setTextSize(1, 15);
        content.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        requirementsLayout = new PluginRequirementsView(context, resourcesProvider);
        requirementsLayout.setVisibility(GONE);
        content.addView(requirementsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
        content.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1.0f / AndroidUtilities.density, 0, 0, 12, 0, 8));

        FrameLayout actionsWrap = new FrameLayout(context);
        content.addView(actionsWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actionsWrap.addView(actions, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.CENTER_VERTICAL | Gravity.START, 0, 0, 48, 0));

        shareButton = createButton(context, R.drawable.msg_share, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (delegate != null) {
                    delegate.sharePlugin();
                }
            }
        });
        actions.addView(shareButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        openInButton = createButton(context, R.drawable.msg_openin, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (delegate != null) {
                    delegate.openInExternalApp();
                }
            }
        });
        actions.addView(openInButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        pinButton = createButton(context, R.drawable.msg_pin, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (delegate != null) {
                    delegate.pinPlugin(PluginCell.this);
                }
            }
        });
        actions.addView(pinButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        settingsButton = createButton(context, R.drawable.msg_settings, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (delegate != null) {
                    delegate.openPluginSettings();
                }
            }
        });
        settingsButton.setVisibility(GONE);
        actions.addView(settingsButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        deleteButton = createButton(context, R.drawable.msg_delete, true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (delegate != null) {
                    delegate.deletePlugin();
                }
            }
        });
        actionsWrap.addView(deleteButton, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | Gravity.END));

        setCompact(ExteraConfig.pluginsCompactView);
    }

    private ImageView createButton(Context context, int iconRes, boolean destructive, OnClickListener listener) {
        AppCompatImageView button = new AppCompatImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        setButtonIcon(button, iconRes, destructive);
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(destructive ? Theme.key_dialogRedIcon : Theme.key_dialogButtonSelector, resourcesProvider), 1, AndroidUtilities.dp(20)));
        ScaleStateListAnimator.apply(button, 0.15f, 1.5f);
        applyClickAnimation(button);
        button.setOnClickListener(listener);
        return button;
    }

    private void setButtonIcon(ImageView button, int iconRes, boolean destructive) {
        Drawable drawable = ContextCompat.getDrawable(getContext(), iconRes);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(Theme.getColor(destructive ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider));
            button.setImageDrawable(drawable);
        } else {
            button.setImageResource(iconRes);
        }
    }

    private void applyClickAnimation(View view) {
        view.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80L).start();
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    if (action == MotionEvent.ACTION_UP && event.getX() >= 0.0f && event.getX() <= v.getWidth() && event.getY() >= 0.0f && event.getY() <= v.getHeight()) {
                        v.performClick();
                    }
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(350L).setInterpolator(new OvershootInterpolator(1.5f)).start();
                    return true;
                }
                return false;
            }
        });
    }

    public void setCompact(boolean compact) {
        if (this.compact == compact) {
            return;
        }
        this.compact = compact;
        headerLayout.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams imageParams = (LinearLayout.LayoutParams) imageView.getLayoutParams();
        imageParams.width = AndroidUtilities.dp(compact ? 49 : 56);
        imageParams.height = AndroidUtilities.dp(compact ? 49 : 56);
        imageParams.rightMargin = compact ? AndroidUtilities.dp(16) : 0;
        imageParams.bottomMargin = compact ? 0 : AndroidUtilities.dp(12);
        imageView.setLayoutParams(imageParams);
        pluginNameView.setSingleLine(compact);
        pluginNameView.setMaxLines(compact ? 1 : 2);
        subtitleView.setSingleLine(compact);
        subtitleView.setMaxLines(compact ? 1 : 2);
        LinearLayout.LayoutParams textParams = (LinearLayout.LayoutParams) textContainer.getLayoutParams();
        textParams.gravity = compact ? Gravity.CENTER_VERTICAL : Gravity.TOP;
        textContainer.setLayoutParams(textParams);
        updateTextPadding();
        requestLayout();
    }

    public void set(Plugin plugin, PluginCellDelegate delegate) {
        if (plugin == null || delegate == null) {
            return;
        }
        this.plugin = plugin;
        this.delegate = delegate;
        setCompact(ExteraConfig.pluginsCompactView);
        setPinned(PluginsController.isPluginPinned(plugin.getId()));
        openInButton.setVisibility(delegate.canOpenInExternalApp() ? VISIBLE : GONE);

        boolean hasIcon = plugin.getPack() != null && plugin.getIndex() >= 0;
        imageView.setVisibility(hasIcon ? VISIBLE : GONE);
        if (hasIcon) {
            MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImageByIndex(imageView, plugin.getPack(), plugin.getIndex(), "100_100");
        } else {
            imageView.setImage((ImageLocation) null, (String) null, (Drawable) null, 0, (Object) null);
        }

        pluginNameView.setText(plugin.getName());
        CharSequence authorText = LocaleUtils.formatWithUsernames(plugin.getAuthor());
        CharSequence versionPrefix = compact ? "v" : LocaleController.getString(R.string.PluginVersion) + " ";
        subtitleView.setText(new SpannableStringBuilder(versionPrefix).append(plugin.getVersion()).append(" • ").append(authorText));

        if (plugin.isNotResponding()) {
            bindNotRespondingState();
        } else if (plugin.hasError()) {
            bindErrorState();
        } else {
            bindNormalState();
        }
        requirementsLayout.setRequirements(plugin.getRequirements());
        updateTextPadding();

        checkBox.setChecked(plugin.isEnabled(), false);
        checkBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (PluginCell.this.delegate != null) {
                    PluginCell.this.delegate.togglePlugin(PluginCell.this);
                }
            }
        });
        AndroidUtilities.updateViewVisibilityAnimated(settingsButton, plugin.isEnabled() && PluginsController.getInstance().hasPluginSettings(plugin.getId()), 0.5f, true, false);
    }

    private void bindNormalState() {
        descriptionView.setText(LocaleUtils.fullyFormatText(plugin.getDescription()));
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        descriptionView.setTypeface(Typeface.DEFAULT);
        descriptionView.setTextSize(1, 15);
        descriptionView.setOnClickListener(null);
        checkBox.setVisibility(VISIBLE);
        updateDeleteButton();
    }

    private void bindNotRespondingState() {
        descriptionView.setText(LocaleController.getString(R.string.PluginIsNotResponding));
        descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        descriptionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        descriptionView.setTextSize(1, 12);
        descriptionView.setOnClickListener(null);
        checkBox.setVisibility(GONE);
        updateDeleteButton();
    }

    private void updateDeleteButton() {
        if (plugin == null) {
            return;
        }
        boolean notResponding = plugin.isNotResponding();
        deleteButton.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(notResponding ? Theme.key_windowBackgroundWhiteGrayIcon : Theme.key_text_RedRegular, resourcesProvider),
                PorterDuff.Mode.MULTIPLY));
        deleteButton.setBackground(Theme.createSelectorDrawable(
                notResponding
                        ? Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider)
                        : Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider), 0.12f),
                1,
                AndroidUtilities.dp(20)));
        deleteButton.setImageResource(notResponding ? R.drawable.ic_ab_other : R.drawable.msg_delete);
    }

    private void bindErrorState() {
        Throwable error = plugin.getError();
        descriptionView.setText(error != null ? error.getLocalizedMessage() : "Error");
        descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        descriptionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        descriptionView.setTextSize(1, 12);
        descriptionView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                if (lastFragment != null && AndroidUtilities.addToClipboard(AppUtils.stackTraceToString(plugin.getError()))) {
                    BulletinFactory.of(lastFragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            }
        });
        checkBox.setVisibility(GONE);
        updateDeleteButton();
    }

    private void updateTextPadding() {
        if (plugin == null) {
            return;
        }
        int rightPadding = ((!compact || plugin.hasError()) && (compact || (plugin.getPack() != null && plugin.getIndex() >= 0) || plugin.hasError())) ? 0 : AndroidUtilities.dp(61);
        pluginNameView.setPadding(0, 0, rightPadding, 0);
        pluginNameView.post(new Runnable() {
            @Override
            public void run() {
                subtitleView.setPadding(0, 0, pluginNameView.getLineCount() > 1 ? 0 : rightPadding, 0);
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    private String toStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }

    public void setChecked(boolean checked, boolean animated) {
        checkBox.setChecked(checked, animated);
    }

    public void setPinned(boolean pinned) {
        setButtonIcon(pinButton, pinned ? R.drawable.msg_unpin : R.drawable.msg_pin, false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginIsNotResponding);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginIsNotResponding);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginIsNotResponding && plugin != null) {
            set(plugin, delegate);
            return;
        }
        if ((id == NotificationCenter.pluginSettingsRegistered || id == NotificationCenter.pluginSettingsUnregistered) && plugin != null && args != null && args.length > 0 && args[0] instanceof String) {
            String pluginId = (String) args[0];
            if (TextUtils.equals(plugin.getId(), pluginId)) {
                boolean visible = id == NotificationCenter.pluginSettingsRegistered && plugin.isEnabled();
                AndroidUtilities.updateViewVisibilityAnimated(settingsButton, visible, 0.5f, true, true);
            }
        }
    }

    public static final class Factory extends UItem.UItemFactory<PluginCell> {
        static {
            setup(new Factory());
        }

        @Override
        public PluginCell createView(Context context, org.telegram.ui.Components.RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new PluginCell(context, resourcesProvider);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof PluginCell) {
                Plugin plugin = item.object instanceof Plugin ? (Plugin) item.object : null;
                PluginCellDelegate delegate = item.object2 instanceof PluginCellDelegate ? (PluginCellDelegate) item.object2 : null;
                ((PluginCell) view).set(plugin, delegate);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.id == b.id && a.intValue == b.intValue;
        }

        public static UItem of(Plugin plugin, PluginCellDelegate delegate) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = plugin != null ? plugin.getId().hashCode() : 0;
            item.object = plugin;
            item.object2 = delegate;
            if (plugin != null) {
                item.intValue = (plugin.isNotResponding() ? 1 : 0)
                        | (plugin.isEnabled() ? 2 : 0)
                        | (plugin.hasError() ? 4 : 0);
            }
            return item;
        }
    }
}

