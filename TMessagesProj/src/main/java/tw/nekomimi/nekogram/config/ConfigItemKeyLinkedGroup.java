package tw.nekomimi.nekogram.config;

import java.util.List;

/**
 * 聚合多个 {@link ConfigItemKeyLinked} 位开关为一组，用于单个 checkbox 控制多位。
 * 任一位开启即视为勾选；切换时同步设置组内所有位。
 */
public class ConfigItemKeyLinkedGroup extends ConfigItem {

    public final List<ConfigItemKeyLinked> items;

    public ConfigItemKeyLinkedGroup(String key, List<ConfigItemKeyLinked> items) {
        super(key, ConfigItem.configTypeBoolLinkInt, false);
        this.items = items;
    }

    @Override
    public boolean Bool() {
        if (items.isEmpty()) return false;
        for (ConfigItemKeyLinked item : items) {
            if (item.Bool()) return true;
        }
        return false;
    }

    @Override
    public boolean toggleConfigBool() {
        boolean newValue = !Bool();
        setConfigBool(newValue);
        return newValue;
    }

    @Override
    public void setConfigBool(boolean v) {
        for (ConfigItemKeyLinked item : items) {
            item.setConfigBool(v);
        }
    }
}
