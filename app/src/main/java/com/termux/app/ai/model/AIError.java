package com.termux.app.ai.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AIError {

    public enum Type {
        NO_INTERNET,
        MISSING_API_KEY,
        INVALID_API_KEY,
        UNAUTHORIZED,
        RATE_LIMITED,
        QUOTA_EXCEEDED,
        INVALID_MODEL,
        SERVER_ERROR,
        TIMEOUT,
        MALFORMED_RESPONSE,
        CANCELLED,
        UNKNOWN
    }

    private final Type mType;
    private final int mHttpStatus;
    private final String mMessage;
    private final AIProviderType mProvider;
    private final boolean mRetryable;

    public AIError(@NonNull Type type, int httpStatus, @NonNull String message, @Nullable AIProviderType provider, boolean retryable) {
        mType = type;
        mHttpStatus = httpStatus;
        mMessage = message;
        mProvider = provider;
        mRetryable = retryable;
    }

    @NonNull
    public Type getType() {
        return mType;
    }

    public int getHttpStatus() {
        return mHttpStatus;
    }

    @NonNull
    public String getMessage() {
        return mMessage;
    }

    @Nullable
    public AIProviderType getProvider() {
        return mProvider;
    }

    public boolean isRetryable() {
        return mRetryable;
    }

    public static AIError fromHttpStatus(int statusCode, @NonNull String serverMessage, @Nullable AIProviderType provider) {
        String lower = serverMessage.toLowerCase();
        if (statusCode == 401) {
            return new AIError(Type.INVALID_API_KEY, statusCode,
                sanitizeErrorMessage(provider, statusCode, serverMessage, "Authentication failed: check your API key."),
                provider, false);
        }
        if (statusCode == 402 || lower.contains("quota") || lower.contains("insufficient_quota") || lower.contains("billing") || lower.contains("credits")) {
            return new AIError(Type.QUOTA_EXCEEDED, statusCode,
                sanitizeErrorMessage(provider, statusCode, serverMessage, "API quota or credit limit exceeded."),
                provider, false);
        }
        if (statusCode == 403) {
            Type type = (lower.contains("api key") || lower.contains("api_key_invalid") || lower.contains("key not valid"))
                ? Type.INVALID_API_KEY : Type.UNAUTHORIZED;
            return new AIError(type, statusCode,
                sanitizeErrorMessage(provider, statusCode, serverMessage, "Access forbidden: verify API key permissions."),
                provider, false);
        }
        if (statusCode == 404 || (statusCode == 400 && (lower.contains("model") && (lower.contains("not found") || lower.contains("invalid") || lower.contains("not a valid"))))) {
            return new AIError(Type.INVALID_MODEL, statusCode,
                sanitizeErrorMessage(provider, statusCode, serverMessage, "Selected model was not found or is invalid."),
                provider, false);
        }
        if (statusCode == 400 && (lower.contains("api key") || lower.contains("api_key_invalid"))) {
            return new AIError(Type.INVALID_API_KEY, statusCode,
                sanitizeErrorMessage(provider, statusCode, serverMessage, "Invalid API key format or rejected key."),
                provider, false);
        }
        if (statusCode == 429) {
            return new AIError(Type.RATE_LIMITED, statusCode,
                sanitizeErrorMessage(provider, statusCode, serverMessage, "Rate limit reached. Please wait a moment and retry."),
                provider, true);
        }
        if (statusCode >= 500) {
            return new AIError(Type.SERVER_ERROR, statusCode,
                sanitizeErrorMessage(provider, statusCode, serverMessage, "Provider server error (" + statusCode + ")."),
                provider, true);
        }
        return new AIError(Type.UNKNOWN, statusCode,
            sanitizeErrorMessage(provider, statusCode, serverMessage, "Request failed (" + statusCode + ")."),
            provider, false);
    }

    private static String sanitizeErrorMessage(@Nullable AIProviderType provider, int statusCode, String serverMessage, String fallback) {
        String providerLabel = provider != null ? provider.getDisplayName() : "AI Provider";
        String msg = (serverMessage != null && !serverMessage.trim().isEmpty()) ? serverMessage.trim() : fallback;
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "...";
        }
        return providerLabel + " (" + statusCode + "): " + msg;
    }
}
