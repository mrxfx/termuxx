package com.termux.app.ai.model;

import androidx.annotation.NonNull;

public enum AIProviderType {
    GEMINI("gemini", "Google Gemini", "gemini-2.5-flash"),
    OPENROUTER("openrouter", "OpenRouter", "meta-llama/llama-3.3-70b-instruct:free");

    private final String mId;
    private final String mDisplayName;
    private final String mDefaultModelId;

    AIProviderType(String id, String displayName, String defaultModelId) {
        mId = id;
        mDisplayName = displayName;
        mDefaultModelId = defaultModelId;
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

    @NonNull
    public static AIProviderType fromId(String id) {
        if (id != null) {
            for (AIProviderType value : values()) {
                if (value.mId.equalsIgnoreCase(id) || value.name().equalsIgnoreCase(id)) {
                    return value;
                }
            }
        }
        return GEMINI;
    }
}
