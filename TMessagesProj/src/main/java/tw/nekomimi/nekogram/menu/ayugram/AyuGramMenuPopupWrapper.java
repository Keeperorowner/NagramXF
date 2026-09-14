package tw.nekomimi.nekogram.menu.ayugram;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.messages.AyuSavePreferences;
import com.radolyn.ayugram.ui.AyuViewDeleted;
import com.radolyn.ayugram.utils.AyuGhostPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.filters.AyuFilter;
import tw.nekomimi.nekogram.filters.RegexChatFiltersListActivity;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;

/**
 * AyuGram 聊天菜单子面板：单容器内 mainPage + detailPage（初始 GONE），
 * 打开 detail 时按内容扩展容器宽度（上限屏宽-40dp），detail 始终铺满容器宽度。
 */
public class AyuGramMenuPopupWrapper {

    public final FrameLayout swipeBack;
    public ActionBarMenuSubItem showFilteredItem;

    private final ChatActivity fragment;
    private final PopupSwipeBackLayout popupSwipeBackLayout;
    private final Theme.ResourcesProvider resourcesProvider;
    private final long chatId;
    private final Runnable dismissMenu;

    private final LinearLayout mainPage;
    private final LinearLayout mainOptionsContainer;
    private final LinearLayout detailPage;
    private final LinearLayout detailOptionsContainer;

    private boolean detailOpen;
    private float detailProgress;
    private int detailWidth;
    private int detailMainWidth;
    private int detailMainHeight;
    private int detailDetailHeight;
    private ValueAnimator detailAnimator;

    private ActionBarMenuSubItem ghostDefaultItem;
    private ActionBarMenuSubItem ghostReadItem;
    private ActionBarMenuSubItem ghostTypingItem;
    private ActionBarMenuSubItem saveDefaultItem;
    private ActionBarMenuSubItem saveExclusionItem;
    private ActionBarMenuSubItem regexDefaultItem;
    private ActionBarMenuSubItem regexExclusionItem;

    private final int touchSlop;

