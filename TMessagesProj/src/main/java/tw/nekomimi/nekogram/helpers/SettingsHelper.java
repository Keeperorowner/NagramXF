package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ProfileActivity.sendLogs;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;

import com.exteragram.messenger.ai.ui.activities.AiPreferencesActivity;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.ui.PluginsActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;

import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import tw.nekomimi.nekogram.settings.GhostModeActivity;
import tw.nekomimi.nekogram.settings.MainTabsCustomizeActivity;
import tw.nekomimi.nekogram.settings.NekoAboutActivity;
import tw.nekomimi.nekogram.settings.NekoAppearanceSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoAyuMomentsSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoAyuSpySettingsActivity;
import tw.nekomimi.nekogram.settings.NekoChatSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoEmojiSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoExperimentalSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoGeneralSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoPasscodeSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoTranslatorSettingsActivity;
import tw.nekomimi.nekogram.settings.SidebarMenuActivity;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;

public class SettingsHelper {

    public static void processDeepLink(Activity activity, Uri uri, Callback callback, Runnable unknown) {
        if (uri == null) {
            unknown.run();
            return;
        }
        var segments = uri.getPathSegments();
        if (segments.isEmpty() || segments.size() > 2 || !"nasettings".equals(segments.get(0))) {
            unknown.run();
            return;
        }
        BaseFragment fragment;
        BaseNekoSettingsActivity neko_fragment = null;
        BaseNekoXSettingsActivity nekox_fragment = null;
        if (segments.size() == 1) {
            fragment = new NekoSettingsActivity();
        } else if (PasscodeHelper.getSettingsKey().equals(segments.get(1))) {
            fragment = neko_fragment = new NekoPasscodeSettingsActivity();
        } else {
            switch (segments.get(1)) {
                case "about":
                    fragment = new NekoAboutActivity();
                    break;
                case "chat":
                case "chats":
                case "c":
                    fragment = nekox_fragment = new NekoChatSettingsActivity();
                    break;
                case "appearance":
                case "a":
                    fragment = nekox_fragment = new NekoAppearanceSettingsActivity();
                    break;
                case "ayumoments":
                case "ayugrammoment":
                case "m":
                    fragment = nekox_fragment = new NekoAyuMomentsSettingsActivity();
                    break;
                case "ayuspy":
                case "spy":
                    fragment = nekox_fragment = new NekoAyuSpySettingsActivity();
                    break;
                case "experimental":
                case "e":
                    fragment = nekox_fragment = new NekoExperimentalSettingsActivity();
                    break;
                case "emoji":
                    fragment = neko_fragment = new NekoEmojiSettingsActivity();
                    break;
                case "general":
                case "g":
                    fragment = nekox_fragment = new NekoGeneralSettingsActivity();
                    break;
                case "translator":
                case "translate":
                case "t":
                    fragment = nekox_fragment = new NekoTranslatorSettingsActivity();
                    break;
                case "ghostmode":
                case "ghost":
                    fragment = nekox_fragment = new GhostModeActivity();
                    break;
                case "maintabs":
                case "main_tabs":
                case "tabs":
                    fragment = nekox_fragment = new MainTabsCustomizeActivity();
                    break;
                case "sidebar":
                case "drawer":
                    fragment = neko_fragment = new SidebarMenuActivity();
                    break;
                case "pillstack":
                case "pills":
                    fragment = neko_fragment = new PillStackPreferencesActivity();
                    break;
                case "regexfilters":
                case "regex":
                    fragment = nekox_fragment = new RegexFiltersSettingActivity();
                    break;
                case "plugins":
                case "plugin":
                case "p":
                    if (!PluginsController.isPluginEngineSupported()) {
                        unknown.run();
                        return;
                    }
                    fragment = new PluginsActivity();
                    break;
                case "send_logs":
                    sendLogs(activity, false);
                    return;
                default:
                    unknown.run();
                    return;
            }
        }
        // The navigation-drawer toggle moved into the sidebar manager; forward its old deep link.
        if (fragment instanceof NekoAppearanceSettingsActivity) {
            String navRow = uri.getQueryParameter("r");
            if (TextUtils.isEmpty(navRow)) {
                navRow = uri.getQueryParameter("row");
            }
            if ("navigationDrawerEnabled".equals(navRow)) {
                fragment = neko_fragment = new SidebarMenuActivity();
                nekox_fragment = null;
            }
        }
        callback.presentFragment(fragment);
        var row = uri.getQueryParameter("r");
        if (TextUtils.isEmpty(row)) {
            row = uri.getQueryParameter("row");
        }
        var value = uri.getQueryParameter("v");
        if (TextUtils.isEmpty(value)) {
            value = uri.getQueryParameter("value");
        }
        if (!TextUtils.isEmpty(row)) {
            var rowFinal = row;
            if (neko_fragment != null) {
                BaseNekoSettingsActivity finalNeko_fragment = neko_fragment;
                AndroidUtilities.runOnUIThread(() -> finalNeko_fragment.scrollToRow(rowFinal, unknown));
            } else if (nekox_fragment != null) {
                BaseNekoXSettingsActivity finalNekoX_fragment = nekox_fragment;
                if (!TextUtils.isEmpty(value)) {
                    String finalValue = value;
                    AndroidUtilities.runOnUIThread(() -> finalNekoX_fragment.importToRow(rowFinal, finalValue, unknown));
                } else {
                    AndroidUtilities.runOnUIThread(() -> finalNekoX_fragment.scrollToRow(rowFinal, unknown));
                }
            }
        }
    }

