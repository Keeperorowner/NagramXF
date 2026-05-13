package com.exteragram.messenger.pillstack.ui.pills.crypto.utils;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.utils.network.ExteraHttpClient;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BillingController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import android.text.TextUtils;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public abstract class ExchangeRates {

    public static final String[] MAIN_CURRENCIES = {
            "USD", "EUR", "RUB", "GBP", "KZT", "TRY", "UAH", "PLN",
            "AED", "CNY", "JPY", "BYN", "ILS", "CZK", "INR",
            "TON", "BTC", "ETH", "SOL"
    };

    private static final Gson GSON = new Gson();
    private static final Object sync = new Object();
    private static final ArrayList<Utilities.Callback<State>> pendingCallbacks = new ArrayList<>();

    private static volatile State cacheValue;
    private static volatile long cacheTimestamp;
    private static volatile boolean requestInFlight;

    static {
        NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> {
            if (id == NotificationCenter.pillStackSettingsChanged
                    && PillStackConfig.shouldUpdatePill(args,
                        PillStackConfig.PillType.TON.id,
                        PillStackConfig.PillType.BTC.id,
                        PillStackConfig.PillType.USD.id)) {
                clearCache();
            }
        }, NotificationCenter.pillStackSettingsChanged);
    }

    public static final class State {
        private final Map<String, BigDecimal> usdRates;

        public State(Map<String, BigDecimal> usdRates) {
            this.usdRates = usdRates;
        }

        public Map<String, BigDecimal> usdRates() {
            return usdRates;
        }

        public BigDecimal getUsdRate(String currency) {
            if (currency == null || usdRates == null) return null;
            Object value = usdRates.get(normalize(currency));
            if (value == null) return null;
            if (value instanceof BigDecimal) return (BigDecimal) value;
            if (value instanceof Double) return BigDecimal.valueOf((Double) value);
            if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
            try { return new BigDecimal(value.toString()); } catch (Exception e) { return null; }
        }

        public BigDecimal getRate(String from, String to) {
            BigDecimal fromUsd = getUsdRate(from);
            BigDecimal toUsd = getUsdRate(to);
            if (fromUsd == null || toUsd == null || toUsd.signum() == 0) {
                return null;
            }
            return fromUsd.divide(toUsd, 12, RoundingMode.HALF_UP);
        }
    }

    private static class CoinbaseResponse {
        @SerializedName("data")
        Data data;
    }

    private static class Data {
        @SerializedName("currency")
        String currency;
        @SerializedName("rates")
        Map<String, String> rates;
    }

    public static State getCached() {
        if (cacheValue == null) {
            try {
                String json = PillStackConfig.preferences.getString("exchangeRatesCache", null);
                if (json != null) {
                    cacheValue = GSON.fromJson(json, State.class);
                    cacheTimestamp = PillStackConfig.preferences.getLong("exchangeRatesTimestamp", 0L);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return cacheValue;
    }

    private static void saveCache(State state) {
        if (state == null) return;
        try {
            PillStackConfig.editor.putString("exchangeRatesCache", GSON.toJson(state)).apply();
            PillStackConfig.editor.putLong("exchangeRatesTimestamp", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void clearCache() {
        cacheTimestamp = 0L;
    }

    public static boolean isSupportedCurrency(String currency) {
        if (currency == null) return false;
        String normalized = normalize(currency);
        for (String c : MAIN_CURRENCIES) {
            if (c.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static String resolveTargetCurrency(int account, String target) {
        String normalized = normalize(target);
        if (!"AUTO".equals(normalized)) {
            if (TextUtils.isEmpty(normalized) || !isSupportedCurrency(normalized)) {
                return "USD";
            }
            return normalized;
        }
        try {
            String phone = org.telegram.messenger.UserConfig.getInstance(account).getClientPhone();
            String currency = getCurrencyByPhone(phone);
            if (!TextUtils.isEmpty(currency) && isSupportedCurrency(currency)) {
                return normalize(currency);
            }
        } catch (Exception ignored) {
        }
        try {
            String country = Locale.getDefault().getCountry();
            if (!TextUtils.isEmpty(country)) {
                java.util.Currency c = java.util.Currency.getInstance(new Locale("", country));
                if (c != null && isSupportedCurrency(c.getCurrencyCode())) {
                    return normalize(c.getCurrencyCode());
                }
            }
        } catch (Exception ignored) {
        }
        return "USD";
    }

    private static String getCurrencyByPhone(String phone) {
        if (TextUtils.isEmpty(phone)) return null;
        String stripped = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(phone);
        if (TextUtils.isEmpty(stripped)) return null;
        org.telegram.PhoneFormat.CallingCodeInfo info = org.telegram.PhoneFormat.PhoneFormat.getInstance().findCallingCodeInfo(stripped);
        if (info == null || info.countries == null || info.countries.isEmpty()) return null;
        String country = info.countries.get(0);
        String localeCountry = Locale.getDefault().getCountry();
        if (!TextUtils.isEmpty(localeCountry)) {
            for (String c : info.countries) {
                if (localeCountry.equalsIgnoreCase(c)) {
                    country = c;
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(country) || country.charAt(0) == '_') return null;
        try {
            java.util.Currency currency = java.util.Currency.getInstance(new Locale("", country.toUpperCase(Locale.ROOT)));
            if (currency != null) {
                return currency.getCurrencyCode();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static void fetch(Utilities.Callback<State> callback) {
        if (callback == null) return;
        State state = cacheValue;
        if (state != null && !isStale()) {
            AndroidUtilities.runOnUIThread(() -> callback.run(state));
            return;
        }
        boolean dispatch;
        synchronized (sync) {
            pendingCallbacks.add(callback);
            dispatch = !requestInFlight;
            if (dispatch) {
                requestInFlight = true;
            }
        }
        if (!dispatch) return;
        Request request = new Request.Builder()
                .url("https://api.coinbase.com/v2/exchange-rates?currency=USD")
                .build();
        ExteraHttpClient.INSTANCE.getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                FileLog.e(e);
                complete(getCached());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    response.close();
                    onFailure(call, new IOException("Unexpected code " + response));
                    return;
                }
                try (ResponseBody body = response.body()) {
                    State parsed = null;
                    if (body != null) {
                        CoinbaseResponse cb = GSON.fromJson(body.charStream(), CoinbaseResponse.class);
                        parsed = parseState(cb);
                    }
                    if (parsed != null) {
                        cacheValue = parsed;
                        cacheTimestamp = System.currentTimeMillis();
                        saveCache(parsed);
                    } else {
                        parsed = getCached();
                    }
                    complete(parsed);
                } catch (Exception e) {
                    FileLog.e(e);
                    complete(getCached());
                }
            }
        });
    }

    private static boolean isStale() {
        return getCached() == null || cacheTimestamp == 0L
                || System.currentTimeMillis() - cacheTimestamp >= 300_000L;
    }

    private static void complete(State state) {
        final ArrayList<Utilities.Callback<State>> callbacks;
        synchronized (sync) {
            requestInFlight = false;
            callbacks = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Utilities.Callback<State> cb : callbacks) {
                cb.run(state);
            }
        });
    }

    private static State parseState(CoinbaseResponse response) {
        if (response == null || response.data == null || response.data.rates == null) {
            return null;
        }
        HashMap<String, BigDecimal> map = new HashMap<>();
        for (String currency : MAIN_CURRENCIES) {
            BigDecimal usd = parseUsdRate(currency, response.data.rates);
            if (usd != null) {
                map.put(currency, usd);
            }
        }
        if (map.isEmpty()) {
            return null;
        }
        return new State(map);
    }

    private static BigDecimal parseUsdRate(String currency, Map<String, String> rates) {
        if ("USD".equals(currency)) {
            return BigDecimal.ONE;
        }
        String raw = rates.get(currency);
        if (raw == null) return null;
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() == 0) {
                return null;
            }
            return BigDecimal.ONE.divide(value, 16, RoundingMode.HALF_UP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    static String normalize(String currency) {
        if (currency == null) return "";
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
