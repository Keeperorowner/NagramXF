package tw.nekomimi.nekogram.filters.popup;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import tw.nekomimi.nekogram.filters.AyuFilter;
import tw.nekomimi.nekogram.filters.RegexFilterEditActivity;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;
import tw.nekomimi.nekogram.filters.RegexSharedFiltersListActivity;


public class RegexFilterPopup {
    public static void show(RegexFiltersSettingActivity fragment, View anchorView, float touchedX, float touchedY, int filterIdx) {
        show(fragment, anchorView, touchedX, touchedY, fragment.getResourceProvider(), filterIdx);
    }

    public static void show(RegexSharedFiltersListActivity fragment, View anchorView, float touchedX, float touchedY, int filterIdx) {
        show(fragment, anchorView, touchedX, touchedY, fragment.getResourceProvider(), filterIdx);
    }

    private static void show(BaseFragment fragment, View anchorView, float touchedX, float touchedY, Theme.ResourcesProvider resourcesProvider, int filterIdx) {
        if (fragment.getFragmentView() == null) {
            return;
        }

        var layout = RegexPopupUtils.createLayout(fragment);
        var popupWindow = RegexPopupUtils.createPopupWindow(layout);
        var windowLayout = createPopupLayout(layout, popupWindow, fragment, resourcesProvider, filterIdx);
        RegexPopupUtils.showPopupAtTouch(fragment, anchorView, touchedX, touchedY, popupWindow, windowLayout);
    }

    private static ActionBarPopupWindow.ActionBarPopupWindowLayout createPopupLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout layout, ActionBarPopupWindow popupWindow, BaseFragment fragment, Theme.ResourcesProvider resourcesProvider, int filterIdx) {
        layout.setFitItems(true);

        ArrayList<AyuFilter.FilterModel> filters = AyuFilter.getRegexFilters();
        boolean enabled = filterIdx >= 0 && filterIdx < filters.size() && filters.get(filterIdx).enabled;

        var editBtn = ActionBarMenuItem.addItem(layout, R.drawable.msg_edit, getString(R.string.Edit), false, resourcesProvider);
        editBtn.setOnClickListener(view -> {
            fragment.presentFragment(new RegexFilterEditActivity(filterIdx));
            popupWindow.dismiss();
        });

        var toggleBtn = ActionBarMenuItem.addItem(layout,
                enabled ? R.drawable.msg_noise_off_solar : R.drawable.msg_noise_on_solar,
                getString(enabled ? R.string.Disable : R.string.Enable),
                false, resourcesProvider);
        toggleBtn.setOnClickListener(view -> {
            ArrayList<AyuFilter.FilterModel> list = AyuFilter.getRegexFilters();
            if (filterIdx >= 0 && filterIdx < list.size()) {
                AyuFilter.FilterModel model = list.get(filterIdx);
                model.enabled = !model.enabled;
                AyuFilter.saveFilter(list);
                fragment.onResume();
            }
            popupWindow.dismiss();
        });

        ActionBarPopupWindow.GapView gap = new ActionBarPopupWindow.GapView(layout.getContext(), resourcesProvider);
        gap.setTag(R.id.fit_width_tag, 1);
        layout.addView(gap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        var deleteBtn = ActionBarMenuItem.addItem(layout, R.drawable.msg_delete, getString(R.string.Delete), false, resourcesProvider);
        deleteBtn.setOnClickListener(view -> {
            var currentFilters = AyuFilter.getRegexFilters();
            if (filterIdx >= 0 && filterIdx < currentFilters.size()) {
                AyuFilter.removeFilter(filterIdx);
                fragment.onResume();
                popupWindow.dismiss();
            }
        });
        RegexPopupUtils.applyDeleteItemColor(deleteBtn);

        return layout;
    }
}
