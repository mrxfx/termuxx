package com.termux.app.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.AIError;
import com.termux.app.ai.model.AIException;
import com.termux.app.ai.model.AIMessage;
import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIRequest;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.CancellationSignalHandle;
import com.termux.app.ai.network.HttpTransport;
import com.termux.app.ai.network.UrlConnectionHttpTransport;
import com.termux.app.ai.provider.AIProvider;
import com.termux.app.ai.provider.GeminiProvider;
import com.termux.app.ai.provider.OpenRouterProvider;
import com.termux.app.ai.security.KeystoreCredentialManager;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class AIManager {

    private static final String BASE_SYSTEM_PROMPT =
        "You are Termux AI, a sharp, concise Linux, Bash, Android Termux, Git, Python, and CLI engineering assistant. "
        + "Provide accurate, safe shell commands and clear explanations. "
        + "Always place runnable commands inside fenced ```bash code blocks. "
        + "Never invent package names or flags; prefer standard POSIX/Debian/Termux commands. "
        + "Warn clearly before suggesting destructive commands (such as rm -rf or dd).";

    private static AIManager sInstance;

    private final AISettingsManager mSettings;
    private final KeystoreCredentialManager mCredentials;
    private final AIModelManager mModelManager;
    private final AIRequestManager mRequestManager;
    private final AIConversationStore mConversationStore;

    public static synchronized AIManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new AIManager(context.getApplicationContext(), new UrlConnectionHttpTransport());
        }
        return sInstance;
    }

    public AIManager(@NonNull Context context, @NonNull HttpTransport transport) {
        mSettings = new AISettingsManager(context);
        mCredentials = new KeystoreCredentialManager(context);
        mModelManager = new AIModelManager();
        Map<AIProviderType, AIProvider> providers = new EnumMap<>(AIProviderType.class);
        providers.put(AIProviderType.GEMINI, new GeminiProvider(transport));
        providers.put(AIProviderType.OPENROUTER, new OpenRouterProvider(transport));
        mRequestManager = new AIRequestManager(providers);
        mConversationStore = new AIConversationStore(context);
    }

    @NonNull
    public AISettingsManager getSettings() {
        return mSettings;
    }

    @NonNull
    public KeystoreCredentialManager getCredentials() {
        return mCredentials;
    }

    @NonNull
    public AIModelManager getModelManager() {
        return mModelManager;
    }

    @NonNull
    public AIConversationStore getConversationStore() {
        return mConversationStore;
    }

    @NonNull
    public CancellationSignalHandle sendConversation(
        @NonNull List<AIMessage> history,
        @Nullable String redactedContextBlock,
        @NonNull AIStreamCallback callback
    ) {
        CancellationSignalHandle cancelHandle = new CancellationSignalHandle();
        AIProviderType primary = mSettings.getActiveProvider();
        String primaryKey = mCredentials.getApiKey(primary);
        if (primaryKey == null || primaryKey.trim().isEmpty()) {
            callback.onError(new AIError(
                AIError.Type.MISSING_API_KEY,
                0,
                primary.getDisplayName() + " API key is not set. Open AI Settings to add your key.",
                primary,
                false
            ));
            return cancelHandle;
        }

        String systemPrompt = buildSystemPrompt(redactedContextBlock);
        String primaryModel = mSettings.getModelForProvider(primary);
        String proxyUrl = mSettings.getCustomProxyUrl();
        float temperature = mSettings.getTemperature();
        int maxTokens = mSettings.getMaxOutputTokens();
        boolean stream = mSettings.isStreamingEnabled();

        AIRequest primaryReq = new AIRequest(history, systemPrompt, primaryModel, temperature, maxTokens, stream, proxyUrl);

        boolean explicitFallback = mSettings.isExplicitFallbackEnabled();
        AIProviderType secondary = primary == AIProviderType.GEMINI ? AIProviderType.OPENROUTER : AIProviderType.GEMINI;
        String secondaryKey = explicitFallback ? mCredentials.getApiKey(secondary) : null;
        AIRequest fallbackReq = (explicitFallback && secondaryKey != null && !secondaryKey.trim().isEmpty())
            ? new AIRequest(history, systemPrompt, mSettings.getModelForProvider(secondary), temperature, maxTokens, stream, proxyUrl)
            : null;

        mRequestManager.executeRequest(
            primary, primaryKey, primaryReq,
            explicitFallback, secondary, secondaryKey, fallbackReq,
            callback, cancelHandle
        );
        return cancelHandle;
    }

    @NonNull
    public AIModelInfo testConnectionSync(@NonNull AIProviderType provider) throws AIException {
        String key = mCredentials.getApiKey(provider);
        if (key == null || key.trim().isEmpty()) {
            throw new AIException(new AIError(AIError.Type.MISSING_API_KEY, 0,
                provider.getDisplayName() + " API key is not configured.", provider, false));
        }
        return mRequestManager.testConnectionSync(
            provider, key, mSettings.getModelForProvider(provider), mSettings.getCustomProxyUrl()
        );
    }

    @NonNull
    public List<AIModelInfo> fetchModelsSync(@NonNull AIProviderType provider) throws AIException {
        String key = mCredentials.getApiKey(provider);
        if (key == null || key.trim().isEmpty()) {
            throw new AIException(new AIError(AIError.Type.MISSING_API_KEY, 0,
                provider.getDisplayName() + " API key is not configured.", provider, false));
        }
        List<AIModelInfo> models = mRequestManager.listModelsSync(provider, key, mSettings.getCustomProxyUrl());
        mModelManager.updateFetchedModels(provider, models);
        return models;
    }

    @NonNull
    public static String buildSystemPrompt(@Nullable String redactedContextBlock) {
        if (redactedContextBlock == null || redactedContextBlock.trim().isEmpty()) {
            return BASE_SYSTEM_PROMPT;
        }
        return BASE_SYSTEM_PROMPT + "\n\n[OPT-IN TERMINAL CONTEXT]\n" + redactedContextBlock.trim();
    }
}
