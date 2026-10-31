package tw.nekomimi.nekogram.settings;

import android.content.Context;

import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GroupCreateUserCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;

/** Avatar-backed selection row for peers and whole chat kinds. */
final class AyuSaveExclusionCell extends GroupCreateUserCell implements Theme.Colorable {
    private final BackupImageView avatarView;
    private final Theme.ResourcesProvider resourcesProvider;
    private AyuSaveExclusionCategory category;
    private AvatarDrawable categoryAvatar;

    AyuSaveExclusionCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, 1, 0, true, false, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        BackupImageView avatar = null;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof BackupImageView) {
                avatar = (BackupImageView) getChildAt(i);
                break;
            }
        }
        avatarView = avatar;
    }

    void setCategory(AyuSaveExclusionCategory category) {
        this.category = category;
        setObject("contacts", category.getTitle(), null);
        if (categoryAvatar == null) {
            categoryAvatar = new AvatarDrawable();
        }
        refreshCategoryAvatar();
        setDrawDivider(false);
    }

    void setPeer(TLObject peer, CharSequence name, CharSequence status) {
        category = null;
        setObject(peer, name, status);
        setDrawDivider(false);
    }

    private void refreshCategoryAvatar() {
        if (category != null && avatarView != null && categoryAvatar != null) {
            category.configureAvatar(getContext(), categoryAvatar, resourcesProvider);
            avatarView.setImageDrawable(categoryAvatar);
        }
    }

    @Override
    public void updateColors() {
        if (avatarView == null) {
            return;
        }
        update(0);
        SimpleTextView status = getStatusTextView();
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof SimpleTextView && getChildAt(i) != status) {
                ((SimpleTextView) getChildAt(i)).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            }
        }
        if (status != null) {
            int colorKey = status.getTag() instanceof Integer ? (Integer) status.getTag() : Theme.key_windowBackgroundWhiteGrayText;
            status.setTextColor(Theme.getColor(colorKey, resourcesProvider));
            status.setEmojiColor(status.getTextColor());
        }
        refreshCategoryAvatar();
        avatarView.invalidate();
        invalidate();
    }
}