    public AyuGramMenuPopupWrapper(ChatActivity fragment, PopupSwipeBackLayout popupSwipeBackLayout, long chatId, Theme.ResourcesProvider resourcesProvider, Runnable dismissMenu, boolean showGhostMode, boolean showSaveDeleted, boolean showRegexFilters, boolean showViewDeleted, boolean showClearDeleted) {
        this.fragment = fragment;
        this.popupSwipeBackLayout = popupSwipeBackLayout;
        this.chatId = chatId;
        this.resourcesProvider = resourcesProvider;
        this.dismissMenu = dismissMenu;
        Activity parentActivity = fragment.getParentActivity();
        touchSlop = ViewConfiguration.get(parentActivity).getScaledTouchSlop();

        swipeBack = new FrameLayout(parentActivity) {
            boolean decided;
            boolean dragging;
            float downX;
            float downY;
            final Paint dimPaint = new Paint();

            @Override
            protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                boolean result = super.drawChild(canvas, child, drawingTime);
                if (child == mainPage && detailProgress > 0) {
                    dimPaint.setColor(Color.BLACK);
                    dimPaint.setAlpha((int) (detailProgress * 64));
                    canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
                }
                return result;
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (!detailOpen || detailAnimator != null) {
                    return false;
                }
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    decided = false;
                } else if (action == MotionEvent.ACTION_MOVE && !decided) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        decided = true;
                        if (dx > 0 && dx > Math.abs(dy) * 1.5f) {
                            dragging = true;
                            return true;
                        }
                    }
                }
                return dragging;
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (!detailOpen) {
                    return super.onTouchEvent(ev);
                }
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_MOVE) {
                    if (dragging && detailWidth > 0) {
                        float dx = Math.max(0, ev.getX() - downX);
                        applyDetailProgress(Math.max(0, Math.min(1, 1 - dx / detailWidth)));
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (dragging) {
                        animateDetailTo(detailProgress < 0.5f ? 0 : 1);
                    }
                    dragging = false;
                    return true;
                }
                return dragging;
            }

            @Override
            protected void onDetachedFromWindow() {
                resetDetail();
                super.onDetachedFromWindow();
            }
        };

        mainPage = new LinearLayout(parentActivity);
        mainPage.setOrientation(LinearLayout.VERTICAL);
        swipeBack.addView(mainPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        detailPage = new LinearLayout(parentActivity);
        detailPage.setOrientation(LinearLayout.VERTICAL);
        detailPage.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider));
        detailPage.setVisibility(View.GONE);
        swipeBack.addView(detailPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ActionBarMenuSubItem backItem = createBackItem(v -> {
            if (popupSwipeBackLayout != null) {
                popupSwipeBackLayout.closeForeground();
            } else if (dismissMenu != null) {
                dismissMenu.run();
            }
        });
        mainPage.addView(backItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        mainPage.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        ScrollView scrollView = new ScrollView(parentActivity);
        mainOptionsContainer = new LinearLayout(parentActivity);
        mainOptionsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(mainOptionsContainer);
        mainPage.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (showGhostMode) {
            ActionBarMenuSubItem item = addItem(mainOptionsContainer, R.drawable.ayu_ghost, LocaleController.getString(R.string.GhostMode), false);
            View.OnClickListener open = v -> openDetail(buildGhostOptions());
            item.setOnClickListener(open);
            item.setRightIcon(R.drawable.msg_arrowright, open);
        }

        if (showSaveDeleted) {
            ActionBarMenuSubItem item = addItem(mainOptionsContainer, R.drawable.msg_delete, LocaleController.getString(R.string.SaveDeletedExclusionMenu), false);
            View.OnClickListener open = v -> openDetail(buildSaveOptions());
            item.setOnClickListener(open);
            item.setRightIcon(R.drawable.msg_arrowright, open);
        }

        if (showRegexFilters) {
            ActionBarMenuSubItem item = addItem(mainOptionsContainer, R.drawable.hide_title, LocaleController.getString(R.string.RegexFilters), false);
            item.setOnClickListener(v -> {
                if (dismissMenu != null) dismissMenu.run();
                AndroidUtilities.runOnUIThread(() -> fragment.presentFragment(new RegexChatFiltersListActivity(chatId)), 50);
            });
            item.setOnLongClickListener(v -> {
                if (dismissMenu != null) dismissMenu.run();
                AndroidUtilities.runOnUIThread(() -> fragment.presentFragment(new RegexFiltersSettingActivity()), 50);
                return true;
            });
            item.setRightIcon(R.drawable.msg_arrowright, v -> openDetail(buildRegexOptions()));

            showFilteredItem = addItem(mainOptionsContainer, R.drawable.msg_clear_recent, LocaleController.getString(R.string.ShowFilteredMessagesMenuText), false);
            showFilteredItem.setVisibility(View.GONE);
        }

        if (showViewDeleted) {
            ActionBarMenuSubItem item = addItem(mainOptionsContainer, R.drawable.msg_view_file, LocaleController.getString(R.string.ViewDeleted), false);
            item.setOnClickListener(v -> {
                if (dismissMenu != null) dismissMenu.run();
                // 带话题打开：话题内查看 / 搜索不能混进同会话其它话题的归档
                long ayuTopicId = fragment.getAyuDeletedMessagesTopicId();
                AndroidUtilities.runOnUIThread(() -> fragment.presentFragment(new AyuViewDeleted(fragment.getCurrentAccount(), chatId, ayuTopicId)), 50);
            });
        }

        if (showClearDeleted) {
            ActionBarMenuSubItem item = addItem(mainOptionsContainer, R.drawable.msg_clear, LocaleController.getString(R.string.ClearDeleted), false);
            int red = Theme.getColor(Theme.key_dialogTextRed, resourcesProvider);
            item.setColors(red, red);
            item.setOnClickListener(v -> {
                if (dismissMenu != null) dismissMenu.run();
                AndroidUtilities.runOnUIThread(() -> {
                    AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
                    builder.setTitle(LocaleController.getString(R.string.ClearDeleted));
                    builder.setMessage(LocaleController.getString(R.string.ClearDeletedAlertMessage));
                    builder.setPositiveButton(LocaleController.getString(R.string.Clear), (dialogInterface, i) -> {
                        // 话题内清理只作用于当前话题；账号取发起操作的 fragment
                        long ayuTopicId = fragment.getAyuDeletedMessagesTopicId();
                        AyuMessagesController.getInstance().deleteCurrent(fragment.getCurrentAccount(), chatId, fragment.getMergeDialogId(), ayuTopicId, () -> {
                            AndroidUtilities.runOnUIThread(() -> {
                                NotificationCenter.getInstance(fragment.getCurrentAccount()).removeObserver(fragment, NotificationCenter.closeChats);
                                NotificationCenter.getInstance(fragment.getCurrentAccount()).postNotificationName(NotificationCenter.closeChats);
                                fragment.finishFragment();
                            });
                            if (!NekoConfig.disableVibration.Bool() && LaunchActivity.getLastFragment() != null && LaunchActivity.getLastFragment().getFragmentView() != null) {
                                LaunchActivity.getLastFragment().getFragmentView().performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                            }
                        });
                    });
                    builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                    AlertDialog alertDialog = builder.create();
                    fragment.showDialog(alertDialog);
                    TextView button = (TextView) alertDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                    if (button != null) {
                        button.setTextColor(red);
                    }
                }, 50);
            });
        }

        ActionBarMenuSubItem detailBackItem = createBackItem(v -> closeDetail());
        detailPage.addView(detailBackItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        detailPage.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        detailOptionsContainer = new LinearLayout(parentActivity);
        detailOptionsContainer.setOrientation(LinearLayout.VERTICAL);
        detailPage.addView(detailOptionsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private ActionBarMenuSubItem createBackItem(View.OnClickListener listener) {
        ActionBarMenuSubItem back = new ActionBarMenuSubItem(fragment.getParentActivity(), true, false, false, resourcesProvider);
        back.setItemHeight(44);
        back.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.msg_arrow_back);
        back.getTextView().setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(40), 0, LocaleController.isRTL ? AndroidUtilities.dp(40) : 0, 0);
        back.setOnClickListener(listener);
        return back;
    }

    private static ActionBarMenuSubItem addItem(LinearLayout container, int icon, CharSequence text, boolean needCheck) {
        ActionBarMenuSubItem cell = new ActionBarMenuSubItem(container.getContext(), needCheck, false, false, null);
        cell.setTextAndIcon(text, icon);
        cell.setMinimumWidth(AndroidUtilities.dp(196));
        container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        return cell;
    }

    private View createGap() {
        android.graphics.drawable.ColorDrawable colorDrawable = new android.graphics.drawable.ColorDrawable(Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator, resourcesProvider));
        org.telegram.ui.Components.CombinedDrawable combined = new org.telegram.ui.Components.CombinedDrawable(colorDrawable, Theme.getThemedDrawable(fragment.getParentActivity(), R.drawable.greydivider, Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider)));
        combined.setFullsize(true);
        FrameLayout gap = new FrameLayout(fragment.getParentActivity());
        gap.setBackground(combined);
        return gap;
    }

    // ---- detail pages ----

    private LinearLayout buildGhostOptions() {
        LinearLayout options = new LinearLayout(fragment.getParentActivity());
        options.setOrientation(LinearLayout.VERTICAL);
        ghostDefaultItem = addItem(options, 0, LocaleController.getString(R.string.Default), true);
        ghostDefaultItem.setOnClickListener(v -> {
            AyuGhostPreferences.setReadException(chatId, AyuGhostPreferences.TYPE_DEFAULT);
            AyuGhostPreferences.setTypingException(chatId, AyuGhostPreferences.TYPE_DEFAULT);
            updateGhostItems();
        });
        ghostReadItem = addItem(options, 0, getReadLabel(AyuGhostPreferences.getReadException(chatId)), true);
        ghostReadItem.setOnClickListener(v -> {
            AyuGhostPreferences.setReadException(chatId, nextType(AyuGhostPreferences.getReadException(chatId)));
            updateGhostItems();
        });
        ghostTypingItem = addItem(options, 0, getTypingLabel(AyuGhostPreferences.getTypingException(chatId)), true);
        ghostTypingItem.setOnClickListener(v -> {
            AyuGhostPreferences.setTypingException(chatId, nextType(AyuGhostPreferences.getTypingException(chatId)));
            updateGhostItems();
        });
        updateGhostItems();
        return options;
    }

    private void updateGhostItems() {
        int readType = AyuGhostPreferences.getReadException(chatId);
        int typingType = AyuGhostPreferences.getTypingException(chatId);
        ghostDefaultItem.setChecked(readType == AyuGhostPreferences.TYPE_DEFAULT && typingType == AyuGhostPreferences.TYPE_DEFAULT);
        ghostReadItem.setText(getReadLabel(readType));
        ghostReadItem.setChecked(readType != AyuGhostPreferences.TYPE_DEFAULT);
        ghostTypingItem.setText(getTypingLabel(typingType));
        ghostTypingItem.setChecked(typingType != AyuGhostPreferences.TYPE_DEFAULT);
        if (detailOpen) {
            expandDetailWidth();
        }
    }

    private static int nextType(int type) {
        if (type == AyuGhostPreferences.TYPE_DEFAULT) {
            return AyuGhostPreferences.TYPE_FORCE_BLOCK;
        }
        if (type == AyuGhostPreferences.TYPE_FORCE_BLOCK) {
            return AyuGhostPreferences.TYPE_FORCE_ALLOW;
        }
        return AyuGhostPreferences.TYPE_DEFAULT;
    }

    private static String getReadLabel(int type) {
        if (type == AyuGhostPreferences.TYPE_FORCE_BLOCK) {
            return LocaleController.getString(R.string.GhostModeExceptionForceBlockRead);
        }
        if (type == AyuGhostPreferences.TYPE_FORCE_ALLOW) {
            return LocaleController.getString(R.string.GhostModeExceptionForceAllowRead);
        }
        return LocaleController.getString(R.string.GhostModeExcludeRead) + ": " + LocaleController.getString(R.string.GhostModeExceptionDefault);
    }

    private static String getTypingLabel(int type) {
        if (type == AyuGhostPreferences.TYPE_FORCE_BLOCK) {
            return LocaleController.getString(R.string.GhostModeExceptionForceBlockTyping);
        }
        if (type == AyuGhostPreferences.TYPE_FORCE_ALLOW) {
            return LocaleController.getString(R.string.GhostModeExceptionForceAllowTyping);
        }
        return LocaleController.getString(R.string.GhostModeExcludeTyping) + ": " + LocaleController.getString(R.string.GhostModeExceptionDefault);
    }

    private LinearLayout buildSaveOptions() {
        LinearLayout options = new LinearLayout(fragment.getParentActivity());
        options.setOrientation(LinearLayout.VERTICAL);
        saveDefaultItem = addItem(options, 0, LocaleController.getString(R.string.Default), true);
        saveDefaultItem.setOnClickListener(v -> {
            AyuSavePreferences.setSaveDeletedExclusion(chatId, false);
            updateSaveItems();
        });
        saveExclusionItem = addItem(options, 0, LocaleController.getString(R.string.SaveDeletedExcluded), true);
        saveExclusionItem.setOnClickListener(v -> {
            AyuSavePreferences.setSaveDeletedExclusion(chatId, true);
            updateSaveItems();
        });
        updateSaveItems();
        return options;
    }

    private void updateSaveItems() {
        boolean excluded = AyuSavePreferences.getSaveDeletedExclusion(chatId);
        saveDefaultItem.setChecked(!excluded);
        saveExclusionItem.setChecked(excluded);
    }

    private LinearLayout buildRegexOptions() {
        LinearLayout options = new LinearLayout(fragment.getParentActivity());
        options.setOrientation(LinearLayout.VERTICAL);
        regexDefaultItem = addItem(options, 0, LocaleController.getString(R.string.Default), true);
        regexDefaultItem.setOnClickListener(v -> {
            AyuFilter.setDialogExcluded(chatId, false);
            updateRegexItems();
        });
        regexExclusionItem = addItem(options, 0, LocaleController.getString(R.string.SaveDeletedExcluded), true);
        regexExclusionItem.setOnClickListener(v -> {
            AyuFilter.setDialogExcluded(chatId, true);
            updateRegexItems();
        });
        updateRegexItems();
        TextView description = new TextView(fragment.getParentActivity());
        description.setTag(R.id.fit_width_tag, 1);
        description.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(8), AndroidUtilities.dp(13), AndroidUtilities.dp(8));
        description.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        description.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider));
        description.setText(LocaleController.getString(R.string.RegexFiltersSubMenuDescription));
        options.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return options;
    }

    private void updateRegexItems() {
        boolean excluded = AyuFilter.isDialogExcluded(chatId);
        regexDefaultItem.setChecked(!excluded);
        regexExclusionItem.setChecked(excluded);
    }

    // ---- detail open/close (single container, width expands to fit detail) ----

    private void openDetail(View options) {
        detailOptionsContainer.removeAllViews();
        detailOptionsContainer.addView(options, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        detailPage.setVisibility(View.VISIBLE);
        detailMainWidth = swipeBack.getWidth() == 0 ? AndroidUtilities.dp(196) : swipeBack.getWidth();
        int maxWidth = AndroidUtilities.displaySize.x - AndroidUtilities.dp(40);
        detailPage.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        detailWidth = Math.min(maxWidth, Math.max(detailMainWidth, detailPage.getMeasuredWidth()));
        detailMainHeight = swipeBack.getHeight();
        detailPage.measure(View.MeasureSpec.makeMeasureSpec(detailWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        detailDetailHeight = detailPage.getMeasuredHeight();
        detailOpen = true;
        if (popupSwipeBackLayout != null) {
            popupSwipeBackLayout.setSwipeBackDisallowed(true);
        }
        mainPage.setAlpha(1f);
        applyDetailProgress(0);
        animateDetailTo(1);
    }

    private void expandDetailWidth() {
        int maxWidth = AndroidUtilities.displaySize.x - AndroidUtilities.dp(40);
        detailPage.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = Math.min(maxWidth, Math.max(detailMainWidth, detailPage.getMeasuredWidth()));
        if (w == detailWidth) {
            return;
        }
        detailWidth = w;
        if (detailProgress >= 1f && swipeBack.getLayoutParams() != null) {
            swipeBack.getLayoutParams().width = w;
        }
        detailPage.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        detailDetailHeight = detailPage.getMeasuredHeight();
        swipeBack.requestLayout();
    }

    private void closeDetail() {
        animateDetailTo(0);
    }

    private void applyDetailProgress(float progress) {
        detailProgress = progress;
        int w = Math.round(AndroidUtilities.lerp(detailMainWidth, detailWidth, progress));
        if (swipeBack.getLayoutParams() != null && swipeBack.getLayoutParams().width != w) {
            swipeBack.getLayoutParams().width = w;
            swipeBack.requestLayout();
        }
        mainPage.setTranslationX(-detailWidth * 0.5f * progress);
        float scale = 1f - 0.05f * progress;
        mainPage.setScaleX(scale);
        mainPage.setScaleY(scale);
        detailPage.setTranslationX(detailWidth * (1f - progress));
        if (popupSwipeBackLayout != null) {
            int index = popupSwipeBackLayout.indexOfChild(swipeBack);
            if (index >= 0) {
                popupSwipeBackLayout.setNewForegroundHeight(index, AndroidUtilities.lerp(detailMainHeight, detailDetailHeight, progress), false);
            }
        }
        swipeBack.invalidate();
    }

    private void animateDetailTo(float toProgress) {
        if (detailAnimator != null) {
            detailAnimator.cancel();
            detailAnimator = null;
        }
        ValueAnimator valueAnimator = ValueAnimator.ofFloat(detailProgress, toProgress);
        valueAnimator.addUpdateListener(animation -> applyDetailProgress((float) animation.getAnimatedValue()));
        valueAnimator.setDuration(300);
        valueAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                detailAnimator = null;
                if (toProgress <= 0) {
                    resetDetail();
                }
            }
        });
        detailAnimator = valueAnimator;
        valueAnimator.start();
    }

    private void resetDetail() {
        if (detailPage == null || mainPage == null) {
            return;
        }
        if (detailAnimator != null) {
            detailAnimator.cancel();
            detailAnimator = null;
        }
        detailOpen = false;
        detailProgress = 0;
        if (popupSwipeBackLayout != null) {
            popupSwipeBackLayout.setSwipeBackDisallowed(false);
        }
        detailPage.setVisibility(View.GONE);
        detailPage.setTranslationX(swipeBack.getWidth() == 0 ? AndroidUtilities.dp(196) : swipeBack.getWidth());
        mainPage.setTranslationX(0);
        mainPage.setAlpha(1f);
        mainPage.setScaleX(1f);
        mainPage.setScaleY(1f);
        if (swipeBack.getLayoutParams() != null) {
            swipeBack.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
            swipeBack.getLayoutParams().height = LayoutHelper.WRAP_CONTENT;
        }
        if (popupSwipeBackLayout != null) {
            int index = popupSwipeBackLayout.indexOfChild(swipeBack);
            if (index >= 0) {
                popupSwipeBackLayout.setNewForegroundHeight(index, 0, false);
            }
        }
        swipeBack.requestLayout();
    }
}
