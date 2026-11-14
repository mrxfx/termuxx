package com.termux.app.workspace;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.app.ai.AISettingsManager;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.security.SensitiveDataRedactor;
import com.termux.app.autocomplete.AutocompleteSettingsManager;
import com.termux.app.studio.ShellConfigManager;
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
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Secure Configuration Backup & Restore engine for Termux AI V2.
 *
 * Includes:
 * - Shell configuration files (.bashrc, .zshrc, config.fish) with secret/env-var scrubbing
 * - Termux theme & terminal properties (colors.properties, termux.properties)
 * - Terminal preferences
 * - Workspaces & Reusable Terminal Profiles
 * - Command Snippets
 * - Offline Autocomplete preferences
 * - User-selected AI preferences (provider, model IDs, generation & privacy flags)
 *
 * Strictly Excludes:
 * - Private API keys & Keystore credentials
 * - Signing keystores (.jks / .keystore)
 * - Passwords & studio_lock.json
 * - Sensitive environment variables (export *_KEY, *_TOKEN, *_SECRET, *_PASSWORD)
 * - Unselected personal files
 */
public class BackupArchiveManager {

    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

    private static final Pattern SENSITIVE_ENV_LINE = Pattern.compile(
        "(?i)^\\s*(export\\s+|set\\s+-gx\\s+|set\\s+-x\\s+)?[A-Z0-9_]*(API_KEY|SECRET|TOKEN|PASSWORD|PASSWD|PRIVATE_KEY|CREDENTIAL|AUTH)[A-Z0-9_]*\\s*[=\\s].*$"
    );

    public static final class BackupEntry {
        @NonNull public final File file;
        @NonNull public final String label;
        public final long createdAtMs;
        public final long sizeBytes;
        public final int workspaceCount;
        public final int profileCount;
        public final int snippetCount;

        public BackupEntry(
            @NonNull File file,
            @NonNull String label,
            long createdAtMs,
            long sizeBytes,
            int workspaceCount,
            int profileCount,
            int snippetCount
        ) {
            this.file = file;
            this.label = label;
            this.createdAtMs = createdAtMs;
            this.sizeBytes = sizeBytes;
            this.workspaceCount = workspaceCount;
            this.profileCount = profileCount;
            this.snippetCount = snippetCount;
        }
    }

    public static final class RestorePreviewReport {
        public final boolean valid;
        @NonNull public final String validationMessage;
        public final int schemaVersion;
        @NonNull public final String label;
        public final long createdAtMs;
        public final boolean checksumVerified;
        public final int workspaceCount;
        public final int profileCount;
        public final int snippetCount;
        @NonNull public final List<String> includedShellFiles;
        public final boolean hasColorsProperties;
        public final boolean hasTermuxProperties;
        public final boolean hasTerminalPrefs;
        public final boolean hasAutocompletePrefs;
        public final boolean hasAiPrefs;
        @NonNull public final List<String> existingFilesToOverwrite;

        public RestorePreviewReport(
            boolean valid,
            @NonNull String validationMessage,
            int schemaVersion,
            @NonNull String label,
            long createdAtMs,
            boolean checksumVerified,
            int workspaceCount,
            int profileCount,
            int snippetCount,
            @NonNull List<String> includedShellFiles,
            boolean hasColorsProperties,
            boolean hasTermuxProperties,
            boolean hasTerminalPrefs,
            boolean hasAutocompletePrefs,
            boolean hasAiPrefs,
            @NonNull List<String> existingFilesToOverwrite
        ) {
            this.valid = valid;
            this.validationMessage = validationMessage;
            this.schemaVersion = schemaVersion;
            this.label = label;
            this.createdAtMs = createdAtMs;
            this.checksumVerified = checksumVerified;
            this.workspaceCount = workspaceCount;
            this.profileCount = profileCount;
            this.snippetCount = snippetCount;
            this.includedShellFiles = includedShellFiles;
            this.hasColorsProperties = hasColorsProperties;
            this.hasTermuxProperties = hasTermuxProperties;
            this.hasTerminalPrefs = hasTerminalPrefs;
            this.hasAutocompletePrefs = hasAutocompletePrefs;
            this.hasAiPrefs = hasAiPrefs;
            this.existingFilesToOverwrite = existingFilesToOverwrite;
        }
    }

