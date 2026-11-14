package com.termux.app.ai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.AIProviderType;

public class AISettingsManager {

    private static final String PREFS_NAME = "termux_ai_settings";

    public static final String KEY_ACTIVE_PROVIDER = "ai_active_provider";
    public static final String KEY_GEMINI_MODEL = "ai_gemini_model";
    public static final String KEY_OPENROUTER_MODEL = "ai_openrouter_model";
    public static final String KEY_TEMPERATURE = "ai_temperature";
    public static final String KEY_MAX_TOKENS = "ai_max_output_tokens";
    public static final String KEY_STREAMING_ENABLED = "ai_streaming_enabled";
    public static final String KEY_EXPLICIT_FALLBACK_ENABLED = "ai_explicit_fallback_enabled";
    public static final String KEY_CUSTOM_PROXY_URL = "ai_custom_proxy_url";

    public static final String KEY_CTX_INCLUDE_CWD = "ai_ctx_include_cwd";
    public static final String KEY_CTX_INCLUDE_SHELL = "ai_ctx_include_shell";
    public static final String KEY_CTX_INCLUDE_LAST_COMMAND = "ai_ctx_include_last_command";
    public static final String KEY_CTX_INCLUDE_RECENT_OUTPUT = "ai_ctx_include_recent_output";
    public static final String KEY_CTX_INCLUDE_TOOLS = "ai_ctx_include_tools";

    public static final float DEFAULT_TEMPERATURE = 0.3f;
    public static final int DEFAULT_MAX_TOKENS = 2048;

    private final SharedPreferences mPrefs;

    public AISettingsManager(@NonNull Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public AIProviderType getActiveProvider() {
        return AIProviderType.fromId(mPrefs.getString(KEY_ACTIVE_PROVIDER, AIProviderType.GEMINI.getId()));
    }

    public void setActiveProvider(@NonNull AIProviderType provider) {
        mPrefs.edit().putString(KEY_ACTIVE_PROVIDER, provider.getId()).apply();
    }

    @NonNull
    public String getModelForProvider(@NonNull AIProviderType provider) {
        if (provider == AIProviderType.OPENROUTER) {
            String val = mPrefs.getString(KEY_OPENROUTER_MODEL, AIProviderType.OPENROUTER.getDefaultModelId());
            return (val == null || val.trim().isEmpty()) ? AIProviderType.OPENROUTER.getDefaultModelId() : val.trim();
        }
        String val = mPrefs.getString(KEY_GEMINI_MODEL, AIProviderType.GEMINI.getDefaultModelId());
        return (val == null || val.trim().isEmpty()) ? AIProviderType.GEMINI.getDefaultModelId() : val.trim();
    }

    public void setModelForProvider(@NonNull AIProviderType provider, @NonNull String modelId) {
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) trimmed = provider.getDefaultModelId();
        String key = provider == AIProviderType.OPENROUTER ? KEY_OPENROUTER_MODEL : KEY_GEMINI_MODEL;
        mPrefs.edit().putString(key, trimmed).apply();
    }

    public float getTemperature() {
        float val = mPrefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE);
        return Math.max(0.0f, Math.min(2.0f, val));
    }

    public void setTemperature(float temperature) {
        float clamped = Math.max(0.0f, Math.min(2.0f, temperature));
        mPrefs.edit().putFloat(KEY_TEMPERATURE, clamped).apply();
    }

    public int getMaxOutputTokens() {
        int val = mPrefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS);
        return Math.max(128, Math.min(32768, val));
    }

    public void setMaxOutputTokens(int maxTokens) {
        int clamped = Math.max(128, Math.min(32768, maxTokens));
        mPrefs.edit().putInt(KEY_MAX_TOKENS, clamped).apply();
    }

    public boolean isStreamingEnabled() {
        return mPrefs.getBoolean(KEY_STREAMING_ENABLED, true);
    }

    public void setStreamingEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_STREAMING_ENABLED, enabled).apply();
    }

    public boolean isExplicitFallbackEnabled() {
        return mPrefs.getBoolean(KEY_EXPLICIT_FALLBACK_ENABLED, false);
    }

    public void setExplicitFallbackEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_EXPLICIT_FALLBACK_ENABLED, enabled).apply();
    }

    @Nullable
    public String getCustomProxyUrl() {
        String raw = mPrefs.getString(KEY_CUSTOM_PROXY_URL, "");
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public void setCustomProxyUrl(@Nullable String proxyUrl) {
        String trimmed = proxyUrl == null ? "" : proxyUrl.trim();
        mPrefs.edit().putString(KEY_CUSTOM_PROXY_URL, trimmed).apply();
    }

    public boolean isIncludeCwdEnabled() {
        return mPrefs.getBoolean(KEY_CTX_INCLUDE_CWD, true);
    }

    public void setIncludeCwdEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_CTX_INCLUDE_CWD, enabled).apply();
    }

    public boolean isIncludeShellEnabled() {
        return mPrefs.getBoolean(KEY_CTX_INCLUDE_SHELL, true);
    }

    public void setIncludeShellEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_CTX_INCLUDE_SHELL, enabled).apply();
    }

    public boolean isIncludeLastCommandEnabled() {
        return mPrefs.getBoolean(KEY_CTX_INCLUDE_LAST_COMMAND, true);
    }

    public void setIncludeLastCommandEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_CTX_INCLUDE_LAST_COMMAND, enabled).apply();
    }

    public boolean isIncludeRecentOutputEnabled() {
        return mPrefs.getBoolean(KEY_CTX_INCLUDE_RECENT_OUTPUT, false);
    }

    public void setIncludeRecentOutputEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_CTX_INCLUDE_RECENT_OUTPUT, enabled).apply();
    }

    public boolean isIncludeToolsEnabled() {
        return mPrefs.getBoolean(KEY_CTX_INCLUDE_TOOLS, true);
    }

    public void setIncludeToolsEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_CTX_INCLUDE_TOOLS, enabled).apply();
    }
}
