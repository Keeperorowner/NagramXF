package com.exteragram.messenger.pillstack.core;

import android.content.Context;

import androidx.annotation.Keep;

import com.exteragram.messenger.pillstack.ui.pills.BasePill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.BtcPill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.TonPill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.UsdPill;
import com.exteragram.messenger.pillstack.ui.pills.system.CachePill;
import com.exteragram.messenger.pillstack.ui.pills.system.LastSeenPill;
import com.exteragram.messenger.pillstack.ui.pills.system.ProxyPill;
import com.exteragram.messenger.pillstack.ui.pills.weather.WeatherPill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class PillRegistry {

    public interface PillCreator {
        BasePill create(Context context, Theme.ResourcesProvider resourcesProvider);
    }

    public static final class PillInfo {
        public final int id;
        public final CharSequence name;
        public final int iconRes;
        public final int iconColorTop;
        public final int iconColorBottom;
        public final PillCreator creator;

        public PillInfo(int id, CharSequence name, int iconRes, int iconColorTop, int iconColorBottom, PillCreator creator) {
            this.id = id;
            this.name = name;
            this.iconRes = iconRes;
            this.iconColorTop = iconColorTop;
            this.iconColorBottom = iconColorBottom;
            this.creator = creator;
        }

        public int id() {
            return id;
        }

        public CharSequence name() {
            return name;
        }

        public int iconRes() {
            return iconRes;
        }

        public int iconColorTop() {
            return iconColorTop;
        }

        public int iconColorBottom() {
            return iconColorBottom;
        }

        public PillCreator creator() {
            return creator;
        }
    }

    private static final Map<Integer, PillInfo> registry = new LinkedHashMap<>();
    private static boolean batchRegistration;

    static {
        beginTransaction();
        registerDefaultPills();
        endTransaction();
    }

    @Keep
    public static void beginTransaction() {
        batchRegistration = true;
    }

    @Keep
    public static void endTransaction() {
        batchRegistration = false;
        if (PillStackConfig.configLoaded) {
            PillStackConfig.sanitizePills();
            AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged));
        }
    }

    private static void registerDefaultPills() {
        register(new PillInfo(
            PillStackConfig.PillType.WEATHER.id,
            LocaleController.getString(R.string.WeatherPill),
            R.drawable.weather_cloudy,
            0xFF5AA2B2, 0xFF3A7788,
            WeatherPill::new
        ));
        register(new PillInfo(
            PillStackConfig.PillType.TON.id,
            "TON",
            R.drawable.settings_ton,
            0xFF28A2ED, 0xFF1873E1,
            TonPill::new
        ));
        register(new PillInfo(
            PillStackConfig.PillType.BTC.id,
            "BTC",
            R.drawable.pillstack_btc_settings,
            0xFFEFA812, 0xFFE7A012,
            BtcPill::new
        ));
        register(new PillInfo(
            PillStackConfig.PillType.USD.id,
            "USD",
            R.drawable.pillstack_usd_settings,
            0xFF2DA35D, 0xFF287B49,
            UsdPill::new
        ));
        register(new PillInfo(
            PillStackConfig.PillType.CACHE.id,
            LocaleController.getString(R.string.StorageUsage),
            R.drawable.msg_filled_storageusage,
            0xFF4CAFEF, 0xFF333E68,
            CachePill::new
        ));
        register(new PillInfo(
            PillStackConfig.PillType.PROXY.id,
            LocaleController.getString(R.string.Proxy),
            R.drawable.msg_proxy,
            0xFF55C4D7, 0xFF27475C,
            ProxyPill::new
        ));
        register(new PillInfo(
            PillStackConfig.PillType.LAST_SEEN.id,
            LocaleController.getString(R.string.PremiumPreviewLastSeen),
            R.drawable.menu_premium_seen,
            0xFF8E94D5, 0xFF635A66,
            LastSeenPill::new
        ));
    }

    public static void register(PillInfo info) {
        registry.put(info.id, info);
        if (batchRegistration) {
            return;
        }
        PillStackConfig.sanitizePills();
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged));
    }

    @Keep
    public static void activatePill(int id) {
        if (!isRegistered(id) || PillStackConfig.activePills.contains(id)) {
            return;
        }
        PillStackConfig.hiddenPills.remove((Integer) id);
        PillStackConfig.activePills.add(id);
        PillStackConfig.savePillsLayout();
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged));
    }

    public static PillInfo getPillInfo(int id) {
        return registry.get(id);
    }

    public static Collection<PillInfo> getRegisteredPills() {
        return registry.values();
    }

    public static boolean isRegistered(int id) {
        return registry.containsKey(id);
    }

    @Keep
    public static void unregister(int id) {
        if (registry.remove(id) == null || batchRegistration) {
            return;
        }
        PillStackConfig.sanitizePills();
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged));
    }
}