    public static final class RestoreOptions {
        public boolean restoreWorkspaces = true;
        public boolean restoreProfiles = true;
        public boolean restoreSnippets = true;
        public boolean restoreShellConfigs = true;
        public boolean restoreThemeAndProperties = true;
        public boolean restorePreferences = true;
    }

    private final Context mContext;
    private final ShellConfigManager mConfigManager;
    private final WorkspaceRepository mWorkspaceRepo;
    private final ProfileRepository mProfileRepo;
    private final SnippetRepository mSnippetRepo;
    private final File mBackupHistoryDir;

    public BackupArchiveManager(@NonNull Context context) {
        this(
            context.getApplicationContext(),
            new ShellConfigManager(),
            new WorkspaceRepository(context),
            new ProfileRepository(context),
            new SnippetRepository(context),
            new File(context.getApplicationContext().getFilesDir(), "workspace_backups")
        );
    }

    public BackupArchiveManager(
        @NonNull Context context,
        @NonNull ShellConfigManager configManager,
        @NonNull WorkspaceRepository workspaceRepo,
        @NonNull ProfileRepository profileRepo,
        @NonNull SnippetRepository snippetRepo,
        @NonNull File backupHistoryDir
    ) {
        mContext = context.getApplicationContext();
        mConfigManager = configManager;
        mWorkspaceRepo = workspaceRepo;
        mProfileRepo = profileRepo;
        mSnippetRepo = snippetRepo;
        mBackupHistoryDir = backupHistoryDir;
    }

