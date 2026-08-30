package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UndoView;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheckIcon;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.ui.cells.AvatarCornersPreviewCell;
import tw.nekomimi.nekogram.ui.cells.ChatListPreviewCell;
import tw.nekomimi.nekogram.ui.cells.FabShapePreviewCell;
import tw.nekomimi.nekogram.ui.cells.FilterTabsPreviewCell;
import xyz.nextalone.nagram.NaConfig;

@SuppressLint("RtlHardcoded")
@SuppressWarnings({"unused", "FieldCanBeLocal"})
public class NekoAppearanceSettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;
    private AvatarCornersPreviewCell avatarCornersPreviewCell;
    private FabShapePreviewCell fabShapePreviewCell;
    private ChatListPreviewCell chatListPreviewCell;
    private FilterTabsPreviewCell filterTabsPreviewCell;
    private Parcelable recyclerViewState = null;

    private boolean wasCentered = false;
    private boolean wasCenteredAtBeginning = false;
    private float centeredMeasure = -1;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerAppearance = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Appearance)));
    private final AbstractConfigCell typefaceRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.typeface));
    private final AbstractConfigCell hideDividersRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideDividers()));
    private final AbstractConfigCell sectionsSeparatedHeadersRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSectionsSeparatedHeaders(), null, getString(R.string.SeparateHeaders)));
    private final AbstractConfigCell fabShapePreviewRow = cellGroup.appendCell(new ConfigCellCustom("FabShapePreview", ConfigCellCustom.CUSTOM_ITEM_FabShapePreview, false));
    private final AbstractConfigCell alwaysShowDownloadIconRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getAlwaysShowDownloadIcon()));
    private final AbstractConfigCell showStickersInTopLevelRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowStickersRowToplevel()));
    private final AbstractConfigCell hidePremiumSectionRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHidePremiumSection()));
    private final AbstractConfigCell hideHelpSectionRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideHelpSection()));
    private final AbstractConfigCell iconReplacementsRow = cellGroup.appendCell(new ConfigCellSelectBox("IconReplacements", NaConfig.INSTANCE.getIconReplacements(), new String[]{
            getString(R.string.Default),
            getString(R.string.IconReplacementSolar),
            getString(R.string.IconReplacementRemix),
    }, null));
    private final AbstractConfigCell switchStyleRow = cellGroup.appendCell(new ConfigCellSelectBox("SwitchStyle", NaConfig.INSTANCE.getSwitchStyle(), new String[]{
            getString(R.string.Default),
            getString(R.string.StyleMaterialDesign3),
            getString(R.string.StyleOneUI)
    }, null));
    private final AbstractConfigCell sliderStyleRow = cellGroup.appendCell(new ConfigCellSelectBox("SliderStyle", NaConfig.INSTANCE.getSliderStyle(), new String[]{
            getString(R.string.Default),
            getString(R.string.StyleModern),
            getString(R.string.StyleMaterialDesign3)
    }, null));
    private final AbstractConfigCell notificationIconRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getNotificationIcon(), new String[]{
            getString(R.string.MapPreviewProviderTelegram),
            getString(R.string.NagramX),
            getString(R.string.Nagram),
            getString(R.string.NekoX)
    }, null));
    private final AbstractConfigCell tabletModeRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.tabletMode, new String[]{
            getString(R.string.TabletModeDefault),
            getString(R.string.Enable),
            getString(R.string.Disable)
    }, null));
    private final AbstractConfigCell dividerAppearance = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell avatarCornersPreviewRow = cellGroup.appendCell(new ConfigCellCustom("AvatarCorners", ConfigCellCustom.CUSTOM_ITEM_AvatarCorners, false));
    private final AbstractConfigCell singleCornerRadiusRow = cellGroup.appendCell(
            new ConfigCellTextCheck(
                    NaConfig.INSTANCE.getSingleCornerRadius(),
                    null,
                    getString(R.string.SingleCornerRadius)
            )
    );
    private final AbstractConfigCell avatarCornersInfoRow = cellGroup.appendCell(new ConfigCellCustom("SingleCornerRadiusInfo", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell headerDialogs = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.DialogsSettings)));
    private final AbstractConfigCell chatListPreviewRow = cellGroup.appendCell(new ConfigCellCustom("ChatListPreview", ConfigCellCustom.CUSTOM_ITEM_ChatListPreview, false));
    private final AbstractConfigCell forceSnowfallRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getForceSnowfall(), getString(R.string.ForceSnowfallInfo), getString(R.string.ForceSnowfall)));
    private final AbstractConfigCell centerActionBarTitleRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getCenterActionBarTitle(), null, getString(R.string.CenterActionBarTitleType)));
    private final AbstractConfigCell folderNameAsTitleRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getFolderNameAsTitle()));
    private final AbstractConfigCell customTitleUserNameRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getCustomTitleUserName()));
    private final AbstractConfigCell customTitleRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getCustomTitle(),
            getString(R.string.CustomTitleHint), null,
            (input) -> input.isEmpty() ? (String) NaConfig.INSTANCE.getCustomTitle().defaultValue : input));
    private final AbstractConfigCell sortByUnreadRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSortByUnread()));
    private final AbstractConfigCell disableDialogsFloatingButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableDialogsFloatingButton()));
    private final AbstractConfigCell hideHomeSearchFieldRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideHomeSearchField()));
    private final AbstractConfigCell disableBotOpenButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableBotOpenButton()));
    private final AbstractConfigCell mediaPreviewRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.mediaPreview));
    private final AbstractConfigCell userAvatarsInMessagePreviewRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getUserAvatarsInMessagePreview()));
    private final AbstractConfigCell dividerDialogs = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerFolder = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Folder)));
    private final AbstractConfigCell filterTabsPreviewRow = cellGroup.appendCell(new ConfigCellCustom("FilterTabsPreview", ConfigCellCustom.CUSTOM_ITEM_FilterTabsPreview, false));
    private final AbstractConfigCell hideAllTabRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideAllTab, getString(R.string.HideAllTabAbout)));
    private final ConfigCellTextCheck foldersAtBottomRow = (ConfigCellTextCheck) cellGroup.appendCell(
            new ConfigCellTextCheck(NaConfig.INSTANCE.getFoldersAtBottom())
    );
    private final AbstractConfigCell doNotUnarchiveBySwipeRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDoNotUnarchiveBySwipe()));
    private final AbstractConfigCell openArchiveOnPullRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.openArchiveOnPull));
    private final AbstractConfigCell hideArchiveRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideArchive()));
    private final AbstractConfigCell tabsTitleTypeRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.tabsTitleType, new String[]{
            getString(R.string.TabTitleTypeText),
            getString(R.string.TabTitleTypeIcon),
            getString(R.string.TabTitleTypeMix)
    }, null));
    private final AbstractConfigCell tabStyleStrokeRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.tabStyleStroke));
    private final AbstractConfigCell ignoreUnreadCountRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getIgnoreUnreadCount()));
    private final AbstractConfigCell dividerNavigationTop = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerNavigation = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AppNavigation)));
    private final AbstractConfigCell drawerElementsRow = cellGroup.appendCell(
            new ConfigCellTextCheckIcon(null, "DrawerElements", getString(R.string.DrawerElements), R.drawable.menu_newfilter, false, () ->
                    presentFragment(new SidebarMenuActivity()))
    );
    private final AbstractConfigCell mainTabsCustomizeRow = cellGroup.appendCell(
            new ConfigCellTextCheckIcon(null, "MainTabsCustomize", getString(R.string.MainTabsCustomize), R.drawable.tabs_reorder, false, () ->
                    presentFragment(new MainTabsCustomizeActivity()))
    );
    private final AbstractConfigCell pillStackRow = cellGroup.appendCell(
            new ConfigCellTextCheckIcon(null, "PillStack", getString(R.string.PillStackPills), R.drawable.ic_ab_search, false, () ->
                    presentFragment(new com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity()))
    );
    private final AbstractConfigCell dividerFolder = cellGroup.appendCell(new ConfigCellDivider());

    // Blur
    private final AbstractConfigCell headerBlur = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.LiteOptionsBlur2)));
    private final AbstractConfigCell strokeOnViewsRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getStrokeOnViews()));
    private final AbstractConfigCell disableAvatarBlurRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableAvatarBlur()));
    private final AbstractConfigCell dividerBlur = cellGroup.appendCell(new ConfigCellDivider());

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return "appearance";
    }

    @Override
    protected void styleTextInfoPrivacyCell(TextInfoPrivacyCell cell) {
        cell.setBackground(Theme.getThemedDrawable(cell.getContext(), R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
    }

    public NekoAppearanceSettingsActivity() {
        cellGroup.rows.remove(headerAppearance);
        cellGroup.rows.remove(fabShapePreviewRow);
        cellGroup.rows.remove(typefaceRow);
        cellGroup.rows.remove(avatarCornersPreviewRow);
        cellGroup.rows.remove(singleCornerRadiusRow);
        cellGroup.rows.remove(avatarCornersInfoRow);
        cellGroup.rows.add(0, avatarCornersPreviewRow);
        cellGroup.rows.add(1, singleCornerRadiusRow);
        cellGroup.rows.add(2, avatarCornersInfoRow);
        cellGroup.rows.add(3, headerAppearance);
        cellGroup.rows.add(4, fabShapePreviewRow);
        cellGroup.rows.add(5, typefaceRow);
        List<AbstractConfigCell> dialogsBlock = Arrays.asList(
                headerDialogs,
                chatListPreviewRow,
                forceSnowfallRow,
                centerActionBarTitleRow,
                folderNameAsTitleRow,
                customTitleUserNameRow,
                customTitleRow,
                sortByUnreadRow,
                disableDialogsFloatingButtonRow,
                hideHomeSearchFieldRow,
                disableBotOpenButtonRow,
                mediaPreviewRow,
                userAvatarsInMessagePreviewRow,
                dividerDialogs
        );
        cellGroup.rows.removeAll(dialogsBlock);
        int appearanceIdx = cellGroup.rows.indexOf(headerAppearance);
        cellGroup.rows.addAll(appearanceIdx, dialogsBlock);
        cellGroup.rows.remove(ignoreUnreadCountRow);
        cellGroup.rows.remove(tabStyleStrokeRow);
        cellGroup.rows.remove(tabsTitleTypeRow);
        int hideAllTabIdx = cellGroup.rows.indexOf(hideAllTabRow);
        if (hideAllTabIdx >= 0) {
            cellGroup.rows.add(hideAllTabIdx, ignoreUnreadCountRow);
            cellGroup.rows.add(hideAllTabIdx, tabStyleStrokeRow);
            cellGroup.rows.add(hideAllTabIdx, tabsTitleTypeRow);
        }
        wasCentered = isCentered();
        wasCenteredAtBeginning = wasCentered;
        checkOpenArchiveOnPullRows();
        checkCustomTitleRows();
        addRowsToMap(cellGroup);
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (filterTabsPreviewCell != null
                    && key.equals(NaConfig.INSTANCE.getFoldersAtBottom().getKey())) {
                filterTabsPreviewCell.refresh();
            }
            if (key.equals(NaConfig.INSTANCE.getForceSnowfall().getKey())) {
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.invalidate();
                }
                if (getActionBar() != null) {
                    getActionBar().invalidate();
                }
                if (listView != null) {
                    listView.invalidate();
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.invalidateMotionBackground);
            } else if (key.equals(NekoConfig.hideAllTab.getKey())
                    || key.equals(NaConfig.INSTANCE.getIgnoreUnreadCount().getKey())
                    || key.equals(NekoConfig.tabsTitleType.getKey())
                    || key.equals(NekoConfig.tabStyleStroke.getKey())) {
                getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            } else if (key.equals(NaConfig.INSTANCE.getNotificationIcon().getKey())
                    || key.equals(NekoConfig.tabletMode.getKey())
                    || key.equals(NaConfig.INSTANCE.getHideDividers().getKey())
                    || key.equals(NekoConfig.typeface.getKey())
                    || key.equals(NaConfig.INSTANCE.getHidePremiumSection().getKey())
                    || key.equals(NaConfig.INSTANCE.getHideHelpSection().getKey())
                    || key.equals(NaConfig.INSTANCE.getAlwaysShowDownloadIcon().getKey())
                    || key.equals(NaConfig.INSTANCE.getShowStickersRowToplevel().getKey())
                    || key.equals(NaConfig.INSTANCE.getFoldersAtBottom().getKey())
                    || key.equals(NaConfig.INSTANCE.getDisableDialogsFloatingButton().getKey())
                    || key.equals(NaConfig.INSTANCE.getDisableBotOpenButton().getKey())
                    || key.equals(NekoConfig.navigationDrawerEnabled.getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getSectionsSeparatedHeaders().getKey())) {
                if (listView != null) {
                    for (int i = 0, n = listView.getChildCount(); i < n; i++) {
                        View child = listView.getChildAt(i);
                        if (child instanceof HeaderCell) {
                            ((HeaderCell) child).applySeparatedHeadersStyle();
                        }
                    }
                    listView.getRecycledViewPool().clear();
                    listView.invalidateItemDecorations();
                }
                reloadUI(0);

            } else if (key.equals(NaConfig.INSTANCE.getSwitchStyle().getKey()) || key.equals(NaConfig.INSTANCE.getSliderStyle().getKey())) {
                if (listView.getLayoutManager() != null) {
                    recyclerViewState = listView.getLayoutManager().onSaveInstanceState();
                    parentLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
                    listView.getLayoutManager().onRestoreInstanceState(recyclerViewState);
                }
            } else if (key.equals(NaConfig.INSTANCE.getCenterActionBarTitle().getKey())) {
                animateActionBarUpdate(this);
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.refresh();
                }
            } else if (key.equals(NaConfig.INSTANCE.getSingleCornerRadius().getKey())) {
                reloadAvatarCorners();
            } else if (key.equals(NaConfig.INSTANCE.getSortByUnread().getKey())) {
                getMessagesController().sortDialogs(null);
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
            } else if (key.equals(NaConfig.INSTANCE.getHideArchive().getKey())) {
                checkOpenArchiveOnPullRows();
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NaConfig.INSTANCE.getUserAvatarsInMessagePreview().getKey())) {
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
            } else if (key.equals(NaConfig.INSTANCE.getHideHomeSearchField().getKey())) {
                getNotificationCenter().postNotificationName(NotificationCenter.updateSearchSettings);
            } else if (key.equals(NaConfig.INSTANCE.getCustomTitleUserName().getKey())) {
                checkCustomTitleRows();
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.refresh();
                }
            } else if (key.equals(NaConfig.INSTANCE.getCustomTitle().getKey())
                    || key.equals(NaConfig.INSTANCE.getFolderNameAsTitle().getKey())) {
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.refresh();
                }
            }
        };

        return superView;
    }

    @Override
    public int getBaseGuid() {
        return 14000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg_theme;
    }

    @Override
    public String getTitle() {
        return getString(R.string.Appearance);
    }

    private void reloadAvatarCorners() {
        if (avatarCornersPreviewCell != null) {
            avatarCornersPreviewCell.invalidate();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
        if (getParentLayout() != null) {
            getParentLayout().rebuildAllFragmentViews(false, false);
        }
    }

    private void checkOpenArchiveOnPullRows() {
        boolean hideArchive = NaConfig.INSTANCE.getHideArchive().Bool();
        if (listAdapter == null) {
            if (hideArchive) {
                cellGroup.rows.remove(openArchiveOnPullRow);
            }
            return;
        }
        if (!hideArchive) {
            final int index = cellGroup.rows.indexOf(hideArchiveRow);
            if (!cellGroup.rows.contains(openArchiveOnPullRow)) {
                cellGroup.rows.add(index, openArchiveOnPullRow);
                listAdapter.notifyItemInserted(index);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(openArchiveOnPullRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(openArchiveOnPullRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }

    private void checkCustomTitleRows() {
        boolean useUserName = NaConfig.INSTANCE.getCustomTitleUserName().Bool();
        if (listAdapter == null) {
            if (useUserName) {
                cellGroup.rows.remove(customTitleRow);
            }
            return;
        }
        if (!useUserName) {
            final int index = cellGroup.rows.indexOf(customTitleUserNameRow);
            if (!cellGroup.rows.contains(customTitleRow)) {
                cellGroup.rows.add(index + 1, customTitleRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int rowIndex = cellGroup.rows.indexOf(customTitleRow);
            if (rowIndex != -1) {
                cellGroup.rows.remove(customTitleRow);
                listAdapter.notifyItemRemoved(rowIndex);
            }
        }
        addRowsToMap(cellGroup);
    }

    private boolean isCentered() {
        return NaConfig.INSTANCE.getCenterActionBarTitle().Bool();
    }

    private void animateActionBarUpdate(BaseNekoXSettingsActivity fragment) {
        boolean centered = isCentered();
        ActionBar actionBar = fragment.getActionBar();
        if (wasCentered == centered) {
            return;
        }
        if (actionBar != null) {
            SimpleTextView titleTextView = actionBar.getTitleTextView();
            if (centeredMeasure == -1) {
                centeredMeasure = actionBar.getMeasuredWidth() / 2f - titleTextView.getTextWidth() / 2f - dp((AndroidUtilities.isTablet() ? 80 : 72));
            }
            titleTextView.animate()
                    .translationX(centeredMeasure * (centered ? 1 : 0) - (wasCenteredAtBeginning ? Math.abs(centeredMeasure) : 0))
                    .setDuration(150)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            wasCentered = centered;
                            reloadUI(0);
                            LaunchActivity.makeRipple(centered ? (actionBar.getMeasuredWidth() / 2f) : 0, 0, centered ? 1.3f : 0.1f);
                        }
                    })
                    .start();
        } else {
            reloadUI(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
        }
    }

    private void reloadUI(int flags) {
        RecyclerView.LayoutManager layoutManager = listView.getLayoutManager();
        if (layoutManager != null) {
            recyclerViewState = layoutManager.onSaveInstanceState();
            parentLayout.rebuildFragments(flags);
            layoutManager.onRestoreInstanceState(recyclerViewState);
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            if (row == avatarCornersInfoRow) {
                TextInfoPrivacyCell textInfoPrivacyCell = (TextInfoPrivacyCell) holder.itemView;
                textInfoPrivacyCell.setText(getString(R.string.SingleCornerRadiusInfo));
            }
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            return switch (viewType) {
                case ConfigCellCustom.CUSTOM_ITEM_AvatarCorners -> avatarCornersPreviewCell = new AvatarCornersPreviewCell(
                        mContext,
                        NekoAppearanceSettingsActivity.this::reloadAvatarCorners
                );
                case ConfigCellCustom.CUSTOM_ITEM_FabShapePreview -> fabShapePreviewCell = new FabShapePreviewCell(
                        mContext,
                        null
                );
                case ConfigCellCustom.CUSTOM_ITEM_ChatListPreview -> chatListPreviewCell = new ChatListPreviewCell(mContext);
                case ConfigCellCustom.CUSTOM_ITEM_FilterTabsPreview -> filterTabsPreviewCell = new FilterTabsPreviewCell(mContext);
                default -> null;
            };
        }
    }
}
