package com.radolyn.ayugram.preferences.components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

public class AccountCell extends FrameLayout {

    private final AvatarDrawable avatarDrawable;
    private final ImageView checkImageView;
    private final BackupImageView imageView;
    private final TextView infoTextView;
    private final SimpleTextView textView;

    public AccountCell(Context context) {
        super(context);

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        this.avatarDrawable = avatarDrawable;
        avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_FILTER_GROUPS);
        avatarDrawable.setColor(-18621, -618956);

        BackupImageView backupImageView = new BackupImageView(context);
        this.imageView = backupImageView;
        backupImageView.setRoundRadius(AndroidUtilities.dp(36));
        backupImageView.setImageDrawable(avatarDrawable);
        addView(backupImageView, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.TOP, 10, 10, 0, 0));

        SimpleTextView simpleTextView = new SimpleTextView(context);
        this.textView = simpleTextView;
        simpleTextView.setTextSize(15);
        simpleTextView.setTypeface(AndroidUtilities.bold());
        simpleTextView.setEllipsizeByGradient(true);
        simpleTextView.setMaxLines(1);
        simpleTextView.setGravity(Gravity.LEFT | Gravity.TOP);
        addView(simpleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 61, 10, 52, 0));
        simpleTextView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem));

        TextView textView = new TextView(context);
        this.infoTextView = textView;
        textView.setTextColor(Theme.getColor(Theme.key_voipgroup_lastSeenText));
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        textView.setLines(1);
        textView.setMaxLines(1);
        textView.setSingleLine(true);
        textView.setMaxWidth(AndroidUtilities.dp(320));
        textView.setGravity(Gravity.LEFT | Gravity.TOP);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 61, 27, 8, 0));

        ImageView imageView = new ImageView(context);
        this.checkImageView = imageView;
        imageView.setImageResource(R.drawable.account_check);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_menuItemCheck), PorterDuff.Mode.MULTIPLY));
        addView(imageView, LayoutHelper.createFrame(40, LayoutHelper.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP, 0, 0, 6, 0));

        textView.setText(LocaleController.getString(R.string.GhostModeGlobalSettingsDescription));
        simpleTextView.setText(LocaleController.getString(R.string.GhostModeGlobalSettings));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(56), MeasureSpec.EXACTLY));
    }

    @Override
    public void setSelected(boolean z) {
        checkImageView.setVisibility(z ? VISIBLE : INVISIBLE);
    }

    public AvatarDrawable getAvatarDrawable() {
        return avatarDrawable;
    }
}