    /**
     * Scrubs shell rc content so sensitive environment variables, API keys, tokens,
     * and optional shell lock blocks are never exported in backups.
     */
    @NonNull
    public static String sanitizeShellRcForBackup(@Nullable String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) return "";
        String withoutLock = ShellConfigManager.removeManagedBlock(
            rawContent, StudioShellCustomizer.MODULE_CYBER_LOCK);
        String[] lines = withoutLock.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (SENSITIVE_ENV_LINE.matcher(line).matches()
                || SensitiveDataRedactor.containsLikelySecret(line)) {
                out.append("# [EXCLUDED SENSITIVE CREDENTIAL LINE]");
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /**
     * Generates a complete, validated JSON backup archive string.
     */
    @NonNull
    public String generateBackupJson(@Nullable String customLabel) throws Exception {
        String label = (customLabel == null || customLabel.trim().isEmpty())
            ? "Termux AI Backup"
            : customLabel.trim();
        long now = System.currentTimeMillis();

        JSONObject payload = new JSONObject();

        // 1. Shell configuration files (scrubbed of secrets and passwords)
        JSONObject shellConfigs = new JSONObject();
        putScrubbedFileIfPresent(shellConfigs, "bashrc", mConfigManager.getBashRcFile());
        putScrubbedFileIfPresent(shellConfigs, "zshrc", mConfigManager.getZshRcFile());
        putScrubbedFileIfPresent(shellConfigs, "config_fish", mConfigManager.getFishConfigFile());
        payload.put("shellConfigs", shellConfigs);

        // 2. Termux theme & properties
        JSONObject termuxFiles = new JSONObject();
        String colors = mConfigManager.readFileSafely(mConfigManager.getColorsPropertiesFile());
        if (!colors.isEmpty()) {
            termuxFiles.put("colors_properties", colors);
        }
        String props = mConfigManager.readFileSafely(mConfigManager.getTermuxPropertiesFile());
        if (!props.isEmpty()) {
            termuxFiles.put("termux_properties", sanitizeShellRcForBackup(props));
        }
        termuxFiles.put("defaultShell", mConfigManager.detectActiveDefaultShell());
        payload.put("termuxFiles", termuxFiles);

        // 3. Terminal preferences
        JSONObject terminalPrefs = new JSONObject();
        TermuxAppSharedPreferences appPrefs = TermuxAppSharedPreferences.build(mContext, false);
        if (appPrefs != null) {
            terminalPrefs.put("fontSize", appPrefs.getFontSize());
            terminalPrefs.put("showTerminalToolbar", appPrefs.shouldShowTerminalToolbar());
            terminalPrefs.put("keepScreenOn", appPrefs.shouldKeepScreenOn());
            terminalPrefs.put("softKeyboardEnabled", appPrefs.isSoftKeyboardEnabled());
            terminalPrefs.put("terminalMarginAdjustment", appPrefs.isTerminalMarginAdjustmentEnabled());
        }
        payload.put("terminalPreferences", terminalPrefs);

        // 4. Workspaces
        JSONArray workspacesArr = new JSONArray();
        for (WorkspaceItem ws : mWorkspaceRepo.getAllWorkspaces()) {
            workspacesArr.put(ws.toJson());
        }
        payload.put("workspaces", workspacesArr);
        payload.put("activeWorkspaceId", mWorkspaceRepo.getActiveWorkspaceId());

        // 5. Profiles
        JSONArray profilesArr = new JSONArray();
        for (TerminalProfile prof : mProfileRepo.getAllProfiles()) {
            profilesArr.put(prof.toJson());
        }
        payload.put("profiles", profilesArr);
        payload.put("activeProfileId", mProfileRepo.getActiveProfileId());

        // 6. Command Snippets
        JSONArray snippetsArr = new JSONArray();
        for (CommandSnippet s : mSnippetRepo.getAllSnippets()) {
            snippetsArr.put(s.toJson());
        }
        payload.put("snippets", snippetsArr);

        // 7. Autocomplete preferences
        AutocompleteSettingsManager ac = new AutocompleteSettingsManager(mContext);
        JSONObject acJson = new JSONObject();
        acJson.put("enabled", ac.isEnabled());
        acJson.put("ghostTextEnabled", ac.isGhostTextEnabled());
        acJson.put("dropdownEnabled", ac.isDropdownEnabled());
        acJson.put("maxSuggestions", ac.getMaxSuggestions());
        acJson.put("historyEnabled", ac.isHistoryEnabled());
        acJson.put("filesEnabled", ac.isFileSuggestionsEnabled());
        acJson.put("packagesEnabled", ac.isPackageSuggestionsEnabled());
        acJson.put("acceptKey", ac.getAcceptKey());
        payload.put("autocompletePreferences", acJson);

        // 8. User-selected AI preferences (NEVER includes API keys!)
        AISettingsManager ai = new AISettingsManager(mContext);
        JSONObject aiJson = new JSONObject();
        aiJson.put("activeProvider", ai.getActiveProvider().getId());
        aiJson.put("builtinModel", ai.getModelForProvider(AIProviderType.BUILTIN));
        aiJson.put("geminiModel", ai.getModelForProvider(AIProviderType.GEMINI));
        aiJson.put("openrouterModel", ai.getModelForProvider(AIProviderType.OPENROUTER));
        aiJson.put("temperature", (double) ai.getTemperature());
        aiJson.put("maxOutputTokens", ai.getMaxOutputTokens());
        aiJson.put("streamingEnabled", ai.isStreamingEnabled());
        aiJson.put("explicitFallbackEnabled", ai.isExplicitFallbackEnabled());
        aiJson.put("includeCwd", ai.isIncludeCwdEnabled());
        aiJson.put("includeShell", ai.isIncludeShellEnabled());
        aiJson.put("includeLastCommand", ai.isIncludeLastCommandEnabled());
        aiJson.put("includeRecentOutput", ai.isIncludeRecentOutputEnabled());
        aiJson.put("includeTools", ai.isIncludeToolsEnabled());
        payload.put("aiPreferences", aiJson);

        String payloadCanonical = payload.toString();
        String checksum = sha256Hex(payloadCanonical);

        JSONObject root = new JSONObject();
        root.put("format", "termux_ai_backup_v2");
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.put("label", label);
        root.put("createdAtMs", now);
        root.put("checksumSha256", checksum);
        root.put("payload", payload);
        return root.toString(2);
    }

    private void putScrubbedFileIfPresent(
        @NonNull JSONObject target,
        @NonNull String key,
        @NonNull File file
    ) throws JSONException {
        if (file.exists() && file.isFile()) {
            String raw = mConfigManager.readFileSafely(file);
            if (!raw.isEmpty()) {
                target.put(key, sanitizeShellRcForBackup(raw));
            }
        }
    }

    /**
     * Creates a new backup archive in internal backup history and returns its metadata entry.
     */
    @NonNull
    public synchronized BackupEntry createBackup(@Nullable String customLabel) throws Exception {
        if (!mBackupHistoryDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mBackupHistoryDir.mkdirs();
        }
        String json = generateBackupJson(customLabel);
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File file = new File(mBackupHistoryDir, "termux_ai_backup_" + ts + ".json");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        BackupEntry parsed = parseBackupFileMetadata(file);
        if (parsed == null) {
            throw new IllegalStateException("Failed to verify created backup archive");
        }
        return parsed;
    }

    @NonNull
    public synchronized List<BackupEntry> listSavedBackups() {
        List<BackupEntry> list = new ArrayList<>();
        if (!mBackupHistoryDir.exists() || !mBackupHistoryDir.isDirectory()) return list;
        File[] files = mBackupHistoryDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return list;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            BackupEntry entry = parseBackupFileMetadata(f);
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }

    public synchronized boolean deleteBackupFile(@NonNull File file) {
        return file.exists() && file.delete();
    }

    @Nullable
    public synchronized BackupEntry findDuplicateBackup(@Nullable String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return null;
        try {
            JSONObject incoming = new JSONObject(rawJson.trim());
            String incomingChecksum = incoming.optString("checksumSha256", "");
            if (incomingChecksum.isEmpty()) return null;
            for (BackupEntry existing : listSavedBackups()) {
                String existingRaw = readBackupFile(existing.file);
                JSONObject existingRoot = new JSONObject(existingRaw);
                if (incomingChecksum.equalsIgnoreCase(existingRoot.optString("checksumSha256", ""))) {
                    return existing;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @NonNull
    public String readBackupFile(@NonNull File file) {
        return mConfigManager.readFileSafely(file);
    }

    @Nullable
    private BackupEntry parseBackupFileMetadata(@NonNull File file) {
        try {
            String raw = readFileString(file);
            JSONObject root = new JSONObject(raw);
            String label = root.optString("label", file.getName());
            long createdAt = root.optLong("createdAtMs", file.lastModified());
            JSONObject payload = root.optJSONObject("payload");
            int wsCount = payload != null && payload.optJSONArray("workspaces") != null
                ? payload.optJSONArray("workspaces").length() : 0;
            int profCount = payload != null && payload.optJSONArray("profiles") != null
                ? payload.optJSONArray("profiles").length() : 0;
            int snipCount = payload != null && payload.optJSONArray("snippets") != null
                ? payload.optJSONArray("snippets").length() : 0;
            return new BackupEntry(file, label, createdAt, file.length(), wsCount, profCount, snipCount);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates backup format, schema version compatibility, and checksum, and produces a
     * non-destructive RestorePreviewReport before any file is modified.
     */
    @NonNull
    public RestorePreviewReport validateAndPreview(@Nullable String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return invalidReport("Backup content is empty.");
        }
        try {
            JSONObject root = new JSONObject(rawJson.trim());
            String format = root.optString("format", "");
            if (!"termux_ai_backup_v2".equals(format) && !"termux_ai_backup_v1".equals(format)) {
                return invalidReport("Unrecognized backup format: " + format);
            }
            int schemaVersion = root.optInt("schemaVersion", -1);
            if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION || schemaVersion > CURRENT_SCHEMA_VERSION) {
                return invalidReport("Unsupported backup schema version: " + schemaVersion);
            }
            JSONObject payload = root.optJSONObject("payload");
            if (payload == null) {
                return invalidReport("Backup archive is missing payload object.");
            }

            String expectedChecksum = root.optString("checksumSha256", "");
            boolean checksumOk = true;
            if (!expectedChecksum.isEmpty()) {
                String actualChecksum = sha256Hex(payload.toString());
                checksumOk = expectedChecksum.equalsIgnoreCase(actualChecksum);
                if (!checksumOk) {
                    return invalidReport("Backup SHA-256 integrity checksum mismatch.");
                }
            }

            String label = root.optString("label", "Imported Backup");
            long createdAt = root.optLong("createdAtMs", System.currentTimeMillis());

            int wsCount = payload.optJSONArray("workspaces") != null
                ? payload.optJSONArray("workspaces").length() : 0;
            int profCount = payload.optJSONArray("profiles") != null
                ? payload.optJSONArray("profiles").length() : 0;
            int snipCount = payload.optJSONArray("snippets") != null
                ? payload.optJSONArray("snippets").length() : 0;

            List<String> shellFiles = new ArrayList<>();
            List<String> overwrites = new ArrayList<>();
            JSONObject shellConfigs = payload.optJSONObject("shellConfigs");
            if (shellConfigs != null) {
                if (shellConfigs.has("bashrc")) {
                    shellFiles.add("~/.bashrc");
                    if (mConfigManager.getBashRcFile().exists()) overwrites.add("~/.bashrc");
                }
                if (shellConfigs.has("zshrc")) {
                    shellFiles.add("~/.zshrc");
                    if (mConfigManager.getZshRcFile().exists()) overwrites.add("~/.zshrc");
                }
                if (shellConfigs.has("config_fish")) {
                    shellFiles.add("~/.config/fish/config.fish");
                    if (mConfigManager.getFishConfigFile().exists()) overwrites.add("~/.config/fish/config.fish");
                }
            }

            JSONObject termuxFiles = payload.optJSONObject("termuxFiles");
            boolean hasColors = termuxFiles != null && termuxFiles.has("colors_properties");
            if (hasColors && mConfigManager.getColorsPropertiesFile().exists()) {
                overwrites.add("~/.termux/colors.properties");
            }
            boolean hasTermuxProps = termuxFiles != null && termuxFiles.has("termux_properties");
            if (hasTermuxProps && mConfigManager.getTermuxPropertiesFile().exists()) {
                overwrites.add("~/.termux/termux.properties");
            }

            boolean hasTermPrefs = payload.optJSONObject("terminalPreferences") != null;
            boolean hasAcPrefs = payload.optJSONObject("autocompletePreferences") != null;
            boolean hasAiPrefs = payload.optJSONObject("aiPreferences") != null;

            return new RestorePreviewReport(
                true,
                "Valid Termux AI backup archive (v" + schemaVersion + ")",
                schemaVersion,
                label,
                createdAt,
                checksumOk,
                wsCount,
                profCount,
                snipCount,
                shellFiles,
                hasColors,
                hasTermuxProps,
                hasTermPrefs,
                hasAcPrefs,
                hasAiPrefs,
                overwrites
            );
        } catch (Exception e) {
            return invalidReport("Malformed backup JSON: " + e.getMessage());
        }
    }

    @NonNull
    private static RestorePreviewReport invalidReport(@NonNull String reason) {
        return new RestorePreviewReport(
            false, reason, 0, "", 0L, false, 0, 0, 0,
            new ArrayList<>(), false, false, false, false, false, new ArrayList<>()
        );
    }

    /**
     * Restores selected sections from a validated backup JSON after taking an automatic safety snapshot.
     */
    public synchronized boolean restoreBackup(
        @NonNull String rawJson,
        @NonNull RestoreOptions options
    ) {
        RestorePreviewReport report = validateAndPreview(rawJson);
        if (!report.valid) return false;

        try {
            // Take a pre-restore safety backup first so nothing is ever lost
            try {
                createBackup("Pre-Restore Safety Snapshot");
            } catch (Exception ignored) {}

            JSONObject root = new JSONObject(rawJson.trim());
            JSONObject payload = root.getJSONObject("payload");

            // 1. Profiles
            if (options.restoreProfiles && payload.optJSONArray("profiles") != null) {
                JSONArray arr = payload.getJSONArray("profiles");
                List<TerminalProfile> profiles = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        try { profiles.add(TerminalProfile.fromJson(o)); } catch (JSONException ignored) {}
                    }
                }
                if (!profiles.isEmpty()) {
                    mProfileRepo.replaceAllProfiles(profiles, payload.optString("activeProfileId", null));
                }
            }

            // 2. Workspaces
            if (options.restoreWorkspaces && payload.optJSONArray("workspaces") != null) {
                JSONArray arr = payload.getJSONArray("workspaces");
                List<WorkspaceItem> workspaces = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        try { workspaces.add(WorkspaceItem.fromJson(o)); } catch (JSONException ignored) {}
                    }
                }
                if (!workspaces.isEmpty()) {
                    mWorkspaceRepo.replaceAllWorkspaces(workspaces, payload.optString("activeWorkspaceId", null));
                }
            }

            // 3. Snippets
            if (options.restoreSnippets && payload.optJSONArray("snippets") != null) {
                JSONArray arr = payload.getJSONArray("snippets");
                List<CommandSnippet> snippets = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        try { snippets.add(CommandSnippet.fromJson(o)); } catch (JSONException ignored) {}
                    }
                }
                mSnippetRepo.replaceAllSnippets(snippets);
            }

            // 4. Shell config files (with atomic backup in ~/.termux/studio_backups)
            if (options.restoreShellConfigs) {
                JSONObject shellConfigs = payload.optJSONObject("shellConfigs");
                if (shellConfigs != null) {
                    if (shellConfigs.has("bashrc")) {
                        mConfigManager.writeFileAtomicallyWithBackup(
                            mConfigManager.getBashRcFile(), shellConfigs.getString("bashrc"));
                    }
                    if (shellConfigs.has("zshrc")) {
                        mConfigManager.writeFileAtomicallyWithBackup(
                            mConfigManager.getZshRcFile(), shellConfigs.getString("zshrc"));
                    }
                    if (shellConfigs.has("config_fish")) {
                        mConfigManager.writeFileAtomicallyWithBackup(
                            mConfigManager.getFishConfigFile(), shellConfigs.getString("config_fish"));
                    }
                }
            }

            // 5. Termux theme & properties
            if (options.restoreThemeAndProperties) {
                JSONObject termuxFiles = payload.optJSONObject("termuxFiles");
                if (termuxFiles != null) {
                    if (termuxFiles.has("colors_properties")) {
                        mConfigManager.writeFileAtomicallyWithBackup(
                            mConfigManager.getColorsPropertiesFile(), termuxFiles.getString("colors_properties"));
                    }
                    if (termuxFiles.has("termux_properties")) {
                        mConfigManager.writeFileAtomicallyWithBackup(
                            mConfigManager.getTermuxPropertiesFile(), termuxFiles.getString("termux_properties"));
                    }
                    String defShell = termuxFiles.optString("defaultShell", "");
                    if (!defShell.isEmpty()) {
                        mConfigManager.switchDefaultShell(defShell);
                    }
                }
            }

            // 6. Preferences (Terminal, Autocomplete, AI non-secret settings)
            if (options.restorePreferences) {
                JSONObject termPrefs = payload.optJSONObject("terminalPreferences");
                TermuxAppSharedPreferences appPrefs = TermuxAppSharedPreferences.build(mContext, false);
                if (termPrefs != null && appPrefs != null) {
                    if (termPrefs.has("fontSize")) appPrefs.setFontSize(termPrefs.getInt("fontSize"));
                    if (termPrefs.has("showTerminalToolbar")) appPrefs.setShowTerminalToolbar(termPrefs.getBoolean("showTerminalToolbar"));
                    if (termPrefs.has("keepScreenOn")) appPrefs.setKeepScreenOn(termPrefs.getBoolean("keepScreenOn"));
                    if (termPrefs.has("softKeyboardEnabled")) appPrefs.setSoftKeyboardEnabled(termPrefs.getBoolean("softKeyboardEnabled"));
                    if (termPrefs.has("terminalMarginAdjustment")) appPrefs.setTerminalMarginAdjustment(termPrefs.getBoolean("terminalMarginAdjustment"));
                }

                JSONObject acJson = payload.optJSONObject("autocompletePreferences");
                if (acJson != null) {
                    AutocompleteSettingsManager ac = new AutocompleteSettingsManager(mContext);
                    if (acJson.has("enabled")) ac.setEnabled(acJson.getBoolean("enabled"));
                    if (acJson.has("ghostTextEnabled")) ac.setGhostTextEnabled(acJson.getBoolean("ghostTextEnabled"));
                    if (acJson.has("dropdownEnabled")) ac.setDropdownEnabled(acJson.getBoolean("dropdownEnabled"));
                    if (acJson.has("maxSuggestions")) ac.setMaxSuggestions(acJson.getInt("maxSuggestions"));
                    if (acJson.has("historyEnabled")) ac.setHistoryEnabled(acJson.getBoolean("historyEnabled"));
                    if (acJson.has("filesEnabled")) ac.setFileSuggestionsEnabled(acJson.getBoolean("filesEnabled"));
                    if (acJson.has("packagesEnabled")) ac.setPackageSuggestionsEnabled(acJson.getBoolean("packagesEnabled"));
                    if (acJson.has("acceptKey")) ac.setAcceptKey(acJson.getString("acceptKey"));
                }

                JSONObject aiJson = payload.optJSONObject("aiPreferences");
                if (aiJson != null) {
                    AISettingsManager ai = new AISettingsManager(mContext);
                    if (aiJson.has("activeProvider")) {
                        ai.setActiveProvider(AIProviderType.fromId(aiJson.getString("activeProvider")));
                    }
                    if (aiJson.has("builtinModel")) {
                        ai.setModelForProvider(AIProviderType.BUILTIN, aiJson.getString("builtinModel"));
                    }
                    if (aiJson.has("geminiModel")) {
                        ai.setModelForProvider(AIProviderType.GEMINI, aiJson.getString("geminiModel"));
                    }
                    if (aiJson.has("openrouterModel")) {
                        ai.setModelForProvider(AIProviderType.OPENROUTER, aiJson.getString("openrouterModel"));
                    }
                    if (aiJson.has("temperature")) {
                        ai.setTemperature((float) aiJson.getDouble("temperature"));
                    }
                    if (aiJson.has("maxOutputTokens")) {
                        ai.setMaxOutputTokens(aiJson.getInt("maxOutputTokens"));
                    }
                    if (aiJson.has("streamingEnabled")) {
                        ai.setStreamingEnabled(aiJson.getBoolean("streamingEnabled"));
                    }
                    if (aiJson.has("explicitFallbackEnabled")) {
                        ai.setExplicitFallbackEnabled(aiJson.getBoolean("explicitFallbackEnabled"));
                    }
                    if (aiJson.has("includeCwd")) ai.setIncludeCwdEnabled(aiJson.getBoolean("includeCwd"));
                    if (aiJson.has("includeShell")) ai.setIncludeShellEnabled(aiJson.getBoolean("includeShell"));
                    if (aiJson.has("includeLastCommand")) ai.setIncludeLastCommandEnabled(aiJson.getBoolean("includeLastCommand"));
                    if (aiJson.has("includeRecentOutput")) ai.setIncludeRecentOutputEnabled(aiJson.getBoolean("includeRecentOutput"));
                    if (aiJson.has("includeTools")) ai.setIncludeToolsEnabled(aiJson.getBoolean("includeTools"));
                }
            }

            try {
                TermuxActivity.updateTermuxActivityStyling(mContext, false);
            } catch (Exception ignored) {}
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    private static String readFileString(@NonNull File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    public static String sha256Hex(@NonNull String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
