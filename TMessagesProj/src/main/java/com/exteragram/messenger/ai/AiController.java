package com.exteragram.messenger.ai;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import tw.nekomimi.nekogram.llm.LlmConfig;
import xyz.nextalone.nagram.NaConfig;

public class AiController {

    private final List<Role> roles = new ArrayList<>();
    private final List<Service> services = new ArrayList<>();

    private static class SingletonHolder {
        private static final AiController INSTANCE = new AiController();
    }

    private AiController() {
        loadRoles();
        loadServices();
    }

    public static AiController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public static boolean canUseAI() {
        if (AiConfig.useLlmProvider) {
            return LlmConfig.isLLMTranslatorAvailable();
        }
        return !TextUtils.isEmpty(getInstance().getSelected().getKey());
    }


    public void loadRoles() {
        ArrayList<Role> loaded = AiConfig.getRoles();
        roles.clear();
        roles.addAll(loaded);
        roles.removeIf(r -> r == null || r.getName() == null || r.getPrompt() == null);
    }

    public List<Role> getRoles() {
        return Collections.unmodifiableList(roles);
    }

    public List<Role> getSuggestedRoles() {
        List<Role> suggested = new ArrayList<>();
        for (Suggestions s : Suggestions.values()) {
            suggested.add(s.getRole());
        }
        return suggested;
    }

    public boolean isCustomRole(Role role) {
        return role != null && roles.contains(role);
    }

    public boolean isSuggestedRole(Role role) {
        return role != null && getSuggestedRoles().contains(role);
    }

    public Role getSelectedRole() {
        for (Role role : roles) {
            if (role.isSelected()) return role;
        }
        for (Role role : getSuggestedRoles()) {
            if (role.isSelected()) return role;
        }
        return Suggestions.ASSISTANT.getRole();
    }

    public boolean addRole(Role role) {
        if (isSuggestedRole(role) || isCustomRole(role)) return false;
        boolean added = roles.add(role);
        if (added) saveRoles();
        return added;
    }

    public boolean removeRole(Role role) {
        if (role == null) return false;
        boolean removed = roles.remove(role);
        if (removed) saveRoles();
        return removed;
    }

    public boolean updateRole(Role oldRole, Role newRole) {
        int idx = roles.indexOf(oldRole);
        if (idx == -1) return false;
        if (isSuggestedRole(newRole) && !oldRole.equals(newRole)) return false;
        roles.set(idx, newRole);
        saveRoles();
        return true;
    }

    public void saveRoles() {
        try {
            Collections.sort(roles);
        } catch (Exception e) {
            FileLog.e(e);
        }
        AiConfig.saveRoles(new ArrayList<>(roles));
    }


    public void loadServices() {
        ArrayList<Service> loaded = AiConfig.getServices();
        services.clear();
        services.addAll(loaded);
    }

    public List<Service> getAll() {
        return Collections.unmodifiableList(services);
    }

    public boolean isServicesEmpty() {
        return services.isEmpty();
    }

    public boolean contains(Service service) {
        return services.contains(service);
    }

    public void addService(Service service) {
        if (services.contains(service)) return;
        services.add(service);
        saveServices();
    }

    public void updateService(Service oldService, Service newService) {
        int idx = services.indexOf(oldService);
        if (idx != -1) {
            services.set(idx, newService);
            saveServices();
        }
    }

    public boolean removeService(Service service) {
        boolean removed = services.remove(service);
        if (removed) saveServices();
        return removed;
    }

    public Service getSelected() {
        if (AiConfig.useLlmProvider) {
            Service llmService = getLlmProviderService();
            if (llmService != null) return llmService;
        }
        for (Service s : services) {
            if (s.isSelected()) return s;
        }
        return services.isEmpty() ? AiConfig.DEFAULT_SERVICE : services.get(0);
    }

