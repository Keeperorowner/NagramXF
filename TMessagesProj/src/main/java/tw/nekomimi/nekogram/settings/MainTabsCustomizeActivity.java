package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsConfigManager;

import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

public class MainTabsCustomizeActivity extends UniversalFragment {
    private static final int BUTTON_SHOW_TAB_TITLES = 1;
    private static final int BUTTON_HIDE_BOTTOM_BAR = 2;

    private MainTabsPreviewCell previewCell;
    private ArrayList<MainTabsConfigManager.TabState> tabs = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.MainTabsCustomize);
    }

    @Override
    public boolean onFragmentCreate() {
        tabs = MainTabsConfigManager.copyTabs(MainTabsConfigManager.getAllTabs());
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        saveAndNotify();
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        saveAndNotify();
        return super.onBackPressed(invoked);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.MainTabsCustomize)));

        int previewHeight = 68;
        int previewRowHeight = 76;
        int previewSideMargin = 10;
        int previewTopBottomMargin = 2;

        PreviewContainer container = new PreviewContainer(getContext());
        previewCell = new MainTabsPreviewCell(getContext());
        previewCell.setTabs(tabs, getContext(), getResourceProvider(), currentAccount);
        previewCell.setOnTabsChangedListener(updatedTabs -> {
            tabs = updatedTabs;
            saveAndNotify();
        });
        container.addView(previewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, previewHeight, Gravity.CENTER, dp(previewSideMargin), dp(previewTopBottomMargin), dp(previewSideMargin), dp(previewTopBottomMargin)));

        items.add(UItem.asCustom(container, previewRowHeight));
        items.add(UItem.asShadow(getString(R.string.MainTabsCustomizeDesc)));
        items.add(UItem.asCheck(BUTTON_SHOW_TAB_TITLES, getString(R.string.MainTabsShowTitles)).setChecked(!NaConfig.INSTANCE.getMainTabsHideTitles().Bool()));
        items.add(UItem.asCheck(BUTTON_HIDE_BOTTOM_BAR, getString(R.string.MainTabsHideBottomBar)).setChecked(NaConfig.INSTANCE.getMainTabsHideBottomBar().Bool()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_SHOW_TAB_TITLES) {
            boolean checked = !NaConfig.INSTANCE.getMainTabsHideTitles().toggleConfigBool();
            ((TextCheckCell) view).setChecked(checked);
            if (previewCell != null) {
                previewCell.refreshTabs(getContext());
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsLayoutChanged);
            return;
        }
        if (item.id == BUTTON_HIDE_BOTTOM_BAR) {
            boolean checked = NaConfig.INSTANCE.getMainTabsHideBottomBar().toggleConfigBool();
            ((TextCheckCell) view).setChecked(checked);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsLayoutChanged);
            return;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void saveAndNotify() {
        String oldValue = NaConfig.INSTANCE.getMainTabsOrder().String();
        MainTabsConfigManager.saveTabs(tabs);
        String newValue = NaConfig.INSTANCE.getMainTabsOrder().String();
        if (!TextUtils.equals(oldValue, newValue)) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsLayoutChanged);
        }
    }

    private class PreviewContainer extends FrameLayout {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public PreviewContainer(Context context) {
            super(context);
            setWillNotDraw(false);
            int base = Theme.getColor(Theme.key_switchTrack, resourceProvider);
            bgPaint.setColor(Theme.multAlpha(base, 0.12f));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(2, dp(1)));
            strokePaint.setColor(Theme.multAlpha(base, 0.25f));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float stroke = strokePaint.getStrokeWidth() / 2f;
            if (previewCell != null && previewCell.getMeasuredWidth() > 0 && previewCell.getMeasuredHeight() > 0) {
                rect.set(
                    previewCell.getLeft() + stroke,
                    previewCell.getTop() + stroke,
                    previewCell.getRight() - stroke,
                    previewCell.getBottom() - stroke
                );
            } else {
                rect.set(dp(6) + stroke, dp(2) + stroke, getMeasuredWidth() - dp(6) - stroke, getMeasuredHeight() - dp(2) - stroke);
            }
            float radius = rect.height() / 2f;
            canvas.drawRoundRect(rect, radius, radius, bgPaint);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }
    }

    private static class MainTabsPreviewCell extends org.telegram.ui.MainTabsLayout {
        private ArrayList<MainTabsConfigManager.TabState> tabs = new ArrayList<>();
        private Theme.ResourcesProvider resourceProvider;
        private int currentAccount;
        private View draggingView;
        private OnTabsChangedListener onTabsChangedListener;

        public interface OnTabsChangedListener {
            void onTabsChanged(ArrayList<MainTabsConfigManager.TabState> tabs);
        }

        public MainTabsPreviewCell(Context context) {
            super(context);
            setEqualWidthWhenTitlesVisible(true);
            setClipChildren(false);
            setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4));
        }

        public void setOnTabsChangedListener(OnTabsChangedListener listener) {
            onTabsChangedListener = listener;
        }

        public void setTabs(
            ArrayList<MainTabsConfigManager.TabState> tabs,
            Context context,
            Theme.ResourcesProvider resourceProvider,
            int currentAccount
        ) {
            this.tabs = MainTabsConfigManager.copyTabs(tabs);
            this.resourceProvider = resourceProvider;
            this.currentAccount = currentAccount;
            rebuildTabs(context);
        }

        public void refreshTabs(Context context) {
            rebuildTabs(context);
        }

        private void rebuildTabs(Context context) {
            int pad = dp(DialogsActivity.MAIN_TABS_MARGIN + 4);
            setPadding(pad, pad, pad, pad);
            removeAllViews();
            for (int i = 0; i < tabs.size(); i++) {
                final int index = i;
                MainTabsConfigManager.TabState state = tabs.get(i);

                GlassTabView tabView = MainTabsConfigManager.createTabView(context, resourceProvider, currentAccount, state.type, true);
                tabView.setSelected(false, false);
                tabView.setGestureSelectedOverride(0, false);
                applyEnabledVisual(tabView, state);

                tabView.setOnClickListener(v -> {
                    MainTabsConfigManager.TabState clicked = tabs.get(index);
                    if (clicked.type == MainTabsConfigManager.TabType.CHATS) {
                        return;
                    }
                    if (clicked.enabled && getEnabledCount() <= 1) {
                        return;
                    }
                    clicked.enabled = !clicked.enabled;
                    applyEnabledVisual((GlassTabView) v, clicked);
                    dispatchTabsChanged();
                });
                tabView.setOnLongClickListener(v -> startDrag(index, v));

                addView(tabView);
                setViewVisible(tabView, true, false);
            }
            requestLayout();
        }

        private int getEnabledCount() {
            int count = 0;
            for (MainTabsConfigManager.TabState tab : tabs) {
                if (tab.enabled) {
                    count++;
                }
            }
            return count;
        }

        private void applyEnabledVisual(GlassTabView tabView, MainTabsConfigManager.TabState state) {
            boolean enabled = state.type == MainTabsConfigManager.TabType.CHATS || state.enabled;
            tabView.setAlpha(enabled ? 1f : 0.4f);
            tabView.setTag(state);
        }

        @Override
        protected void setChildVisibilityFactor(View view, float factor) {
            float scale = org.telegram.messenger.AndroidUtilities.lerp(0.7f, 1f, factor);
            float enabledAlpha = 1f;
            Object tag = view.getTag();
            if (tag instanceof MainTabsConfigManager.TabState) {
                MainTabsConfigManager.TabState state = (MainTabsConfigManager.TabState) tag;
                enabledAlpha = (state.type == MainTabsConfigManager.TabType.CHATS || state.enabled) ? 1f : 0.4f;
            }
            view.setAlpha(factor * enabledAlpha);
            view.setScaleX(scale);
            view.setScaleY(scale);
        }

        private boolean startDrag(int index, View view) {
            draggingView = view;
            ClipData clipData = ClipData.newPlainText("tab_index", String.valueOf(index));
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(view);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                view.startDragAndDrop(clipData, shadow, null, 0);
            } else {
                view.startDrag(clipData, shadow, null, 0);
            }
            return true;
        }

        @Override
        public boolean onDragEvent(DragEvent event) {
            int action = event.getAction();
            if (action == DragEvent.ACTION_DRAG_STARTED) {
                if (draggingView != null) {
                    draggingView.setVisibility(INVISIBLE);
                }
                return true;
            } else if (action == DragEvent.ACTION_DROP) {
                try {
                    int fromIndex = Integer.parseInt(String.valueOf(event.getClipData().getItemAt(0).getText()));
                    int toIndex = findDropIndex(event.getX());
                    moveTab(fromIndex, toIndex);
                } catch (Exception ignore) {
                }
                return true;
            } else if (action == DragEvent.ACTION_DRAG_ENDED) {
                if (draggingView != null) {
                    draggingView.setVisibility(VISIBLE);
                    draggingView = null;
                }
                return true;
            }
            return true;
        }

        private int findDropIndex(float x) {
            int count = getChildCount();
            if (count <= 0) {
                return 0;
            }
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (x < child.getRight()) {
                    return i;
                }
            }
            return count - 1;
        }

        private void moveTab(int fromIndex, int toIndex) {
            if (fromIndex < 0 || fromIndex >= tabs.size() || toIndex < 0 || toIndex >= tabs.size() || fromIndex == toIndex) {
                return;
            }
            MainTabsConfigManager.TabState moving = tabs.remove(fromIndex);
            tabs.add(toIndex, moving);
            rebuildTabs(getContext());
            dispatchTabsChanged();
        }

        private void dispatchTabsChanged() {
            if (onTabsChangedListener != null) {
                onTabsChangedListener.onTabsChanged(MainTabsConfigManager.copyTabs(tabs));
            }
        }
    }
}
