package tw.nekomimi.nekogram.filters.popup;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import tw.nekomimi.nekogram.filters.AyuFilter;
import tw.nekomimi.nekogram.filters.RegexChatFiltersListActivity;
import tw.nekomimi.nekogram.filters.RegexFilterEditActivity;

public class RegexChatFilterPopup {
    public static void show(RegexChatFiltersListActivity fragment, View anchorView, float touchedX, float touchedY, long dialogId, int filterIdx) {
        if (fragment.getFragmentView() == null) return;

        var layout = RegexPopupUtils.createLayout(fragment);
        var popupWindow = RegexPopupUtils.createPopupWindow(layout);
        var windowLayout = createPopupLayout(layout, popupWindow, fragment, dialogId, filterIdx);
        RegexPopupUtils.showPopupAtTouch(fragment, anchorView, touchedX, touchedY, popupWindow, windowLayout);
    }

    private static ActionBarPopupWindow.ActionBarPopupWindowLayout createPopupLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout layout, ActionBarPopupWindow popupWindow, RegexChatFiltersListActivity fragment, long dialogId, int filterIdx) {
        layout.setFitItems(true);

        ArrayList<AyuFilter.FilterModel> filters = AyuFilter.getChatFiltersForDialog(dialogId);
        boolean enabled = filterIdx >= 0 && filterIdx < filters.size() && filters.get(filterIdx).enabled;

        var editBtn = ActionBarMenuItem.addItem(layout, R.drawable.msg_edit, getString(R.string.Edit), false, fragment.getResourceProvider());
        editBtn.setOnClickListener(view -> {
            fragment.presentFragment(new RegexFilterEditActivity(dialogId, filterIdx));
            popupWindow.dismiss();
        });

        var toggleBtn = ActionBarMenuItem.addItem(layout,
                enabled ? R.drawable.msg_noise_off_solar : R.drawable.msg_noise_on_solar,
                getString(enabled ? R.string.Disable : R.string.Enable),
                false, fragment.getResourceProvider());
        toggleBtn.setOnClickListener(view -> {
            ArrayList<AyuFilter.ChatFilterEntry> entries = AyuFilter.getChatFilterEntries();
            for (AyuFilter.ChatFilterEntry entry : entries) {
                if (entry.dialogId == dialogId && entry.filters != null && filterIdx >= 0 && filterIdx < entry.filters.size()) {
                    AyuFilter.FilterModel model = entry.filters.get(filterIdx);
                    model.enabled = !model.enabled;
                    AyuFilter.saveChatFilterEntries(entries);
                    break;
                }
            }
            fragment.onResume();
            popupWindow.dismiss();
        });

        ActionBarPopupWindow.GapView gap = new ActionBarPopupWindow.GapView(layout.getContext(), fragment.getResourceProvider());
        gap.setTag(R.id.fit_width_tag, 1);
        layout.addView(gap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        var deleteBtn = ActionBarMenuItem.addItem(layout, R.drawable.msg_delete, getString(R.string.Delete), false, fragment.getResourceProvider());
        deleteBtn.setOnClickListener(view -> {
            AyuFilter.removeChatFilter(dialogId, filterIdx);
            fragment.onResume();
            popupWindow.dismiss();
        });
        RegexPopupUtils.applyDeleteItemColor(deleteBtn);

        return layout;
    }
}
