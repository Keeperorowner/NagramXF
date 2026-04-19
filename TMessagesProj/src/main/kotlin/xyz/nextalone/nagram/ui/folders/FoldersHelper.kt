package xyz.nextalone.nagram.ui.folders

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LiteMode
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.DialogsActivityTopPanelLayout
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor
import org.telegram.ui.Components.blur3.capture.IBlur3Capture
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.DialogsActivity
import org.telegram.ui.Stories.DialogStoriesCell
import xyz.nextalone.nagram.NaConfig

object FoldersHelper {
    private const val FLOATING_BUTTONS_OFFSET_WITH_MAIN_TABS_DP = 55f
    private const val FLOATING_BUTTONS_OFFSET_WITHOUT_MAIN_TABS_DP = 10f

    @JvmStatic
    fun moveFoldersToBottom(): Boolean {
        return NaConfig.foldersAtBottom.Bool()
    }

    @JvmStatic
    fun getFloatingButtonsOffset(filterTabsView: FilterTabsView?, showMainTabs: Boolean): Float {
        if (!moveFoldersToBottom() || filterTabsView == null || filterTabsView.height <= AndroidUtilities.dp(5f)) {
            return 0f
        }
        return AndroidUtilities.dp(
            if (showMainTabs) {
                FLOATING_BUTTONS_OFFSET_WITH_MAIN_TABS_DP
            } else {
                FLOATING_BUTTONS_OFFSET_WITHOUT_MAIN_TABS_DP
            }
        ).toFloat()
    }

    private fun getFilterTabsOffset(showMainTabs: Boolean): Int {
        if (!moveFoldersToBottom()) {
            return 0
        }
        return AndroidUtilities.dp(35f)
    }

    @JvmStatic
    fun setupFilterTabs(
        context: Context,
        contentView: ViewGroup,
        filterTabsView: FilterTabsView,
        resourceProvider: Theme.ResourcesProvider?,
        iBlur3FactoryLiquidGlass: BlurredBackgroundDrawableViewFactory,
        iBlur3FactoryFade: BlurredBackgroundDrawableViewFactory,
        showMainTabs: Boolean
    ) {
        val tabsBackground = iBlur3FactoryLiquidGlass.create(
            filterTabsView,
            BlurredBackgroundProviderImpl.topPanel(resourceProvider)
        )
        tabsBackground.setRadius(AndroidUtilities.dp(18f).toFloat())
        tabsBackground.setPadding(AndroidUtilities.dp(6.666f))
        filterTabsView.setPadding(0, AndroidUtilities.dp(7f), 0, AndroidUtilities.dp(7f))
        filterTabsView.setBlurredBackground(tabsBackground)
        contentView.addView(
            filterTabsView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, (36 + 7 + 7).toFloat(), Gravity.BOTTOM, 4f, 0f, 4f, 14f)
        )

        if (showMainTabs) {
            return
        }

