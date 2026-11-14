package com.termux.app.workspace;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.app.autocomplete.AutocompleteSettingsManager;
import com.termux.app.studio.ShellConfigManager;
import com.termux.app.studio.StudioColorThemes;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages workspaces with strict directory validation (never creates fake directories),
 * duplicate/search/default workspace support, non-destructive session preservation,
 * and one-tap settings restoration.
 */
public class WorkspaceRepository {

    public static final class ValidationResult {
        public final boolean valid;
        @NonNull public final String message;
        @Nullable public final String normalizedPath;

        public ValidationResult(boolean valid, @NonNull String message, @Nullable String normalizedPath) {
            this.valid = valid;
            this.message = message;
            this.normalizedPath = normalizedPath;
        }
    }

    private static final String PREFS_NAME = "termux_workspace_prefs";
    private static final String KEY_ACTIVE_WORKSPACE_ID = "active_workspace_id";
    private static final String KEY_DEFAULT_WORKSPACE_ID = "default_workspace_id";

    private final Context mContext;
    private final File mStoreFile;
    private final SharedPreferences mPrefs;
    private final ProfileRepository mProfileRepository;
    private final Map<String, WorkspaceItem> mWorkspaces = new LinkedHashMap<>();
    private boolean mLoaded = false;

    public WorkspaceRepository(@NonNull Context context) {
        this(
            context.getApplicationContext(),
            new File(context.getApplicationContext().getFilesDir(), "termux_ai_workspaces.json"),
            new ProfileRepository(context)
        );
    }

    public WorkspaceRepository(
        @NonNull Context context,
        @NonNull File storeFile,
        @NonNull ProfileRepository profileRepository
    ) {
        mContext = context.getApplicationContext();
        mStoreFile = storeFile;
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mProfileRepository = profileRepository;
    }

    private synchronized void ensureLoaded() {
        if (mLoaded) return;
        mLoaded = true;
        loadFromDisk();
        if (mWorkspaces.isEmpty()) {
            seedDefaultWorkspace();
            saveToDisk();
        }
    }

    private void seedDefaultWorkspace() {
        File termuxHome = new File("/data/data/com.termux/files/home");
        String defaultDir = (termuxHome.exists() && termuxHome.isDirectory())
            ? termuxHome.getAbsolutePath()
            : mContext.getFilesDir().getAbsolutePath();
        WorkspaceItem homeWs = new WorkspaceItem(
            "workspace_home",
            "Termux Home",
            defaultDir,
            "bash",
            "default_dark",
            28,
            true,
            true,
            true,
            "profile_default_dark",
            "Primary Termux $HOME workspace",
            System.currentTimeMillis()
        );
        mWorkspaces.put(homeWs.id, homeWs);
        if (mPrefs.getString(KEY_ACTIVE_WORKSPACE_ID, null) == null) {
            mPrefs.edit()
                .putString(KEY_ACTIVE_WORKSPACE_ID, homeWs.id)
                .putString(KEY_DEFAULT_WORKSPACE_ID, homeWs.id)
                .apply();
        }
    }

