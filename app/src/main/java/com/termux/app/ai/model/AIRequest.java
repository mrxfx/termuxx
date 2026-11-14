package com.termux.app.ai.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AIRequest {

    private final List<AIMessage> mMessages;
    private final String mSystemPrompt;
    private final String mModelId;
    private final float mTemperature;
    private final int mMaxOutputTokens;
    private final boolean mStream;
    @Nullable
    private final String mCustomProxyUrl;

    public AIRequest(
        @NonNull List<AIMessage> messages,
        @Nullable String systemPrompt,
        @NonNull String modelId,
        float temperature,
        int maxOutputTokens,
        boolean stream,
        @Nullable String customProxyUrl
    ) {
        mMessages = Collections.unmodifiableList(new ArrayList<>(messages));
        mSystemPrompt = systemPrompt != null ? systemPrompt : "";
        mModelId = modelId;
        mTemperature = temperature;
        mMaxOutputTokens = maxOutputTokens;
        mStream = stream;
        mCustomProxyUrl = (customProxyUrl != null && !customProxyUrl.trim().isEmpty()) ? customProxyUrl.trim() : null;
    }

    @NonNull
    public List<AIMessage> getMessages() {
        return mMessages;
    }

    @NonNull
    public String getSystemPrompt() {
        return mSystemPrompt;
    }

    @NonNull
    public String getModelId() {
        return mModelId;
    }

    public float getTemperature() {
        return mTemperature;
    }

    public int getMaxOutputTokens() {
        return mMaxOutputTokens;
    }

    public boolean isStream() {
        return mStream;
    }

    @Nullable
    public String getCustomProxyUrl() {
        return mCustomProxyUrl;
    }
}
