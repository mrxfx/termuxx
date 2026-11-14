package com.termux.app.ai.model;

import androidx.annotation.NonNull;

public final class AIException extends Exception {
    private final AIError mError;

    public AIException(@NonNull AIError error) {
        super(error.getMessage());
        mError = error;
    }

    public AIException(@NonNull AIError error, Throwable cause) {
        super(error.getMessage(), cause);
        mError = error;
    }

    @NonNull
    public AIError getError() {
        return mError;
    }
}
