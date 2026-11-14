package com.termux.app.workspace;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.app.autocomplete.AutocompleteSettingsManager;
import com.termux.app.studio.ShellConfigManager;
import com.termux.app.studio.StudioColorThemes;
import com.termux.app.studio.StudioPackageHelper;
import com.termux.app.studio.StudioShellCustomizer;
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
import java.util.Map;

/**
 * Persistent repository and applicator for reusable TerminalProfiles.
 */
public class ProfileRepository {

    private static final String PREFS_NAME = "termux_workspace_prefs";
    private static final String KEY_ACTIVE_PROFILE_ID = "active_profile_id";
    private static final String KEY_PREFERRED_SHELL = "preferred_shell";
    private static final String KEY_PRE_APPLY_SNAPSHOT = "pre_apply_profile_snapshot";

    private final Context mContext;
    private final File mStoreFile;
    private final SharedPreferences mPrefs;
    private final Map<String, TerminalProfile> mProfiles = new LinkedHashMap<>();
    private boolean mLoaded = false;

    public ProfileRepository(@NonNull Context context) {
        this(
            context.getApplicationContext(),
            new File(context.getApplicationContext().getFilesDir(), "termux_ai_profiles.json")
        );
    }

    public ProfileRepository(@NonNull Context context, @NonNull File storeFile) {
        mContext = context.getApplicationContext();
        mStoreFile = storeFile;
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private synchronized void ensureLoaded() {
        if (mLoaded) return;
        mLoaded = true;
        loadFromDisk();
        if (mProfiles.isEmpty()) {
            seedDefaults();
            saveToDisk();
        }
    }

    private void seedDefaults() {
        TerminalProfile defaultDark = new TerminalProfile(
            "profile_default_dark",
            "Pure Dark Default",
            "bash",
            "default_dark",
            28,
            "block",
            0,
            "default",
            true,
            true,
            true,
            true,
            "clean_mono",
            false,
            false,
            System.currentTimeMillis()
        );
        TerminalProfile devZsh = new TerminalProfile(
            "profile_dev_zsh",
            "Developer Zsh",
            "zsh",
            "dracula",
            28,
            "bar",
            500,
            "developer",
            true,
            true,
            true,
            true,
            "dev_git",
            true,
            false,
            System.currentTimeMillis()
        );
        TerminalProfile vimFocus = new TerminalProfile(
            "profile_vim_focus",
            "Vim & Editor Focus",
            "bash",
            "nord",
            30,
            "block",
            0,
            "vim_nav",
            true,
            true,
            false,
            true,
            "minimal_dir",
            false,
            false,
            System.currentTimeMillis()
        );
        mProfiles.put(defaultDark.id, defaultDark);
        mProfiles.put(devZsh.id, devZsh);
        mProfiles.put(vimFocus.id, vimFocus);

        long now = System.currentTimeMillis();
        mProfiles.put("profile_general_dev", new TerminalProfile(
            "profile_general_dev", "General Development", "bash", "default_dark", 28,
            "block", 0, "developer", true, true, true, true, "dev_git", true, false, now
        ));
        mProfiles.put("profile_android_dev", new TerminalProfile(
            "profile_android_dev", "Android Development", "zsh", "gunmetal_neon", 28,
            "bar", 500, "developer", true, true, true, true, "dev_git", true, true, now
        ));
        mProfiles.put("profile_web_dev", new TerminalProfile(
            "profile_web_dev", "Web Development", "zsh", "dracula", 28,
            "bar", 500, "developer", true, true, true, true, "dev_git", true, true, now
        ));
        mProfiles.put("profile_python_dev", new TerminalProfile(
            "profile_python_dev", "Python Development", "bash", "monokai", 28,
            "block", 0, "developer", true, true, true, true, "dev_git", true, false, now
        ));
        mProfiles.put("profile_nodejs_dev", new TerminalProfile(
            "profile_nodejs_dev", "Node.js Development", "zsh", "nord", 28,
            "bar", 500, "developer", true, true, true, true, "dev_git", true, true, now
        ));
        mProfiles.put("profile_linux_admin", new TerminalProfile(
            "profile_linux_admin", "Linux Administration", "bash", "solarized_dark", 28,
            "block", 0, "default", true, true, true, true, "clean_mono", false, false, now
        ));

        if (mPrefs.getString(KEY_ACTIVE_PROFILE_ID, null) == null) {
            mPrefs.edit().putString(KEY_ACTIVE_PROFILE_ID, defaultDark.id).apply();
        }
    }

    @NonNull
    public synchronized List<TerminalProfile> getAllProfiles() {
        ensureLoaded();
        return new ArrayList<>(mProfiles.values());
    }

    @Nullable
    public synchronized TerminalProfile findById(@Nullable String id) {
        if (id == null) return null;
        ensureLoaded();
        return mProfiles.get(id);
    }

    @NonNull
    public synchronized String getActiveProfileId() {
        ensureLoaded();
        String id = mPrefs.getString(KEY_ACTIVE_PROFILE_ID, "profile_default_dark");
        if (id != null && mProfiles.containsKey(id)) return id;
        return mProfiles.isEmpty() ? "" : mProfiles.keySet().iterator().next();
    }

    @NonNull
    public synchronized String getPreferredShell() {
        return mPrefs.getString(KEY_PREFERRED_SHELL, "bash");
    }

    public synchronized void saveProfile(@NonNull TerminalProfile profile) {
        ensureLoaded();
        mProfiles.put(profile.id, profile);
        saveToDisk();
    }

    @Nullable
    public synchronized TerminalProfile renameProfile(@NonNull String id, @NonNull String newName) {
        ensureLoaded();
        TerminalProfile existing = mProfiles.get(id);
        if (existing == null || newName.trim().isEmpty()) return null;
        TerminalProfile updated = existing.withName(newName.trim());
        mProfiles.put(id, updated);
        saveToDisk();
        return updated;
    }

    @Nullable
    public synchronized TerminalProfile duplicateProfile(@NonNull String id, @NonNull String duplicateName) {
        ensureLoaded();
        TerminalProfile existing = mProfiles.get(id);
        if (existing == null) return null;
        String targetName = duplicateName.trim().isEmpty() ? existing.name + " (Copy)" : duplicateName.trim();
        TerminalProfile copy = existing.duplicateAs(targetName);
        mProfiles.put(copy.id, copy);
        saveToDisk();
        return copy;
    }

    public synchronized boolean deleteProfile(@NonNull String id) {
        ensureLoaded();
        if (mProfiles.size() <= 1) {
            return false;
        }
        TerminalProfile removed = mProfiles.remove(id);
        if (removed != null) {
            if (id.equals(mPrefs.getString(KEY_ACTIVE_PROFILE_ID, ""))) {
                String nextId = mProfiles.keySet().iterator().next();
                mPrefs.edit().putString(KEY_ACTIVE_PROFILE_ID, nextId).apply();
            }
            saveToDisk();
            return true;
        }
        return false;
    }

    @NonNull
    public synchronized String exportProfileToJson(@NonNull TerminalProfile profile) throws JSONException {
        return profile.toJson().toString(2);
    }

    @NonNull
    public synchronized TerminalProfile importProfileFromJson(@NonNull String rawJson) throws JSONException {
        ensureLoaded();
        JSONObject obj = new JSONObject(rawJson.trim());
        TerminalProfile parsed = TerminalProfile.fromJson(obj);
        // Ensure unique ID if colliding with an existing profile
        TerminalProfile toSave = mProfiles.containsKey(parsed.id)
            ? parsed.duplicateAs(parsed.name)
            : parsed;
        mProfiles.put(toSave.id, toSave);
        saveToDisk();
        return toSave;
    }

    public synchronized void replaceAllProfiles(@NonNull List<TerminalProfile> profiles, @Nullable String activeId) {
        mLoaded = true;
        mProfiles.clear();
        for (TerminalProfile p : profiles) {
            mProfiles.put(p.id, p);
        }
        if (mProfiles.isEmpty()) {
            seedDefaults();
        }
        if (activeId != null && mProfiles.containsKey(activeId)) {
            mPrefs.edit().putString(KEY_ACTIVE_PROFILE_ID, activeId).apply();
        }
        saveToDisk();
    }

    /**
     * Captures a snapshot of current terminal settings before applying a profile or workspace,
     * allowing 1-tap restoration of previous settings.
     */
    public synchronized void capturePreApplySnapshot(@NonNull ShellConfigManager configManager) {
        try {
            AutocompleteSettingsManager ac = new AutocompleteSettingsManager(mContext);
            TermuxAppSharedPreferences termuxPrefs = TermuxAppSharedPreferences.build(mContext, false);
            StudioColorThemes.ColorThemePreset activeTheme = StudioColorThemes.detectActiveTheme(configManager);
            String shell = configManager.detectActiveDefaultShell();
            int fontSize = termuxPrefs != null ? termuxPrefs.getFontSize() : 28;
            boolean showToolbar = termuxPrefs == null || termuxPrefs.shouldShowTerminalToolbar();
            String cursorStyle = configManager.readTermuxProperty("terminal-cursor-style");
            String blinkStr = configManager.readTermuxProperty("terminal-cursor-blink-rate");
            int blink = 0;
            if (blinkStr != null) {
                try { blink = Integer.parseInt(blinkStr.trim()); } catch (NumberFormatException ignored) {}
            }
            TerminalProfile snapshot = new TerminalProfile(
                "snapshot_previous",
                "Previous Settings Snapshot",
                shell,
                activeTheme != null ? activeTheme.id : "default_dark",
                fontSize,
                cursorStyle != null ? cursorStyle : "block",
                blink,
                "default",
                showToolbar,
                ac.isEnabled(),
                ac.isGhostTextEnabled(),
                ac.isDropdownEnabled(),
                "clean_mono",
                false,
                false,
                System.currentTimeMillis()
            );
            mPrefs.edit().putString(KEY_PRE_APPLY_SNAPSHOT, snapshot.toJson().toString()).apply();
        } catch (Exception ignored) {}
    }

    public synchronized boolean hasPreApplySnapshot() {
        return mPrefs.getString(KEY_PRE_APPLY_SNAPSHOT, null) != null;
    }

    public synchronized boolean restorePreApplySnapshot(@NonNull ShellConfigManager configManager) {
        String raw = mPrefs.getString(KEY_PRE_APPLY_SNAPSHOT, null);
        if (raw == null || raw.isEmpty()) return false;
        try {
            TerminalProfile snapshot = TerminalProfile.fromJson(new JSONObject(raw));
            applyProfileInternal(snapshot, configManager, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Applies a TerminalProfile to Termux preferences, colors.properties, termux.properties,
     * offline AutocompleteSettingsManager, and shell rc files.
     */
    public synchronized boolean applyProfile(
        @NonNull TerminalProfile profile,
        @NonNull ShellConfigManager configManager
    ) {
        ensureLoaded();
        capturePreApplySnapshot(configManager);
        return applyProfileInternal(profile, configManager, true);
    }

    public synchronized boolean applyProfileWithoutSnapshot(
        @NonNull TerminalProfile profile,
        @NonNull ShellConfigManager configManager
    ) {
        ensureLoaded();
        return applyProfileInternal(profile, configManager, true);
    }

    private boolean applyProfileInternal(
        @NonNull TerminalProfile profile,
        @NonNull ShellConfigManager configManager,
        boolean updateActiveId
    ) {
        if (updateActiveId) {
            mPrefs.edit()
                .putString(KEY_ACTIVE_PROFILE_ID, profile.id)
                .putString(KEY_PREFERRED_SHELL, profile.shell)
                .apply();
        } else {
            mPrefs.edit().putString(KEY_PREFERRED_SHELL, profile.shell).apply();
        }

        // 1. Default shell symlink (if binary exists)
        configManager.switchDefaultShell(profile.shell);

        // 2. Color scheme
        StudioColorThemes.ColorThemePreset colorPreset = StudioColorThemes.findById(profile.colorSchemeId);
        if (colorPreset != null) {
            configManager.writeFileAtomicallyWithBackup(
                configManager.getColorsPropertiesFile(),
                colorPreset.propertiesContent
            );
        }

        // 3. Font size & Extra Keys visibility
        TermuxAppSharedPreferences termuxPrefs = TermuxAppSharedPreferences.build(mContext, false);
        if (termuxPrefs != null) {
            termuxPrefs.setFontSize(profile.fontSize);
            termuxPrefs.setShowTerminalToolbar(profile.showExtraKeys);
        }

        // 4. Cursor style, blink rate, and Extra Keys preset in ~/.termux/termux.properties
        configManager.upsertTermuxProperty("terminal-cursor-style", profile.cursorStyle);
        configManager.upsertTermuxProperty("terminal-cursor-blink-rate", String.valueOf(profile.cursorBlinkRate));
        for (StudioShellCustomizer.ExtraKeysPreset ek : StudioShellCustomizer.getExtraKeysPresets()) {
            if (ek.id.equals(profile.extraKeysPresetId)) {
                configManager.upsertTermuxProperty("extra-keys", ek.propertyValue);
                break;
            }
        }

        // 5. Autocomplete preferences
        AutocompleteSettingsManager ac = new AutocompleteSettingsManager(mContext);
        ac.setEnabled(profile.autocompleteEnabled);
        ac.setGhostTextEnabled(profile.ghostTextEnabled);
        ac.setDropdownEnabled(profile.dropdownEnabled);

        // 6. Shell prompt theme & plugins
        StudioPackageHelper pkgHelper = new StudioPackageHelper(configManager);
        StudioShellCustomizer customizer = new StudioShellCustomizer(configManager, pkgHelper);
        if ("none".equalsIgnoreCase(profile.shellPromptThemeId)) {
            customizer.clearPromptTheme(profile.shell);
        } else {
            customizer.applyPromptTheme(profile.shell, profile.shellPromptThemeId);
        }

        if ("zsh".equals(profile.shell)) {
            customizer.setPluginEnabled(StudioShellCustomizer.MODULE_ZSH_SYNTAX, profile.syntaxHighlightingEnabled);
            customizer.setPluginEnabled(StudioShellCustomizer.MODULE_ZSH_AUTOSUGGEST, profile.autosuggestionsEnabled);
        } else if ("fish".equals(profile.shell)) {
            customizer.setPluginEnabled(StudioShellCustomizer.MODULE_FISH_SYNTAX, profile.syntaxHighlightingEnabled);
            customizer.setPluginEnabled(StudioShellCustomizer.MODULE_FISH_AUTOSUGGEST, profile.autosuggestionsEnabled);
        } else {
            customizer.setPluginEnabled(StudioShellCustomizer.MODULE_BASH_BLESH,
                profile.syntaxHighlightingEnabled || profile.autosuggestionsEnabled);
        }

        try {
            TermuxActivity.updateTermuxActivityStyling(mContext, false);
        } catch (Exception ignored) {}
        return true;
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
                        TerminalProfile p = TerminalProfile.fromJson(obj);
                        mProfiles.put(p.id, p);
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
            for (TerminalProfile p : mProfiles.values()) {
                arr.put(p.toJson());
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
