package com.termux.app.ai.model;

import androidx.annotation.NonNull;

public enum AIProviderType {
    BUILTIN("builtin", "Built-in AI", "gemini-3.8-flash", false),
    GEMINI("gemini", "Google Gemini", "gemini-2.5-flash", true),
    OPENROUTER("openrouter", "OpenRouter", "meta-llama/llama-3.3-70b-instruct:free", true);

    private final String mId;
    private final String mDisplayName;
    private final String mDefaultModelId;
    private final boolean mRequiresApiKey;

    AIProviderType(String id, String displayName, String defaultModelId, boolean requiresApiKey) {
        mId = id;
        mDisplayName = displayName;
        mDefaultModelId = defaultModelId;
        mRequiresApiKey = requiresApiKey;
    }

    public String getId() {
        return mId;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public String getDefaultModelId() {
        return mDefaultModelId;
    }

    public boolean requiresApiKey() {
        return mRequiresApiKey;
    }

    @NonNull
    public static AIProviderType fromId(String id) {
        if (id != null) {
            for (AIProviderType value : values()) {
                if (value.mId.equalsIgnoreCase(id) || value.name().equalsIgnoreCase(id)) {
                    return value;
                }
            }
        }
        return BUILTIN;
    }
}
