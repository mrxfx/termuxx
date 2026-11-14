package com.termux.app.ai.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.firebase.FirebaseAiLogicBridge;
import com.termux.app.ai.model.AIException;
import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIRequest;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.CancellationSignalHandle;

import java.util.List;

/**
 * Zero-config Built-in AI provider powered by Firebase AI Logic & App Check.
 * Does not require a user-supplied API key.
 */
public class BuiltInFirebaseProvider implements AIProvider {

    @NonNull
    @Override
    public AIProviderType getType() {
        return AIProviderType.BUILTIN;
    }

    @Override
    public void sendMessage(
        @NonNull AIRequest request,
        @NonNull String apiKey,
        @NonNull AIStreamCallback callback,
        @Nullable CancellationSignalHandle cancelHandle
    ) {
        FirebaseAiLogicBridge.sendMessage(request, callback, cancelHandle);
    }

    @NonNull
    @Override
    public List<AIModelInfo> listModels(@NonNull String apiKey, @Nullable String customProxyUrl) throws AIException {
        return FirebaseAiLogicBridge.listBuiltInModels();
    }

    @NonNull
    @Override
    public AIModelInfo testConnection(@NonNull String apiKey, @NonNull String modelId, @Nullable String customProxyUrl) throws AIException {
        return FirebaseAiLogicBridge.testConnection(modelId);
    }
}
