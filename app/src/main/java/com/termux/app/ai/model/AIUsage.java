package com.termux.app.ai.model;

public final class AIUsage {
    private final int mPromptTokens;
    private final int mCompletionTokens;
    private final int mTotalTokens;

    public AIUsage(int promptTokens, int completionTokens, int totalTokens) {
        mPromptTokens = Math.max(0, promptTokens);
        mCompletionTokens = Math.max(0, completionTokens);
        mTotalTokens = totalTokens > 0 ? totalTokens : (mPromptTokens + mCompletionTokens);
    }

    public int getPromptTokens() {
        return mPromptTokens;
    }

    public int getCompletionTokens() {
        return mCompletionTokens;
    }

    public int getTotalTokens() {
        return mTotalTokens;
    }
}
