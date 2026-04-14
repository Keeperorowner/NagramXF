package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.MainTabsConfigManager;

import java.util.ArrayList;

import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.MainTabsHelper;
import xyz.nextalone.nagram.NaConfig;

public class MainTabsCustomizeActivity extends BaseNekoSettingsActivity {

    private static final int VIEW_TYPE_PREVIEW = 100;

    private int headerRow;
    private int previewRow;
    private int previewInfoRow;
    private int showTabTitlesRow;
    private int bottomBarDisplayModeRow;
    private int shadowRow;

    private ArrayList<MainTabsConfigManager.TabState> tabs = new ArrayList<>();
    private MainTabsPreviewCell previewCell;

    @Override
    public boolean onFragmentCreate() {
        tabs = MainTabsConfigManager.copyTabs(MainTabsConfigManager.getAllTabs());
        return super.onFragmentCreate();
    }

    @Override
    protected void updateRows() {
        super.updateRows();
        headerRow = addRow();
        previewRow = addRow();
        previewInfoRow = addRow();
        showTabTitlesRow = addRow();
        bottomBarDisplayModeRow = addRow("MainTabsDisplayMode");
        shadowRow = addRow();
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
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == showTabTitlesRow) {
            boolean checked = !NaConfig.INSTANCE.getMainTabsHideTitles().toggleConfigBool();
            if (view instanceof TextCheckCell textCheckCell) {
                textCheckCell.setChecked(checked);
            }
            if (previewCell != null) {
                previewCell.refreshTabs(getContext());
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsLayoutChanged);
        } else if (position == bottomBarDisplayModeRow) {
            showBottomBarDisplayModeDialog();
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.MainTabsCustomize);
    }

    private String getBottomBarDisplayModeTitle() {
        return getBottomBarDisplayModeName(MainTabsHelper.getBottomBarDisplayMode());
    }

    private void showBottomBarDisplayModeDialog() {
        if (getParentActivity() == null) {
            return;
        }

        final int[] modes = new int[]{
                MainTabsHelper.BOTTOM_BAR_MODE_SHOW,
                MainTabsHelper.BOTTOM_BAR_MODE_HIDE,
                MainTabsHelper.BOTTOM_BAR_MODE_FLOATING
        };
        final AlertDialog[] dialog = new AlertDialog[1];

        FrameLayout container = new FrameLayout(getParentActivity());
        container.setPadding(0, dp(8), 0, 0);

        android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(getParentActivity());
        linearLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            RadioColorCell cell = new RadioColorCell(getParentActivity(), getResourceProvider());
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_dialogRadioBackground, getResourceProvider()), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, getResourceProvider()));
            cell.setTextAndValue(getBottomBarDisplayModeName(mode), MainTabsHelper.getBottomBarDisplayMode() == mode);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, getResourceProvider()), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                if (MainTabsHelper.getBottomBarDisplayMode() != mode) {
                    MainTabsHelper.setBottomBarDisplayMode(mode);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsLayoutChanged);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(bottomBarDisplayModeRow);
                    }
                }
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
            });
        }

        dialog[0] = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.MainTabsDisplayMode))
                .setView(container)
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        showDialog(dialog[0]);
    }

    private String getBottomBarDisplayModeName(int mode) {
        if (mode == MainTabsHelper.BOTTOM_BAR_MODE_HIDE) {
            return getString(R.string.MainTabsDisplayModeHide);
        } else if (mode == MainTabsHelper.BOTTOM_BAR_MODE_FLOATING) {
            return getString(R.string.MainTabsDisplayModeFloating);
        }
        return getString(R.string.MainTabsDisplayModeShow);
    }

    private void saveAndNotify() {
        String oldValue = NaConfig.INSTANCE.getMainTabsOrder().String();
        MainTabsConfigManager.saveTabs(tabs);
        String newValue = NaConfig.INSTANCE.getMainTabsOrder().String();
        if (!TextUtils.equals(oldValue, newValue)) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsLayoutChanged);
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(getString(R.string.MainTabsCustomize));
                    break;
                }
                case VIEW_TYPE_PREVIEW: {
                    PreviewRowCell cell = (PreviewRowCell) holder.itemView;
                    cell.bind();
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    StringBuilder desc = new StringBuilder(getString(R.string.MainTabsCustomizeDesc));
                    if (NekoConfig.navigationDrawerEnabled.Bool()) {
                        desc.append("\n\n").append(getString(R.string.MainTabsCustomizeDrawerLocked));
                    }
                    cell.setText(desc);
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(getString(R.string.MainTabsDisplayMode), getBottomBarDisplayModeTitle(), false);
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == showTabTitlesRow) {
                        cell.setTextAndCheck(getString(R.string.MainTabsShowTitles), !NaConfig.INSTANCE.getMainTabsHideTitles().Bool(), true);
                    }
                    break;
                }
                case TYPE_SHADOW:
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_PREVIEW) {
                View view = new PreviewRowCell(mContext);
                view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return TYPE_HEADER;
            } else if (position == previewRow) {
                return VIEW_TYPE_PREVIEW;
            } else if (position == previewInfoRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == bottomBarDisplayModeRow) {
                return TYPE_SETTINGS;
            } else if (position == shadowRow) {
                return TYPE_SHADOW;
            }
            return TYPE_CHECK;
        }
    }

    private class PreviewRowCell extends FrameLayout {

        private final MainTabsPreviewCell tabsPreviewCell;

        public PreviewRowCell(Context context) {
            super(context);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

            PreviewContainer container = new PreviewContainer(context);
            addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            tabsPreviewCell = new MainTabsPreviewCell(context);
            previewCell = tabsPreviewCell;
            container.setPreviewCell(tabsPreviewCell);
            container.addView(tabsPreviewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 60, Gravity.CENTER, dp(6), dp(2), dp(6), dp(2)));
            setPadding(0, 0, 0, dp(12));
        }

        public void bind() {
            tabsPreviewCell.setTabs(tabs, getContext(), getResourceProvider(), currentAccount);
            tabsPreviewCell.setOnTabsChangedListener(updatedTabs -> {
                tabs = updatedTabs;
                saveAndNotify();
            });
        }
    }

    private class PreviewContainer extends FrameLayout {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private MainTabsPreviewCell previewCell;

        public PreviewContainer(Context context) {
            super(context);
            setWillNotDraw(false);
            int base = Theme.getColor(Theme.key_switchTrack, getResourceProvider());
            bgPaint.setColor(Theme.multAlpha(base, 0.12f));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(2, dp(1)));
            strokePaint.setColor(Theme.multAlpha(base, 0.25f));
        }

        public void setPreviewCell(MainTabsPreviewCell previewCell) {
            this.previewCell = previewCell;
        }

        @Override
        protected void onDraw(Canvas canvas) {
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
            setPadding(dp(12), dp(6), dp(12), dp(6));
        }

        public void setOnTabsChangedListener(OnTabsChangedListener listener) {
            onTabsChangedListener = listener;
        }

        public void setTabs(ArrayList<MainTabsConfigManager.TabState> tabs, Context context, Theme.ResourcesProvider resourceProvider, int currentAccount) {
            this.tabs = MainTabsConfigManager.copyTabs(tabs);
            this.resourceProvider = resourceProvider;
            this.currentAccount = currentAccount;
            rebuildTabs(context);
        }

        public void refreshTabs(Context context) {
            rebuildTabs(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY) {
                setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), getMeasuredHeight());
            }
        }

        private void rebuildTabs(Context context) {
            int horizontalPad = dp(12);
            int verticalPad = dp(6);
            setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad);
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
                tabView.setOnLongClickListener(v -> {
                    MainTabsConfigManager.TabState clicked = tabs.get(index);
                    if (NekoConfig.navigationDrawerEnabled.Bool() && clicked.type == MainTabsConfigManager.TabType.CHATS) {
                        return false;
                    }
                    return startDrag(index, v);
                });

                addView(tabView);
                setViewVisible(tabView, true, false);
            }

            GlassTabView searchTab = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CONTACTS, R.string.Search);
            searchTab.setIcon(R.drawable.outline_header_search);
            searchTab.setTitleVisible(!NaConfig.INSTANCE.getMainTabsHideTitles().Bool());
            boolean searchEnabled = NaConfig.INSTANCE.getMainTabsShowSearchButton().Bool();
            applySearchButtonVisual(searchTab, searchEnabled);
            searchTab.setSelected(false, false);
            searchTab.setOnClickListener(v -> {
                boolean checked = NaConfig.INSTANCE.getMainTabsShowSearchButton().toggleConfigBool();
                applySearchButtonVisual(searchTab, checked);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsLayoutChanged);
            });
            addView(searchTab);
            setViewVisible(searchTab, true, false);

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

        private void applySearchButtonVisual(GlassTabView tabView, boolean enabled) {
            tabView.setAlpha(enabled ? 1f : 0.4f);
            tabView.setTag(Boolean.valueOf(enabled));
        }

        @Override
        protected void setChildVisibilityFactor(View view, float factor) {
            float scale = org.telegram.messenger.AndroidUtilities.lerp(0.7f, 1f, factor);
            float enabledAlpha = 1f;
            Object tag = view.getTag();
            if (tag instanceof MainTabsConfigManager.TabState state) {
                enabledAlpha = (state.type == MainTabsConfigManager.TabType.CHATS || state.enabled) ? 1f : 0.4f;
            } else if (tag instanceof Boolean) {
                enabledAlpha = (Boolean) tag ? 1f : 0.4f;
            }
            view.setTranslationX(view.getTranslationX() + getCenteredOffset());
            view.setTranslationY(getVerticalOffset());
            view.setAlpha(factor * enabledAlpha);
            view.setScaleX(scale);
            view.setScaleY(scale);
        }

        private float getCenteredOffset() {
            int availableWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
            if (availableWidth <= 0) {
                return 0f;
            }
            float contentWidth = Math.min(availableWidth, getMetadata().getTotalWidth());
            return Math.max(0f, (availableWidth - contentWidth) / 2f);
        }

        private float getVerticalOffset() {
            return 0f;
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
            if (NekoConfig.navigationDrawerEnabled.Bool()) {
                MainTabsConfigManager.TabState movingTab = tabs.get(fromIndex);
                if (movingTab.type == MainTabsConfigManager.TabType.CHATS) {
                    return;
                }
                toIndex = Math.max(toIndex, 1);
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
