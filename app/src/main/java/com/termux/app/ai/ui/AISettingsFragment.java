package com.termux.app.ai.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.ai.AIManager;
import com.termux.app.ai.AISettingsManager;
import com.termux.app.ai.model.AIException;
import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.network.UrlConnectionHttpTransport;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Keep
public class AISettingsFragment extends PreferenceFragmentCompat {

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private AIManager mAiManager;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        mAiManager = AIManager.getInstance(context);
        getPreferenceManager().setPreferenceDataStore(new AISettingsDataStore(mAiManager.getSettings()));
        setPreferencesFromResource(R.xml.ai_preferences, rootKey);

        bindProviderSelector();
        bindBuiltInProvider();
        bindProviderConfig(AIProviderType.GEMINI,
            "ai_gemini_api_key", "ai_gemini_model", "ai_gemini_custom_model",
            "ai_gemini_test_connection", "ai_gemini_fetch_models");
        bindProviderConfig(AIProviderType.OPENROUTER,
            "ai_openrouter_api_key", "ai_openrouter_model", "ai_openrouter_custom_model",
            "ai_openrouter_test_connection", "ai_openrouter_fetch_models");
        bindGenerationSettings();
        bindPrivacyActions();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    private void bindProviderSelector() {
        ListPreference providerPref = findPreference(AISettingsManager.KEY_ACTIVE_PROVIDER);
        if (providerPref == null) return;
        providerPref.setEntries(new CharSequence[]{
            AIProviderType.BUILTIN.getDisplayName(),
            AIProviderType.GEMINI.getDisplayName(),
            AIProviderType.OPENROUTER.getDisplayName()
        });
        providerPref.setEntryValues(new CharSequence[]{
            AIProviderType.BUILTIN.getId(),
            AIProviderType.GEMINI.getId(),
            AIProviderType.OPENROUTER.getId()
        });
        providerPref.setValue(mAiManager.getSettings().getActiveProvider().getId());
    }

    private void bindBuiltInProvider() {
        ListPreference modelPref = findPreference(AISettingsManager.KEY_BUILTIN_MODEL);
        if (modelPref != null) {
            populateModelEntries(modelPref, AIProviderType.BUILTIN);
        }
        Preference testPref = findPreference("ai_builtin_test_connection");
        if (testPref != null) {
            testPref.setOnPreferenceClickListener(p -> {
                runTestConnection(AIProviderType.BUILTIN, testPref);
                return true;
            });
        }
    }

    private void bindProviderConfig(
        @NonNull AIProviderType provider,
        @NonNull String keyPrefKey,
        @NonNull String modelPrefKey,
        @NonNull String customModelPrefKey,
        @NonNull String testPrefKey,
        @NonNull String fetchPrefKey
    ) {
        Preference keyPref = findPreference(keyPrefKey);
        if (keyPref != null) {
            refreshKeySummary(keyPref, provider);
            keyPref.setOnPreferenceClickListener(p -> {
                showApiKeyDialog(provider, keyPref);
                return true;
            });
        }

        ListPreference modelPref = findPreference(modelPrefKey);
        if (modelPref != null) {
            populateModelEntries(modelPref, provider);
        }

        Preference customModelPref = findPreference(customModelPrefKey);
        if (customModelPref != null) {
            customModelPref.setSummary(mAiManager.getSettings().getModelForProvider(provider));
            customModelPref.setOnPreferenceClickListener(p -> {
                showCustomModelDialog(provider, customModelPref, modelPref);
                return true;
            });
        }

        Preference testPref = findPreference(testPrefKey);
        if (testPref != null) {
            testPref.setOnPreferenceClickListener(p -> {
                runTestConnection(provider, testPref);
                return true;
            });
        }

        Preference fetchPref = findPreference(fetchPrefKey);
        if (fetchPref != null) {
            fetchPref.setOnPreferenceClickListener(p -> {
                runFetchModels(provider, fetchPref, modelPref);
                return true;
            });
        }
    }

