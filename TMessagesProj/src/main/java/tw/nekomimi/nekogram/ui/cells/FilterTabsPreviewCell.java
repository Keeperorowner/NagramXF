package tw.nekomimi.nekogram.ui.cells;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

public class FilterTabsPreviewCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final FilterTabsView filterTabsView;
    private final BlurredBackgroundSourceColor blurSource;
    private final HashMap<Integer, Integer> idsWithCounters = new HashMap<>();
    private final int counterSeed = Utilities.random.nextInt(40) + 20;

    public FilterTabsPreviewCell(Context context) {
        super(context);
        setWillNotDraw(false);

        filterTabsView = new FilterTabsView(context, null);
        filterTabsView.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        filterTabsView.setColors(
                Theme.key_actionBarTabLine,
                Theme.key_actionBarTabActiveText,
                Theme.key_actionBarTabUnactiveText,
                Theme.key_actionBarTabSelector,
                Theme.key_actionBarDefault
        );
        blurSource = new BlurredBackgroundSourceColor();
        blurSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        BlurredBackgroundDrawableViewFactory factory =
                new BlurredBackgroundDrawableViewFactory(blurSource);
        BlurredBackgroundDrawable pill = factory.create(
                filterTabsView,
                BlurredBackgroundProviderImpl.topPanel(null)
        );
        pill.setRadius(AndroidUtilities.dp(18));
        pill.setPadding(AndroidUtilities.dp(6.666f));
        filterTabsView.setBlurredBackground(pill);
        filterTabsView.setDelegate(new FilterTabsView.FilterTabsViewDelegate() {
            @Override
            public boolean canPerformActions() {
                return false;
            }

            @Override
            public boolean didSelectTab(FilterTabsView.TabView tabView, boolean selected) {
                return false;
            }

            @Override
            public boolean isTabMenuVisible() {
                return false;
            }

            @Override
            public void onDeletePressed(int id) {
            }

            @Override
            public void onPageReorder(int fromId, int toId) {
            }

            @Override
            public void onPageScrolled(float progress) {
            }

            @Override
            public void onPageSelected(FilterTabsView.Tab tab, boolean forward) {
            }

            @Override
            public void onSamePageSelected() {
            }

            @Override
            public int getTabCounter(int tabId) {
                if (NaConfig.INSTANCE.getIgnoreUnreadCount().Bool()) {
                    return 0;
                }
                Integer c = idsWithCounters.get(tabId);
                return c != null ? c : 0;
            }
        });
        addView(filterTabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50,
                Gravity.CENTER, 12, 0, 12, 0));
        updateTabs(false);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    public void refresh() {
        updateTabs(true);
    }

    private void updateTabs(boolean animated) {
        filterTabsView.resetTabId();
        filterTabsView.removeTabs();
        idsWithCounters.clear();

        ArrayList<MessagesController.DialogFilter> filters =
                MessagesController.getInstance(UserConfig.selectedAccount).getDialogFilters();
        int firstId = -1;
        int count = 0;
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter f = filters.get(i);
            if (f.isDefault() && NekoConfig.hideAllTab.Bool()) {
                continue;
            }
            if (i == 0 || i == 1 || i == 3) {
                idsWithCounters.put(f.id, counterSeed / (i + 1));
            }
            if (firstId == -1) {
                firstId = f.id;
            }
            if (f.isDefault()) {
                filterTabsView.addTab(f.id, 0,
                        LocaleController.getString(R.string.FilterAllChats),
                        "\uD83D\uDCAC", null, false, true, f.locked);
            } else {
                String emoji = f.emoticon == null ? "\uD83D\uDCC1" : f.emoticon;
                filterTabsView.addTab(f.id, f.localId, f.name, emoji,
                        f.entities, f.title_noanimate, false, f.locked);
            }
            count++;
        }

        if (count < 6) {
            int[] ids = {100, 101, 102, 103};
            int[] strings = {
                    R.string.FilterContacts,
                    R.string.FilterGroups,
                    R.string.FilterChannels,
                    R.string.FilterBots
            };
            String[] emojis = {
                    "\uD83D\uDC64",
                    "\uD83D\uDC65",
                    "\uD83D\uDCE2",
                    "\uD83E\uDD16"
            };
            for (int i = 0; i < ids.length && count < 6; i++) {
                int seedIndex = filters.size() + i;
                if (seedIndex == 0 || seedIndex == 1 || seedIndex == 3) {
                    idsWithCounters.put(ids[i], counterSeed / (seedIndex + 1));
                }
                if (firstId == -1) {
                    firstId = ids[i];
                }
                filterTabsView.addTab(ids[i], ids[i],
                        LocaleController.getString(strings[i]),
                        emojis[i], null, false, false, false);
                count++;
            }
        }

        filterTabsView.finishAddingTabs(animated);
        if (firstId == -1 && !filters.isEmpty()) {
            firstId = filters.get(0).id;
        }
        if (firstId != -1) {
            filterTabsView.selectTabWithId(firstId, 1f);
        } else {
            filterTabsView.selectFirstTab();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount)
                .addObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount)
                .removeObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogFiltersUpdated) {
            updateTabs(true);
        }
    }

    public void refreshCounters() {
        if (filterTabsView != null) {
            filterTabsView.checkTabsCounter();
        }
    }

    public void updateColors() {
        if (blurSource != null) {
            blurSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        if (filterTabsView != null) {
            filterTabsView.updateColors();
            filterTabsView.invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(74), MeasureSpec.EXACTLY)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(),
                getMeasuredHeight() - 1, Theme.dividerPaint);
    }
}
