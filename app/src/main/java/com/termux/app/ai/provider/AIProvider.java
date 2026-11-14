package com.termux.app.ai.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.AIException;
import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIRequest;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.CancellationSignalHandle;

import java.util.List;

public interface AIProvider {

    @NonNull
    AIProviderType getType();

    void sendMessage(
        @NonNull AIRequest request,
        @NonNull String apiKey,
        @NonNull AIStreamCallback callback,
        @Nullable CancellationSignalHandle cancelHandle
    );

    @NonNull
    List<AIModelInfo> listModels(@NonNull String apiKey, @Nullable String customProxyUrl) throws AIException;

    @NonNull
    AIModelInfo testConnection(@NonNull String apiKey, @NonNull String modelId, @Nullable String customProxyUrl) throws AIException;
}
