package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Service;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CreationTextCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class ServicesActivity extends BaseNekoSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_TYPE_RADIO = 100;
    private static final int VIEW_TYPE_CREATION = 101;

    private int addServiceRow;
    private int infoRow;
    private int servicesHeaderRow;
    private int servicesStartRow;
    private int servicesEndRow;
    private int rowCount;

    private List<Service> services = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        boolean result = super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.servicesUpdated);
        reloadServices();
        return result;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.servicesUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.servicesUpdated) {
            reloadServices();
            updateRows();
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadServices();
        updateRows();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void reloadServices() {
        AiController.getInstance().loadServices();
        services = AiController.getInstance().getAll();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == addServiceRow) {
            showProviderDialog();
        } else if (position >= servicesStartRow && position < servicesEndRow) {
            int idx = position - servicesStartRow;
            if (idx >= 0 && idx < services.size()) {
                Service service = services.get(idx);
                if (!service.isSelected()) {
                    AiConfig.setSelectedServices(service);
                    for (int i = 0; i < listView.getChildCount(); i++) {
                        View child = listView.getChildAt(i);
                        int pos = listView.getChildAdapterPosition(child);
                        if (pos >= servicesStartRow && pos < servicesEndRow && child instanceof RadioColorCell) {
                            ((RadioColorCell) child).setChecked(pos == position, true);
                        }
                    }
                }
            }
        }
    }

    private void showProviderDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.AIChatProvider));
        builder.setItems(ProviderPresets.PRESET_NAMES, (dialog, which) -> presentFragment(new EditServiceActivity(which)));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position >= servicesStartRow && position < servicesEndRow) {
            int idx = position - servicesStartRow;
            if (idx >= 0 && idx < services.size()) {
                Service service = services.get(idx);
                ItemOptions.makeOptions(this, view)
                        .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> presentFragment(new EditServiceActivity(service)))
                        .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> {
                            if (AiController.getInstance().removeService(service)) {
                                AiConfig.clearSelectedService();
                                reloadServices();
                                updateRows();
                                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                            }
                        })
                        .setScrimViewBackground(listView.getClipBackground(view))
                        .setGravity(LocaleController.isRTL ? 3 : 5)
                        .show();
                return true;
            }
        }
        return false;
    }

    @Override
    protected BaseNekoSettingsActivity.BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.AIChatServices);
    }

    @Override
    protected void updateRows() {
        rowCount = 0;
        addServiceRow = rowCount++;
        infoRow = rowCount++;
        if (!services.isEmpty()) {
            servicesHeaderRow = rowCount++;
            servicesStartRow = rowCount;
            rowCount += services.size();
            servicesEndRow = rowCount;
        } else {
            servicesHeaderRow = -1;
            servicesStartRow = -1;
            servicesEndRow = -1;
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_RADIO || type == VIEW_TYPE_CREATION;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_CREATION) {
                CreationTextCell cell = new CreationTextCell(mContext, 70, null);
                cell.startPadding = 61;
                cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                view = cell;
            } else if (viewType == VIEW_TYPE_RADIO) {
                RadioColorCell cell = new RadioColorCell(mContext);
                cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
                view = cell;
            } else {
                return super.onCreateViewHolder(parent, viewType);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == VIEW_TYPE_CREATION) {
                CreationTextCell cell = (CreationTextCell) holder.itemView;
                Drawable icon = ContextCompat.getDrawable(mContext, R.drawable.msg_add).mutate();
                icon.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2), android.graphics.PorterDuff.Mode.SRC_IN));
                cell.setTextAndIcon(LocaleController.getString(R.string.AIChatNewService), icon, false);
            } else if (type == VIEW_TYPE_RADIO) {
                RadioColorCell cell = (RadioColorCell) holder.itemView;
                if (position >= servicesStartRow && position < servicesEndRow) {
                    int idx = position - servicesStartRow;
                    Service service = services.get(idx);
                    cell.setTextAndText2AndValue(service.getModel(), service.getUrl(), service.isSelected());
                }
            } else if (type == TYPE_HEADER) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                if (position == servicesHeaderRow) {
                    cell.setText(LocaleController.getString(R.string.AIChatServices));
                }
            } else if (type == TYPE_INFO_PRIVACY) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (position == infoRow) {
                    cell.setText(LocaleController.getString(R.string.AIChatServicesInfo));
                }
                cell.setFixedSize(0);
                cell.setBackground(null);
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == addServiceRow) return VIEW_TYPE_CREATION;
            if (position == infoRow) return TYPE_INFO_PRIVACY;
            if (position == servicesHeaderRow) return TYPE_HEADER;
            return VIEW_TYPE_RADIO;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }
    }
}
