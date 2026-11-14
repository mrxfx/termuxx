package com.termux.app.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.AIError;
import com.termux.app.ai.model.AIException;
import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIRequest;
import com.termux.app.ai.model.AIResponse;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.CancellationSignalHandle;
import com.termux.app.ai.provider.AIProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates async AI execution, cancellation, transient retry with exponential backoff,
 * and strictly explicit-only provider fallback.
 */
public class AIRequestManager {

    private static final int MAX_TRANSIENT_RETRIES = 1;
    private static final long INITIAL_BACKOFF_MS = 600L;

    private final Map<AIProviderType, AIProvider> mProviders;
    private final ExecutorService mExecutor;
    private final long mBackoffMs;

    public AIRequestManager(@NonNull Map<AIProviderType, AIProvider> providers) {
        this(providers, Executors.newCachedThreadPool(), INITIAL_BACKOFF_MS);
    }

    public AIRequestManager(@NonNull Map<AIProviderType, AIProvider> providers,
                            @NonNull ExecutorService executor,
                            long backoffMs) {
        mProviders = providers;
        mExecutor = executor;
        mBackoffMs = backoffMs;
    }

    public void executeRequest(
        @NonNull AIProviderType primaryType,
        @NonNull String primaryApiKey,
        @NonNull AIRequest primaryRequest,
        boolean allowExplicitFallback,
        @Nullable AIProviderType fallbackType,
        @Nullable String fallbackApiKey,
        @Nullable AIRequest fallbackRequest,
        @NonNull AIStreamCallback callback,
        @NonNull CancellationSignalHandle cancelHandle
    ) {
        mExecutor.execute(() -> {
            AIError firstError = runWithRetry(primaryType, primaryApiKey, primaryRequest, callback, cancelHandle);
            if (firstError == null) {
                return;
            }
            if (cancelHandle.isCancelled() || firstError.getType() == AIError.Type.CANCELLED) {
                callback.onError(firstError);
                return;
            }
            // Never silently switch providers unless user explicitly enabled fallback AND secondary is ready.
            if (allowExplicitFallback
                && fallbackType != null
                && fallbackType != primaryType
                && (!fallbackType.requiresApiKey() || (fallbackApiKey != null && !fallbackApiKey.trim().isEmpty()))
                && fallbackRequest != null
                && isFallbackEligible(firstError)) {
                AIError fallbackError = runWithRetry(fallbackType, fallbackApiKey == null ? "" : fallbackApiKey, fallbackRequest, callback, cancelHandle);
                if (fallbackError != null) {
                    callback.onError(fallbackError);
                }
                return;
            }
            callback.onError(firstError);
        });
    }

    @Nullable
    private AIError runWithRetry(
        @NonNull AIProviderType type,
        @NonNull String apiKey,
        @NonNull AIRequest request,
        @NonNull AIStreamCallback downstreamCallback,
        @NonNull CancellationSignalHandle cancelHandle
    ) {
        AIProvider provider = mProviders.get(type);
        if (provider == null) {
            return new AIError(AIError.Type.UNKNOWN, 0, "Provider not registered: " + type.getDisplayName(), type, false);
        }

        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            if (cancelHandle.isCancelled()) {
                return new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", type, false);
            }

            final boolean[] emittedChunks = new boolean[1];
            final boolean[] completed = new boolean[1];
            final AIError[] attemptError = new AIError[1];

            provider.sendMessage(request, apiKey, new AIStreamCallback() {
                @Override
                public void onChunk(@NonNull String delta, @NonNull String accumulated) {
                    emittedChunks[0] = true;
                    downstreamCallback.onChunk(delta, accumulated);
                }

                @Override
                public void onComplete(@NonNull AIResponse response) {
                    completed[0] = true;
                    downstreamCallback.onComplete(response);
                }

                @Override
                public void onError(@NonNull AIError error) {
                    attemptError[0] = error;
                }
            }, cancelHandle);

            if (completed[0]) {
                return null;
            }
            AIError err = attemptError[0];
            if (err == null) {
                return new AIError(AIError.Type.UNKNOWN, 0, "Empty response from provider.", type, false);
            }
            if (emittedChunks[0] || !err.isRetryable() || attempt == MAX_TRANSIENT_RETRIES || cancelHandle.isCancelled()) {
                return err;
            }
            sleepBackoff(mBackoffMs * (attempt + 1));
        }
        return new AIError(AIError.Type.UNKNOWN, 0, "Request failed.", type, false);
    }

    @NonNull
    public AIModelInfo testConnectionSync(
        @NonNull AIProviderType type,
        @NonNull String apiKey,
        @NonNull String modelId,
        @Nullable String proxyUrl
    ) throws AIException {
        AIProvider provider = mProviders.get(type);
        if (provider == null) {
            throw new AIException(new AIError(AIError.Type.UNKNOWN, 0, "Provider unavailable.", type, false));
        }
        return provider.testConnection(apiKey, modelId, proxyUrl);
    }

    @NonNull
    public List<AIModelInfo> listModelsSync(
        @NonNull AIProviderType type,
        @NonNull String apiKey,
        @Nullable String proxyUrl
    ) throws AIException {
        AIProvider provider = mProviders.get(type);
        if (provider == null) {
            throw new AIException(new AIError(AIError.Type.UNKNOWN, 0, "Provider unavailable.", type, false));
        }
        return provider.listModels(apiKey, proxyUrl);
    }

    private static boolean isFallbackEligible(@NonNull AIError error) {
        switch (error.getType()) {
            case RATE_LIMITED:
            case QUOTA_EXCEEDED:
            case SERVER_ERROR:
            case TIMEOUT:
                return true;
            default:
                return false;
        }
    }

    private static void sleepBackoff(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
