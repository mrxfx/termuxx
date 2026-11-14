package com.termux.app.ai.model;

import androidx.annotation.NonNull;

public final class AIModelInfo {
    private final String mId;
    private final String mDisplayName;
    private final AIProviderType mProvider;
    private final boolean mFree;
    private final int mContextLength;

    public AIModelInfo(@NonNull String id, @NonNull String displayName, @NonNull AIProviderType provider, boolean free, int contextLength) {
        mId = id;
        mDisplayName = displayName;
        mProvider = provider;
        mFree = free;
        mContextLength = contextLength;
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public String getDisplayName() {
        return mDisplayName;
    }

    @NonNull
    public AIProviderType getProvider() {
        return mProvider;
    }

    public boolean isFree() {
        return mFree;
    }

    public int getContextLength() {
        return mContextLength;
    }

    @NonNull
    @Override
    public String toString() {
        return mDisplayName + (mFree ? " (Free)" : "");
    }
}
