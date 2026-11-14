package com.termux.app.workspace;

import android.content.Context;
import android.content.Intent;

import com.termux.app.ai.AISettingsManager;
import com.termux.app.ai.DeveloperAssistantHelper;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.autocomplete.AutocompleteSettingsManager;
import com.termux.app.studio.ShellConfigManager;
import com.termux.app.studio.StudioColorThemes;
import com.termux.app.studio.StudioPackageHelper;
import com.termux.app.studio.StudioShellCustomizer;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WorkspaceSystemTest {

    @Rule
    public TemporaryFolder mTempFolder = new TemporaryFolder();

    private Context mContext;
    private File mFakeHome;
    private File mFakePrefix;
    private ShellConfigManager mConfigManager;
    private ProfileRepository mProfileRepo;
    private WorkspaceRepository mWorkspaceRepo;
    private SnippetRepository mSnippetRepo;
    private BackupArchiveManager mBackupManager;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.getApplication();
        mFakeHome = mTempFolder.newFolder("home");
        mFakePrefix = mTempFolder.newFolder("usr");
        File binDir = new File(mFakePrefix, "bin");
        assertTrue(binDir.mkdirs());
        File bashBin = new File(binDir, "bash");
        File zshBin = new File(binDir, "zsh");
        File fishBin = new File(binDir, "fish");
        assertTrue(bashBin.createNewFile() && bashBin.setExecutable(true));
        assertTrue(zshBin.createNewFile() && zshBin.setExecutable(true));
        assertTrue(fishBin.createNewFile() && fishBin.setExecutable(true));

        mConfigManager = new ShellConfigManager(mFakeHome, mFakePrefix);
        mProfileRepo = new ProfileRepository(mContext, new File(mTempFolder.getRoot(), "profiles.json"));
        mWorkspaceRepo = new WorkspaceRepository(
            mContext,
            new File(mTempFolder.getRoot(), "workspaces.json"),
            mProfileRepo
        );
        mSnippetRepo = new SnippetRepository(new File(mTempFolder.getRoot(), "snippets.json"));
        mBackupManager = new BackupArchiveManager(
            mContext,
            mConfigManager,
            mWorkspaceRepo,
            mProfileRepo,
            mSnippetRepo,
            new File(mTempFolder.getRoot(), "backups")
        );
    }

    @Test
    public void testWorkspaceCreationValidationSwitchAndRestore() throws Exception {
        // 1. Validate that a non-existent directory is rejected and NEVER created automatically
        File fakeNonExistent = new File(mFakeHome, "does_not_exist_project");
        assertFalse(fakeNonExistent.exists());
        WorkspaceRepository.ValidationResult invalidRes =
            WorkspaceRepository.validateDirectory(fakeNonExistent.getAbsolutePath());
        assertFalse(invalidRes.valid);
        assertFalse("validateDirectory must never create fake directories", fakeNonExistent.exists());

        WorkspaceItem invalidItem = new WorkspaceItem(
            "ws_invalid", "Invalid WS", fakeNonExistent.getAbsolutePath(),
            "zsh", "dracula", true, true, true, "profile_dev_zsh", System.currentTimeMillis()
        );
        assertFalse(mWorkspaceRepo.createOrUpdateWorkspace(invalidItem).valid);
        assertNull(mWorkspaceRepo.findById("ws_invalid"));

        // 2. Create a real directory and save workspace with fontSize and environmentNotes
        File realProjectDir = new File(mFakeHome, "real_project");
        assertTrue(realProjectDir.mkdirs());

        WorkspaceItem validItem = new WorkspaceItem(
            "ws_real", "Android Workspace", realProjectDir.getAbsolutePath(),
            "zsh", "nord", 30, false, false, true, "profile_dev_zsh",
            "ANDROID_HOME=~/android-sdk", System.currentTimeMillis()
        );
        WorkspaceRepository.ValidationResult saveRes = mWorkspaceRepo.createOrUpdateWorkspace(validItem);
        assertTrue(saveRes.valid);
        WorkspaceItem loaded = mWorkspaceRepo.findById("ws_real");
        assertNotNull(loaded);
        assertEquals(30, loaded.fontSize);
        assertEquals("ANDROID_HOME=~/android-sdk", loaded.environmentNotes);

        // 3. Rename, duplicate, search, and set default workspace
        WorkspaceItem renamed = mWorkspaceRepo.renameWorkspace("ws_real", "Renamed Android WS");
        assertNotNull(renamed);
        assertEquals("Renamed Android WS", renamed.name);

        WorkspaceItem duplicated = mWorkspaceRepo.duplicateWorkspace("ws_real", "Duplicated Android WS");
        assertNotNull(duplicated);
        assertEquals("Duplicated Android WS", duplicated.name);
        assertEquals(2, mWorkspaceRepo.searchWorkspaces("Android").size());

        mWorkspaceRepo.setDefaultWorkspace("ws_real");
        assertEquals("ws_real", mWorkspaceRepo.getDefaultWorkspaceId());

        // 4. Select & apply workspace, then verify shell, color theme, working directory, and autocomplete persistence
        AutocompleteSettingsManager ac = new AutocompleteSettingsManager(mContext);
        ac.setEnabled(true);
        mConfigManager.switchDefaultShell("bash");

        assertTrue(mWorkspaceRepo.selectAndApplyWorkspace("ws_real", mConfigManager));
        assertEquals("ws_real", mWorkspaceRepo.getActiveWorkspaceId());
        assertEquals("zsh", mConfigManager.detectActiveDefaultShell());
        assertEquals(realProjectDir.getAbsolutePath(), mConfigManager.readTermuxProperty("default-working-directory"));
        assertFalse(ac.isEnabled());
        assertTrue(mConfigManager.readFileSafely(mConfigManager.getColorsPropertiesFile()).toLowerCase().contains("#2e3440")); // Nord bg

        // 5. Restore previous workspace settings snapshot
        assertTrue(mWorkspaceRepo.restorePreviousWorkspaceSettings(mConfigManager));
        assertTrue(ac.isEnabled());
        assertEquals("bash", mConfigManager.detectActiveDefaultShell());

        // 6. Delete workspaces
        assertTrue(mWorkspaceRepo.deleteWorkspace(duplicated.id));
        assertTrue(mWorkspaceRepo.deleteWorkspace("ws_real"));
        assertNull(mWorkspaceRepo.findById("ws_real"));
    }

    @Test
    public void testProfileCrudDuplicateImportExportAndSwitching() throws Exception {
        List<TerminalProfile> initial = mProfileRepo.getAllProfiles();
        assertEquals("Must include all 9 preset developer profiles", 9, initial.size());

        // Create custom profile
        TerminalProfile custom = new TerminalProfile(
            "prof_custom", "Fish Power Profile", "fish", "monokai", 32,
            "underline", 500, "developer", false, false, true, false,
            "powerline_dark", true, true, System.currentTimeMillis()
        );
        mProfileRepo.saveProfile(custom);
        assertNotNull(mProfileRepo.findById("prof_custom"));

        // Duplicate profile
        TerminalProfile copy = mProfileRepo.duplicateProfile("prof_custom", "Fish Copy");
        assertNotNull(copy);
        assertEquals("Fish Copy", copy.name);
        assertEquals("fish", copy.shell);

        // Rename profile
        TerminalProfile renamed = mProfileRepo.renameProfile(copy.id, "Fish Copy Renamed");
        assertNotNull(renamed);
        assertEquals("Fish Copy Renamed", renamed.name);

        // Export & Import JSON round-trip
        String exportedJson = mProfileRepo.exportProfileToJson(custom);
        assertTrue(exportedJson.contains("Fish Power Profile"));
        TerminalProfile imported = mProfileRepo.importProfileFromJson(exportedJson);
        assertNotNull(imported);
        assertEquals("monokai", imported.colorSchemeId);
        assertEquals(32, imported.fontSize);

        // Apply profile and verify persistence across Shell, Colors, TermuxAppSharedPreferences, termux.properties, Autocomplete, and Fish RC
        assertTrue(mProfileRepo.applyProfile(custom, mConfigManager));
        assertEquals("prof_custom", mProfileRepo.getActiveProfileId());
        assertEquals("fish", mConfigManager.detectActiveDefaultShell());
        assertEquals("underline", mConfigManager.readTermuxProperty("terminal-cursor-style"));
        assertEquals("500", mConfigManager.readTermuxProperty("terminal-cursor-blink-rate"));

        TermuxAppSharedPreferences appPrefs = TermuxAppSharedPreferences.build(mContext, false);
        if (appPrefs != null) {
            assertEquals(32, appPrefs.getFontSize());
            assertFalse(appPrefs.shouldShowTerminalToolbar());
        }

        String fishRc = mConfigManager.readFileSafely(mConfigManager.getFishConfigFile());
        assertTrue(fishRc.contains(ShellConfigManager.beginMarker("PROMPT_THEME")));
        assertTrue(fishRc.contains(ShellConfigManager.beginMarker("FISH_SYNTAX")));
        assertTrue(fishRc.contains(ShellConfigManager.beginMarker("FISH_AUTOSUGGEST")));

        // Delete copied profile
        assertTrue(mProfileRepo.deleteProfile(copy.id));
        assertNull(mProfileRepo.findById(copy.id));
    }

    @Test
    public void testCommandSnippetCrudSearchImportExportAndSafeTerminalInsertion() throws Exception {
        assertEquals("Must include all 11 snippet categories", 11, CommandSnippet.CATEGORIES.size());
        List<CommandSnippet> starters = mSnippetRepo.getAllSnippets();
        assertFalse(starters.isEmpty());

        CommandSnippet custom = new CommandSnippet(
            "snip_custom_1", "Check Gradle Daemon", "gradle --status",
            "Gradle", "Inspect active Gradle daemons", "gradle,jvm,daemon",
            true, System.currentTimeMillis()
        );
        mSnippetRepo.saveSnippet(custom);

        List<CommandSnippet> gradleResults = mSnippetRepo.querySnippets("daemon", "Gradle", true);
        assertEquals(1, gradleResults.size());
        assertEquals("snip_custom_1", gradleResults.get(0).id);

        // Duplicate snippet
        CommandSnippet dup = mSnippetRepo.duplicateSnippet("snip_custom_1", "");
        assertNotNull(dup);
        assertTrue(dup.title.contains("Copy"));

        // Export & import snippets JSON
        String exportedJson = mSnippetRepo.exportSnippetsToJson();
        assertTrue(exportedJson.contains("Check Gradle Daemon"));
        int importedCount = mSnippetRepo.importSnippetsFromJson(exportedJson);
        assertTrue(importedCount > 0);

        // Toggle favorite
        CommandSnippet toggled = mSnippetRepo.toggleFavorite("snip_custom_1");
        assertNotNull(toggled);
        assertFalse(toggled.favorite);

        // Verify insertion into TerminalCommandBridge never appends a newline or executes automatically
        TerminalCommandBridge.attachSession(null);
        TerminalCommandBridge.insertIntoActiveTerminal(custom.command + "\n");
        String pending = TerminalCommandBridge.consumePendingInsertCommand();
        assertNotNull(pending);
        assertEquals("gradle --status", pending);
        assertFalse("Inserted snippet must never contain auto-executing trailing newline",
            pending.endsWith("\n") || pending.endsWith("\r"));

        assertTrue(mSnippetRepo.deleteSnippet("snip_custom_1"));
        assertNull(mSnippetRepo.findById("snip_custom_1"));
    }

    @Test
    public void testBackupGenerationValidationDuplicateDetectionRestoreAndSecretExclusion() throws Exception {
        // 1. Seed sensitive data in shell rc files, studio_lock.json, and AI settings
        StudioPackageHelperStub.seedSensitiveShellConfigs(mConfigManager);
        AISettingsManager aiSettings = new AISettingsManager(mContext);
        aiSettings.setActiveProvider(AIProviderType.GEMINI);
        aiSettings.setModelForProvider(AIProviderType.GEMINI, "gemini-2.5-pro");

        // 2. Create backup archive
        BackupArchiveManager.BackupEntry entry = mBackupManager.createBackup("Unit Test Backup");
        assertNotNull(entry);
        assertTrue(entry.file.exists());

        String backupJson = mBackupManager.readBackupFile(entry.file);

        // 3. Verify duplicate detection by SHA-256 payload checksum
        BackupArchiveManager.BackupEntry duplicateFound = mBackupManager.findDuplicateBackup(backupJson);
        assertNotNull("findDuplicateBackup must detect identical payload checksum", duplicateFound);
        assertEquals(entry.file.getName(), duplicateFound.file.getName());

        // 4. Verify strict secret & password exclusion
        assertFalse("Backup must never include OPENAI_API_KEY value",
            backupJson.contains("sk-live-secret1234567890abcdef"));
        assertFalse("Backup must never include GITHUB_TOKEN value",
            backupJson.contains("ghp_verySecretTokenValue987654321"));
        assertFalse("Backup must never include DB_PASSWORD value",
            backupJson.contains("SuperSecretPassword!2026"));
        assertFalse("Backup must never include CYBER_LOCK password block",
            backupJson.contains(ShellConfigManager.beginMarker("CYBER_LOCK")));
        assertTrue("Non-sensitive shell config must be preserved",
            backupJson.contains("alias ll='ls -la'"));

        // 5. Validate & preview backup
        BackupArchiveManager.RestorePreviewReport preview = mBackupManager.validateAndPreview(backupJson);
        assertTrue(preview.valid);
        assertTrue(preview.checksumVerified);
        assertEquals(BackupArchiveManager.CURRENT_SCHEMA_VERSION, preview.schemaVersion);
        assertEquals("Unit Test Backup", preview.label);
        assertTrue(preview.includedShellFiles.contains("~/.bashrc"));

        // 6. Verify checksum tampering is caught
        String tampered = backupJson.replace("Unit Test Backup", "Tampered Label");
        JSONObject tamperedRoot = new JSONObject(tampered);
        tamperedRoot.getJSONObject("payload").put("activeWorkspaceId", "tampered_ws_id");
        BackupArchiveManager.RestorePreviewReport tamperedReport =
            mBackupManager.validateAndPreview(tamperedRoot.toString());
        assertFalse("Tampered payload must fail SHA-256 verification", tamperedReport.valid);

        // 7. Modify state, then restore from backup and verify full round-trip
        mSnippetRepo.replaceAllSnippets(new ArrayList<>());
        assertEquals(0, mSnippetRepo.getAllSnippets().size());
        aiSettings.setActiveProvider(AIProviderType.OPENROUTER);

        BackupArchiveManager.RestoreOptions options = new BackupArchiveManager.RestoreOptions();
        assertTrue(mBackupManager.restoreBackup(backupJson, options));
        assertFalse(mSnippetRepo.getAllSnippets().isEmpty());
        assertEquals(AIProviderType.GEMINI, aiSettings.getActiveProvider());
        assertEquals("gemini-2.5-pro", aiSettings.getModelForProvider(AIProviderType.GEMINI));
    }

    @Test
    public void testV11ColorThemesDeveloperAliasesAndV3DeveloperAssistantSafety() throws Exception {
        // 1. Verify all 6 V1.1 color themes including Gunmetal Neon
        assertEquals(6, StudioColorThemes.getPresets().size());
        assertNotNull(StudioColorThemes.findById("default_dark"));
        assertNotNull(StudioColorThemes.findById("gunmetal_neon"));
        assertNotNull(StudioColorThemes.findById("dracula"));
        assertNotNull(StudioColorThemes.findById("nord"));
        assertNotNull(StudioColorThemes.findById("monokai"));
        assertNotNull(StudioColorThemes.findById("solarized_dark"));

        // 2. Verify idempotent V1.1 developer shell aliases
        StudioShellCustomizer customizer = new StudioShellCustomizer(
            mConfigManager,
            new StudioPackageHelper(mConfigManager)
        );
        assertTrue(customizer.applyDeveloperAliases("zsh"));
        assertTrue(customizer.applyDeveloperAliases("zsh")); // idempotent second call
        String zshRc = mConfigManager.readFileSafely(mConfigManager.getZshRcFile());
        int firstIdx = zshRc.indexOf(ShellConfigManager.beginMarker(StudioShellCustomizer.MODULE_SHELL_ALIASES));
        int lastIdx = zshRc.lastIndexOf(ShellConfigManager.beginMarker(StudioShellCustomizer.MODULE_SHELL_ALIASES));
        assertTrue(firstIdx >= 0);
        assertEquals("Developer aliases marker block must not duplicate", firstIdx, lastIdx);
        assertTrue(customizer.clearDeveloperAliases("zsh"));

        // 3. Verify all 16 V3 Developer AI Workflows exist
        List<DeveloperAssistantHelper.DeveloperWorkflow> workflows = DeveloperAssistantHelper.getWorkflows();
        assertEquals(16, workflows.size());

        // 4. Verify V3 Project Structure Inspector strictly excludes secrets/keystores/.git/.env
        File projectDir = new File(mFakeHome, "sample_app");
        assertTrue(projectDir.mkdirs());
        assertTrue(new File(projectDir, ".git").mkdirs());
        assertTrue(new File(projectDir, "src").mkdirs());
        Files.write(new File(projectDir, "README.md").toPath(), "# Sample".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "build.gradle.kts").toPath(), "plugins {}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, ".env").toPath(), "API_KEY=secret".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "release.keystore").toPath(), "binary".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "id_rsa.key").toPath(), "private".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "local.properties").toPath(), "sdk.dir=/sdk".getBytes(StandardCharsets.UTF_8));

        String safeTree = DeveloperAssistantHelper.inspectSafeProjectStructure(projectDir);
        assertTrue(safeTree.contains("README.md"));
        assertTrue(safeTree.contains("build.gradle.kts"));
        assertTrue(safeTree.contains("src/"));
        assertFalse("Safe project tree must exclude .git", safeTree.contains(".git"));
        assertFalse("Safe project tree must exclude .env", safeTree.contains(".env"));
        assertFalse("Safe project tree must exclude release.keystore", safeTree.contains("release.keystore"));
        assertFalse("Safe project tree must exclude id_rsa.key", safeTree.contains("id_rsa.key"));
        assertFalse("Safe project tree must exclude local.properties", safeTree.contains("local.properties"));

        List<File> selectableFiles = DeveloperAssistantHelper.listSafeSelectableProjectFiles(projectDir);
        assertEquals(2, selectableFiles.size());
    }

    @Test
    public void testAllSixV2ActivitiesLaunchCleanly() throws Exception {
        WorkspaceListActivity wsList = Robolectric.buildActivity(WorkspaceListActivity.class).setup().get();
        assertNotNull(wsList);

        WorkspaceEditorActivity wsEditor = Robolectric.buildActivity(WorkspaceEditorActivity.class).setup().get();
        assertNotNull(wsEditor);

        ProfileManagerActivity profMgr = Robolectric.buildActivity(ProfileManagerActivity.class).setup().get();
        assertNotNull(profMgr);

        CommandSnippetActivity snipAct = Robolectric.buildActivity(CommandSnippetActivity.class).setup().get();
        assertNotNull(snipAct);

        BackupManagerActivity backupMgr = Robolectric.buildActivity(BackupManagerActivity.class).setup().get();
        assertNotNull(backupMgr);

        String sampleBackup = mBackupManager.generateBackupJson("Preview Test");
        Intent previewIntent = new Intent(mContext, RestorePreviewActivity.class);
        previewIntent.putExtra(RestorePreviewActivity.EXTRA_BACKUP_RAW_JSON, sampleBackup);
        RestorePreviewActivity previewAct =
            Robolectric.buildActivity(RestorePreviewActivity.class, previewIntent).setup().get();
        assertNotNull(previewAct);
    }

    private static final class StudioPackageHelperStub {
        static void seedSensitiveShellConfigs(ShellConfigManager cm) {
            String bashRc = "# User bashrc\n"
                + "alias ll='ls -la'\n"
                + "export OPENAI_API_KEY=sk-live-secret1234567890abcdef\n"
                + "export GITHUB_TOKEN=\"ghp_verySecretTokenValue987654321\"\n"
                + "DB_PASSWORD=SuperSecretPassword!2026\n"
                + ShellConfigManager.beginMarker("CYBER_LOCK") + "\n"
                + "echo secret_lock_hash\n"
                + ShellConfigManager.endMarker("CYBER_LOCK") + "\n";
            cm.writeFileAtomicallyWithBackup(cm.getBashRcFile(), bashRc);
        }
    }
}
