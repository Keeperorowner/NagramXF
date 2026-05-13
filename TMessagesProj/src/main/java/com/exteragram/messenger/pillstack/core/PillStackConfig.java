package com.exteragram.messenger.pillstack.core;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

public abstract class PillStackConfig {

    public enum PillType {
        WEATHER(1),
        TON(2),
        BTC(3),
        USD(4),
        CACHE(5),
        PROXY(6),
        LAST_SEEN(100);

        public final int id;

        PillType(int i) {
            this.id = i;
        }
    }

    public static SharedPreferences preferences;
    public static SharedPreferences.Editor editor;

    public static boolean useCurrentLocation;
    public static String customWeatherLocation;
    public static String customWeatherAddress;
    public static boolean infiniteScrolling;
    public static int lastActivePillId = -1;

    public static String tonTargetCurrency;
    public static String btcTargetCurrency;
    public static String usdTargetCurrency;

    public static ArrayList<Integer> activePills = new ArrayList<>();
    public static ArrayList<Integer> hiddenPills = new ArrayList<>();

    private static final boolean[] lastSeenPeriodicOnline = new boolean[16];

    static boolean configLoaded;
    private static final HashSet<Integer> pendingUpdates = new HashSet<>();
    private static final Object sync = new Object();

    static {
        loadConfig();
    }

    public static ArrayList<Integer> getDefaultActivePills() {
        return new ArrayList<>();
    }

    private static ArrayList<Integer> parsePillsList(String str) {
        ArrayList<Integer> list = new ArrayList<>();
        if (str == null || str.isEmpty()) {
            return list;
        }
        if (str.startsWith("[")) {
            str = str.replaceAll("[\\[\\]\"]", "");
        }
        for (String part : str.split(",")) {
            try {
                list.add(Integer.parseInt(part.trim()));
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private static String serializePillsList(ArrayList<Integer> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i));
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        return sb.toString();
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            SharedPreferences sp = ApplicationLoader.applicationContext.getSharedPreferences("pillstackconfig", 0);
            preferences = sp;
            editor = sp.edit();

            useCurrentLocation = sp.getBoolean("useCurrentLocation", true);
            customWeatherLocation = sp.getString("customWeatherLocation", null);
            customWeatherAddress = sp.getString("customWeatherAddress", null);
            infiniteScrolling = sp.getBoolean("infiniteScrolling", true);
            lastActivePillId = sp.getInt("lastActivePillId", -1);
            tonTargetCurrency = sp.getString("tonTargetCurrency", "AUTO");
            btcTargetCurrency = sp.getString("btcTargetCurrency", "AUTO");
            usdTargetCurrency = sp.getString("usdTargetCurrency", "AUTO");
            for (int i = 0; i < 16; i++) {
                lastSeenPeriodicOnline[i] = sp.getBoolean(getLastSeenPeriodicOnlineKey(i), false);
            }

            String activeStr = sp.getString("activePills", null);
            String hiddenStr = sp.getString("hiddenPills", null);
            if (activeStr != null) {
                activePills = parsePillsList(activeStr);
                hiddenPills = hiddenStr != null ? parsePillsList(hiddenStr) : new ArrayList<>();
            } else {
                activePills = new ArrayList<>();
                hiddenPills = new ArrayList<>();
                activePills.addAll(getDefaultActivePills());
                for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
                    if (!activePills.contains(info.id)) {
                        hiddenPills.add(info.id);
                    }
                }
                savePillsLayout();
            }
            sanitizePills();
            configLoaded = true;
        }
    }

    public static void sanitizePills() {
        boolean changed = false;

        Iterator<Integer> it = activePills.iterator();
        while (it.hasNext()) {
            if (!PillRegistry.isRegistered(it.next())) {
                it.remove();
                changed = true;
            }
        }
        it = hiddenPills.iterator();
        while (it.hasNext()) {
            if (!PillRegistry.isRegistered(it.next())) {
                it.remove();
                changed = true;
            }
        }
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            if (!activePills.contains(info.id) && !hiddenPills.contains(info.id)) {
                hiddenPills.add(info.id);
                changed = true;
            }
        }
        if (changed) {
            savePillsLayout();
        }
    }

    public static void savePillsLayout() {
        editor.putString("activePills", serializePillsList(activePills));
        editor.putString("hiddenPills", serializePillsList(hiddenPills));
        editor.apply();
    }

    public static void saveLastActivePillId(int id) {
        lastActivePillId = id;
        editor.putInt("lastActivePillId", id).apply();
    }

    public static void notifySettingsChanged(int... ids) {
        if (ids != null && ids.length > 0) {
            for (int id : ids) {
                pendingUpdates.add(id);
            }
            Object[] args = new Object[ids.length];
            for (int i = 0; i < ids.length; i++) {
                args[i] = ids[i];
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackSettingsChanged, args);
            return;
        }
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            pendingUpdates.add(info.id);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackSettingsChanged);
    }

    public static boolean checkAndClearPendingUpdate(int id) {
        return pendingUpdates.remove(id);
    }

    public static boolean shouldUpdatePill(Object[] args, int... ids) {
        if (args == null || args.length == 0 || ids == null || ids.length == 0) {
            return true;
        }
        for (Object o : args) {
            if (o instanceof Integer) {
                int v = (Integer) o;
                for (int id : ids) {
                    if (v == id) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String getLastSeenPeriodicOnlineKey(int account) {
        return "lastSeenPeriodicOnline_" + account;
    }

    public static boolean isLastSeenPeriodicOnlineEnabled(int account) {
        if (account < 0 || account >= lastSeenPeriodicOnline.length) {
            return false;
        }
        return lastSeenPeriodicOnline[account];
    }

    public static void setLastSeenPeriodicOnlineEnabled(int account, boolean enabled) {
        if (account < 0 || account >= lastSeenPeriodicOnline.length
                || lastSeenPeriodicOnline[account] == enabled) {
            return;
        }
        lastSeenPeriodicOnline[account] = enabled;
        editor.putBoolean(getLastSeenPeriodicOnlineKey(account), enabled).apply();
        notifySettingsChanged(PillType.LAST_SEEN.id);
    }
}
