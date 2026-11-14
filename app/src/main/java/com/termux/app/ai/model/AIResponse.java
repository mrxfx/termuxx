package com.termux.app.ai.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AIResponse {
    private final String mText;
    @Nullable
    private final AIUsage mUsage;
    @NonNull
    private final AIProviderType mProvider;
    @NonNull
    private final String mModelId;
    @Nullable
    private final String mFinishReason;

    public AIResponse(@NonNull String text, @Nullable AIUsage usage, @NonNull AIProviderType provider,
                      @NonNull String modelId, @Nullable String finishReason) {
        mText = text;
        mUsage = usage;
        mProvider = provider;
        mModelId = modelId;
        mFinishReason = finishReason;
    }

    @NonNull
    public String getText() {
        return mText;
    }

    @Nullable
    public AIUsage getUsage() {
        return mUsage;
    }

    @NonNull
    public AIProviderType getProvider() {
        return mProvider;
    }

    @NonNull
    public String getModelId() {
        return mModelId;
    }

    @Nullable
    public String getFinishReason() {
        return mFinishReason;
    }
}
