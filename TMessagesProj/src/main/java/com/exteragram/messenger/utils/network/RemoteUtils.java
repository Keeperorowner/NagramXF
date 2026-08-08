package com.exteragram.messenger.utils.network;

import android.content.SharedPreferences;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPC.TL_error;
import org.telegram.tgnet.TLRPC.TL_inputPeerChannel;
import org.telegram.tgnet.TLRPC.TL_messages_getHistory;
import org.telegram.tgnet.TLRPC.TL_messages_search;
import org.telegram.tgnet.TLRPC.messages_Messages;

import com.exteragram.messenger.utils.AppUtils;
import com.exteragram.messenger.utils.chats.ChatUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public abstract class RemoteUtils {
    private static final long CONFIG_REFRESH_INTERVAL = 10 * 60 * 1000L;
    // Our own fork channel (the same one the app updates ship from; see
    // tw.nekomimi.nekogram.helpers.remote.BaseRemoteHelper.CHANNEL_METADATA_ID/NAME).
    private static final long SDK_CHANNEL_ID = -3499386246L;
    private static final String SDK_CHANNEL_USERNAME = "nagram_fork_remote_metadata";
    private static final Object messagesRequestLock = new Object();
    private static ArrayList<Utilities.Callback2<messages_Messages, TL_error>> pendingMessagesCallbacks;
    public static SharedPreferences sharedPreferences;

    private static SharedPreferences getPrefs() {
        if (sharedPreferences == null) {
            initCached();
        }
        return sharedPreferences;
    }

    public static void initCached() {
        if (sharedPreferences == null) {
            sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("exteraremoteconfig", 0);
        }
    }

    public static void init() {
        initCached();
        long now = System.currentTimeMillis();
        if (Math.abs(now - sharedPreferences.getLong("__last_fetch_attempt_time", 0L)) < CONFIG_REFRESH_INTERVAL) {
            return;
        }
        sharedPreferences.edit().putLong("__last_fetch_attempt_time", now).apply();
        loadConfig();
    }

    public static void forceRefresh() {
        initCached();
        sharedPreferences.edit().putLong("__last_fetch_attempt_time", System.currentTimeMillis()).apply();
        loadConfig();
    }

    private static void loadConfig() {
        getMessages((res, error) -> {
            if (error != null || res == null) {
                return;
            }
            HashSet<String> keys = new HashSet<>();
            for (TLRPC.Message message : res.messages) {
                if (message instanceof TLRPC.TL_message && message.message != null && message.message.startsWith("remote_config")) {
                    String[] lines = message.message.split("\n");
                    if (lines.length > 1) {
                        for (String line : lines) {
                            String[] parts = line.split("=", 2);
                            if (parts.length == 2) {
                                String key = parts[0].trim();
                                String value = parts[1].trim();
                                if (!value.equals("null")) {
                                    updateValue(key, value);
                                    keys.add(key);
                                }
                            }
                        }
                    }
                }
            }
            removeOldPreferences(keys);
        });
    }

    private static void removeOldPreferences(Set<String> keepKeys) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        for (String key : sharedPreferences.getAll().keySet()) {
            if (!keepKeys.contains(key) && !"__last_fetch_attempt_time".equals(key)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static void updateValue(String key, String value) {
        if (areValuesEqual(sharedPreferences.getAll().get(key), parseConfigValue(value))) {
            return;
        }
        saveToPreferences(key, value);
    }

    private static boolean areValuesEqual(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b);
    }

    private static void saveToPreferences(String key, String value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        saveConfigValueToPreferences(editor, key, parseConfigValue(value));
        editor.apply();
    }

    private static Object parseConfigValue(String value) {
        if (value.matches("-?\\d+")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return Float.parseFloat(value);
            }
        }
        if (value.matches("-?\\d+(\\.\\d+)")) {
            return Float.parseFloat(value);
        }
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            if (inner.isEmpty()) {
                return new HashSet<>();
            }
            return new HashSet<>(Arrays.asList(inner.split(",\\s*")));
        }
        return value;
    }

    private static void saveConfigValueToPreferences(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        }
    }

    public static void getMessages(Utilities.Callback2<messages_Messages, TL_error> callback) {
        synchronized (messagesRequestLock) {
            if (pendingMessagesCallbacks != null) {
                pendingMessagesCallbacks.add(callback);
                return;
            }
            ArrayList<Utilities.Callback2<messages_Messages, TL_error>> callbacks = new ArrayList<>();
            pendingMessagesCallbacks = callbacks;
            callbacks.add(callback);
            final AccountInstance accountInstance = AccountInstance.getInstance(UserConfig.selectedAccount);
            final TL_messages_getHistory request = new TL_messages_getHistory();
            request.peer = accountInstance.getMessagesController().getInputPeer(SDK_CHANNEL_ID);
            request.offset_id = 0;
            request.limit = 75;
            final Runnable send = () -> accountInstance.getConnectionsManager().sendRequest(request, (res, error) -> {
                if (error != null || res == null) {
                    deliverMessagesResult(null, error);
                } else {
                    deliverMessagesResult((messages_Messages) res, null);
                }
            });
            if (request.peer != null && request.peer.access_hash != 0) {
                AndroidUtilities.runOnUIThread(send);
            } else {
                ChatUtils.getInstance().resolveChannel(SDK_CHANNEL_USERNAME, chat -> {
                    if (chat != null && chat.id == -SDK_CHANNEL_ID) {
                        TL_inputPeerChannel peer = new TL_inputPeerChannel();
                        request.peer = peer;
                        peer.channel_id = chat.id;
                        peer.access_hash = chat.access_hash;
                        AndroidUtilities.runOnUIThread(send);
                        return;
                    }
                    TL_error error = new TL_error();
                    error.code = 400;
                    error.text = "CHANNEL_RESOLVE_FAILED";
                    deliverMessagesResult(null, error);
                });
            }
        }
    }

    private static void deliverMessagesResult(messages_Messages messages, TL_error error) {
        ArrayList<Utilities.Callback2<messages_Messages, TL_error>> callbacks;
        synchronized (messagesRequestLock) {
            callbacks = pendingMessagesCallbacks;
            pendingMessagesCallbacks = null;
        }
        if (callbacks == null) {
            return;
        }
        for (Utilities.Callback2<messages_Messages, TL_error> callback : callbacks) {
            callback.run(messages, error);
        }
    }

    public static void searchMessages(String query, TLRPC.MessagesFilter filter, Utilities.Callback2<messages_Messages, TL_error> callback, int timeout) {
        searchMessages(50, query, filter, callback, timeout);
    }

    public static void searchMessages(int limit, String query, TLRPC.MessagesFilter filter, final Utilities.Callback2<messages_Messages, TL_error> callback, final int timeout) {
        final AccountInstance accountInstance = AccountInstance.getInstance(UserConfig.selectedAccount);
        final TL_messages_search request = new TL_messages_search();
        request.peer = accountInstance.getMessagesController().getInputPeer(SDK_CHANNEL_ID);
        request.q = query;
        request.offset_id = 0;
        request.limit = limit;
        request.filter = filter;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicInteger attempts = new AtomicInteger(0);
        final AtomicInteger requestId = new AtomicInteger();
        final AtomicReference<Runnable> timeoutRunnableRef = new AtomicReference<>();
        final Runnable timeoutRunnable = () -> {
            if (finished.compareAndSet(false, true)) {
                TL_error error = new TL_error();
                error.code = 408;
                error.text = "REQUEST_TIMEOUT";
                callback.run(null, error);
            }
        };
        final Runnable sendRequest = () -> {
            if (finished.get()) {
                return;
            }
            attempts.incrementAndGet();
            requestId.set(accountInstance.getConnectionsManager().sendRequest(request, new RequestDelegate() {
                @Override
                public void run(TLObject response, TL_error error) {
                    if (finished.compareAndSet(false, true)) {
                        AndroidUtilities.cancelRunOnUIThread(timeoutRunnableRef.get());
                        if (error != null || response == null) {
                            callback.run(null, error);
                        } else {
                            callback.run((messages_Messages) response, null);
                        }
                    }
                }
            }));
        };
        final Runnable retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (finished.get()) {
                    return;
                }
                accountInstance.getConnectionsManager().cancelRequest(requestId.get(), false);
                if (attempts.get() < 3) {
                    AndroidUtilities.runOnUIThread(sendRequest);
                    AndroidUtilities.runOnUIThread(timeoutRunnableRef.get(), timeout);
                } else {
                    timeoutRunnable.run();
                }
            }
        };
        timeoutRunnableRef.set(retryRunnable);
        if (request.peer != null && request.peer.access_hash != 0) {
            AndroidUtilities.runOnUIThread(sendRequest);
            AndroidUtilities.runOnUIThread(timeoutRunnableRef.get(), timeout);
        } else {
            ChatUtils.getInstance().resolveChannel(SDK_CHANNEL_USERNAME, chat -> {
                if (chat != null && chat.id == -SDK_CHANNEL_ID) {
                    TL_inputPeerChannel peer = new TL_inputPeerChannel();
                    request.peer = peer;
                    peer.channel_id = chat.id;
                    peer.access_hash = chat.access_hash;
                    AndroidUtilities.runOnUIThread(sendRequest);
                    AndroidUtilities.runOnUIThread(timeoutRunnableRef.get(), timeout);
                    return;
                }
                if (finished.compareAndSet(false, true)) {
                    TL_error error = new TL_error();
                    error.code = 400;
                    error.text = "CHANNEL_RESOLVE_FAILED";
                    callback.run(null, error);
                }
            });
        }
    }

    public static Integer getIntConfigValue(String key, int defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) {
                return defaultValue;
            }
            Object value = prefs.getAll().get(key);
            if (value instanceof String) {
                return Integer.parseInt((String) value);
            }
            if (value instanceof Long) {
                return ((Long) value).intValue();
            }
            if (value instanceof Integer) {
                return (Integer) value;
            }
            return defaultValue;
        } catch (Exception e) {
            AppUtils.log("Error getting int config value for key: " + key, e);
            return defaultValue;
        }
    }

    public static Float getFloatConfigValue(String key, float defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) {
                return defaultValue;
            }
            Object value = prefs.getAll().get(key);
            if (value instanceof String) {
                return Float.parseFloat((String) value);
            }
            if (value instanceof Float) {
                return (Float) value;
            }
            if (value instanceof Long) {
                return ((Long) value).floatValue();
            }
            if (value instanceof Integer) {
                return ((Integer) value).floatValue();
            }
            return defaultValue;
        } catch (Exception e) {
            AppUtils.log("Error getting value for key: " + key, e);
            return defaultValue;
        }
    }

    public static Boolean getBooleanConfigValue(String key, boolean defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) {
                return defaultValue;
            }
            try {
                return prefs.getBoolean(key, defaultValue);
            } catch (ClassCastException e) {
                Object value = prefs.getAll().get(key);
                return value instanceof String ? Boolean.parseBoolean((String) value) : defaultValue;
            }
        } catch (Exception e) {
            AppUtils.log("Error getting value for key: " + key, e);
            return defaultValue;
        }
    }

    public static Set<String> getStringSetConfigValue(String key, Set<String> defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                Object value = prefs.getAll().get(key);
                if (value instanceof Set) {
                    return (Set) value;
                }
                if (value instanceof String) {
                    return new HashSet<>(Arrays.asList(((String) value).split(",\\s*")));
                }
            }
            return defaultValue;
        } catch (Exception e) {
            AppUtils.log("Error getting value for key: " + key, e);
            return defaultValue;
        }
    }

    public static String getStringConfigValue(String key, String defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                Object value = prefs.getAll().get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
            return defaultValue;
        } catch (Exception e) {
            AppUtils.log("Error getting value for key: " + key, e);
            return defaultValue;
        }
    }
}
