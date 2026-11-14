package com.termux.app.ai;

import android.content.Intent;
import android.view.View;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.ui.AIAssistantActivity;
import com.termux.app.ai.ui.AISettingsFragment;
import com.termux.app.ai.ui.MarkdownCodeBlockParser;
import com.termux.app.autocomplete.AutocompleteSettingsFragment;
import com.termux.app.autocomplete.MaxHeightRecyclerView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BridgeAndUITest {

    @Test
    public void testTerminalCommandBridgeNeverAutoExecutes() {
        String multiLine = "ls -la\nrm -rf /tmp/safe_test\r\necho done\n";
        String sanitized = TerminalCommandBridge.sanitizeCommandForInsert(multiLine);
        assertFalse(sanitized.contains("\n"));
        assertFalse(sanitized.contains("\r"));
        assertEquals("ls -la rm -rf /tmp/safe_test && echo done", sanitized);

        // Detach any session and verify queuing + consuming strips newlines cleanly
        TerminalCommandBridge.attachSession(null);
        boolean immediate = TerminalCommandBridge.insertIntoActiveTerminal("pkg update\n");
        assertFalse(immediate);
        String consumed = TerminalCommandBridge.consumePendingInsertCommand();
        assertEquals("pkg update", consumed);
        assertNull(TerminalCommandBridge.consumePendingInsertCommand());

        // Unicode line breaks & control characters (ESC, Ctrl+C) must also be stripped
        String injected = "echo ok\u2028rm -rf /\u0085\u0003\u001B[A";
        String safe = TerminalCommandBridge.sanitizeCommandForInsert(injected);
        assertFalse(safe.contains("\u2028"));
        assertFalse(safe.contains("\u0085"));
        assertFalse(safe.contains("\u0003"));
        assertFalse(safe.contains("\u001B"));
    }

    @Test
    public void testMarkdownCodeBlockParserSplitsProseFencesAndStreaming() {
        String md = "Run this first:\n```bash\ngit status -sb\ngit diff\n```\nThen check Python:\n```python\nprint('ok')";
        List<MarkdownCodeBlockParser.Segment> segments = MarkdownCodeBlockParser.parse(md);
        assertEquals(4, segments.size());
        assertEquals(MarkdownCodeBlockParser.Segment.Kind.PROSE, segments.get(0).kind);
        assertEquals("Run this first:", segments.get(0).content);
        assertEquals(MarkdownCodeBlockParser.Segment.Kind.CODE_BLOCK, segments.get(1).kind);
        assertEquals("bash", segments.get(1).language);
        assertEquals("git status -sb\ngit diff", segments.get(1).content);
        assertEquals(MarkdownCodeBlockParser.Segment.Kind.PROSE, segments.get(2).kind);
        assertEquals(MarkdownCodeBlockParser.Segment.Kind.CODE_BLOCK, segments.get(3).kind);
        assertEquals("python", segments.get(3).language);
        assertEquals("print('ok')", segments.get(3).content);
    }

    @Test
    public void testAIAssistantActivityShowsMissingKeyBannerAndHandlesPromptIntent() {
        AIManager manager = AIManager.getInstance(RuntimeEnvironment.getApplication());
        manager.getCredentials().removeApiKey(AIProviderType.GEMINI);
        manager.getSettings().setActiveProvider(AIProviderType.GEMINI);

        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AIAssistantActivity.class);
        intent.putExtra(AIAssistantActivity.EXTRA_INITIAL_PROMPT, "Explain tar -xzvf");

        try (ActivityController<AIAssistantActivity> controller = Robolectric.buildActivity(AIAssistantActivity.class, intent)) {
            AIAssistantActivity activity = controller.create().start().resume().get();
            View missingBanner = activity.findViewById(R.id.ai_missing_key_banner);
            assertEquals(View.VISIBLE, missingBanner.getVisibility());

            android.widget.EditText input = activity.findViewById(R.id.ai_prompt_input);
            assertEquals("Explain tar -xzvf", input.getText().toString());
        }
    }

    @Test
    public void testSettingsActivityOpensAIAndAutocompleteFragments() {
        Intent aiIntent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class);
        aiIntent.putExtra(SettingsActivity.EXTRA_INITIAL_FRAGMENT, "com.termux.app.ai.ui.AISettingsFragment");
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class, aiIntent)) {
            SettingsActivity activity = controller.create().start().resume().get();
            assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.settings) instanceof AISettingsFragment);
        }

        Intent acIntent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class);
        acIntent.putExtra(SettingsActivity.EXTRA_INITIAL_FRAGMENT, "com.termux.app.autocomplete.AutocompleteSettingsFragment");
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class, acIntent)) {
            SettingsActivity activity = controller.create().start().resume().get();
            assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.settings) instanceof AutocompleteSettingsFragment);
        }
    }

    @Test
    public void testMaxHeightRecyclerViewBoundsDropdownHeight() {
        MaxHeightRecyclerView rv = new MaxHeightRecyclerView(RuntimeEnvironment.getApplication());
        int widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int hugeHeightSpec = View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY);
        rv.measure(widthSpec, hugeHeightSpec);
        int maxExpectedPx = Math.round(168f * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density) + 2;
        assertTrue("MaxHeightRecyclerView measured height exceeded cap: " + rv.getMeasuredHeight(),
            rv.getMeasuredHeight() <= maxExpectedPx);
    }

    @Test
    public void testAIConversationStoreAtomicPersistenceAndReload() {
        AIConversationStore store = new AIConversationStore(RuntimeEnvironment.getApplication());
        store.clearHistory();

        com.termux.app.ai.model.AIMessage userMsg =
            new com.termux.app.ai.model.AIMessage(com.termux.app.ai.model.AIMessage.Role.USER, "Check disk usage");
        com.termux.app.ai.model.AIMessage assistantMsg =
            new com.termux.app.ai.model.AIMessage(com.termux.app.ai.model.AIMessage.Role.ASSISTANT, "Run `df -h`");
        assistantMsg.setProvider(AIProviderType.GEMINI);
        assistantMsg.setModelId("gemini-2.5-flash");

        store.saveMessages(java.util.Arrays.asList(userMsg, assistantMsg));

        java.io.File tmpFile = new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "termux_ai_conversation.json.tmp");
        assertFalse("Temp file should not linger after atomic save", tmpFile.exists());

        AIConversationStore reloaded = new AIConversationStore(RuntimeEnvironment.getApplication());
        List<com.termux.app.ai.model.AIMessage> loaded = reloaded.loadMessages();
        assertEquals(2, loaded.size());
        assertEquals("Check disk usage", loaded.get(0).getContent());
        assertEquals("Run `df -h`", loaded.get(1).getContent());
        assertEquals(AIProviderType.GEMINI, loaded.get(1).getProvider());

        store.clearHistory();
        assertTrue(reloaded.loadMessages().isEmpty());
    }

    @Test
    public void testAIAssistantHidesMissingKeyBannerForBuiltInProvider() {
        AIManager manager = AIManager.getInstance(RuntimeEnvironment.getApplication());
        manager.getSettings().setActiveProvider(AIProviderType.BUILTIN);

        Intent intent = new Intent(RuntimeEnvironment.getApplication(), AIAssistantActivity.class);
        try (ActivityController<AIAssistantActivity> controller = Robolectric.buildActivity(AIAssistantActivity.class, intent)) {
            AIAssistantActivity activity = controller.create().start().resume().get();
            View missingBanner = activity.findViewById(R.id.ai_missing_key_banner);
            assertEquals(View.GONE, missingBanner.getVisibility());
        }
    }

    @Test
    public void testCommandHistoryStoreSearchAndDeleteLeavesShellHistoryIntact() throws Exception {
        java.io.File tempDir = RuntimeEnvironment.getApplication().getCacheDir();
        java.io.File storeFile = new java.io.File(tempDir, "test_cmd_hist_" + System.nanoTime() + ".json");
        java.io.File bashHist = new java.io.File(tempDir, "test_bash_hist_" + System.nanoTime());
        String originalBashContent = "pkg update\ngit status -sb\n";
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(bashHist)) {
            fos.write(originalBashContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        com.termux.app.autocomplete.CommandHistoryStore store =
            new com.termux.app.autocomplete.CommandHistoryStore(
                storeFile, java.util.Collections.singletonList(bashHist));
        store.recordCommand("python3 app.py");

        assertEquals(3, store.getAllEntriesSorted().size());
        assertEquals(1, store.searchEntries("git").size());
        assertTrue(store.deleteEntry("git status -sb"));
        assertTrue(store.searchEntries("git").isEmpty());

        // External shell history file must remain completely untouched
        byte[] raw = java.nio.file.Files.readAllBytes(bashHist.toPath());
        assertEquals(originalBashContent, new String(raw, java.nio.charset.StandardCharsets.UTF_8));

        // Clean up temp test files
        store.clearHistory();
        //noinspection ResultOfMethodCallIgnored
        bashHist.delete();
        //noinspection ResultOfMethodCallIgnored
        storeFile.delete();
    }
}