    public interface Callback {
        void presentFragment(BaseFragment fragment);
    }

    public static ArrayList<SettingsSearchResult> onCreateSearchArray(Callback callback) {
        ArrayList<SettingsSearchResult> items = new ArrayList<>();
        String n_title = getString(R.string.NekoSettings);
        if (PluginsController.isPluginEngineSupported()) {
            items.add(new SettingsSearchResult(
                    900000,
                    getString(R.string.Plugins),
                    n_title,
                    null,
                    R.drawable.msg_plugins,
                    () -> callback.presentFragment(new PluginsActivity())
            ));
        }
        // 顶层 N 设置页：既是搜索结果分类入口，也索引其内部选项。
        BaseNekoXSettingsActivity[] pages = {
                new NekoGeneralSettingsActivity(),
                new NekoAppearanceSettingsActivity(),
                new NekoAyuMomentsSettingsActivity(),
                new NekoAyuSpySettingsActivity(),
                new NekoChatSettingsActivity(),
                new NekoExperimentalSettingsActivity(),
                new NekoTranslatorSettingsActivity(),
                // 子设置页：入口行已在父页面可搜，这里只补充索引其内部选项。
                new GhostModeActivity(),
                new MainTabsCustomizeActivity(),
                new RegexFiltersSettingActivity(),
        };
        final int topPageCount = 7;

        for (int idx = 0; idx < pages.length; idx++) {
            BaseNekoXSettingsActivity fragment = pages[idx];
            int drawable = fragment.getDrawable();
            String f_title = fragment.getTitle();
            if (idx < topPageCount && !TextUtils.isEmpty(f_title)) {
                items.add(new SettingsSearchResult(searchGuid(fragment.getClass(), "", 0), f_title, n_title, null, drawable,
                        () -> callback.presentFragment(fragment)));
            }
            for (Map.Entry<String, AbstractConfigCell> entry : fragment.getSearchRows().entrySet()) {
                String key = entry.getKey();
                Runnable open = () -> {
                    callback.presentFragment(fragment);
                    AndroidUtilities.runOnUIThread(() -> fragment.scrollToRow(key, null));
                };
                int titleIndex = 0;
                for (CharSequence title : entry.getValue().getSearchTitles()) {
                    int guid = searchGuid(fragment.getClass(), key, titleIndex++);
                    if (TextUtils.isEmpty(title) || title.toString().startsWith("LOC_ERR")) {
                        continue;
                    }
                    items.add(new SettingsSearchResult(guid, title.toString(), n_title, f_title, drawable, open));
                }
            }
        }
        items.add(new SettingsSearchResult(searchGuid(AiPreferencesActivity.class, "", 0),
                getString(R.string.AIChat), n_title, null, R.drawable.ai_chat,
                () -> callback.presentFragment(new AiPreferencesActivity())));
        addSearchRow(items, callback, SidebarMenuActivity::new, SidebarMenuActivity.class,
                R.string.DrawerElements, R.string.HomeDrawer, "navigationDrawerEnabled", R.drawable.menu_newfilter);
        addSearchRow(items, callback, PillStackPreferencesActivity::new, PillStackPreferencesActivity.class,
                R.string.PillStackPills, R.string.PillStackInfiniteScrolling, "pillStackInfiniteScrolling", R.drawable.ic_ab_search);
        if (!PasscodeHelper.isSettingsHidden()) {
            items.add(new SettingsSearchResult(searchGuid(NekoPasscodeSettingsActivity.class, "", 0),
                    getString(R.string.PasscodeNeko), n_title, null, R.drawable.msg_secret,
                    () -> callback.presentFragment(new NekoPasscodeSettingsActivity())));
        }
        return items;
    }

    private static void addSearchRow(ArrayList<SettingsSearchResult> items, Callback callback,
                                     Supplier<? extends BaseNekoSettingsActivity> factory, Class<?> page,
                                     int pageTitle, int title, String key, int icon) {
        items.add(new SettingsSearchResult(searchGuid(page, key, 0), getString(title),
                getString(R.string.NekoSettings), getString(pageTitle), icon, () -> {
            BaseNekoSettingsActivity fragment = factory.get();
            callback.presentFragment(fragment);
            AndroidUtilities.runOnUIThread(() -> fragment.scrollToRow(key, null));
        }));
    }

    private static int searchGuid(Class<?> page, String key, int titleIndex) {
        // Keep search-history identities stable across row insertion, folding and locale changes.
        return 0x40000000 | ((page.getName() + ":" + key + ":" + titleIndex).hashCode() & 0x3fffffff);
    }
}
