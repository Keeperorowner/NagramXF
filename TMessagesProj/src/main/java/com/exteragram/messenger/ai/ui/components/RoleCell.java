package com.exteragram.messenger.ai.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.ai.data.Role;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * One row of the role picker: emoji badge, name, a single-line prompt preview and the radio button.
 */
@SuppressLint("ViewConstructor")
public class RoleCell extends FrameLayout {

    private static final long DEFAULT_EMOJI_ID = 5359441070201513074L;

    private final BackupImageView emojiView;
    private final RadioButton radioButton;
    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView subtitleView;
    private final TextView titleView;

    private boolean needDivider;

    public RoleCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);

        emojiView = new BackupImageView(context);
        addView(emojiView, LayoutHelper.createFrame(32, 32.0f, 19, 18.0f, 0.0f, 0.0f, 0.0f));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        addView(texts, LayoutHelper.createFrame(-1, -2.0f, 19, 68.0f, 0.0f, 62.0f, 0.0f));

        titleView = new TextView(context);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTextSize(1, 16.0f);
        titleView.setSingleLine(true);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(3);
        titleView.setIncludeFontPadding(false);
        texts.addView(titleView, LayoutHelper.createLinear(-1, -2));

        subtitleView = new TextView(context);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        subtitleView.setTextSize(1, 13.0f);
        subtitleView.setSingleLine(true);
        subtitleView.setMaxLines(1);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setGravity(3);
        subtitleView.setIncludeFontPadding(false);
        texts.addView(subtitleView, LayoutHelper.createLinear(-1, -2, 0.0f, 4.0f, 0.0f, 0.0f));

        radioButton = new RadioButton(context);
        radioButton.setSize(AndroidUtilities.dp(20.0f));
        radioButton.setColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_radioBackgroundChecked));
        addView(radioButton, LayoutHelper.createFrame(48, 48.0f, 21, 0.0f, 0.0f, 8.0f, 0.0f));
    }

    public RadioButton getRadioButton() {
        return radioButton;
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(62.0f), MeasureSpec.EXACTLY)
        );
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(AndroidUtilities.dp(68.0f), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    public void set(Role role, int currentAccount, boolean checked, boolean divider) {
        needDivider = divider;
        titleView.setText(role.getName());
        subtitleView.setText(role.getPrompt());
        subtitleView.setVisibility(View.VISIBLE);
        radioButton.setChecked(checked, true);

        AnimatedEmojiDrawable drawable = new AnimatedEmojiDrawable(AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW, currentAccount, role.getEmojiId() != 0 ? role.getEmojiId() : DEFAULT_EMOJI_ID);
        drawable.setColorFilter(Theme.getAnimatedEmojiColorFilter(resourcesProvider));
        emojiView.setAnimatedEmojiDrawable(drawable);
    }

    public static class Factory extends UItem.UItemFactory<RoleCell> {
        static {
            UItem.UItemFactory.setup(new Factory());
        }

        @Override
        public RoleCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new RoleCell(context, currentAccount, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (!(view instanceof RoleCell)) {
                return;
            }
            Object object = item.object;
            if (object instanceof Role) {
                RoleCell cell = (RoleCell) view;
                cell.set((Role) object, adapter.currentAccount, item.checked, divider);
                cell.getRadioButton().setOnClickListener(item.clickCallback);
            }
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            Object first = a.object;
            Object second = b.object;
            if (!(first instanceof Role) || !(second instanceof Role)) {
                return false;
            }
            Role role = (Role) first;
            Role other = (Role) second;
            return a.checked == b.checked
                    && role.getEmojiId() == other.getEmojiId()
                    && TextUtils.equals(role.getName(), other.getName())
                    && TextUtils.equals(role.getPrompt(), other.getPrompt());
        }

        public static UItem asRoleCell(Role role, View.OnClickListener onClickListener) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = role;
            item.checked = role.isSelected();
            item.clickCallback = onClickListener;
            return item;
        }
    }
}