    private static Service getLlmProviderService() {
        if (!LlmConfig.isLLMTranslatorAvailable()) return null;
        int preset = NaConfig.INSTANCE.getLlmProviderPreset().Int();
        String url = LlmConfig.getEffectiveBaseUrl(preset);
        String model = LlmConfig.getEffectiveModelName(preset);
        String key = LlmConfig.getFirstApiKey(preset);
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(model) || TextUtils.isEmpty(key)) return null;
        return new Service(url, model, key, Service.PROTOCOL_OPENAI, false);
    }

    public void saveServices() {
        services.sort(Comparator.comparing(Service::getModel, Comparator.nullsLast(Comparator.naturalOrder())));
        AiConfig.saveServices(new ArrayList<>(services));
    }


    public static boolean canSendImage(MessageObject messageObject) {
        if (messageObject == null) return false;
        String path = getPathToMessage(messageObject);
        return canSendImage(path);
    }

    public static boolean canSendImage(String path) {
        if (path == null) return false;
        File file = new File(path);
        if (!file.exists() || !file.isFile()) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    public static String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null) return null;
        if (messageObject.messageOwner != null && messageObject.messageOwner.attachPath != null) {
            File f = new File(messageObject.messageOwner.attachPath);
            if (f.exists()) return f.getAbsolutePath();
        }
        File file = org.telegram.messenger.FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        if (file != null && file.exists()) return file.getAbsolutePath();
        return null;
    }


    public static void clearHistory(final BaseFragment fragment, Theme.ResourcesProvider resourcesProvider, boolean showConfirm) {
        if (fragment == null) return;
        if (showConfirm) {
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
            builder.setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.ClearConversationHistoryInfo)));
            builder.setTitle(LocaleController.getString(R.string.ClearHistory));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.setPositiveButton(LocaleController.getString(R.string.ClearButton), (dialog, which) -> {
                AiConfig.clearConversationHistory();
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.AIChatHistoryCleared)).show();
            });
            AlertDialog alertDialog = builder.create();
            fragment.showDialog(alertDialog);
            TextView btn = (TextView) alertDialog.getButton(-1);
            if (btn != null) {
                btn.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
        } else {
            AiConfig.clearConversationHistory();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.AIChatHistoryCleared)).show();
        }
    }


    public static void showErrorBulletin(ViewGroup container, Theme.ResourcesProvider resourcesProvider, int code) {
        showErrorBulletin(null, container, resourcesProvider, code);
    }

    public static void showErrorBulletin(BaseFragment fragment, int code) {
        showErrorBulletin(fragment, null, null, code);
    }

    private static void showErrorBulletin(BaseFragment fragment, ViewGroup container, Theme.ResourcesProvider resourcesProvider, int code) {
        int titleRes, infoRes;

        switch (code) {
            case 400:
                titleRes = R.string.AIError400;
                infoRes = R.string.AIError400Info;
                break;
            case 401:
                titleRes = R.string.AIError401;
                infoRes = R.string.AIError401Info;
                break;
            case 402:
                titleRes = R.string.AIError402;
                infoRes = R.string.AIError402Info;
                break;
            case 403:
                titleRes = R.string.AIError403;
                infoRes = R.string.AIError403Info;
                break;
            case 408:
                titleRes = R.string.AIError408;
                infoRes = R.string.AIError408Info;
                break;
            case 429:
                titleRes = R.string.AIError429;
                infoRes = R.string.AIError429Info;
                break;
            case 502:
                titleRes = R.string.AIError502;
                infoRes = R.string.AIError502Info;
                break;
            case 503:
                titleRes = R.string.AIError503;
                infoRes = R.string.AIError503Info;
                break;
            default:
                titleRes = R.string.AIError;
                infoRes = R.string.AIErrorInfo;
                break;
        }

        final BulletinFactory factory;
        if (container != null) {
            factory = BulletinFactory.of((FrameLayout) container, resourcesProvider);
        } else if (fragment != null) {
            factory = BulletinFactory.of(fragment);
        } else {
            factory = BulletinFactory.global();
        }

        final int t = titleRes, i = infoRes;
        AndroidUtilities.runOnUIThread(() -> factory.createSimpleBulletin(
                LocaleController.getString(t),
                LocaleController.getString(i)).show());
    }
}
