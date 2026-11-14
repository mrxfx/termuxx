package com.termux.app.workspace;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Reusable terminal profile encapsulating shell preference, color scheme, font size,
 * cursor style/blink, Extra Keys layout, offline autocomplete settings, and shell studio plugins.
 */
public final class TerminalProfile {

    @NonNull public final String id;
    @NonNull public final String name;
    @NonNull public final String shell;
    @NonNull public final String colorSchemeId;
    public final int fontSize;
    @NonNull public final String cursorStyle;
    public final int cursorBlinkRate;
    @NonNull public final String extraKeysPresetId;
    public final boolean showExtraKeys;
    public final boolean autocompleteEnabled;
    public final boolean ghostTextEnabled;
    public final boolean dropdownEnabled;
    @NonNull public final String shellPromptThemeId;
    public final boolean syntaxHighlightingEnabled;
    public final boolean autosuggestionsEnabled;
    public final long updatedAtMs;

    public TerminalProfile(
        @NonNull String id,
        @NonNull String name,
        @NonNull String shell,
        @NonNull String colorSchemeId,
        int fontSize,
        @NonNull String cursorStyle,
        int cursorBlinkRate,
        @NonNull String extraKeysPresetId,
        boolean showExtraKeys,
        boolean autocompleteEnabled,
        boolean ghostTextEnabled,
        boolean dropdownEnabled,
        @NonNull String shellPromptThemeId,
        boolean syntaxHighlightingEnabled,
        boolean autosuggestionsEnabled,
        long updatedAtMs
    ) {
        this.id = id.trim().isEmpty() ? UUID.randomUUID().toString() : id.trim();
        this.name = name.trim().isEmpty() ? "Untitled Profile" : name.trim();
        this.shell = normalizeShell(shell);
        this.colorSchemeId = colorSchemeId.trim().isEmpty() ? "default_dark" : colorSchemeId.trim();
        this.fontSize = Math.max(14, Math.min(72, fontSize));
        this.cursorStyle = normalizeCursorStyle(cursorStyle);
        this.cursorBlinkRate = Math.max(0, Math.min(2000, cursorBlinkRate));
        this.extraKeysPresetId = extraKeysPresetId.trim().isEmpty() ? "default" : extraKeysPresetId.trim();
        this.showExtraKeys = showExtraKeys;
        this.autocompleteEnabled = autocompleteEnabled;
        this.ghostTextEnabled = ghostTextEnabled;
        this.dropdownEnabled = dropdownEnabled;
        this.shellPromptThemeId = shellPromptThemeId.trim().isEmpty() ? "clean_mono" : shellPromptThemeId.trim();
        this.syntaxHighlightingEnabled = syntaxHighlightingEnabled;
        this.autosuggestionsEnabled = autosuggestionsEnabled;
        this.updatedAtMs = updatedAtMs > 0 ? updatedAtMs : System.currentTimeMillis();
    }

    @NonNull
    public static String normalizeShell(@NonNull String raw) {
        String s = raw.trim().toLowerCase();
        if ("zsh".equals(s) || "fish".equals(s)) return s;
        return "bash";
    }

    @NonNull
    public static String normalizeCursorStyle(@NonNull String raw) {
        String s = raw.trim().toLowerCase();
        if ("underline".equals(s) || "bar".equals(s)) return s;
        return "block";
    }

    @NonNull
    public TerminalProfile withName(@NonNull String newName) {
        return new TerminalProfile(
            id, newName, shell, colorSchemeId, fontSize, cursorStyle, cursorBlinkRate,
            extraKeysPresetId, showExtraKeys, autocompleteEnabled, ghostTextEnabled,
            dropdownEnabled, shellPromptThemeId, syntaxHighlightingEnabled,
            autosuggestionsEnabled, System.currentTimeMillis()
        );
    }

    @NonNull
    public TerminalProfile duplicateAs(@NonNull String duplicateName) {
        return new TerminalProfile(
            UUID.randomUUID().toString(), duplicateName, shell, colorSchemeId, fontSize,
            cursorStyle, cursorBlinkRate, extraKeysPresetId, showExtraKeys,
            autocompleteEnabled, ghostTextEnabled, dropdownEnabled, shellPromptThemeId,
            syntaxHighlightingEnabled, autosuggestionsEnabled, System.currentTimeMillis()
        );
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("type", "termux_ai_profile");
        obj.put("version", 1);
        obj.put("id", id);
        obj.put("name", name);
        obj.put("shell", shell);
        obj.put("colorSchemeId", colorSchemeId);
        obj.put("fontSize", fontSize);
        obj.put("cursorStyle", cursorStyle);
        obj.put("cursorBlinkRate", cursorBlinkRate);
        obj.put("extraKeysPresetId", extraKeysPresetId);
        obj.put("showExtraKeys", showExtraKeys);
        obj.put("autocompleteEnabled", autocompleteEnabled);
        obj.put("ghostTextEnabled", ghostTextEnabled);
        obj.put("dropdownEnabled", dropdownEnabled);
        obj.put("shellPromptThemeId", shellPromptThemeId);
        obj.put("syntaxHighlightingEnabled", syntaxHighlightingEnabled);
        obj.put("autosuggestionsEnabled", autosuggestionsEnabled);
        obj.put("updatedAtMs", updatedAtMs);
        return obj;
    }

    @NonNull
    public static TerminalProfile fromJson(@NonNull JSONObject obj) throws JSONException {
        String name = obj.optString("name", "").trim();
        if (name.isEmpty()) {
            throw new JSONException("Profile name is required");
        }
        return new TerminalProfile(
            obj.optString("id", UUID.randomUUID().toString()),
            name,
            obj.optString("shell", "bash"),
            obj.optString("colorSchemeId", "default_dark"),
            obj.optInt("fontSize", 28),
            obj.optString("cursorStyle", "block"),
            obj.optInt("cursorBlinkRate", 0),
            obj.optString("extraKeysPresetId", "default"),
            obj.optBoolean("showExtraKeys", true),
            obj.optBoolean("autocompleteEnabled", true),
            obj.optBoolean("ghostTextEnabled", true),
            obj.optBoolean("dropdownEnabled", true),
            obj.optString("shellPromptThemeId", "clean_mono"),
            obj.optBoolean("syntaxHighlightingEnabled", false),
            obj.optBoolean("autosuggestionsEnabled", false),
            obj.optLong("updatedAtMs", System.currentTimeMillis())
        );
    }
}
