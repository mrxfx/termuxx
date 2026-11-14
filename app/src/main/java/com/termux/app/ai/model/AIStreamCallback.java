package com.termux.app.ai.model;

import androidx.annotation.NonNull;

public interface AIStreamCallback {
    void onChunk(@NonNull String delta, @NonNull String accumulated);
    void onComplete(@NonNull AIResponse response);
    void onError(@NonNull AIError error);
}
