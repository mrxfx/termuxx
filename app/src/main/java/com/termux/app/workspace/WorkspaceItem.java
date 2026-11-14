package com.termux.app.workspace;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Represents a configured Termux AI Workspace with a validated working directory,
 * shell preference, terminal color theme, font size, offline autocomplete flags,
 * optional environment notes, and optional linked profile.
 */
public final class WorkspaceItem {

    @NonNull public final String id;
    @NonNull public final String name;
    @NonNull public final String workingDirectory;
    @NonNull public final String shell;
    @NonNull public final String colorThemeId;
    public final int fontSize;
    public final boolean autocompleteEnabled;
    public final boolean ghostTextEnabled;
    public final boolean dropdownEnabled;
    @Nullable public final String profileId;
    @NonNull public final String environmentNotes;
    public final long updatedAtMs;

    public WorkspaceItem(
        @NonNull String id,
        @NonNull String name,
        @NonNull String workingDirectory,
        @NonNull String shell,
        @NonNull String colorThemeId,
        boolean autocompleteEnabled,
        boolean ghostTextEnabled,
        boolean dropdownEnabled,
        @Nullable String profileId,
        long updatedAtMs
    ) {
        this(
            id, name, workingDirectory, shell, colorThemeId, 28,
            autocompleteEnabled, ghostTextEnabled, dropdownEnabled,
            profileId, "", updatedAtMs
        );
    }

    public WorkspaceItem(
        @NonNull String id,
        @NonNull String name,
        @NonNull String workingDirectory,
        @NonNull String shell,
        @NonNull String colorThemeId,
        int fontSize,
        boolean autocompleteEnabled,
        boolean ghostTextEnabled,
        boolean dropdownEnabled,
        @Nullable String profileId,
        @Nullable String environmentNotes,
        long updatedAtMs
    ) {
        this.id = id.trim().isEmpty() ? UUID.randomUUID().toString() : id.trim();
        this.name = name.trim().isEmpty() ? "Workspace" : name.trim();
        this.workingDirectory = workingDirectory.trim();
        this.shell = TerminalProfile.normalizeShell(shell);
        this.colorThemeId = colorThemeId.trim().isEmpty() ? "default_dark" : colorThemeId.trim();
        this.fontSize = Math.max(14, Math.min(48, fontSize));
        this.autocompleteEnabled = autocompleteEnabled;
        this.ghostTextEnabled = ghostTextEnabled;
        this.dropdownEnabled = dropdownEnabled;
        this.profileId = (profileId == null || profileId.trim().isEmpty()) ? null : profileId.trim();
        this.environmentNotes = environmentNotes == null ? "" : environmentNotes.trim();
        this.updatedAtMs = updatedAtMs > 0 ? updatedAtMs : System.currentTimeMillis();
    }

    @NonNull
    public WorkspaceItem withName(@NonNull String newName) {
        return new WorkspaceItem(
            id, newName, workingDirectory, shell, colorThemeId, fontSize,
            autocompleteEnabled, ghostTextEnabled, dropdownEnabled, profileId,
            environmentNotes, System.currentTimeMillis()
        );
    }

    @NonNull
    public WorkspaceItem duplicateAs(@NonNull String duplicateName) {
        String newId = "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return new WorkspaceItem(
            newId, duplicateName, workingDirectory, shell, colorThemeId, fontSize,
            autocompleteEnabled, ghostTextEnabled, dropdownEnabled, profileId,
            environmentNotes, System.currentTimeMillis()
        );
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("workingDirectory", workingDirectory);
        obj.put("shell", shell);
        obj.put("colorThemeId", colorThemeId);
        obj.put("fontSize", fontSize);
        obj.put("autocompleteEnabled", autocompleteEnabled);
        obj.put("ghostTextEnabled", ghostTextEnabled);
        obj.put("dropdownEnabled", dropdownEnabled);
        if (profileId != null) {
            obj.put("profileId", profileId);
        }
        obj.put("environmentNotes", environmentNotes);
        obj.put("updatedAtMs", updatedAtMs);
        return obj;
    }

    @NonNull
    public static WorkspaceItem fromJson(@NonNull JSONObject obj) throws JSONException {
        String name = obj.optString("name", "").trim();
        String dir = obj.optString("workingDirectory", "").trim();
        if (name.isEmpty() || dir.isEmpty()) {
            throw new JSONException("Workspace name and workingDirectory are required");
        }
        return new WorkspaceItem(
            obj.optString("id", UUID.randomUUID().toString()),
            name,
            dir,
            obj.optString("shell", "bash"),
            obj.optString("colorThemeId", "default_dark"),
            obj.optInt("fontSize", 28),
            obj.optBoolean("autocompleteEnabled", true),
            obj.optBoolean("ghostTextEnabled", true),
            obj.optBoolean("dropdownEnabled", true),
            obj.optString("profileId", null),
            obj.optString("environmentNotes", ""),
            obj.optLong("updatedAtMs", System.currentTimeMillis())
        );
    }
}
