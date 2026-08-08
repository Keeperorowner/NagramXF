package tw.nekomimi.nekogram.helpers.remote;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class TrustedPluginsRemoteHelper extends BaseRemoteHelper {
    private static final String TAG = "trusted_plugins";

    private final Object sync = new Object();
    private volatile Set<Long> cachedTrustedPlugins;
    private volatile boolean listenerRegistered;

    public static TrustedPluginsRemoteHelper getInstance() {
        return InstanceHolder.instance;
    }

    @Override
    protected void onError(String text, Delegate delegate) {
        FileLog.e("Failed to load trusted plugins metadata: " + text);
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        JSONObject merged = mergeResponses(responses);
        ArrayList<JSONObject> list = new ArrayList<>();
        if (merged != null) {
            list.add(merged);
        }
        super.onLoadSuccess(list, delegate);
        cachedTrustedPlugins = merged != null ? Collections.unmodifiableSet(parseTrustedPlugins(merged)) : null;
    }

    public void preload() {
        ensureListener();
        load();
    }

    public boolean isTrusted(long dialogId, Set<Long> defaults) {
        ensureListener();
        Set<Long> trusted = cachedTrustedPlugins;
        if (trusted == null) {
            JSONObject json = getJSON();
            if (json != null) {
                trusted = Collections.unmodifiableSet(parseTrustedPlugins(json));
                cachedTrustedPlugins = trusted;
            }
        }
        if (trusted != null && trusted.contains(dialogId)) {
            return true;
        }
        return defaults != null && defaults.contains(dialogId);
    }

    private void ensureListener() {
        if (listenerRegistered) {
            return;
        }
        synchronized (sync) {
            if (listenerRegistered) {
                return;
            }
            preferences.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                if (TextUtils.equals(getTag(), key)) {
                    cachedTrustedPlugins = null;
                }
            });
            listenerRegistered = true;
        }
    }

    private JSONObject mergeResponses(ArrayList<JSONObject> responses) {
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        HashSet<Long> mergedIds = new HashSet<>();
        for (JSONObject response : responses) {
            mergedIds.addAll(parseTrustedPlugins(response));
        }
        JSONArray array = new JSONArray();
        for (Long id : mergedIds) {
            array.put(id);
        }
        JSONObject result = new JSONObject();
        try {
            result.put(TAG, array);
        } catch (JSONException e) {
            FileLog.e(e);
            return null;
        }
        return result;
    }

    private HashSet<Long> parseTrustedPlugins(JSONObject json) {
        HashSet<Long> result = new HashSet<>();
        if (json == null) {
            return result;
        }
        JSONArray array = json.optJSONArray(TAG);
        if (array == null) {
            array = json.optJSONArray("ids");
        }
        if (array != null) {
            parseArray(array, result);
            return result;
        }
        Object value = json.opt(TAG);
        if (value instanceof JSONArray) {
            parseArray((JSONArray) value, result);
        } else if (value instanceof String) {
            parseStringList((String) value, result);
        }
        return result;
    }

    private void parseArray(JSONArray array, Set<Long> result) {
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            Long parsed = parseId(value);
            if (parsed != null) {
                result.add(parsed);
            }
        }
    }

    private void parseStringList(String raw, Set<Long> result) {
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        String normalized = raw.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        for (String part : normalized.split(",")) {
            Long parsed = parseId(part);
            if (parsed != null) {
                result.add(parsed);
            }
        }
    }

    private Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            String string = String.valueOf(value).trim();
            if (TextUtils.isEmpty(string)) {
                return null;
            }
            return Long.parseLong(string);
        } catch (Exception e) {
            FileLog.e("Failed to parse trusted plugin source id: " + value, e);
            return null;
        }
    }

    private static final class InstanceHolder {
        private static final TrustedPluginsRemoteHelper instance = new TrustedPluginsRemoteHelper();
    }
}
