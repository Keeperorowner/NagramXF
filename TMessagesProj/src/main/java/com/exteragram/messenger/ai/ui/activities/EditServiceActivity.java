package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;
import com.exteragram.messenger.ai.network.Client;
import com.exteragram.messenger.ai.network.GenerationCallback;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EditServiceActivity extends BaseFragment {

    private static Service currentService;
    private static int presetIndex = ProviderPresets.PRESET_CUSTOM;

    private EditTextBoldCursor urlField;
    private EditTextBoldCursor modelField;
    private EditTextBoldCursor keyField;
    private OutlineTextContainerView urlFieldContainer;
    private OutlineTextContainerView modelFieldContainer;
    private OutlineTextContainerView keyFieldContainer;
    private View doneButton;
    private ButtonWithCounterView testButton;
    private ButtonWithCounterView fetchModelsButton;

    public EditServiceActivity(int preset) {
        presetIndex = preset;
        currentService = null;
    }

    public EditServiceActivity(Service service) {
        currentService = service;
        presetIndex = detectPreset(service);
    }

    public EditServiceActivity() {
        presetIndex = ProviderPresets.PRESET_CUSTOM;
        currentService = null;
    }

    private static int detectPreset(Service service) {
        String url = service.getUrl();
        if (url == null) return ProviderPresets.PRESET_CUSTOM;
        for (int i = 1; i < ProviderPresets.PRESET_URLS.length; i++) {
            if (url.equals(ProviderPresets.PRESET_URLS[i])) return i;
        }
        return ProviderPresets.PRESET_CUSTOM;
    }

    @Override
    public View createView(Context context) {
        boolean isCustom = presetIndex == ProviderPresets.PRESET_CUSTOM;
        String presetName = ProviderPresets.PRESET_NAMES[presetIndex];

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(currentService != null
                ? LocaleController.getString(R.string.AIChatEditService)
                : presetName.equals("Custom")
                        ? LocaleController.getString(R.string.AIChatNewService)
                        : presetName);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    saveConfig();
                }
            }
        });

        doneButton = actionBar.createMenu().addItemWithWidth(1, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneButton.setContentDescription(LocaleController.getString(R.string.Done));

        ScrollView scrollView = new ScrollView(context);
        fragmentView = scrollView;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        if (isCustom) {
            urlFieldContainer = new OutlineTextContainerView(context);
            urlFieldContainer.setText(LocaleController.getString(R.string.AIChatServiceURL));
            urlField = createEditText(context);
            urlField.setHint("https://api.example.com/v1");
            urlField.addTextChangedListener(createWatcher());
            urlField.setOnFocusChangeListener((v, hasFocus) -> urlFieldContainer.animateSelection(hasFocus ? 1.0f : 0.0f));
            urlFieldContainer.addView(urlField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 0));
            urlFieldContainer.attachEditText(urlField);
            layout.addView(urlFieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 16, 24, 0));
        }

        modelFieldContainer = new OutlineTextContainerView(context);
        modelFieldContainer.setText(LocaleController.getString(R.string.AIChatServiceModel));
        modelField = createEditText(context);
        modelField.addTextChangedListener(createWatcher());
        modelField.setOnFocusChangeListener((v, hasFocus) -> modelFieldContainer.animateSelection(hasFocus ? 1.0f : 0.0f));
        modelFieldContainer.addView(modelField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 0));
        modelFieldContainer.attachEditText(modelField);
        layout.addView(modelFieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 16, 24, 0));

        fetchModelsButton = new ButtonWithCounterView(context, getResourceProvider());
        fetchModelsButton.setText(LocaleController.getString(R.string.AIChatFetchModels), false);
        fetchModelsButton.setOnClickListener(v -> fetchModels());
        layout.addView(fetchModelsButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 24, 12, 24, 0));

        keyFieldContainer = new OutlineTextContainerView(context);
        keyFieldContainer.setText(LocaleController.getString(R.string.AIChatServiceKey));
        keyField = createEditText(context);
        keyField.addTextChangedListener(createWatcher());
        keyField.setOnFocusChangeListener((v, hasFocus) -> keyFieldContainer.animateSelection(hasFocus ? 1.0f : 0.0f));
        keyFieldContainer.addView(keyField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 0));
        keyFieldContainer.attachEditText(keyField);
        layout.addView(keyFieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 16, 24, 0));

        testButton = new ButtonWithCounterView(context, getResourceProvider());
        testButton.setText(LocaleController.getString(R.string.AIChatTestConnection), false);
        testButton.setOnClickListener(v -> testConnection());
        layout.addView(testButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 24, 24, 24, 0));

        if (currentService != null) {
            if (urlField != null) urlField.setText(currentService.getUrl());
            modelField.setText(currentService.getModel());
            keyField.setText(currentService.getKey() != null ? currentService.getKey() : "");
        } else {
            if (urlField != null) {
                urlField.setText(isCustom ? AiConfig.DEFAULT_SERVICE.getUrl() : ProviderPresets.PRESET_URLS[presetIndex]);
            }
            modelField.setText(isCustom ? AiConfig.DEFAULT_SERVICE.getModel() : ProviderPresets.PRESET_DEFAULT_MODELS[presetIndex]);
            keyField.setText("");
        }
        updateDoneButton();

        return fragmentView;
    }

    private String getEffectiveUrl() {
        if (presetIndex == ProviderPresets.PRESET_CUSTOM) {
            return urlField != null ? urlField.getText().toString().trim() : "";
        }
        return ProviderPresets.PRESET_URLS[presetIndex];
    }

    private EditTextBoldCursor createEditText(Context context) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(1, 16);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setBackground(null);
        editText.setMaxLines(1);
        editText.setSingleLine(true);
        editText.setInputType(1);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        editText.setCursorWidth(1.5f);
        editText.setGravity(LocaleController.isRTL ? 5 : 3);
        int dp = AndroidUtilities.dp(16);
        editText.setPadding(0, dp, 0, dp);
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setMinHeight(AndroidUtilities.dp(36));
        return editText;
    }

    private TextWatcher createWatcher() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateDoneButton();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        };
    }

    private void updateDoneButton() {
        if (doneButton != null) {
            boolean hasUrl = presetIndex != ProviderPresets.PRESET_CUSTOM || (urlField != null && !TextUtils.isEmpty(urlField.getText()));
            boolean canSave = hasUrl && !TextUtils.isEmpty(modelField.getText()) && !TextUtils.isEmpty(keyField.getText());
            doneButton.setEnabled(canSave);
            doneButton.animate().alpha(canSave ? 1.0f : 0.5f).setDuration(150).start();
        }
    }

    private void fetchModels() {
        String url = getEffectiveUrl();
        String key = keyField.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            if (TextUtils.isEmpty(key)) AndroidUtilities.shakeView(keyFieldContainer);
            if (urlFieldContainer != null && TextUtils.isEmpty(url)) AndroidUtilities.shakeView(urlFieldContainer);
            return;
        }

        fetchModelsButton.setLoading(true);
        String modelsEndpoint = url.endsWith("/") ? url + "models" : url + "/models";

        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build();

                Request request = new Request.Builder()
                        .url(modelsEndpoint)
                        .addHeader("Authorization", "Bearer " + key)
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        JSONArray data = json.optJSONArray("data");
                        if (data == null) data = json.optJSONArray("models");

                        if (data != null && data.length() > 0) {
                            ArrayList<String> models = new ArrayList<>();
                            for (int i = 0; i < data.length(); i++) {
                                JSONObject model = data.getJSONObject(i);
                                String id = model.optString("id", model.optString("name", ""));
                                if (!TextUtils.isEmpty(id)) {
                                    models.add(id);
                                }
                            }
                            models.sort(String::compareToIgnoreCase);
                            AndroidUtilities.runOnUIThread(() -> {
                                fetchModelsButton.setLoading(false);
                                showModelPicker(models);
                            });
                        } else {
                            AndroidUtilities.runOnUIThread(() -> {
                                fetchModelsButton.setLoading(false);
                                BulletinFactory.of(EditServiceActivity.this).createErrorBulletin("No models found").show();
                            });
                        }
                    } else {
                        final int code = response.code();
                        AndroidUtilities.runOnUIThread(() -> {
                            fetchModelsButton.setLoading(false);
                            BulletinFactory.of(EditServiceActivity.this).createErrorBulletin("Error: " + code).show();
                        });
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    fetchModelsButton.setLoading(false);
                    BulletinFactory.of(EditServiceActivity.this).createErrorBulletin(e.getMessage() != null ? e.getMessage() : "Network error").show();
                });
            }
        }).start();
    }

    private void showModelPicker(ArrayList<String> models) {
        if (models.isEmpty()) return;
        String[] items = models.toArray(new String[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.AIChatServiceModel));
        builder.setItems(items, (dialog, which) -> modelField.setText(items[which]));
        builder.show();
    }

    private void testConnection() {
        String url = getEffectiveUrl();
        String key = keyField.getText().toString().trim();
        String model = modelField.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key) || TextUtils.isEmpty(model)) {
            if (TextUtils.isEmpty(key)) AndroidUtilities.shakeView(keyFieldContainer);
            if (TextUtils.isEmpty(model)) AndroidUtilities.shakeView(modelFieldContainer);
            if (urlFieldContainer != null && TextUtils.isEmpty(url)) AndroidUtilities.shakeView(urlFieldContainer);
            return;
        }

        testButton.setLoading(true);
        Service service = new Service(url, model, key, Service.PROTOCOL_OPENAI);
        Client client = new Client.Builder()
                .serviceOverride(service)
                .roleOverride(Suggestions.ASSISTANT.getRole())
                .build();

        client.getResponse("Say 'hi'.", new GenerationCallback() {
            @Override
            public void onResponse(String response) {
                testButton.setLoading(false);
                if (!TextUtils.isEmpty(response)) {
                    BulletinFactory.of(EditServiceActivity.this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.AIChatTestSuccess)).show();
                }
            }

            @Override
            public void onChunk(String chunk) {}

            @Override
            public void onError(int code, String message) {
                testButton.setLoading(false);
                AiController.showErrorBulletin(EditServiceActivity.this, code);
            }
        });
    }

    private void saveConfig() {
        String url = getEffectiveUrl();
        Service service = new Service(url, modelField.getText().toString().trim(), keyField.getText().toString().trim(), Service.PROTOCOL_OPENAI);

        if (!AiController.getInstance().contains(service)) {
            Client client = new Client.Builder()
                    .serviceOverride(service)
                    .roleOverride(Suggestions.ASSISTANT.getRole())
                    .build();

            AlertDialog progressDialog = new AlertDialog(getParentActivity(), 3);
            String requestId = client.getResponse("Say 'hi'.", new GenerationCallback() {
                @Override
                public void onResponse(String response) {
                    dismissProgress(progressDialog);
                    if (TextUtils.isEmpty(response)) return;
                    if (currentService != null) {
                        AiController.getInstance().updateService(currentService, service);
                    } else {
                        AiController.getInstance().addService(service);
                    }
                    AiConfig.setSelectedServices(service);
                    getNotificationCenter().postNotificationName(NotificationCenter.servicesUpdated);
                    finishFragment();
                }

                @Override
                public void onChunk(String chunk) {}

                @Override
                public void onError(int code, String message) {
                    dismissProgress(progressDialog);
                    AiController.showErrorBulletin(EditServiceActivity.this, code);
                    if (urlFieldContainer != null) AndroidUtilities.shakeView(urlFieldContainer);
                    AndroidUtilities.shakeView(modelFieldContainer);
                    AndroidUtilities.shakeView(keyFieldContainer);
                }
            });

            progressDialog.setOnCancelListener(dialog -> client.stopRequest(requestId));
            progressDialog.show();
        } else {
            finishFragment();
        }
    }

    private void dismissProgress(AlertDialog dialog) {
        if (dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    @Override
    public void onFragmentDestroy() {
        currentService = null;
        super.onFragmentDestroy();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen) {
            keyField.requestFocus();
            AndroidUtilities.showKeyboard(keyField);
        }
    }
}
