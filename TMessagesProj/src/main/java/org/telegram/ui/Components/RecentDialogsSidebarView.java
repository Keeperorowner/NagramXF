package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.LinkedList;

import tw.nekomimi.nekogram.BackButtonMenuRecent;
import tw.nekomimi.nekogram.ChatHistoryUtils;

public class RecentDialogsSidebarView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    public interface Delegate {
        void onDialogSelected(long dialogId);
    }

    private static final int PANEL_WIDTH_DP = 60;
    private static final int HANDLE_WIDTH_DP = 26;
    private static final int HANDLE_HEIGHT_DP = 56;
    private static final int ROW_HEIGHT_DP = 75;
    private static final int MAX_DIALOGS = 20;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final int panelColor;
    private final int pressedColor;
    private final FrameLayout panelView;
    private final RecyclerListView listView;
    private final ImageView toggleButton;
    private final Adapter adapter;
    private final ArrayList<Long> dialogIds = new ArrayList<>();

    private Delegate delegate;
    private long currentDialogId;
    private boolean opened;
    private boolean handleVisible = true;
    private ObjectAnimator toggleAnimator;
    private ObjectAnimator handleAnimator;
    private int currentTopInset = -1;
    private int currentBottomInset = -1;

    public RecentDialogsSidebarView(Context context, int currentAccount, long currentDialogId, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.currentAccount = currentAccount;
        this.currentDialogId = currentDialogId;
        this.resourcesProvider = resourcesProvider;

        setClipChildren(false);
        setClipToPadding(false);

        panelColor = Theme.getColor(Theme.key_chat_goDownButton, resourcesProvider);
        pressedColor = ColorUtils.blendARGB(panelColor, Theme.getColor(Theme.key_listSelector, resourcesProvider), 0.25f);

        panelView = new FrameLayout(context);
        panelView.setBackgroundColor(panelColor);
        addView(panelView, LayoutHelper.createFrame(PANEL_WIDTH_DP, LayoutHelper.MATCH_PARENT, Gravity.END | Gravity.TOP));

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(OVER_SCROLL_ALWAYS);
        listView.setItemAnimator(null);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (delegate == null || position < 0 || position >= dialogIds.size()) {
                return;
            }
            delegate.onDialogSelected(dialogIds.get(position));
        });
        panelView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        toggleButton = new ImageView(context);
        toggleButton.setScaleType(ImageView.ScaleType.CENTER);
        toggleButton.setContentDescription(LocaleController.getString(R.string.RecentChats));
        toggleButton.setColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon, resourcesProvider));
        Drawable toggleBackground = ContextCompat.getDrawable(context, R.drawable.ic_bar_bg_v);
        if (toggleBackground != null) {
            toggleBackground = toggleBackground.mutate();
            toggleBackground.setColorFilter(new PorterDuffColorFilter(panelColor, PorterDuff.Mode.MULTIPLY));
            toggleButton.setBackground(toggleBackground);
        }
        toggleButton.setOnClickListener(v -> setOpened(!opened, true));
        addView(toggleButton, LayoutHelper.createFrame(HANDLE_WIDTH_DP, HANDLE_HEIGHT_DP, Gravity.START | Gravity.CENTER_VERTICAL));

        updateToggleIcon();
        setTranslationX(AndroidUtilities.dp(PANEL_WIDTH_DP));
        reloadDialogs();
    }

    public static int getTotalWidthDp() {
        return PANEL_WIDTH_DP + HANDLE_WIDTH_DP;
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setCurrentDialogId(long currentDialogId) {
        if (this.currentDialogId == currentDialogId) {
            return;
        }
        this.currentDialogId = currentDialogId;
        reloadDialogs();
    }

    public void setPanelInsets(int top, int bottom) {
        if (currentTopInset == top && currentBottomInset == bottom) {
            return;
        }
        currentTopInset = top;
        currentBottomInset = bottom;
        setPadding(0, top, 0, bottom);
    }

    public void reloadDialogs() {
        LinkedList<Long> recentDialogs = BackButtonMenuRecent.getRecentDialogs(currentAccount);
        dialogIds.clear();
        for (Long dialogId : recentDialogs) {
            if (dialogId == null || dialogId == 0 || dialogId == currentDialogId) {
                continue;
            }
            if (dialogId == UserConfig.getInstance(currentAccount).clientUserId || ChatHistoryUtils.isOfficialDialog(dialogId, currentAccount)) {
                continue;
            }
            if (!ensureDialogLoaded(dialogId)) {
                continue;
            }
            dialogIds.add(dialogId);
            if (dialogIds.size() >= MAX_DIALOGS) {
                break;
            }
        }
        adapter.notifyDataSetChanged();
        boolean hasItems = !dialogIds.isEmpty();
        setVisibility(hasItems ? VISIBLE : GONE);
        if (!hasItems) {
            setOpened(false, false);
        }
    }

    public void setOpened(boolean opened, boolean animated) {
        if (this.opened == opened && (!animated || toggleAnimator == null || !toggleAnimator.isRunning())) {
            return;
        }
        this.opened = opened;
        float targetTranslation = opened ? 0.0f : AndroidUtilities.dp(PANEL_WIDTH_DP);
        if (toggleAnimator != null) {
            toggleAnimator.cancel();
            toggleAnimator = null;
        }
        if (animated) {
            toggleAnimator = ObjectAnimator.ofFloat(this, View.TRANSLATION_X, getTranslationX(), targetTranslation);
            toggleAnimator.setDuration(220L);
            toggleAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            toggleAnimator.start();
        } else {
            setTranslationX(targetTranslation);
        }
        updateToggleIcon();
    }

    public boolean isOpened() {
        return opened;
    }

    public void setHandleVisible(boolean visible, boolean animated) {
        if (handleVisible == visible && (!animated || handleAnimator == null || !handleAnimator.isRunning())) {
            return;
        }
        handleVisible = visible;
        if (handleAnimator != null) {
            handleAnimator.cancel();
            handleAnimator = null;
        }
        if (visible) {
            toggleButton.setVisibility(VISIBLE);
        }
        float targetAlpha = visible ? 1.0f : 0.0f;
        if (animated) {
            handleAnimator = ObjectAnimator.ofFloat(toggleButton, View.ALPHA, toggleButton.getAlpha(), targetAlpha);
            handleAnimator.setDuration(180L);
            handleAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            handleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!handleVisible) {
                        toggleButton.setVisibility(INVISIBLE);
                    }
                }
            });
            handleAnimator.start();
        } else {
            toggleButton.setAlpha(targetAlpha);
            toggleButton.setVisibility(visible ? VISIBLE : INVISIBLE);
        }
    }

    public boolean containsPoint(float x, float y) {
        if (getVisibility() != VISIBLE) {
            return false;
        }
        float left = getX();
        float top = getY();
        return x >= left && x <= left + getWidth() && y >= top && y <= top + getHeight();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            reloadDialogs();
        } else if (id == NotificationCenter.updateInterfaces) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof RecentDialogCell) {
                    ((RecentDialogCell) child).update();
                }
            }
        }
    }

    private void updateToggleIcon() {
        toggleButton.setImageResource(opened ? R.drawable.ic_bar_close : R.drawable.ic_bar_open);
    }

    private boolean ensureDialogLoaded(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user == null) {
                user = loadUserFromDatabase(dialogId);
            }
            return user != null;
        } else if (DialogObject.isChatDialog(dialogId)) {
            long chatId = -dialogId;
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
            if (chat == null) {
                chat = loadChatFromDatabase(chatId);
            }
            return chat != null;
        }
        return false;
    }

    private TLRPC.User loadUserFromDatabase(long userId) {
        ArrayList<Long> userIds = new ArrayList<>(1);
        userIds.add(userId);
        ArrayList<TLRPC.User> users = MessagesStorage.getInstance(currentAccount).getUsers(userIds);
        if (users.isEmpty()) {
            return null;
        }
        TLRPC.User user = users.get(0);
        MessagesController.getInstance(currentAccount).putUser(user, true);
        return user;
    }

    private TLRPC.Chat loadChatFromDatabase(long chatId) {
        ArrayList<Long> chatIds = new ArrayList<>(1);
        chatIds.add(chatId);
        ArrayList<TLRPC.Chat> chats = MessagesStorage.getInstance(currentAccount).getChats(chatIds);
        if (chats.isEmpty()) {
            return null;
        }
        TLRPC.Chat chat = chats.get(0);
        MessagesController.getInstance(currentAccount).putChat(chat, true);
        return chat;
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return dialogIds.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            RecentDialogCell cell = new RecentDialogCell(parent.getContext());
            cell.setBackground(Theme.createRadSelectorDrawable(pressedColor, 0, 0));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((RecentDialogCell) holder.itemView).setDialog(dialogIds.get(position));
        }
    }

    private class RecentDialogCell extends FrameLayout {

        private static final int AVATAR_SIZE_DP = 40;
        private static final int COUNTER_HEIGHT_DP = 16;
        private static final int COUNTER_HORIZONTAL_PADDING_DP = 4;

        private final BackupImageView imageView;
        private final TextView nameTextView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final RectF counterRect = new RectF();
        private final Paint counterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        private long dialogId;
        private int lastUnreadCount = -1;
        private int countWidth;
        private StaticLayout countLayout;

        public RecentDialogCell(Context context) {
            super(context);

            setLayoutParams(new RecyclerView.LayoutParams(AndroidUtilities.dp(PANEL_WIDTH_DP), AndroidUtilities.dp(ROW_HEIGHT_DP)));
            setClipChildren(false);
            setClipToPadding(false);

            imageView = new BackupImageView(context);
            imageView.setRoundRadius(AndroidUtilities.dp(20));
            addView(imageView, LayoutHelper.createFrame(AVATAR_SIZE_DP, AVATAR_SIZE_DP, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 5, 0, 0));

            nameTextView = new TextView(context) {
                @Override
                public void setText(CharSequence text, BufferType type) {
                    super.setText(Emoji.replaceEmoji(text != null ? text : "", getPaint().getFontMetricsInt(), false), type);
                }
            };
            NotificationCenter.listenEmojiLoading(nameTextView);
            nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            nameTextView.setMaxLines(2);
            nameTextView.setLines(2);
            nameTextView.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 4, 46, 4, 0));

            countPaint.setTextSize(AndroidUtilities.dp(10));
            countPaint.setTypeface(AndroidUtilities.bold());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(PANEL_WIDTH_DP), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(ROW_HEIGHT_DP), MeasureSpec.EXACTLY)
            );
        }

        public void setDialog(long dialogId) {
            this.dialogId = dialogId;
            update();
        }

        public void update() {
            if (dialogId == 0) {
                return;
            }
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                avatarDrawable.setInfo(currentAccount, user);
                imageView.setForUserOrChat(user, avatarDrawable);
                nameTextView.setText(user != null ? UserObject.getFirstName(user) : "");
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                avatarDrawable.setInfo(currentAccount, chat);
                imageView.setForUserOrChat(chat, avatarDrawable);
                nameTextView.setText(chat != null ? chat.title : "");
            }
            updateUnreadCount();
            invalidate();
        }

        private void updateUnreadCount() {
            TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId);
            int unreadCount = dialog != null ? dialog.unread_count : 0;
            if (lastUnreadCount == unreadCount) {
                return;
            }
            lastUnreadCount = unreadCount;
            if (unreadCount <= 0) {
                countLayout = null;
                invalidate();
                return;
            }

            String text = unreadCount > 99 ? "+99" : String.valueOf(unreadCount);
            countWidth = Math.max(AndroidUtilities.dp(6), (int) Math.ceil(countPaint.measureText(text)));
            countLayout = new StaticLayout(text, countPaint, countWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
            invalidate();
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            boolean result = super.drawChild(canvas, child, drawingTime);
            if (child == imageView && countLayout != null) {
                drawUnreadBadge(canvas);
            }
            return result;
        }

        private void drawUnreadBadge(Canvas canvas) {
            int badgeHeight = AndroidUtilities.dp(COUNTER_HEIGHT_DP);
            int horizontalPadding = AndroidUtilities.dp(COUNTER_HORIZONTAL_PADDING_DP);
            int badgeWidth = Math.max(badgeHeight, countWidth + horizontalPadding * 2);
            int left = imageView.getLeft() - AndroidUtilities.dp(2);
            int top = imageView.getTop() - AndroidUtilities.dp(2);
            int right = left + badgeWidth;
            int bottom = top + badgeHeight;

            boolean muted = MessagesController.getInstance(currentAccount).isDialogMuted(dialogId, 0);
            countPaint.setColor(Theme.getColor(Theme.key_chat_goDownButtonCounter, resourcesProvider));

            counterRect.set(left, top, right, bottom);
            counterPaint.setColor(Theme.getColor(muted ? Theme.key_chats_unreadCounterMuted : Theme.key_chat_goDownButtonCounterBackground, resourcesProvider));
            canvas.drawRoundRect(counterRect, badgeHeight / 2f, badgeHeight / 2f, counterPaint);

            canvas.save();
            canvas.translate(left + (badgeWidth - countWidth) / 2f, top + (badgeHeight - countLayout.getHeight()) / 2f - AndroidUtilities.dp(0.5f));
            countLayout.draw(canvas);
            canvas.restore();
        }
    }
}
