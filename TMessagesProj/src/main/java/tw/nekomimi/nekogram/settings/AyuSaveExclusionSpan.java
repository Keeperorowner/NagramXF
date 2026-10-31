package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.GroupCreateSpan;

/** Removable selection chip whose stored identity is supplied by the picker. */
final class AyuSaveExclusionSpan extends GroupCreateSpan implements Theme.Colorable {
    private final Drawable secretLock;
    private final AyuSaveExclusionCategory category;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean placeholder;

    AyuSaveExclusionSpan(Context context, TLObject peer, String name, boolean secret,
                         Theme.ResourcesProvider resourcesProvider) {
        super(context, peer, peer == null ? contact(name) : null, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        category = null;
        placeholder = peer == null;
        secretLock = secret ? context.getResources().getDrawable(R.drawable.list_secret).mutate() : null;
        isFlag = true;
        updateColors();
    }

    AyuSaveExclusionSpan(Context context, AyuSaveExclusionCategory category, Theme.ResourcesProvider resourcesProvider) {
        super(context, null, contact(category.getTitle()), resourcesProvider);
        this.category = category;
        this.resourcesProvider = resourcesProvider;
        placeholder = false;
        secretLock = null;
        isFlag = true;
        updateColors();
    }

    private static ContactsController.Contact contact(String title) {
        ContactsController.Contact contact = new ContactsController.Contact();
        contact.contact_id = title.hashCode();
        contact.first_name = title;
        return contact;
    }

    @Override
    public void updateColors() {
        if (category != null) {
            category.configureAvatar(getContext(), getAvatarDrawable(), resourcesProvider);
        } else if (placeholder) {
            getAvatarDrawable().setAvatarType(AvatarDrawable.AVATAR_TYPE_ANONYMOUS);
        }
        super.updateColors();
        if (secretLock != null) {
            secretLock.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_secretIcon, resourcesProvider), PorterDuff.Mode.SRC_IN));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (secretLock != null && !isDeleting()) {
            secretLock.setBounds(dp(21), dp(20), dp(32), dp(32));
            secretLock.draw(canvas);
        }
    }
}
