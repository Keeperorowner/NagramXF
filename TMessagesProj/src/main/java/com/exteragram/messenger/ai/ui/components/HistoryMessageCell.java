package com.exteragram.messenger.ai.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.ai.data.Message;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * One entry of the saved conversation: a header naming the author and the message body, collapsed
 * to a few lines until the entry is expanded.
 */
@SuppressLint("ViewConstructor")
public class HistoryMessageCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView titleView;
    private final TextView textView;
    private boolean needDivider;

    public HistoryMessageCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 51, 22.0f, 10.0f, 22.0f, 11.0f));

        titleView = new TextView(context);
        titleView.setTextSize(1, 14.0f);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(LocaleController.isRTL ? 5 : 3);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        textView.setTextSize(1, 15.0f);
        textView.setGravity(LocaleController.isRTL ? 5 : 3);
        content.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0.0f, 3.0f, 0.0f, 0.0f));
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), TLObject.FLAG_30), heightMeasureSpec);
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(AndroidUtilities.dp(22.0f), getMeasuredHeight() - 1, getMeasuredWidth() - AndroidUtilities.dp(22.0f), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    /**
     * Binds one message. The body is clamped to six lines unless the entry is expanded.
     */
    public void set(Message message, CharSequence content, boolean expanded, boolean divider) {
        needDivider = divider;
        textView.setMaxLines(expanded ? Integer.MAX_VALUE : 6);
        textView.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);

        final boolean assistant = "assistant".equals(message.role());
        titleView.setText(LocaleController.getString(assistant ? R.string.AIChat : R.string.FromYou));
        titleView.setTextColor(Theme.getColor(assistant ? Theme.key_windowBackgroundWhiteGrayText2 : Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        textView.setText(Emoji.replaceEmoji(content == null ? "" : content, textView.getPaint().getFontMetricsInt(), false));
    }

    public static class Factory extends UItem.UItemFactory<HistoryMessageCell> {
        static {
            UItem.UItemFactory.setup(new Factory());
        }

        @Override
        public HistoryMessageCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new HistoryMessageCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof HistoryMessageCell && item.object instanceof Message) {
                ((HistoryMessageCell) view).set((Message) item.object, item.text, item.checked, divider);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            if (!(a.object instanceof Message) || !(b.object instanceof Message)) {
                return false;
            }
            final Message first = (Message) a.object;
            final Message second = (Message) b.object;
            return a.checked == b.checked
                    && TextUtils.equals(first.role(), second.role())
                    && TextUtils.equals(a.text, b.text);
        }

        /**
         * Builds the item for one history entry. The body is carried in {@code text} and the
         * expanded flag in {@code checked}.
         */
        public static UItem asHistoryCell(int id, Message message, CharSequence content, boolean expanded) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = message;
            item.text = content;
            item.checked = expanded;
            return item;
        }
    }
}
