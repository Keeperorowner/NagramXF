package com.exteragram.messenger.utils.text;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.helpers.EntitiesHelper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocaleUtils {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_]{1,32})");

    private LocaleUtils() {
    }

    public static CharSequence formatWithUsernames(CharSequence text) {
        return formatWithUsernames(text, LaunchActivity.getSafeLastFragment(), null);
    }

    public static CharSequence formatWithUsernames(CharSequence text, BaseFragment fragment) {
        return formatWithUsernames(text, fragment, null);
    }

    public static CharSequence formatWithUsernames(CharSequence text, BaseFragment fragment, Runnable beforeOpen) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        final BaseFragment[] targetFragment = new BaseFragment[]{fragment != null ? fragment : LaunchActivity.getSafeLastFragment()};
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        Matcher matcher = USERNAME_PATTERN.matcher(text);
        while (matcher.find()) {
            URLSpan[] spans = builder.getSpans(matcher.start(), matcher.end(), URLSpan.class);
            if (spans != null && spans.length > 0) {
                continue;
            }
            final String username = matcher.group(1);
            if (TextUtils.isEmpty(username)) {
                continue;
            }
            builder.setSpan(new URLSpanNoUnderline("@" + username) {
                @Override
                public void onClick(View widget) {
                    if (beforeOpen != null) {
                        beforeOpen.run();
                    }
                    BaseFragment target = targetFragment[0] != null ? targetFragment[0] : LaunchActivity.getSafeLastFragment();
                    if (target != null && target.getMessagesController() != null) {
                        target.getMessagesController().openByUserName(username, target, 1);
                    }
                }
            }, matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    public static CharSequence fullyFormatText(CharSequence text) {
        return fullyFormatText(text, null, null);
    }

    public static CharSequence fullyFormatText(CharSequence text, BaseFragment fragment, Runnable beforeOpen) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        Theme.ResourcesProvider resourcesProvider = fragment != null ? fragment.getResourceProvider() : null;
        SpannableStringBuilder builder = AndroidUtilities.replaceTags(text.toString(), AndroidUtilities.FLAG_TAG_BR | AndroidUtilities.FLAG_TAG_BOLD);
        builder = AndroidUtilities.replaceLinks(builder.toString(), resourcesProvider, beforeOpen);
        builder = new SpannableStringBuilder(EntitiesHelper.parseMarkdown(builder));
        builder = new SpannableStringBuilder(formatWithUsernames(builder, fragment, beforeOpen));
        Linkify.addLinks(builder, Linkify.WEB_URLS);
        return builder;
    }

    public static CharSequence formatWithHtmlURLs(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        SpannableString spannable = new SpannableString(text);
        URLSpan[] spans = spannable.getSpans(0, text.length(), URLSpan.class);
        SpannableStringBuilder builder = new SpannableStringBuilder(spannable);
        for (URLSpan span : spans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            String url = span.getURL();
            builder.removeSpan(span);
            builder.setSpan(new URLSpanNoUnderline(url), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    public static CharSequence fromHtml(String text) {
        if (text == null) {
            return null;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? new SpannableString(Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY))
                : new SpannableString(Html.fromHtml(text));
    }

    public static Spannable createCopySpan(BaseFragment fragment) {
        SpannableString span = new SpannableString(" ");
        if (fragment == null || fragment.getParentActivity() == null) {
            return span;
        }
        Drawable drawable = ContextCompat.getDrawable(fragment.getParentActivity(), R.drawable.msg_copy);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_undo_cancelColor, fragment.getResourceProvider()), PorterDuff.Mode.SRC_IN));
            drawable.setBounds(0, 0, AndroidUtilities.dp(22), AndroidUtilities.dp(22));
            span.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }
}
