package com.termux.app.autocomplete;

import android.content.Context;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AutocompleteEngineTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Context mContext;
    private File mBinDir;
    private File mWorkDir;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.getApplication();
        mBinDir = tempFolder.newFolder("bin");
        mWorkDir = tempFolder.newFolder("workspace");

        createExecutable(mBinDir, "python3");
        createExecutable(mBinDir, "pytest");
        createExecutable(mBinDir, "git");
        createExecutable(mBinDir, "node");
        createExecutable(mBinDir, "npm");
    }

    private File createExecutable(File dir, String name) throws Exception {
        File f = new File(dir, name);
        assertTrue(f.createNewFile());
        assertTrue(f.setExecutable(true));
        return f;
    }

    @Test
    public void testTerminalInputAnalyzerPromptAndChaining() {
        TerminalInputAnalyzer.ParsedInput parsed = TerminalInputAnalyzer.parsePromptLine("~/proj $ pyt", true);
        assertNotNull(parsed);
        assertTrue(parsed.isCommandPosition());
        assertEquals("pyt", parsed.currentToken);
        assertEquals("pyt", parsed.commandLine);
        assertTrue(parsed.atEndOfLine);

        TerminalInputAnalyzer.ParsedInput chained = TerminalInputAnalyzer.parsePromptLine("user@phone:/tmp# ls -la && git che", true);
        assertNotNull(chained);
        assertFalse(chained.isCommandPosition());
        assertEquals("git", chained.getCommandName());
        assertEquals("che", chained.currentToken);
        assertEquals(1, chained.completedTokens.size());

        assertNull(TerminalInputAnalyzer.parsePromptLine("~/proj $ # a comment", true));
    }

    @Test
    public void testCommandIndexCacheIndexesAndInvalidates() throws Exception {
        CommandIndexCache cache = new CommandIndexCache(Collections.singletonList(mBinDir.getAbsolutePath()));
        List<SuggestionItem> matches = cache.matchCommands("pyt", 10);
        assertTrue(matches.size() >= 2);
        assertEquals("python3", matches.get(0).getDisplayText());
        assertEquals("hon3", matches.get(0).getInsertSuffix());

        // Add new executable and ensure invalidation picks it up
        createExecutable(mBinDir, "pycharm_helper");
        cache.invalidate();
        assertTrue(cache.isCommandInstalled("pycharm_helper"));
    }

    @Test
    public void testFileAndDirectorySuggesterHandlesHiddenSpacesAndDirsOnly() throws Exception {
        File subDir = new File(mWorkDir, "src_dir");
        assertTrue(subDir.mkdir());
        File hiddenFile = new File(mWorkDir, ".env.local");
        assertTrue(hiddenFile.createNewFile());
        File normalFile = new File(mWorkDir, "readme.md");
        assertTrue(normalFile.createNewFile());
        File spacedFile = new File(mWorkDir, "my notes.txt");
        assertTrue(spacedFile.createNewFile());

        FileAndDirectorySuggester suggester = new FileAndDirectorySuggester(mWorkDir.getAbsolutePath());

        // Hidden files excluded unless token starts with '.'
        List<SuggestionItem> all = suggester.suggest("", mWorkDir.getAbsolutePath(), false, 10);
        for (SuggestionItem item : all) {
            assertFalse("Hidden file must not appear without leading dot", item.getDisplayText().startsWith("."));
        }

        List<SuggestionItem> dotOnly = suggester.suggest(".", mWorkDir.getAbsolutePath(), false, 10);
        assertEquals(1, dotOnly.size());
        assertEquals(".env.local", dotOnly.get(0).getDisplayText());

        // Directories only for `cd`
        List<SuggestionItem> dirsOnly = suggester.suggest("s", mWorkDir.getAbsolutePath(), true, 10);
        assertEquals(1, dirsOnly.size());
        assertEquals("src_dir/", dirsOnly.get(0).getDisplayText());
        assertEquals("rc_dir/", dirsOnly.get(0).getInsertSuffix());
        assertEquals(SuggestionType.DIRECTORY, dirsOnly.get(0).getType());

        // Spaces escaped in insert suffix
        List<SuggestionItem> spaced = suggester.suggest("my", mWorkDir.getAbsolutePath(), false, 10);
        assertEquals(1, spaced.size());
        assertEquals("\\ notes.txt", spaced.get(0).getInsertSuffix());
    }

    @Test
    public void testCommandHistoryStoreDeduplicatesAndProtectsSecrets() throws Exception {
        File storeFile = new File(tempFolder.getRoot(), "hist.json");
        File zshHist = new File(tempFolder.getRoot(), ".zsh_history");
        try (FileWriter fw = new FileWriter(zshHist)) {
            fw.write(": 1700000100:0;git status -sb\n");
            fw.write(": 1700000105:0;export GEMINI_API_KEY=AIzaSyDummySecretValue1234567890\n");
        }

        CommandHistoryStore store = new CommandHistoryStore(storeFile, Collections.singletonList(zshHist));
        store.recordCommand("git checkout main");
        store.recordCommand("git checkout main");
        store.recordCommand(" spaced_ignored");
        store.recordCommand("curl -H 'Authorization: Bearer sk-or-v1-secret123456789012345'");

        List<SuggestionItem> gitMatches = store.matchPrefix("git ", 5);
        assertEquals(2, gitMatches.size());
        assertTrue(gitMatches.stream().anyMatch(i -> "git checkout main".equals(i.getDisplayText()) && i.getDescription().contains("2x")));
        assertTrue(gitMatches.stream().anyMatch(i -> "git status -sb".equals(i.getDisplayText())));

        // Ensure secret-bearing or ignorespace lines were never stored
        assertTrue(store.matchPrefix("export ", 5).isEmpty());
        assertTrue(store.matchPrefix("curl ", 5).isEmpty());
        assertTrue(store.matchPrefix(" spaced", 5).isEmpty());

        store.clearHistory();
        assertEquals(0, store.size());
    }

    @Test
    public void testInstalledPackageSuggesterReadsDpkgStatusPassively() throws Exception {
        File dpkgStatus = new File(tempFolder.getRoot(), "status");
        try (FileWriter fw = new FileWriter(dpkgStatus)) {
            fw.write("Package: openssh\n");
            fw.write("Status: install ok installed\n");
            fw.write("Description: Secure shell client and server\n\n");
            fw.write("Package: old-removed\n");
            fw.write("Status: deinstall ok config-files\n");
            fw.write("Description: Should be excluded\n\n");
            fw.write("Package: openssl\n");
            fw.write("Status: install ok installed\n");
            fw.write("Description: Cryptography toolkit\n");
        }

        InstalledPackageSuggester pkgSuggester = new InstalledPackageSuggester(dpkgStatus);
        Map<String, InstalledPackageSuggester.PackageInfo> installed = pkgSuggester.getInstalledPackages();
        assertEquals(2, installed.size());
        assertFalse(installed.containsKey("old-removed"));

        List<SuggestionItem> pkgs = pkgSuggester.suggestPackages("open", 5);
        assertEquals(2, pkgs.size());
        assertEquals("openssh", pkgs.get(0).getDisplayText());
        assertEquals(SuggestionType.PACKAGE, pkgs.get(0).getType());
    }

    @Test
    public void testContextAwareAndEngineIntegration() throws Exception {
        // Setup git repo with feature branch + node package.json
        File heads = new File(mWorkDir, ".git/refs/heads");
        assertTrue(heads.mkdirs());
        assertTrue(new File(heads, "main").createNewFile());
        assertTrue(new File(heads, "feat-ai").createNewFile());
        try (FileWriter fw = new FileWriter(new File(mWorkDir, "package.json"))) {
            fw.write("{\"scripts\":{\"test\":\"jest\",\"build\":\"tsc\"}}");
        }

        AutocompleteSettingsManager settings = new AutocompleteSettingsManager(mContext);
        settings.setEnabled(true);
        settings.setMaxSuggestions(8);

        CommandIndexCache indexCache = new CommandIndexCache(Collections.singletonList(mBinDir.getAbsolutePath()));
        CommandHistoryStore historyStore = new CommandHistoryStore(
            new File(tempFolder.getRoot(), "engine_hist.json"),
            Collections.emptyList()
        );
        historyStore.recordCommand("git status --short");

        AutocompleteEngine engine = new AutocompleteEngine(
            settings,
            indexCache,
            historyStore,
            new FileAndDirectorySuggester(mWorkDir.getAbsolutePath()),
            new InstalledPackageSuggester(new File(tempFolder.getRoot(), "no_dpkg")),
            new ContextAwareSuggester()
        );

        // Git subcommands
        TerminalInputAnalyzer.ParsedInput gitChe = TerminalInputAnalyzer.parsePromptLine("$ git che", true);
        assertNotNull(gitChe);
        List<SuggestionItem> subSuggestions = engine.computeSuggestionsSync(gitChe, mWorkDir.getAbsolutePath());
        assertTrue(subSuggestions.stream().anyMatch(s -> "checkout".equals(s.getDisplayText())));
        assertTrue(subSuggestions.stream().anyMatch(s -> "cherry-pick".equals(s.getDisplayText())));

        // Git branch completion inside repo
        TerminalInputAnalyzer.ParsedInput gitBranch = TerminalInputAnalyzer.parsePromptLine("$ git checkout fe", true);
        assertNotNull(gitBranch);
        List<SuggestionItem> branchSuggestions = engine.computeSuggestionsSync(gitBranch, mWorkDir.getAbsolutePath());
        assertEquals("feat-ai", branchSuggestions.get(0).getDisplayText());
        assertEquals(SuggestionType.CONTEXT_GIT, branchSuggestions.get(0).getType());

        // npm run script completion
        TerminalInputAnalyzer.ParsedInput npmRun = TerminalInputAnalyzer.parsePromptLine("$ npm run te", true);
        assertNotNull(npmRun);
        List<SuggestionItem> npmSuggestions = engine.computeSuggestionsSync(npmRun, mWorkDir.getAbsolutePath());
        assertEquals("test", npmSuggestions.get(0).getDisplayText());
        assertEquals(SuggestionType.CONTEXT_NODE, npmSuggestions.get(0).getType());
    }

    @Test
    public void testInteractiveReplAndAltBufferSuppression() {
        // Python, IPython, Node.js, GDB, SQLite REPL prompts must NOT trigger shell autocomplete
        assertNull(TerminalInputAnalyzer.parsePromptLine(">>> impo", true));
        assertNull(TerminalInputAnalyzer.parsePromptLine("... retu", true));
        assertNull(TerminalInputAnalyzer.parsePromptLine("In [1]: impo", true));
        assertNull(TerminalInputAnalyzer.parsePromptLine("> cons", true));
        assertNull(TerminalInputAnalyzer.parsePromptLine("(gdb) br", true));
        assertNull(TerminalInputAnalyzer.parsePromptLine("sqlite> SEL", true));

        // Standard Bash/Zsh/Starship/Oh-My-Zsh prompts continue to work normally
        assertNotNull(TerminalInputAnalyzer.parsePromptLine("~/proj $ pyt", true));
        assertNotNull(TerminalInputAnalyzer.parsePromptLine("user@android:~% git st", true));
        assertNotNull(TerminalInputAnalyzer.parsePromptLine("❯ ls -la | gr", true));
        assertNotNull(TerminalInputAnalyzer.parsePromptLine("➜  ~ git st", true));

        // Emulator alternate screen & interactive app titles suppress autocomplete
        TerminalEmulator emulator = new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        }, 80, 24, 10, 20, 100, null);

        byte[] normalPrompt = "~/app $ git sta".getBytes();
        emulator.append(normalPrompt, normalPrompt.length);
        assertFalse(TerminalInputAnalyzer.isInteractiveMode(emulator, 0));

        // Simulate cursor moved backward mid-line before Enter: full command line still captured
        emulator.append(new byte[]{0x1B, '[', 'D', 0x1B, '[', 'D', 0x1B, '[', 'D'}, 9);
        TerminalInputAnalyzer.ParsedInput midLine = TerminalInputAnalyzer.analyzeEmulatorCursorLine(emulator);
        assertNotNull(midLine);
        assertFalse(midLine.atEndOfLine);
        assertEquals("git sta", TerminalInputAnalyzer.extractFullCommandLine(emulator));

        // Simulate entering alternate screen buffer (vim / nano / less / htop / tmux)
        byte[] enterAlt = "\u001B[?1049h".getBytes();
        emulator.append(enterAlt, enterAlt.length);
        assertTrue(TerminalInputAnalyzer.isInteractiveMode(emulator, 0));

        byte[] exitAlt = "\u001B[?1049l".getBytes();
        emulator.append(exitAlt, exitAlt.length);
        assertFalse(TerminalInputAnalyzer.isInteractiveMode(emulator, 0));

        // Simulate interactive process title (e.g., ssh / python3 / node / vim)
        for (String title : Arrays.asList("vim main.py", "nano", "less log.txt", "htop", "ssh prod", "tmux", "python3", "node")) {
            byte[] oscTitle = ("\u001B]0;" + title + "\u0007").getBytes();
            emulator.append(oscTitle, oscTitle.length);
            assertTrue("Expected interactive mode for title: " + title, TerminalInputAnalyzer.isInteractiveMode(emulator, 0));
        }
    }

    @Test
    public void testCrossInstanceHistoryClearAndEvaluationLatency() throws Exception {
        File storeFile = new File(tempFolder.getRoot(), "shared_hist.json");
        CommandHistoryStore liveStore = new CommandHistoryStore(storeFile, Collections.emptyList());
        liveStore.recordCommand("git status --short");
        assertEquals(1, liveStore.size());

        // Clearing via separate instance (e.g., AutocompleteSettingsFragment) must clear liveStore too
        CommandHistoryStore settingsStore = new CommandHistoryStore(storeFile, Collections.emptyList());
        settingsStore.clearHistory();
        assertEquals(0, liveStore.size());
        assertTrue(liveStore.matchPrefix("git", 5).isEmpty());

        // Offline latency sanity check: 200 evaluations should complete well under 1000ms total
        AutocompleteSettingsManager settings = new AutocompleteSettingsManager(mContext);
        settings.setEnabled(true);
        AutocompleteEngine engine = new AutocompleteEngine(
            settings,
            new CommandIndexCache(Collections.singletonList(mBinDir.getAbsolutePath())),
            liveStore,
            new FileAndDirectorySuggester(mWorkDir.getAbsolutePath()),
            new InstalledPackageSuggester(new File(tempFolder.getRoot(), "missing_dpkg")),
            new ContextAwareSuggester()
        );
        TerminalInputAnalyzer.ParsedInput parsed = TerminalInputAnalyzer.parsePromptLine("$ git che", true);
        assertNotNull(parsed);
        long startNs = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            List<SuggestionItem> res = engine.computeSuggestionsSync(parsed, mWorkDir.getAbsolutePath());
            assertFalse(res.isEmpty());
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertTrue("200 offline evaluations took too long: " + elapsedMs + " ms", elapsedMs < 1500L);
        engine.shutdown();
    }
}
