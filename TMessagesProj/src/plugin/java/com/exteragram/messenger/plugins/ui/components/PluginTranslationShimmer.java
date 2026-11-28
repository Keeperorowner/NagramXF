package com.exteragram.messenger.plugins.ui.components;

import android.graphics.Canvas;
import android.graphics.Path;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.plugins.models.DividerSetting;
import com.exteragram.messenger.plugins.ui.PluginSettingsTranslation;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

/** Draws a shared translation shimmer over visible labels without replacing their text or layout. */
public final class PluginTranslationShimmer extends RecyclerView.ItemDecoration {
    private final PluginSettingsTranslation translation;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Path path = new Path();
    private final LoadingDrawable drawable = new LoadingDrawable();

    public PluginTranslationShimmer(PluginSettingsTranslation translation, Theme.ResourcesProvider resourcesProvider) {
        this.translation = translation;
        this.resourcesProvider = resourcesProvider;
        drawable.usePath(path);
        drawable.setRadiiDp(4);
        drawable.setAppearByGradient(true);
    }

    @Override
    public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!translation.getEnabled() || !translation.isLoading()) {
            drawable.reset();
            return;
        }
        if (!(parent.getAdapter() instanceof UniversalAdapter adapter)) {
            return;
        }
        path.rewind();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View row = parent.getChildAt(i);
            if (row.getY() + row.getHeight() <= 0 || row.getY() >= parent.getHeight()
                    || row.getVisibility() != View.VISIBLE || row.getAlpha() == 0f) {
                continue;
            }
            UItem item = adapter.getItem(parent.getChildAdapterPosition(row));
            if (item == null) {
                continue;
            }
            if (row instanceof TextCell text) {
                if (isPending(item.text)) {
                    addText(row, text.getTextView());
                }
            } else if (row instanceof TextCheckCell check) {
                if (isPending(item.text)) {
                    addText(row, check.getTextView());
                }
                if (isPending(item.textValue)) {
                    addText(row, check.getValueTextView());
                }
            } else if (row instanceof HeaderCell header) {
                if (isPending(item.text)) {
                    addText(row, header.getTextView());
                }
            } else if (row instanceof TextInfoPrivacyCell info && item.object2 instanceof DividerSetting divider
                    && translation.isPending(divider.text)) {
                addText(row, info.getTextView());
            }
        }
        if (path.isEmpty()) {
            return;
        }
        int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
        drawable.setColors(Theme.multAlpha(color, .05f), Theme.multAlpha(color, .15f),
                Theme.multAlpha(color, .1f), Theme.multAlpha(color, .3f));
        drawable.updateBounds();
        drawable.draw(canvas);
        parent.postInvalidateOnAnimation();
    }

    private boolean isPending(CharSequence text) {
        return text instanceof TranslatableText translatable && translation.isPending(translatable.getOriginal());
    }

    private void addText(View row, TextView view) {
        if (view != null) {
            addLines(row, view, view.getLayout(), view.getCompoundPaddingLeft() - view.getScrollX(),
                    view.getTotalPaddingTop() - view.getScrollY());
        }
    }

    private void addText(View row, SimpleTextView view) {
        addLines(row, view, view.getLayout(), view.getLayoutX(), view.getLayoutY());
    }

    private void addLines(View row, View text, Layout layout, float layoutX, float layoutY) {
        if (layout == null || text.getVisibility() != View.VISIBLE) {
            return;
        }
        final float inset = AndroidUtilities.dp(2);
        final float x = row.getX() + text.getX();
        final float y = row.getY() + text.getY();
        for (int line = 0; line < layout.getLineCount(); line++) {
            final float left = Math.max(0, layoutX + layout.getLineLeft(line) - inset);
            final float right = Math.min(text.getWidth(), layoutX + layout.getLineRight(line) + inset);
            final float top = Math.max(0, layoutY + layout.getLineTop(line) + inset);
            final float bottom = Math.min(text.getHeight(), layoutY + layout.getLineBottom(line) - inset);
            if (right - left > inset * 2 && bottom > top) {
                path.addRect(x + left, y + top, x + right, y + bottom, Path.Direction.CW);
            }
        }
    }
}
