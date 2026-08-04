package tw.nekomimi.nekogram.llm.utils;

import android.text.TextUtils;

import java.util.Locale;

public class ReasoningContentFilter {

    private boolean inReasoning;
    private String pending = "";
    private boolean reasoningSignal;

    public String filter(String input) {
        if (TextUtils.isEmpty(input)) return null;
        String text = pending + input;
        pending = "";
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (inReasoning) {
                reasoningSignal = true;
                int closeIdx = lower.indexOf("</think>", i);
                if (closeIdx < 0) {
                    pending = getCloseTagPrefix(text, i);
                    return sb.toString();
                }
                i = closeIdx + 8;
                inReasoning = false;
            } else {
                int openIdx = lower.indexOf("<think>", i);
                if (openIdx < 0) {
                    pending = getOpenTagPrefix(text, i);
                    reasoningSignal = !sb.toString().isEmpty();
                    int end = text.length() - pending.length();
                    if (end > i) sb.append(text, i, end);
                    return sb.toString();
                }
                sb.append(text, i, openIdx);
                i = openIdx + 7;
                inReasoning = true;
                reasoningSignal = true;
            }
        }
        return sb.toString();
    }

    public boolean consumeReasoningSignal() {
        boolean signal = reasoningSignal;
        reasoningSignal = false;
        return signal;
    }

    public String flush() {
        String result = inReasoning ? "" : pending;
        pending = "";
        return result;
    }

    private String getOpenTagPrefix(String text, int from) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (int len = Math.min(6, text.length() - from); len > 0; len--) {
            if ("<think>".startsWith(lower.substring(text.length() - len))) {
                return text.substring(text.length() - len);
            }
        }
        return "";
    }

    private String getCloseTagPrefix(String text, int from) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (int len = Math.min(7, text.length() - from); len > 0; len--) {
            if ("</think>".startsWith(lower.substring(text.length() - len))) {
                return text.substring(text.length() - len);
            }
        }
        return "";
    }

    public static String stripReasoningMarkup(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < text.length()) {
            int openIdx = lower.indexOf("<think>", i);
            if (openIdx < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, openIdx);
            int closeIdx = lower.indexOf("</think>", openIdx + 7);
            if (closeIdx < 0) break;
            i = closeIdx + 8;
        }
        return trimLeading(sb.toString());
    }

    private static String trimLeading(String str) {
        int i = 0;
        while (i < str.length() && Character.isWhitespace(str.charAt(i))) i++;
        return str.substring(i);
    }
}
