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
}
