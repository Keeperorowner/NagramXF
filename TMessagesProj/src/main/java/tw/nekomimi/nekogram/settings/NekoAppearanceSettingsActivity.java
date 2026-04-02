package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.UndoView;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheckIcon;
import tw.nekomimi.nekogram.ui.cells.AvatarCornersPreviewCell;
import xyz.nextalone.nagram.NaConfig;

@SuppressLint("RtlHardcoded")
@SuppressWarnings({"unused", "FieldCanBeLocal"})
public class NekoAppearanceSettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;
    private AvatarCornersCardCell avatarCornersCell;
    private ChatBlurAlphaSeekBar chatBlurAlphaSeekbar;
    private Parcelable recyclerViewState = null;

    private boolean wasCentered = false;
    private boolean wasCenteredAtBeginning = false;
    private float centeredMeasure = -1;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerAppearance = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Appearance)));
    private final AbstractConfigCell typefaceRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.typeface));
    private final AbstractConfigCell hideDividersRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideDividers()));
    private final AbstractConfigCell squareFloatingButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSquareFloatingButton()));
    private final AbstractConfigCell alwaysShowDownloadIconRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getAlwaysShowDownloadIcon()));
    private final AbstractConfigCell showStickersInTopLevelRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getShowStickersRowToplevel()));
    private final AbstractConfigCell hidePremiumSectionRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHidePremiumSection()));
    private final AbstractConfigCell hideHelpSectionRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideHelpSection()));
    private final AbstractConfigCell disableAvatarBlurRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableAvatarBlur()));
    private final AbstractConfigCell iconReplacementsRow = cellGroup.appendCell(new ConfigCellSelectBox("IconReplacements", NaConfig.INSTANCE.getIconReplacements(), new String[]{
            getString(R.string.Default),
            getString(R.string.IconReplacementSolar),
    }, null));
    private final AbstractConfigCell switchStyleRow = cellGroup.appendCell(new ConfigCellSelectBox("SwitchStyle", NaConfig.INSTANCE.getSwitchStyle(), new String[]{
            getString(R.string.Default),
            getString(R.string.StyleModern),
            getString(R.string.StyleMaterialDesign3),
            getString(R.string.StyleOneUI4)
    }, null));
    private final AbstractConfigCell sliderStyleRow = cellGroup.appendCell(new ConfigCellSelectBox("SliderStyle", NaConfig.INSTANCE.getSliderStyle(), new String[]{
            getString(R.string.Default),
            getString(R.string.StyleModern),
            getString(R.string.StyleMaterialDesign3)
    }, null));
    private final AbstractConfigCell actionBarDecorationRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.actionBarDecoration, new String[]{
            getString(R.string.DependsOnDate),
            getString(R.string.Snowflakes),
            getString(R.string.Fireworks),
            getString(R.string.DecorationNone),
    }, null));
    private final AbstractConfigCell chatDecorationRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getChatDecoration(), new String[]{
            getString(R.string.DependsOnDate),
            getString(R.string.Snowflakes),
            getString(R.string.DecorationNone),
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
    private final AbstractConfigCell centerActionBarTitleRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getCenterActionBarTitleType(), new String[]{
            getString(R.string.Disable),
            getString(R.string.Enable),
            getString(R.string.SettingsOnly),
            getString(R.string.ChatsOnly)
    }, null));
    private final AbstractConfigCell dividerAppearance = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell avatarCornersRow = cellGroup.appendCell(new ConfigCellCustom("AvatarCorners", ConfigCellCustom.CUSTOM_ITEM_AvatarCorners, false));
    private final AbstractConfigCell avatarCornersInfoRow = cellGroup.appendCell(new ConfigCellCustom("SingleCornerRadiusInfo", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell headerDialogs = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.DialogsSettings)));
    private final AbstractConfigCell sortByUnreadRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSortByUnread()));
    private final AbstractConfigCell disableDialogsFloatingButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableDialogsFloatingButton()));
    private final AbstractConfigCell hideHomeSearchFieldRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideHomeSearchField()));
    private final AbstractConfigCell disableBotOpenButtonRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDisableBotOpenButton()));
    private final AbstractConfigCell mediaPreviewRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.mediaPreview));
    private final AbstractConfigCell userAvatarsInMessagePreviewRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getUserAvatarsInMessagePreview()));
    private final AbstractConfigCell dividerDialogs = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerFolder = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.Folder)));
    private final AbstractConfigCell hideAllTabRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideAllTab, getString(R.string.HideAllTabAbout)));
    private final ConfigCellTextCheck foldersAtBottomRow = (ConfigCellTextCheck) cellGroup.appendCell(
            new ConfigCellTextCheck(NaConfig.INSTANCE.getFoldersAtBottom())
                    .setBlockEnable(true)
    );
    private final AbstractConfigCell doNotUnarchiveBySwipeRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getDoNotUnarchiveBySwipe()));
    private final AbstractConfigCell openArchiveOnPullRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.openArchiveOnPull));
    private final AbstractConfigCell hideArchiveRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideArchive()));
    private final AbstractConfigCell ignoreUnreadCountRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NaConfig.INSTANCE.getIgnoreUnreadCount(), new String[]{
            getString(R.string.Disable),
            getString(R.string.FilterMuted),
            getString(R.string.FilterAllChatsShort)
    }, null));
    private final AbstractConfigCell tabsTitleTypeRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.tabsTitleType, new String[]{
            getString(R.string.TabTitleTypeText),
            getString(R.string.TabTitleTypeIcon),
            getString(R.string.TabTitleTypeMix)
    }, null));
    private final AbstractConfigCell dividerNavigationTop = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerNavigation = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AppNavigation)));
    private final AbstractConfigCell navigationDrawerRow = cellGroup.appendCell(
            new ConfigCellTextCheck(NekoConfig.navigationDrawerEnabled, null, getString(R.string.HomeDrawer))
    );
    private final AbstractConfigCell mainTabsCustomizeRow = cellGroup.appendCell(
            new ConfigCellTextCheckIcon(null, "MainTabsCustomize", getString(R.string.MainTabsCustomize), R.drawable.tabs_reorder, false, () ->
                    presentFragment(new MainTabsCustomizeActivity()))
    );
    private final AbstractConfigCell dividerFolder = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerBlurOptions = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.BlurOptions)));
    private final AbstractConfigCell forceBlurInChatRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.forceBlurInChat));
    private final AbstractConfigCell headerChatBlur = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.ChatBlurAlphaValue)));
    private final AbstractConfigCell chatBlurAlphaValueRow = cellGroup.appendCell(new ConfigCellCustom("ChatBlurAlphaValue", ConfigCellCustom.CUSTOM_ITEM_CharBlurAlpha, NekoConfig.forceBlurInChat.Bool()));

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
        if (!NaConfig.INSTANCE.getCenterActionBarTitle().Bool()) {
            NaConfig.INSTANCE.getCenterActionBarTitleType().setConfigInt(0);
        }
        cellGroup.rows.remove(avatarCornersRow);
        cellGroup.rows.remove(avatarCornersInfoRow);
        cellGroup.rows.add(0, avatarCornersRow);
        cellGroup.rows.add(1, avatarCornersInfoRow);
        wasCentered = isCentered();
        wasCenteredAtBeginning = wasCentered;
        checkOpenArchiveOnPullRows();
        addRowsToMap(cellGroup);
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NekoConfig.actionBarDecoration.getKey())
                    || key.equals(NaConfig.INSTANCE.getNotificationIcon().getKey())
                    || key.equals(NekoConfig.tabletMode.getKey())
                    || key.equals(NaConfig.INSTANCE.getHideDividers().getKey())
                    || key.equals(NaConfig.INSTANCE.getSquareFloatingButton().getKey())
                    || key.equals(NekoConfig.typeface.getKey())
                    || key.equals(NaConfig.INSTANCE.getHidePremiumSection().getKey())
                    || key.equals(NaConfig.INSTANCE.getHideHelpSection().getKey())
                    || key.equals(NaConfig.INSTANCE.getAlwaysShowDownloadIcon().getKey())
                    || key.equals(NaConfig.INSTANCE.getShowStickersRowToplevel().getKey())
                    || key.equals(NekoConfig.hideAllTab.getKey())
                    || key.equals(NaConfig.INSTANCE.getFoldersAtBottom().getKey())
                    || key.equals(NaConfig.INSTANCE.getIgnoreUnreadCount().getKey())
                    || key.equals(NaConfig.INSTANCE.getDisableDialogsFloatingButton().getKey())
                    || key.equals(NaConfig.INSTANCE.getDisableBotOpenButton().getKey())
                    || key.equals(NekoConfig.navigationDrawerEnabled.getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NekoConfig.forceBlurInChat.getKey())) {
                boolean enabled = (Boolean) newValue;
                if (chatBlurAlphaSeekbar != null) {
                    chatBlurAlphaSeekbar.setEnabled(enabled);
                }
                ((ConfigCellCustom) chatBlurAlphaValueRow).setEnabled(enabled);
            } else if (key.equals(NaConfig.INSTANCE.getSwitchStyle().getKey()) || key.equals(NaConfig.INSTANCE.getSliderStyle().getKey())) {
                if (listView.getLayoutManager() != null) {
                    recyclerViewState = listView.getLayoutManager().onSaveInstanceState();
                    parentLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
                    listView.getLayoutManager().onRestoreInstanceState(recyclerViewState);
                }
            } else if (key.equals(NaConfig.INSTANCE.getCenterActionBarTitleType().getKey())) {
                int value = (int) newValue;
                NaConfig.INSTANCE.getCenterActionBarTitle().setConfigBool(value != 0);
                animateActionBarUpdate(this);
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

    private boolean showSingleCornerRadiusLongClickOptions(View view) {
        ItemOptions options = makeLongClickOptions(view);
        addDefaultLongClickOptions(
                options,
                getSettingsPrefix(),
                NaConfig.INSTANCE.getSingleCornerRadius().getKey(),
                NaConfig.INSTANCE.getSingleCornerRadius().String()
        );
        showLongClickOptions(view, options);
        return true;
    }

    private void reloadAvatarCorners() {
        if (avatarCornersCell != null) {
            avatarCornersCell.invalidate();
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

    private boolean isCentered() {
        return NaConfig.INSTANCE.getCenterActionBarTitle().Bool() && NaConfig.INSTANCE.getCenterActionBarTitleType().Int() != 3;
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
                case ConfigCellCustom.CUSTOM_ITEM_AvatarCorners ->
                        avatarCornersCell = new AvatarCornersCardCell(
                                mContext,
                                NekoAppearanceSettingsActivity.this::reloadAvatarCorners,
                                NekoAppearanceSettingsActivity.this::showSingleCornerRadiusLongClickOptions
                        );
                case ConfigCellCustom.CUSTOM_ITEM_CharBlurAlpha -> {
                    chatBlurAlphaSeekbar = new ChatBlurAlphaSeekBar(mContext);
                    chatBlurAlphaSeekbar.setEnabled(NekoConfig.forceBlurInChat.Bool());
                    yield chatBlurAlphaSeekbar;
                }
                default -> null;
            };
        }
    }

    private static class AvatarCornersCardCell extends FrameLayout {

        private final AvatarCornersPreviewCell previewCell;
        private final TextCheckCell switchCell;

        public AvatarCornersCardCell(Context context, Runnable onValueChanged, View.OnLongClickListener onSwitchLongClickListener) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            previewCell = new AvatarCornersPreviewCell(context, onValueChanged);
            content.addView(previewCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            switchCell = new TextCheckCell(context);
            switchCell.setBackground(Theme.getSelectorDrawable(false));
            switchCell.setTextAndCheck(getString(R.string.SingleCornerRadius), NaConfig.INSTANCE.getSingleCornerRadius().Bool(), false, true);
            switchCell.setOnClickListener(v -> {
                boolean checked = NaConfig.INSTANCE.getSingleCornerRadius().toggleConfigBool();
                switchCell.setChecked(checked);
                if (onValueChanged != null) {
                    onValueChanged.run();
                }
            });
            switchCell.setOnLongClickListener(onSwitchLongClickListener);
            content.addView(switchCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        @Override
        public void invalidate() {
            super.invalidate();
            if (previewCell != null) {
                previewCell.invalidate();
            }
            if (switchCell != null) {
                switchCell.setChecked(NaConfig.INSTANCE.getSingleCornerRadius().Bool());
            }
        }
    }

    private static class ChatBlurAlphaSeekBar extends FrameLayout {

        private final SeekBarView sizeBar;
        private final android.text.TextPaint textPaint;
        private boolean enabled = true;

        @SuppressLint("ClickableViewAccessibility")
        public ChatBlurAlphaSeekBar(Context context) {
            super(context);

            setWillNotDraw(false);
            setClickable(true);

            textPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(dp(16));

            sizeBar = new SeekBarView(context);
            sizeBar.setReportChanges(true);
            sizeBar.setSeparatorsCount(256);
            sizeBar.setDelegate((stop, progress) -> {
                NekoConfig.chatBlueAlphaValue.setConfigInt(Math.min(255, (int) (255 * progress)));
                invalidate();
            });
            sizeBar.setOnTouchListener((v, event) -> !enabled);
            sizeBar.setProgress(NekoConfig.chatBlueAlphaValue.Int());
            addView(sizeBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 9, 5, 43, 11));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            canvas.drawText(String.valueOf(NekoConfig.chatBlueAlphaValue.Int()), getMeasuredWidth() - dp(39), dp(28), textPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            sizeBar.setProgress((NekoConfig.chatBlueAlphaValue.Int() / 255.0f));
        }

        @Override
        public void invalidate() {
            super.invalidate();
            sizeBar.invalidate();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            this.enabled = enabled;
            sizeBar.setAlpha(enabled ? 1.0f : 0.5f);
            textPaint.setAlpha((int) ((enabled ? 1.0f : 0.3f) * 255));
            invalidate();
        }
    }
}
