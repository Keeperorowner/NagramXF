package tw.nekomimi.nekogram.filters;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.StickerImageView;

public class FiltersImportBottomSheet extends BottomSheet {

    public static class Summary {
        public int newFilters;
        public int updatedFilters;
        public int newChatFilters;
        public int newExclusions;
        public int newShadowBans;
        public int peersToResolve;

        public boolean isEmpty() {
            return newFilters == 0
                    && updatedFilters == 0
                    && newChatFilters == 0
                    && newExclusions == 0
                    && newShadowBans == 0
                    && peersToResolve == 0;
        }
    }

    public FiltersImportBottomSheet(BaseFragment fragment, Summary summary, Runnable onConfirm) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        Context context = fragment.getParentActivity();

        fixNavigationBar();

        FrameLayout frameLayout = new FrameLayout(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        frameLayout.addView(layout);

        StickerImageView sticker = new StickerImageView(context, currentAccount);
        sticker.setStickerPackName("exteraGramPlaceholders");
        sticker.setStickerNum(6);
        sticker.getImageReceiver().setAutoRepeat(1);
        sticker.getImageReceiver().setAutoRepeatCount(1);
        layout.addView(sticker, LayoutHelper.createLinear(144, 144, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView title = new TextView(context);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(getString(R.string.RegexFiltersImportPreviewTitle));
        layout.addView(title, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 40, 20, 40, 0));

        TextView subtitle = new TextView(context);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setText(AndroidUtilities.replaceTags(buildBulletText(summary)));
        layout.addView(subtitle, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 21, 15, 21, 8));

        TextView confirm = new TextView(context);
        ScaleStateListAnimator.apply(confirm, 0.02f, 1.5f);
        confirm.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
        confirm.setGravity(Gravity.CENTER);
        confirm.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        confirm.setTypeface(AndroidUtilities.bold());
        confirm.setText(getString(R.string.RegexFiltersImportConfirm));
        confirm.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        int btnColor = getThemedColor(Theme.key_featuredStickers_addButton);
        confirm.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8), btnColor,
                ColorUtils.setAlphaComponent(getThemedColor(Theme.key_windowBackgroundWhite), 120)));
        confirm.setOnClickListener(v -> {
            dismiss();
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        layout.addView(confirm, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 16, 15, 16, 8));

        TextView cancel = new TextView(context);
        ScaleStateListAnimator.apply(cancel, 0.02f, 1.5f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        cancel.setText(getString(R.string.Cancel));
        cancel.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        cancel.setOnClickListener(v -> dismiss());
        layout.addView(cancel, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(frameLayout);
        setCustomView(scroll);
    }

    private static String buildBulletText(Summary s) {
        StringBuilder sb = new StringBuilder();
        if (s.newFilters > 0) {
            appendLine(sb, LocaleController.formatString(
                    "RegexFiltersImportNewFilters", R.string.RegexFiltersImportNewFilters, s.newFilters));
        }
        if (s.updatedFilters > 0) {
            appendLine(sb, LocaleController.formatString(
                    "RegexFiltersImportUpdatedFilters", R.string.RegexFiltersImportUpdatedFilters, s.updatedFilters));
        }
        if (s.newChatFilters > 0) {
            appendLine(sb, LocaleController.formatString(
                    "RegexFiltersImportChatFilters", R.string.RegexFiltersImportChatFilters, s.newChatFilters));
        }
        if (s.newExclusions > 0) {
            appendLine(sb, LocaleController.formatString(
                    "RegexFiltersImportExclusions", R.string.RegexFiltersImportExclusions, s.newExclusions));
        }
        if (s.newShadowBans > 0) {
            appendLine(sb, LocaleController.formatString(
                    "RegexFiltersImportShadowBans", R.string.RegexFiltersImportShadowBans, s.newShadowBans));
        }
        if (s.peersToResolve > 0) {
            appendLine(sb, LocaleController.formatString(
                    "RegexFiltersImportPeers", R.string.RegexFiltersImportPeers, s.peersToResolve));
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb.length() > 0) sb.append('\n');
        sb.append("• ").append(line);
    }
}
