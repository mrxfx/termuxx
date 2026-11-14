package com.termux.app.autocomplete;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public class AutocompleteSettingsManager {

    private static final String PREFS_NAME = "termux_autocomplete_settings";

    public static final String KEY_ENABLED = "ac_enabled";
    public static final String KEY_GHOST_TEXT = "ac_ghost_text_enabled";
    public static final String KEY_DROPDOWN = "ac_dropdown_enabled";
    public static final String KEY_MAX_SUGGESTIONS = "ac_max_suggestions";
    public static final String KEY_HISTORY_ENABLED = "ac_history_enabled";
    public static final String KEY_FILES_ENABLED = "ac_files_enabled";
    public static final String KEY_PACKAGES_ENABLED = "ac_packages_enabled";
    public static final String KEY_ACCEPT_KEY = "ac_accept_key";

    public static final String ACCEPT_KEY_TAB = "tab";
    public static final String ACCEPT_KEY_RIGHT = "right";
    public static final String ACCEPT_KEY_BOTH = "both";

    public static final int DEFAULT_MAX_SUGGESTIONS = 6;

    private final SharedPreferences mPrefs;

    public AutocompleteSettingsManager(@NonNull Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public boolean isGhostTextEnabled() {
        return mPrefs.getBoolean(KEY_GHOST_TEXT, true);
    }

    public void setGhostTextEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_GHOST_TEXT, enabled).apply();
    }

    public boolean isDropdownEnabled() {
        return mPrefs.getBoolean(KEY_DROPDOWN, true);
    }

    public void setDropdownEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_DROPDOWN, enabled).apply();
    }

    public int getMaxSuggestions() {
        int val = mPrefs.getInt(KEY_MAX_SUGGESTIONS, DEFAULT_MAX_SUGGESTIONS);
        return Math.max(2, Math.min(12, val));
    }

    public void setMaxSuggestions(int count) {
        int clamped = Math.max(2, Math.min(12, count));
        mPrefs.edit().putInt(KEY_MAX_SUGGESTIONS, clamped).apply();
    }

    public boolean isHistoryEnabled() {
        return mPrefs.getBoolean(KEY_HISTORY_ENABLED, true);
    }

    public void setHistoryEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply();
    }

    public boolean isFileSuggestionsEnabled() {
        return mPrefs.getBoolean(KEY_FILES_ENABLED, true);
    }

    public void setFileSuggestionsEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_FILES_ENABLED, enabled).apply();
    }

    public boolean isPackageSuggestionsEnabled() {
        return mPrefs.getBoolean(KEY_PACKAGES_ENABLED, true);
    }

    public void setPackageSuggestionsEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_PACKAGES_ENABLED, enabled).apply();
    }

    @NonNull
    public String getAcceptKey() {
        String val = mPrefs.getString(KEY_ACCEPT_KEY, ACCEPT_KEY_TAB);
        if (ACCEPT_KEY_RIGHT.equals(val) || ACCEPT_KEY_BOTH.equals(val)) {
            return val;
        }
        return ACCEPT_KEY_TAB;
    }

    public void setAcceptKey(@NonNull String acceptKey) {
        mPrefs.edit().putString(KEY_ACCEPT_KEY, acceptKey).apply();
    }

    public boolean acceptsTab() {
        String key = getAcceptKey();
        return ACCEPT_KEY_TAB.equals(key) || ACCEPT_KEY_BOTH.equals(key);
    }

    public boolean acceptsRightArrow() {
        String key = getAcceptKey();
        return ACCEPT_KEY_RIGHT.equals(key) || ACCEPT_KEY_BOTH.equals(key);
    }
}
