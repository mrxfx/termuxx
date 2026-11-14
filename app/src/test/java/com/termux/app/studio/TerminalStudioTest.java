package com.termux.app.studio;

import android.content.Intent;

import com.termux.R;
import com.termux.app.activities.CommandHistoryActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.StorageMaintenanceFragment;
import com.termux.terminal.TerminalColors;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TerminalStudioTest {

    @Rule
    public final TemporaryFolder mTempFolder = new TemporaryFolder();

    @Test
    public void testIdempotentManagedBlockUpsertAndRemovalPreservesUserLines() throws Exception {
        File home = mTempFolder.newFolder("home");
        File prefix = mTempFolder.newFolder("usr");
        ShellConfigManager mgr = new ShellConfigManager(home, prefix);
        File bashrc = mgr.getBashRcFile();

        String initialUserContent = "# My custom bash settings\nalias ll='ls -la'\nexport EDITOR=nano\n";
        assertTrue(mgr.writeFileAtomicallyWithBackup(bashrc, initialUserContent));

        assertTrue(mgr.upsertModuleInRc(bashrc, StudioShellCustomizer.MODULE_PROMPT_THEME, "PS1='first> '"));
        assertTrue(mgr.upsertModuleInRc(bashrc, StudioShellCustomizer.MODULE_PROMPT_THEME, "PS1='second> '"));

        String afterSecond = mgr.readFileSafely(bashrc);
        assertTrue(afterSecond.contains("alias ll='ls -la'"));
        assertTrue(afterSecond.contains("export EDITOR=nano"));
        assertFalse(afterSecond.contains("PS1='first> '"));
        assertTrue(afterSecond.contains("PS1='second> '"));

        // Marker appears exactly once (idempotent)
        String marker = ShellConfigManager.beginMarker(StudioShellCustomizer.MODULE_PROMPT_THEME);
        assertEquals(afterSecond.indexOf(marker), afterSecond.lastIndexOf(marker));

        assertTrue(mgr.removeModuleFromRc(bashrc, StudioShellCustomizer.MODULE_PROMPT_THEME));
        String afterRemove = mgr.readFileSafely(bashrc);
        assertFalse(afterRemove.contains(marker));
        assertFalse(afterRemove.contains("PS1='second> '"));
        assertTrue(afterRemove.contains("alias ll='ls -la'"));
        assertTrue(afterRemove.contains("export EDITOR=nano"));
    }

    @Test
    public void testAtomicBackupAndRestoreLatestBackup() throws Exception {
        File home = mTempFolder.newFolder("home_bak");
        File prefix = mTempFolder.newFolder("usr_bak");
        ShellConfigManager mgr = new ShellConfigManager(home, prefix);
        File colors = mgr.getColorsPropertiesFile();

        assertTrue(mgr.writeFileAtomicallyWithBackup(colors, "background=#000000\n"));
        Thread.sleep(5);
        assertTrue(mgr.writeFileAtomicallyWithBackup(colors, "background=#123456\n"));

        assertEquals("background=#123456\n", mgr.readFileSafely(colors));
        assertTrue(mgr.restoreLatestBackup(colors));
        assertEquals("background=#000000\n", mgr.readFileSafely(colors));
        assertFalse(mgr.listAllBackups().isEmpty());
    }

    @Test
    public void testUpsertTermuxPropertyPreservesExistingSettings() throws Exception {
        File home = mTempFolder.newFolder("home_props");
        File prefix = mTempFolder.newFolder("usr_props");
        ShellConfigManager mgr = new ShellConfigManager(home, prefix);

        File propsFile = mgr.getTermuxPropertiesFile();
        assertTrue(mgr.writeFileAtomicallyWithBackup(propsFile, "bell-character=ignore\n# Comment\nextra-keys = [['ESC']]\n"));
        assertTrue(mgr.upsertTermuxProperty("extra-keys", "[['ESC','TAB']]"));

        String updated = mgr.readFileSafely(propsFile);
        assertTrue(updated.contains("bell-character=ignore"));
        assertTrue(updated.contains("# Comment"));
        assertEquals("[['ESC','TAB']]", mgr.readTermuxProperty("extra-keys"));
    }

    @Test
    public void testAllColorThemesParseIntoTerminalColorScheme() throws Exception {
        List<StudioColorThemes.ColorThemePreset> presets = StudioColorThemes.getPresets();
        assertEquals(6, presets.size());
        for (StudioColorThemes.ColorThemePreset preset : presets) {
            Properties props = new Properties();
            props.load(new StringReader(preset.propertiesContent));
            assertNotNull("Missing background for " + preset.id, props.getProperty("background"));
            assertNotNull("Missing foreground for " + preset.id, props.getProperty("foreground"));
            TerminalColors.COLOR_SCHEME.updateWith(props);
        }
    }

    @Test
    public void testBannerEscapesShellInjectionPayloads() throws Exception {
        File home = mTempFolder.newFolder("home_banner");
        File prefix = mTempFolder.newFolder("usr_banner");
        ShellConfigManager mgr = new ShellConfigManager(home, prefix);
        StudioShellCustomizer customizer = new StudioShellCustomizer(mgr, new StudioPackageHelper(mgr));

        String malicious = "dev$(rm -rf /) `id` 'test'";
        assertTrue(customizer.applyBanner("bash", malicious, StudioShellCustomizer.BannerStyle.MINIMAL_BOX));
        String bashrc = mgr.readFileSafely(mgr.getBashRcFile());
        // Must use single-quoted heredoc delimiter 'TERMUX_AI_BANNER_EOF' so $() and `` never expand
        assertTrue(bashrc.contains("cat << 'TERMUX_AI_BANNER_EOF'"));

        assertTrue(customizer.applyBanner("fish", malicious, StudioShellCustomizer.BannerStyle.COMPACT_TAG));
        String fishRc = mgr.readFileSafely(mgr.getFishConfigFile());
        assertTrue(fishRc.contains("printf '%s\\n'"));
    }

    @Test
    public void testPromptThemesAndSaltedSha256ShellLockLifecycle() throws Exception {
        File home = mTempFolder.newFolder("home_lock");
        File prefix = mTempFolder.newFolder("usr_lock");
        ShellConfigManager mgr = new ShellConfigManager(home, prefix);
        StudioShellCustomizer customizer = new StudioShellCustomizer(mgr, new StudioPackageHelper(mgr));

        assertFalse(customizer.isShellLockEnabled());
        assertTrue(customizer.applyPromptTheme("bash", "dev_git"));
        assertTrue(customizer.applyPromptTheme("zsh", "clean_mono"));
        assertTrue(customizer.applyPromptTheme("fish", "two_line_box"));

        String secret = "S3curePass!";
        assertTrue(customizer.enableShellLock(secret));
        assertTrue(customizer.isShellLockEnabled());

        File lockFile = customizer.getLockMetadataFile();
        assertTrue(lockFile.exists());
        String rawMeta = mgr.readFileSafely(lockFile);
        assertFalse("Plaintext passcode must never be saved", rawMeta.contains(secret));
        JSONObject json = new JSONObject(rawMeta);
        assertEquals("SHA-256", json.getString("algo"));
        String salt = json.getString("salt");
        String hash = json.getString("hash");
        assertEquals(StudioShellCustomizer.sha256Hex(salt + secret), hash);

        String bashRc = mgr.readFileSafely(mgr.getBashRcFile());
        assertFalse("Plaintext passcode must never be in rc", bashRc.contains(secret));
        assertTrue(bashRc.contains("[ -t 0 ] && [ -z \"$SSH_CONNECTION\" ]"));

        assertTrue(customizer.disableShellLock());
        assertFalse(customizer.isShellLockEnabled());
        assertFalse(lockFile.exists());
        assertFalse(ShellConfigManager.hasManagedBlock(mgr.readFileSafely(mgr.getBashRcFile()), StudioShellCustomizer.MODULE_CYBER_LOCK));
    }

    @Test
    public void testTerminalStudioAndCommandHistoryAndStorageSettingsLaunchUnderRobolectric() {
        try (ActivityController<TerminalStudioActivity> studioController =
                 Robolectric.buildActivity(TerminalStudioActivity.class)) {
            TerminalStudioActivity studio = studioController.create().start().resume().get();
            assertNotNull(studio.findViewById(R.id.studio_shell_list_container));
            assertNotNull(studio.findViewById(R.id.studio_color_themes_container));
            assertNotNull(studio.findViewById(R.id.studio_prompt_themes_container));
        }

        try (ActivityController<CommandHistoryActivity> historyController =
                 Robolectric.buildActivity(CommandHistoryActivity.class)) {
            CommandHistoryActivity history = historyController.create().start().resume().get();
            assertNotNull(history.findViewById(R.id.history_search_input));
            assertNotNull(history.findViewById(R.id.history_recycler));
        }

        Intent storageIntent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class);
        storageIntent.putExtra(SettingsActivity.EXTRA_INITIAL_FRAGMENT,
            "com.termux.app.fragments.settings.StorageMaintenanceFragment");
        try (ActivityController<SettingsActivity> settingsController =
                 Robolectric.buildActivity(SettingsActivity.class, storageIntent)) {
            SettingsActivity settings = settingsController.create().start().resume().get();
            assertTrue(settings.getSupportFragmentManager().findFragmentById(R.id.settings)
                instanceof StorageMaintenanceFragment);
        }
    }
}