    /**
     * Validates that the supplied directory path actually exists on disk and is a directory.
     * NEVER creates fake or non-existent directories.
     */
    @NonNull
    public static ValidationResult validateDirectory(@Nullable String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return new ValidationResult(false, "Working directory path cannot be empty.", null);
        }
        String trimmed = rawPath.trim();
        if (trimmed.equals("~") || trimmed.startsWith("~/")) {
            trimmed = "/data/data/com.termux/files/home" + trimmed.substring(1);
        }
        File dir = new File(trimmed);
        if (!dir.exists()) {
            return new ValidationResult(false, "Directory does not exist on device: " + trimmed, null);
        }
        if (!dir.isDirectory()) {
            return new ValidationResult(false, "Path is not a directory: " + trimmed, null);
        }
        return new ValidationResult(true, "Valid directory", dir.getAbsolutePath());
    }

    @NonNull
    public synchronized List<WorkspaceItem> getAllWorkspaces() {
        ensureLoaded();
        return new ArrayList<>(mWorkspaces.values());
    }

    @NonNull
    public synchronized List<WorkspaceItem> searchWorkspaces(@Nullable String query) {
        ensureLoaded();
        if (query == null || query.trim().isEmpty()) {
            return getAllWorkspaces();
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<WorkspaceItem> out = new ArrayList<>();
        for (WorkspaceItem ws : mWorkspaces.values()) {
            if (ws.name.toLowerCase(Locale.ROOT).contains(needle)
                || ws.workingDirectory.toLowerCase(Locale.ROOT).contains(needle)
                || ws.shell.toLowerCase(Locale.ROOT).contains(needle)
                || ws.environmentNotes.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(ws);
            }
        }
        return out;
    }

    @Nullable
    public synchronized WorkspaceItem findById(@Nullable String id) {
        if (id == null) return null;
        ensureLoaded();
        return mWorkspaces.get(id);
    }

    @Nullable
    public synchronized WorkspaceItem getActiveWorkspace() {
        ensureLoaded();
        String id = getActiveWorkspaceId();
        return mWorkspaces.get(id);
    }

    @NonNull
    public synchronized String getActiveWorkspaceId() {
        ensureLoaded();
        String id = mPrefs.getString(KEY_ACTIVE_WORKSPACE_ID, "workspace_home");
        if (id != null && mWorkspaces.containsKey(id)) return id;
        return mWorkspaces.isEmpty() ? "" : mWorkspaces.keySet().iterator().next();
    }

    @NonNull
    public synchronized String getDefaultWorkspaceId() {
        ensureLoaded();
        String id = mPrefs.getString(KEY_DEFAULT_WORKSPACE_ID, getActiveWorkspaceId());
        if (id != null && mWorkspaces.containsKey(id)) return id;
        return getActiveWorkspaceId();
    }

    public synchronized boolean setDefaultWorkspace(@NonNull String id) {
        ensureLoaded();
        if (!mWorkspaces.containsKey(id)) return false;
        mPrefs.edit().putString(KEY_DEFAULT_WORKSPACE_ID, id).apply();
        return true;
    }

    @NonNull
    public synchronized ValidationResult createOrUpdateWorkspace(@NonNull WorkspaceItem item) {
        ensureLoaded();
        ValidationResult dirCheck = validateDirectory(item.workingDirectory);
        if (!dirCheck.valid || dirCheck.normalizedPath == null) {
            return dirCheck;
        }
        WorkspaceItem normalized = new WorkspaceItem(
            item.id,
            item.name,
            dirCheck.normalizedPath,
            item.shell,
            item.colorThemeId,
            item.fontSize,
            item.autocompleteEnabled,
            item.ghostTextEnabled,
            item.dropdownEnabled,
            item.profileId,
            item.environmentNotes,
            System.currentTimeMillis()
        );
        mWorkspaces.put(normalized.id, normalized);
        saveToDisk();
        return new ValidationResult(true, "Workspace saved", normalized.workingDirectory);
    }

    @Nullable
    public synchronized WorkspaceItem renameWorkspace(@NonNull String id, @NonNull String newName) {
        ensureLoaded();
        WorkspaceItem existing = mWorkspaces.get(id);
        if (existing == null || newName.trim().isEmpty()) return null;
        WorkspaceItem renamed = existing.withName(newName.trim());
        mWorkspaces.put(id, renamed);
        saveToDisk();
        return renamed;
    }

    @Nullable
    public synchronized WorkspaceItem duplicateWorkspace(@NonNull String id, @NonNull String duplicateName) {
        ensureLoaded();
        WorkspaceItem existing = mWorkspaces.get(id);
        if (existing == null) return null;
        String targetName = duplicateName.trim().isEmpty() ? existing.name + " (Copy)" : duplicateName.trim();
        WorkspaceItem copy = existing.duplicateAs(targetName);
        mWorkspaces.put(copy.id, copy);
        saveToDisk();
        return copy;
    }

    public synchronized boolean deleteWorkspace(@NonNull String id) {
        ensureLoaded();
        if (mWorkspaces.size() <= 1) {
            return false;
        }
        WorkspaceItem removed = mWorkspaces.remove(id);
        if (removed != null) {
            String fallback = mWorkspaces.keySet().iterator().next();
            if (id.equals(mPrefs.getString(KEY_ACTIVE_WORKSPACE_ID, ""))) {
                mPrefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, fallback).apply();
            }
            if (id.equals(mPrefs.getString(KEY_DEFAULT_WORKSPACE_ID, ""))) {
                mPrefs.edit().putString(KEY_DEFAULT_WORKSPACE_ID, fallback).apply();
            }
            saveToDisk();
            return true;
        }
        return false;
    }

    /**
     * Activates the selected workspace:
     * - Captures a restore snapshot of previous settings first.
     * - Applies assigned TerminalProfile (if any), then workspace overrides (shell, color theme, font size, autocomplete).
     * - Updates default working-directory property in ~/.termux/termux.properties for new sessions
     *   without killing or auto-running anything in existing sessions.
     */
    public synchronized boolean selectAndApplyWorkspace(
        @NonNull String workspaceId,
        @NonNull ShellConfigManager configManager
    ) {
        ensureLoaded();
        WorkspaceItem ws = mWorkspaces.get(workspaceId);
        if (ws == null) return false;

        mProfileRepository.capturePreApplySnapshot(configManager);
        mPrefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, ws.id).apply();

        // 1. If a customization profile is linked, apply it first without overwriting the snapshot
        if (ws.profileId != null) {
            TerminalProfile linked = mProfileRepository.findById(ws.profileId);
            if (linked != null) {
                mProfileRepository.applyProfileWithoutSnapshot(linked, configManager);
            }
        }

        // 2. Apply workspace-specific shell, color theme, font size, working directory, and autocomplete flags
        configManager.switchDefaultShell(ws.shell);
        mPrefs.edit().putString("preferred_shell", ws.shell).apply();

        StudioColorThemes.ColorThemePreset colorPreset = StudioColorThemes.findById(ws.colorThemeId);
        if (colorPreset != null) {
            configManager.writeFileAtomicallyWithBackup(
                configManager.getColorsPropertiesFile(),
                colorPreset.propertiesContent
            );
        }

        TermuxAppSharedPreferences termuxPrefs = TermuxAppSharedPreferences.build(mContext, false);
        if (termuxPrefs != null) {
            termuxPrefs.setFontSize(ws.fontSize);
        }

        ValidationResult dirValidation = validateDirectory(ws.workingDirectory);
        if (dirValidation.valid && dirValidation.normalizedPath != null) {
            configManager.upsertTermuxProperty("default-working-directory", dirValidation.normalizedPath);
        }

        AutocompleteSettingsManager ac = new AutocompleteSettingsManager(mContext);
        ac.setEnabled(ws.autocompleteEnabled);
        ac.setGhostTextEnabled(ws.ghostTextEnabled);
        ac.setDropdownEnabled(ws.dropdownEnabled);

        try {
            TermuxActivity.updateTermuxActivityStyling(mContext, false);
        } catch (Exception ignored) {}
        return true;
    }

    public synchronized boolean restorePreviousWorkspaceSettings(@NonNull ShellConfigManager configManager) {
        return mProfileRepository.restorePreApplySnapshot(configManager);
    }

    public synchronized void replaceAllWorkspaces(@NonNull List<WorkspaceItem> items, @Nullable String activeId) {
        mLoaded = true;
        mWorkspaces.clear();
        for (WorkspaceItem item : items) {
            mWorkspaces.put(item.id, item);
        }
        if (mWorkspaces.isEmpty()) {
            seedDefaultWorkspace();
        }
        if (activeId != null && mWorkspaces.containsKey(activeId)) {
            mPrefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, activeId).apply();
        }
        saveToDisk();
    }

    private void loadFromDisk() {
        if (!mStoreFile.exists() || !mStoreFile.isFile()) return;
        try (FileInputStream fis = new FileInputStream(mStoreFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    try {
                        WorkspaceItem ws = WorkspaceItem.fromJson(obj);
                        mWorkspaces.put(ws.id, ws);
                    } catch (JSONException ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveToDisk() {
        try {
            File parent = mStoreFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            JSONArray arr = new JSONArray();
            for (WorkspaceItem item : mWorkspaces.values()) {
                arr.put(item.toJson());
            }
            File tmp = new File(parent, mStoreFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (!tmp.renameTo(mStoreFile)) {
                try (FileOutputStream fos = new FileOutputStream(mStoreFile)) {
                    fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }
}
