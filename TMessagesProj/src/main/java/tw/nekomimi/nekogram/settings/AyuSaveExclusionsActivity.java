package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.radolyn.ayugram.AyuSaveExclusions;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Set;

/** Rules for retaining deleted messages and edit history in selected chats. */
public class AyuSaveExclusionsActivity extends BasePreferencesActivity {
    private static final int MODE_ALL = 1;
    private static final int MODE_SELECTED = 2;
    private static final int ALWAYS_LIST = 3;
    private static final int NEVER_LIST = 4;

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        actionBar.setTitle(null);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateHeaderTitle();
            }
        });
        updateHeaderTitle();
        return view;
    }

    private void updateHeaderTitle() {
        if (listView == null || actionBar == null) {
            return;
        }
        boolean topVisible = listView.layoutManager.findFirstVisibleItemPosition() <= 0;
        actionBar.setTitle(topVisible ? null : getTitle());
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean whitelist = AyuSaveExclusions.isWhitelist();
        String description = LocaleController.getString(whitelist
                ? R.string.AyuSaveExclusionsInfoNever : R.string.AyuSaveExclusionsInfoAlways);
        if (whitelist && AyuSaveExclusions.getIds(true).isEmpty() && AyuSaveExclusions.getFlags(true) == 0) {
            description += " " + LocaleController.getString(R.string.AyuSaveExclusionsNothingSaved);
        }
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.AyuSaveExclusionsTopInfo), "RestrictedEmoji", "📥"));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AyuSaveExclusionsMode)));
        items.add(UItem.asRadio(MODE_ALL, LocaleController.getString(R.string.FilterAllChats)).setChecked(!whitelist));
        items.add(UItem.asRadio(MODE_SELECTED, LocaleController.getString(R.string.AyuSaveExclusionsModeNeverShort)).setChecked(whitelist));
        items.add(UItem.asShadow(AndroidUtilities.replaceTags(description)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AyuSaveExclusionsHeader)));
        items.add(listButton(whitelist));
        boolean showSecondary = AyuSaveExclusions.getFlags(whitelist) != 0
                || !AyuSaveExclusions.getIds(!whitelist).isEmpty()
                || AyuSaveExclusions.getFlags(!whitelist) != 0;
        if (showSecondary) {
            items.add(listButton(!whitelist));
        }
        items.add(UItem.asShadow(AndroidUtilities.replaceTags(LocaleController.getString(R.string.AyuSaveExclusionsInfo))));
    }

    private UItem listButton(boolean always) {
        return UItem.asButton(always ? ALWAYS_LIST : NEVER_LIST,
                LocaleController.getString(always ? R.string.AyuSaveExclusionsAlwaysList : R.string.AyuSaveExclusionsNeverList),
                listValue(always));
    }

    private CharSequence listValue(boolean always) {
        int flags = AyuSaveExclusions.getFlags(always);
        Set<String> ids = AyuSaveExclusions.getIds(always);
        ArrayList<String> categories = new ArrayList<>();
        for (AyuSaveExclusionCategory category : AyuSaveExclusionCategory.ALL) {
            if ((flags & category.flag) != 0) {
                categories.add(category.getTitle());
            }
        }
        if (categories.isEmpty() && ids.isEmpty()) {
            return LocaleController.getString(R.string.AyuSaveExclusionsAddChats);
        }
        if (categories.isEmpty()) {
            return LocaleController.formatPluralString("Chats", ids.size());
        }
        if (categories.size() == 1 && ids.isEmpty()) {
            return categories.get(0);
        }
        return categories.get(0) + " +" + (categories.size() - 1 + ids.size());
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == MODE_ALL || item.id == MODE_SELECTED) {
            boolean whitelist = item.id == MODE_SELECTED;
            if (AyuSaveExclusions.isWhitelist() != whitelist) {
                AyuSaveExclusions.setWhitelist(whitelist);
                refreshRows();
            }
        } else if (item.id == ALWAYS_LIST || item.id == NEVER_LIST) {
            presentFragment(new AyuSaveExclusionsSelectFragment(item.id == ALWAYS_LIST, this::refreshRows));
        }
    }

    private void refreshRows() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRows();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(left, 0, right, bottom);
            listView.setClipToPadding(false);
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.AyuSaveExclusions);
    }

    public int getBaseGuid() {
        return 15200;
    }

    public int getDrawable() {
        return R.drawable.msg_discuss;
    }
}
