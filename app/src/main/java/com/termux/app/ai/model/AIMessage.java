package com.termux.app.ai.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

public class AIMessage {

    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    private final String mId;
    private final Role mRole;
    private String mContent;
    private final long mTimestampMs;
    @Nullable
    private AIProviderType mProvider;
    @Nullable
    private String mModelId;
    @Nullable
    private AIUsage mUsage;
    @Nullable
    private String mErrorMessage;
    private boolean mStreaming;

    public AIMessage(@NonNull Role role, @NonNull String content) {
        this(UUID.randomUUID().toString(), role, content, System.currentTimeMillis());
    }

    public AIMessage(@NonNull String id, @NonNull Role role, @NonNull String content, long timestampMs) {
        mId = id;
        mRole = role;
        mContent = content;
        mTimestampMs = timestampMs;
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public Role getRole() {
        return mRole;
    }

    @NonNull
    public String getContent() {
        return mContent;
    }

    public void setContent(@NonNull String content) {
        mContent = content;
    }

    public long getTimestampMs() {
        return mTimestampMs;
    }

    @Nullable
    public AIProviderType getProvider() {
        return mProvider;
    }

    public void setProvider(@Nullable AIProviderType provider) {
        mProvider = provider;
    }

    @Nullable
    public String getModelId() {
        return mModelId;
    }

    public void setModelId(@Nullable String modelId) {
        mModelId = modelId;
    }

    @Nullable
    public AIUsage getUsage() {
        return mUsage;
    }

    public void setUsage(@Nullable AIUsage usage) {
        mUsage = usage;
    }

    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    public void setErrorMessage(@Nullable String errorMessage) {
        mErrorMessage = errorMessage;
    }

    public boolean isStreaming() {
        return mStreaming;
    }

    public void setStreaming(boolean streaming) {
        mStreaming = streaming;
    }
}
