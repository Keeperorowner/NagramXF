package tw.nekomimi.nekogram.config.cell;

import static org.telegram.messenger.LocaleController.getString;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.FileLog;

import org.telegram.ui.Cells.TextDetailSettingsCell;

import tw.nekomimi.nekogram.config.CellGroup;

public class ConfigCellTextDetailIcon extends AbstractConfigCell implements WithKey, WithOnClick {
    private final String key;
    private final String title;
    private final String subtitle;
    private final int iconResId;
    private final boolean divider;
    private final Runnable onClickCustom;

    public ConfigCellTextDetailIcon(String key, String title, String subtitle, int iconResId, boolean divider, Runnable onClickCustom) {
        this.key = key;
        this.title = title;
        this.subtitle = subtitle;
        this.iconResId = iconResId;
        this.divider = divider;
        this.onClickCustom = onClickCustom;
    }

    public int getType() {
        return CellGroup.ITEM_TYPE_TEXT_DETAIL_ICON;
    }

    public String getKey() {
        return key;
    }

    public boolean isEnabled() {
        return true;
    }

    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
        cell.setTextAndValueAndIcon(title, subtitle, iconResId, cellGroup.needSetDivider(this));
    }

    public void onClick() {
        if (onClickCustom != null) {
            try {
                onClickCustom.run();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }
}