        val fadeOverlay = FadeOverlayView(context)
        contentView.addView(fadeOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 300, Gravity.BOTTOM))

        val fadeDrawable = BlurredBackgroundWithFadeDrawable(iBlur3FactoryFade.create(fadeOverlay, null))
        if (!SharedConfig.chatBlurEnabled() || LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            fadeDrawable.setFadeHeight(AndroidUtilities.dp(40f), true)
        }
        fadeOverlay.fadeDrawable = fadeDrawable
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("FunctionName")
    fun blur3_InvalidateBlur(
        fragmentView: View,
        actionBar: ActionBar,
        filterTabsView: FilterTabsView?,
        hasMainTabs: Boolean,
        hasStories: Boolean,
        topPanelLayout: DialogsActivityTopPanelLayout?,
        scrollYOffset: Float,
        searchFieldReservedHeight: Int,
        listViewPaddingBottom: Int,
        hasCommentInput: Boolean,
        navigationBarHeight: Int,
        iBlur3Capture: IBlur3Capture,
        iBlur3PositionActionBar: RectF,
        iBlur3PositionFolders: RectF,
        iBlur3PositionMainTabs: RectF,
        iBlur3Positions: ArrayList<RectF>,
        scrollableViewNoiseSuppressor: DownscaleScrollableNoiseSuppressor
    ) {
        val additionalList = AndroidUtilities.dp(48f)
        val mainTabBottom =
            fragmentView.measuredHeight - navigationBarHeight - AndroidUtilities.dp(DialogsActivity.MAIN_TABS_MARGIN.toFloat())
        val mainTabTop = mainTabBottom - AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT.toFloat())

        val topPanelHeight =
            if (topPanelLayout != null && topPanelLayout.visibility == View.VISIBLE) {
                topPanelLayout.getSumHeightOfAllVisibleChild()
            } else {
                0
            }

        val actionBarHeight = actionBar.measuredHeight +
            searchFieldReservedHeight +
            AndroidUtilities.dp(if (hasStories) DialogStoriesCell.HEIGHT_IN_DP.toFloat() else 0f) +
            topPanelHeight +
            scrollYOffset.toInt()

        iBlur3PositionActionBar.set(
            0f,
            -additionalList.toFloat(),
            fragmentView.measuredWidth.toFloat(),
            (actionBarHeight + additionalList).toFloat()
        )

        iBlur3Positions.clear()
        iBlur3Positions.add(iBlur3PositionActionBar)

        if (hasMainTabs) {
            iBlur3PositionMainTabs.set(
                0f,
                mainTabTop.toFloat(),
                fragmentView.measuredWidth.toFloat(),
                mainTabBottom.toFloat()
            )
            iBlur3PositionMainTabs.inset(
                0f,
                if (LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) 0f else -AndroidUtilities.dp(48f).toFloat()
            )
            iBlur3Positions.add(iBlur3PositionMainTabs)
        } else if (hasCommentInput) {
            iBlur3PositionMainTabs.set(
                0f,
                (fragmentView.measuredHeight - listViewPaddingBottom).toFloat(),
                fragmentView.measuredWidth.toFloat(),
                fragmentView.measuredHeight.toFloat()
            )
            iBlur3PositionMainTabs.inset(
                0f,
                if (LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) 0f else -AndroidUtilities.dp(48f).toFloat()
            )
            iBlur3Positions.add(iBlur3PositionMainTabs)
        }

        if (filterTabsView != null && filterTabsView.visibility == View.VISIBLE) {
            iBlur3PositionFolders.set(
                0f,
                filterTabsView.y,
                fragmentView.measuredWidth.toFloat(),
                filterTabsView.y + filterTabsView.height
            )
            iBlur3Positions.add(iBlur3PositionFolders)
        }

        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, iBlur3Positions.size)
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(
            iBlur3Capture,
            fragmentView.measuredWidth,
            fragmentView.measuredHeight
        )
    }

    @JvmStatic
    fun updateFoldersOffset(
        filterTabsView: FilterTabsView?,
        forwardControlsVisibleProgress: Float,
        mainTabsScrollHideProgress: Float,
        showMainTabs: Boolean,
        navigationBarHeight: Int,
        additionFloatingButtonOffset: Int,
        additionalFloatingTranslation: Float,
        floatingButtonPanOffset: Float
    ) {
        if (!moveFoldersToBottom() || filterTabsView == null) {
            return
        }

        val update = Runnable {
            val clampedForwardControlsVisibleProgress = forwardControlsVisibleProgress.coerceIn(0f, 1f)
            val forwardControlsOffset = AndroidUtilities.dp(150f) * clampedForwardControlsVisibleProgress
            val tabsScrollHideOffset = getFilterTabsOffset(showMainTabs) * mainTabsScrollHideProgress.coerceIn(0f, 1f)
            val hiddenMainTabsOffset = if (showMainTabs) {
                0
            } else {
                AndroidUtilities.dp(FLOATING_BUTTONS_OFFSET_WITH_MAIN_TABS_DP - FLOATING_BUTTONS_OFFSET_WITHOUT_MAIN_TABS_DP)
            }
            filterTabsView.translationY = (
                -navigationBarHeight
                    - additionFloatingButtonOffset
                    + hiddenMainTabsOffset
                    + tabsScrollHideOffset
                    - additionalFloatingTranslation
                    - floatingButtonPanOffset
                    - AndroidUtilities.dp(52f)
                    - getFilterTabsOffset(showMainTabs)
                    - forwardControlsOffset
                ).toFloat()
        }

        if (filterTabsView.height == 0) {
            AndroidUtilities.runOnUIThread(update)
        } else {
            filterTabsView.post(update)
        }
    }

    private class FadeOverlayView(context: Context) : View(context) {
        var fadeDrawable: BlurredBackgroundWithFadeDrawable? = null

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val drawable = fadeDrawable ?: return
            val top = measuredHeight - AndroidUtilities.navigationBarHeight - AndroidUtilities.dp(40f)
            drawable.setBounds(0, top, measuredWidth, measuredHeight)
            drawable.draw(canvas)
        }
    }
}
