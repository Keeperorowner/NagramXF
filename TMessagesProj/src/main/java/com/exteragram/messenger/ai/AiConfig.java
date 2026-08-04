package com.exteragram.messenger.ai;

import android.content.SharedPreferences;

import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.lang.reflect.Type;
import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

public abstract class AiConfig {

    public static final Service DEFAULT_SERVICE = new Service(
            "https://chenhai.shop/v1",
            "kimi-k2.6",
            null
    );

    private static final Gson GSON = new Gson();
    private static final Object sync = new Object();

    public static SharedPreferences preferences;
    public static SharedPreferences.Editor editor;

    public static final ConfigItem saveHistoryConfig = new ConfigItem("aiChat_saveHistory", ConfigItem.configTypeBool, true);
    public static final ConfigItem useLlmProviderConfig = new ConfigItem("aiChat_useLlmProvider", ConfigItem.configTypeBool, false);
    public static final ConfigItem responseStreamingConfig = new ConfigItem("aiChat_responseStreaming", ConfigItem.configTypeBool, true);
    public static final ConfigItem showResponseOnlyConfig = new ConfigItem("aiChat_showResponseOnly", ConfigItem.configTypeBool, false);
    public static final ConfigItem insertAsQuoteConfig = new ConfigItem("aiChat_insertAsQuote", ConfigItem.configTypeBool, true);
    public static final ConfigItem temperatureConfig = new ConfigItem("aiChat_temperature", ConfigItem.configTypeInt, 10);

    public static boolean saveHistory;
    public static boolean useLlmProvider;
    public static boolean responseStreaming;
    public static boolean showResponseOnly;
    public static boolean insertAsQuote;
    public static int temperature;

    private static boolean configLoaded;

    static {
        loadConfig();
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) return;
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("aichatconfig", 0);
            preferences = prefs;
            editor = prefs.edit();

            SharedPreferences nekoPrefs = NekoConfig.getPreferences();
            saveHistoryConfig.value = nekoPrefs.getBoolean(saveHistoryConfig.key, (boolean) saveHistoryConfig.defaultValue);
            useLlmProviderConfig.value = nekoPrefs.getBoolean(useLlmProviderConfig.key, (boolean) useLlmProviderConfig.defaultValue);
            responseStreamingConfig.value = nekoPrefs.getBoolean(responseStreamingConfig.key, (boolean) responseStreamingConfig.defaultValue);
            showResponseOnlyConfig.value = nekoPrefs.getBoolean(showResponseOnlyConfig.key, (boolean) showResponseOnlyConfig.defaultValue);
            insertAsQuoteConfig.value = nekoPrefs.getBoolean(insertAsQuoteConfig.key, (boolean) insertAsQuoteConfig.defaultValue);
            temperatureConfig.value = nekoPrefs.getInt(temperatureConfig.key, (int) temperatureConfig.defaultValue);

            saveHistory = saveHistoryConfig.Bool();
            useLlmProvider = useLlmProviderConfig.Bool();
            responseStreaming = responseStreamingConfig.Bool();
            showResponseOnly = showResponseOnlyConfig.Bool();
            insertAsQuote = insertAsQuoteConfig.Bool();
            temperature = temperatureConfig.Int();

            configLoaded = true;
        }
    }

    public static void syncFields() {
        saveHistory = saveHistoryConfig.Bool();
        useLlmProvider = useLlmProviderConfig.Bool();
        responseStreaming = responseStreamingConfig.Bool();
        showResponseOnly = showResponseOnlyConfig.Bool();
        insertAsQuote = insertAsQuoteConfig.Bool();
        temperature = temperatureConfig.Int();
    }

    public static void saveConversationHistory(ArrayList<Message> history) {
        editor.putString("conversationHistory", GSON.toJson(history)).commit();
    }

    public static ArrayList<Message> getConversationHistory() {
        Type type = new TypeToken<ArrayList<Message>>() {}.getType();
        String json = preferences.getString("conversationHistory", null);
        if (json == null) return new ArrayList<>();
        try {
            ArrayList<Message> list = GSON.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    public static void clearConversationHistory() {
        editor.remove("conversationHistory").commit();
    }

    public static void removeLastFromHistory() {
        ArrayList<Message> history = getConversationHistory();
        if (history.size() >= 2
                && "assistant".equals(history.get(history.size() - 1).role())
                && "user".equals(history.get(history.size() - 2).role())) {
            history.remove(history.size() - 1);
            history.remove(history.size() - 1);
            saveConversationHistory(history);
        }
    }

    public static void saveRoles(ArrayList<Role> roles) {
        editor.putString("roles", GSON.toJson(roles)).commit();
    }

    public static ArrayList<Role> getRoles() {
        Type type = new TypeToken<ArrayList<Role>>() {}.getType();
        String json = preferences.getString("roles", null);
        if (json == null) return new ArrayList<>();
        try {
            ArrayList<Role> list = GSON.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    public static String getSelectedRole() {
        return preferences.getString("selectedRole", Suggestions.values()[0].getRole().getName());
    }

    public static void setSelectedRole(Role role) {
        editor.putString("selectedRole", role.getName()).commit();
        clearConversationHistory();
    }

    public static int getSelectedService() {
        return preferences.getInt("selectedService", DEFAULT_SERVICE.hashCode());
    }

    public static void setSelectedServices(Service service) {
        editor.putInt("selectedService", service.hashCode()).commit();
        clearConversationHistory();
    }

    public static void clearSelectedService() {
        editor.remove("selectedService").commit();
        clearConversationHistory();
    }

    public static ArrayList<Service> getServices() {
        Type type = new TypeToken<ArrayList<Service>>() {}.getType();
        String json = preferences.getString("services", null);
        if (json == null) return new ArrayList<>();
        try {
            ArrayList<Service> list = GSON.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    public static void saveServices(ArrayList<Service> services) {
        editor.putString("services", GSON.toJson(services)).commit();
    }
}
