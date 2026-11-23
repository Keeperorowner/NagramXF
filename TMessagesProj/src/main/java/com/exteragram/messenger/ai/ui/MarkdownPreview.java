package com.exteragram.messenger.ai.ui;

import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.TypefaceSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.Components.MarkdownParser;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.iv.RichMessageConvert;
import org.telegram.utils.code.highlight.CodeHighlighting;

import java.util.ArrayList;

/**
 * Renders an answer as formatted text for places that only need a CharSequence, such as the
 * conversation history list.
 */
public abstract class MarkdownPreview {

    /**
     * Parses the markdown and returns the styled text, or the input itself when nothing could be
     * parsed. Trailing whitespace is trimmed because the blocks add their own spacing.
     */
    public static CharSequence format(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        final ArrayList<TL_iv.PageBlock> blocks = new ArrayList<>();
        try {
            MarkdownParser.parse(text, blocks);
        } catch (Throwable e) {
            FileLog.e(e);
            blocks.clear();
        }
        if (!blocks.isEmpty()) {
            final CharSequence formatted = RichMessageConvert.blocksToCharSequence(blocks);
            if (!TextUtils.isEmpty(formatted)) {
                if (formatted instanceof Spannable) {
                    final Spannable spannable = (Spannable) formatted;
                    for (CodeHighlighting.Span span : spannable.getSpans(0, spannable.length(), CodeHighlighting.Span.class)) {
                        final int start = spannable.getSpanStart(span);
                        final int end = spannable.getSpanEnd(span);
                        spannable.removeSpan(span);
                        spannable.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                AndroidUtilities.removeSpans(formatted, QuoteSpan.QuoteStyleSpan.class);
                AndroidUtilities.removeSpans(formatted, QuoteSpan.class);
                int length = formatted.length();
                while (length > 0 && Character.isWhitespace(formatted.charAt(length - 1))) {
                    length--;
                }
                return length == formatted.length() ? formatted : formatted.subSequence(0, length);
            }
        }
        return text;
    }
}
