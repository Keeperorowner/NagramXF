package tw.nekomimi.nekogram.ui.cells;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackgroundGradientDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MotionBackgroundDrawable;

@SuppressLint("ViewConstructor")
public class DeletedMessagesPreviewCell extends FrameLayout {

    private final DeletedMessagePreviewCard previewCell;

    public DeletedMessagesPreviewCell(Context context) {
        super(context);

        setClipChildren(false);
        setClipToPadding(false);

        previewCell = new DeletedMessagePreviewCard(context);
        previewCell.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(previewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public void refresh() {
        previewCell.refresh();
    }

    public void invalidateTime() {
        previewCell.invalidateTime();
    }

    private static class DeletedMessagePreviewCard extends LinearLayout {

        private final ChatMessageCell cell;
        private final MessageObject messageObject;
        private final Drawable monetBackgroundDrawable;
        private final Drawable shadowDrawable;
        private BackgroundGradientDrawable.Disposable backgroundGradientDisposable;

        public DeletedMessagePreviewCard(Context context) {
            super(context);

            setWillNotDraw(false);
            setOrientation(VERTICAL);
            setPadding(0, dp(11), 0, dp(11));

            monetBackgroundDrawable = new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray));
            shadowDrawable = Theme.getThemedDrawable(context, R.drawable.greydivider_bottom, Theme.getColor(Theme.key_windowBackgroundGrayShadow));

            int currentAccount = UserConfig.selectedAccount;
            int date = (int) (System.currentTimeMillis() / 1000) - 3540;
            long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();

            TLRPC.TL_message message = new TLRPC.TL_message();
            message.message = getString(R.string.FontSizePreviewLine2);
            message.date = date;
            message.dialog_id = 1L;
            message.flags = 259;
            message.from_id = new TLRPC.TL_peerUser();
            message.from_id.user_id = clientUserId;
            message.id = 1;
            message.media = new TLRPC.TL_messageMediaEmpty();
            message.out = false;
            message.peer_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = 0;
            message.ayuDeleted = true;

            messageObject = new MessageObject(currentAccount, message, true, false);
            messageObject.forceAvatar = true;
            messageObject.eventId = 1;
            messageObject.resetLayout();

            TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (currentUser != null) {
                messageObject.customName = ContactsController.formatName(currentUser.first_name, currentUser.last_name);
                if (currentUser.photo == null) {
                    messageObject.customAvatarDrawable = new AvatarDrawable(currentUser, false);
                }
            }

            cell = new ChatMessageCell(context, currentAccount) {
                @Override
                protected void dispatchDraw(Canvas canvas) {
                    if (getAvatarImage() != null && getAvatarImage().getImageHeight() != 0) {
                        getAvatarImage().setImageCoords(
                                getAvatarImage().getImageX(),
                                getMeasuredHeight() - getAvatarImage().getImageHeight() - dp(4),
                                getAvatarImage().getImageWidth(),
                                getAvatarImage().getImageHeight()
                        );
                        getAvatarImage().setRoundRadius((int) (getAvatarImage().getImageHeight() / 2f));
                        getAvatarImage().draw(canvas);
                    }
                    super.dispatchDraw(canvas);
                }
            };
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return false;
                }
            });
            cell.isChat = true;
            cell.setFullyDraw(true);
            cell.setMessageObject(messageObject, null, false, false, false);
            addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        public void refresh() {
            cell.setMessageObject(messageObject, null, false, false, false);
            cell.invalidate();
        }

        public void invalidateTime() {
            messageObject.messageOwner.ayuDeleted = false;
            cell.setMessageObject(messageObject, null, false, false, false);
            messageObject.messageOwner.ayuDeleted = true;
            cell.setMessageObject(messageObject, null, false, false, false);
            cell.invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            boolean isMonetTheme = Theme.getActiveTheme() != null && Theme.getActiveTheme().isMonet();
            Drawable drawable = isMonetTheme ? monetBackgroundDrawable : Theme.getCachedWallpaperNonBlocking();
            if (drawable == null) {
                canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundGray));
            } else {
                drawable.setAlpha(drawable == monetBackgroundDrawable ? 150 : 255);
                if (drawable instanceof ColorDrawable || drawable instanceof GradientDrawable || drawable instanceof MotionBackgroundDrawable) {
                    drawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    if (drawable instanceof BackgroundGradientDrawable backgroundGradientDrawable) {
                        backgroundGradientDisposable = backgroundGradientDrawable.drawExactBoundsSize(canvas, this);
                    } else {
                        drawable.draw(canvas);
                    }
                } else if (drawable instanceof BitmapDrawable bitmapDrawable) {
                    if (bitmapDrawable.getTileModeX() == Shader.TileMode.REPEAT) {
                        canvas.save();
                        float scale = 2.0f / AndroidUtilities.density;
                        canvas.scale(scale, scale);
                        drawable.setBounds(0, 0, (int) Math.ceil(getMeasuredWidth() / scale), (int) Math.ceil(getMeasuredHeight() / scale));
                    } else {
                        int viewHeight = getMeasuredHeight();
                        float scaleX = (float) getMeasuredWidth() / drawable.getIntrinsicWidth();
                        float scaleY = (float) viewHeight / drawable.getIntrinsicHeight();
                        float scale = Math.max(scaleX, scaleY);
                        int width = (int) Math.ceil(drawable.getIntrinsicWidth() * scale);
                        int height = (int) Math.ceil(drawable.getIntrinsicHeight() * scale);
                        int x = (getMeasuredWidth() - width) / 2;
                        int y = (viewHeight - height) / 2;
                        canvas.save();
                        canvas.clipRect(0, 0, width, getMeasuredHeight());
                        drawable.setBounds(x, y, x + width, y + height);
                    }
                    drawable.draw(canvas);
                    canvas.restore();
                }
            }

            shadowDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            shadowDrawable.draw(canvas);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (backgroundGradientDisposable != null) {
                backgroundGradientDisposable.dispose();
                backgroundGradientDisposable = null;
            }
        }

        @Override
        protected void dispatchSetPressed(boolean pressed) {
        }
    }
}
