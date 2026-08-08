package com.exteragram.messenger.plugins.ui.components;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PluginRequirementsView extends ViewGroup {
    private static final Pattern REQUIREMENT_PATTERN = Pattern.compile("^([a-zA-Z0-9_\\-.]+)");

    private final int itemSpacing;
    private final int lineSpacing;
    private final Theme.ResourcesProvider resourcesProvider;

    public PluginRequirementsView(Context context) {
        this(context, null);
    }

    public PluginRequirementsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        itemSpacing = AndroidUtilities.dp(4);
        lineSpacing = AndroidUtilities.dp(4);
    }

    public void setRequirements(List<String> requirements) {
        removeAllViews();
        if (requirements == null || requirements.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        for (String requirement : requirements) {
            final String itemText = requirement;
            TextView view = new TextView(getContext());
            int color = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            view.setText(itemText);
            view.setTextSize(1, 12);
            view.setTypeface(Typeface.DEFAULT);
            view.setTextColor(color);
            view.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(6), ColorUtils.setAlphaComponent(color, 30)));
            view.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(2), AndroidUtilities.dp(6), AndroidUtilities.dp(2));
            ScaleStateListAnimator.apply(view);
            view.setOnClickListener(v -> openRequirement(itemText));
            addView(view);
        }
        requestLayout();
    }

    private void openRequirement(String requirement) {
        Matcher matcher = REQUIREMENT_PATTERN.matcher(requirement);
        if (matcher.find()) {
            Browser.openUrl(getContext(), "https://pypi.org/project/" + matcher.group(1) + "/");
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int availableWidth = width - getPaddingLeft() - getPaddingRight();
        int currentX = 0;
        int currentY = getPaddingTop();
        int lineHeight = 0;
        int contentWidth = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (currentX + childWidth > availableWidth && currentX > 0) {
                currentY += lineHeight + lineSpacing;
                currentX = 0;
                lineHeight = 0;
            }
            currentX += childWidth + itemSpacing;
            lineHeight = Math.max(lineHeight, childHeight);
            contentWidth = Math.max(contentWidth, currentX - itemSpacing);
        }
        int measuredHeight = currentY + lineHeight + getPaddingBottom();
        if (mode != MeasureSpec.EXACTLY) {
            width = contentWidth + getPaddingLeft() + getPaddingRight();
        }
        setMeasuredDimension(width, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int maxRight = right - left - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (x + childWidth > maxRight && x > getPaddingLeft()) {
                x = getPaddingLeft();
                y += lineHeight + lineSpacing;
                lineHeight = 0;
            }
            child.layout(x, y, x + childWidth, y + childHeight);
            x += childWidth + itemSpacing;
            lineHeight = Math.max(lineHeight, childHeight);
        }
    }
}