    private void bindGenerationSettings() {
        ListPreference tempPref = findPreference("ai_temperature_preset");
        if (tempPref != null) {
            CharSequence[] values = new CharSequence[]{"0.0", "0.2", "0.3", "0.5", "0.7", "1.0"};
            tempPref.setEntries(values);
            tempPref.setEntryValues(values);
            tempPref.setValue(String.valueOf(mAiManager.getSettings().getTemperature()));
        }

        ListPreference maxTokensPref = findPreference("ai_max_tokens_preset");
        if (maxTokensPref != null) {
            CharSequence[] labels = new CharSequence[]{"512 tokens", "1024 tokens", "2048 tokens", "4096 tokens", "8192 tokens"};
            CharSequence[] values = new CharSequence[]{"512", "1024", "2048", "4096", "8192"};
            maxTokensPref.setEntries(labels);
            maxTokensPref.setEntryValues(values);
            maxTokensPref.setValue(String.valueOf(mAiManager.getSettings().getMaxOutputTokens()));
        }

        Preference proxyPref = findPreference(AISettingsManager.KEY_CUSTOM_PROXY_URL);
        if (proxyPref != null) {
            refreshProxySummary(proxyPref);
            proxyPref.setOnPreferenceClickListener(p -> {
                showProxyDialog(proxyPref);
                return true;
            });
        }
    }

    private void bindPrivacyActions() {
        Preference clearPref = findPreference("ai_clear_history");
        if (clearPref != null) {
            clearPref.setOnPreferenceClickListener(p -> {
                Context context = getContext();
                if (context == null) return true;
                new AlertDialog.Builder(context)
                    .setTitle(R.string.ai_clear_chat)
                    .setMessage(R.string.ai_clear_chat_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        mAiManager.getConversationStore().clearHistory();
                        Toast.makeText(context, "AI chat history cleared", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            });
        }
    }

    private void refreshKeySummary(@NonNull Preference pref, @NonNull AIProviderType provider) {
        if (mAiManager.getCredentials().hasApiKey(provider)) {
            pref.setSummary("Configured (encrypted): " + mAiManager.getCredentials().getMaskedApiKey(provider));
        } else {
            pref.setSummary("Not configured");
        }
    }

    private void refreshProxySummary(@NonNull Preference pref) {
        String proxy = mAiManager.getSettings().getCustomProxyUrl();
        pref.setSummary(proxy == null ? "Direct connection (no proxy)" : proxy);
    }

    private void populateModelEntries(@NonNull ListPreference modelPref, @NonNull AIProviderType provider) {
        List<AIModelInfo> models = mAiManager.getModelManager().getModelsForProvider(provider);
        CharSequence[] entries = new CharSequence[models.size()];
        CharSequence[] values = new CharSequence[models.size()];
        for (int i = 0; i < models.size(); i++) {
            AIModelInfo info = models.get(i);
            entries[i] = info.toString();
            values[i] = info.getId();
        }
        modelPref.setEntries(entries);
        modelPref.setEntryValues(values);
        modelPref.setValue(mAiManager.getSettings().getModelForProvider(provider));
    }

    private void showApiKeyDialog(@NonNull AIProviderType provider, @NonNull Preference pref) {
        Context context = getContext();
        if (context == null) return;

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Paste your " + provider.getDisplayName() + " API key");

        String currentMask = mAiManager.getCredentials().hasApiKey(provider)
            ? "\n\nStored key: " + mAiManager.getCredentials().getMaskedApiKey(provider)
            : "";

        new AlertDialog.Builder(context)
            .setTitle(provider.getDisplayName() + " API Key")
            .setMessage("Keys are encrypted using Android Keystore and stored only on this device." + currentMask)
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) {
                    mAiManager.getCredentials().saveApiKey(provider, val);
                    refreshKeySummary(pref, provider);
                    Toast.makeText(context, provider.getDisplayName() + " key saved securely", Toast.LENGTH_SHORT).show();
                }
            })
            .setNeutralButton("Remove Key", (d, w) -> {
                mAiManager.getCredentials().removeApiKey(provider);
                refreshKeySummary(pref, provider);
                Toast.makeText(context, provider.getDisplayName() + " key removed", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showCustomModelDialog(
        @NonNull AIProviderType provider,
        @NonNull Preference customModelPref,
        ListPreference modelListPref
    ) {
        Context context = getContext();
        if (context == null) return;

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setText(mAiManager.getSettings().getModelForProvider(provider));

        new AlertDialog.Builder(context)
            .setTitle(provider.getDisplayName() + " Model ID")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String modelId = input.getText().toString().trim();
                if (!modelId.isEmpty()) {
                    mAiManager.getSettings().setModelForProvider(provider, modelId);
                    customModelPref.setSummary(modelId);
                    if (modelListPref != null) {
                        modelListPref.setValue(modelId);
                    }
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showProxyDialog(@NonNull Preference proxyPref) {
        Context context = getContext();
        if (context == null) return;

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://your-proxy.example.com/v1/...");
        String current = mAiManager.getSettings().getCustomProxyUrl();
        if (current != null) input.setText(current);

        new AlertDialog.Builder(context)
            .setTitle(R.string.ai_custom_proxy_title)
            .setMessage("Optional custom HTTPS proxy endpoint. Leave empty to call the official provider directly.")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String proxy = input.getText().toString().trim();
                if (!proxy.isEmpty() && !UrlConnectionHttpTransport.isValidEndpointUrl(proxy)) {
                    Toast.makeText(context, "Invalid proxy URL: use HTTPS (or local loopback for testing).", Toast.LENGTH_LONG).show();
                    return;
                }
                mAiManager.getSettings().setCustomProxyUrl(proxy);
                refreshProxySummary(proxyPref);
            })
            .setNeutralButton("Clear", (d, w) -> {
                mAiManager.getSettings().setCustomProxyUrl("");
                refreshProxySummary(proxyPref);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void runTestConnection(@NonNull AIProviderType provider, @NonNull Preference testPref) {
        testPref.setSummary("Testing " + provider.getDisplayName() + "…");
        mExecutor.submit(() -> {
            try {
                AIModelInfo info = mAiManager.testConnectionSync(provider);
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    testPref.setSummary("Connected: " + info.getId());
                    Toast.makeText(getContext(), provider.getDisplayName() + " connected (" + info.getId() + ")", Toast.LENGTH_SHORT).show();
                });
            } catch (AIException e) {
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    testPref.setSummary("Failed: " + e.getError().getMessage());
                    Toast.makeText(getContext(), e.getError().getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void runFetchModels(@NonNull AIProviderType provider, @NonNull Preference fetchPref, ListPreference modelPref) {
        fetchPref.setSummary("Fetching models from " + provider.getDisplayName() + "…");
        mExecutor.submit(() -> {
            try {
                List<AIModelInfo> models = mAiManager.fetchModelsSync(provider);
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    if (modelPref != null) {
                        populateModelEntries(modelPref, provider);
                    }
                    fetchPref.setSummary("Loaded " + models.size() + " models");
                    Toast.makeText(getContext(), "Loaded " + models.size() + " models for " + provider.getDisplayName(), Toast.LENGTH_SHORT).show();
                });
            } catch (AIException e) {
                mMainHandler.post(() -> {
                    if (!isAdded()) return;
                    fetchPref.setSummary("Failed: " + e.getError().getMessage());
                    Toast.makeText(getContext(), e.getError().getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static final class AISettingsDataStore extends PreferenceDataStore {
        private final AISettingsManager mSettings;

        AISettingsDataStore(@NonNull AISettingsManager settings) {
            mSettings = settings;
        }

        @Override
        public void putString(String key, String value) {
            if (key == null || value == null) return;
            switch (key) {
                case AISettingsManager.KEY_ACTIVE_PROVIDER:
                    mSettings.setActiveProvider(AIProviderType.fromId(value));
                    break;
                case AISettingsManager.KEY_BUILTIN_MODEL:
                    mSettings.setModelForProvider(AIProviderType.BUILTIN, value);
                    break;
                case AISettingsManager.KEY_GEMINI_MODEL:
                    mSettings.setModelForProvider(AIProviderType.GEMINI, value);
                    break;
                case AISettingsManager.KEY_OPENROUTER_MODEL:
                    mSettings.setModelForProvider(AIProviderType.OPENROUTER, value);
                    break;
                case "ai_temperature_preset":
                    try {
                        mSettings.setTemperature(Float.parseFloat(value));
                    } catch (NumberFormatException ignored) {}
                    break;
                case "ai_max_tokens_preset":
                    try {
                        mSettings.setMaxOutputTokens(Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {}
                    break;
                default:
                    break;
            }
        }

        @Override
        public String getString(String key, String defValue) {
            if (key == null) return defValue;
            switch (key) {
                case AISettingsManager.KEY_ACTIVE_PROVIDER:
                    return mSettings.getActiveProvider().getId();
                case AISettingsManager.KEY_BUILTIN_MODEL:
                    return mSettings.getModelForProvider(AIProviderType.BUILTIN);
                case AISettingsManager.KEY_GEMINI_MODEL:
                    return mSettings.getModelForProvider(AIProviderType.GEMINI);
                case AISettingsManager.KEY_OPENROUTER_MODEL:
                    return mSettings.getModelForProvider(AIProviderType.OPENROUTER);
                case "ai_temperature_preset":
                    return String.valueOf(mSettings.getTemperature());
                case "ai_max_tokens_preset":
                    return String.valueOf(mSettings.getMaxOutputTokens());
                default:
                    return defValue;
            }
        }

        @Override
        public void putBoolean(String key, boolean value) {
            if (key == null) return;
            switch (key) {
                case AISettingsManager.KEY_STREAMING_ENABLED:
                    mSettings.setStreamingEnabled(value);
                    break;
                case AISettingsManager.KEY_EXPLICIT_FALLBACK_ENABLED:
                    mSettings.setExplicitFallbackEnabled(value);
                    break;
                case AISettingsManager.KEY_CTX_INCLUDE_CWD:
                    mSettings.setIncludeCwdEnabled(value);
                    break;
                case AISettingsManager.KEY_CTX_INCLUDE_SHELL:
                    mSettings.setIncludeShellEnabled(value);
                    break;
                case AISettingsManager.KEY_CTX_INCLUDE_LAST_COMMAND:
                    mSettings.setIncludeLastCommandEnabled(value);
                    break;
                case AISettingsManager.KEY_CTX_INCLUDE_RECENT_OUTPUT:
                    mSettings.setIncludeRecentOutputEnabled(value);
                    break;
                case AISettingsManager.KEY_CTX_INCLUDE_TOOLS:
                    mSettings.setIncludeToolsEnabled(value);
                    break;
                default:
                    break;
            }
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            if (key == null) return defValue;
            switch (key) {
                case AISettingsManager.KEY_STREAMING_ENABLED:
                    return mSettings.isStreamingEnabled();
                case AISettingsManager.KEY_EXPLICIT_FALLBACK_ENABLED:
                    return mSettings.isExplicitFallbackEnabled();
                case AISettingsManager.KEY_CTX_INCLUDE_CWD:
                    return mSettings.isIncludeCwdEnabled();
                case AISettingsManager.KEY_CTX_INCLUDE_SHELL:
                    return mSettings.isIncludeShellEnabled();
                case AISettingsManager.KEY_CTX_INCLUDE_LAST_COMMAND:
                    return mSettings.isIncludeLastCommandEnabled();
                case AISettingsManager.KEY_CTX_INCLUDE_RECENT_OUTPUT:
                    return mSettings.isIncludeRecentOutputEnabled();
                case AISettingsManager.KEY_CTX_INCLUDE_TOOLS:
                    return mSettings.isIncludeToolsEnabled();
                default:
                    return defValue;
            }
        }
    }
}
