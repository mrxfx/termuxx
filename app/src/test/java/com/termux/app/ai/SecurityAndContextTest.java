package com.termux.app.ai;

import android.content.Context;

import com.termux.app.ai.context.AIContextManager;
import com.termux.app.ai.context.AIContextSnapshot;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.security.KeystoreCredentialManager;
import com.termux.app.ai.security.SensitiveDataRedactor;

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

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SecurityAndContextTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
    }

    @Test
    public void testSensitiveDataRedactorMasksSecretsPreservesSafeText() {
        String raw = "export GEMINI_API_KEY=AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz123456\n"
            + "curl -H \"Authorization: Bearer sk-or-v1-abc1234567890abcdef1234567890\" https://user:supersecret@api.example.com\n"
            + "ls -la /data/data/com.termux/files/home";

        String redacted = SensitiveDataRedactor.redact(raw);
        assertFalse(redacted.contains("AIzaSy"));
        assertFalse(redacted.contains("sk-or-v1-"));
        assertFalse(redacted.contains("supersecret"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("ls -la /data/data/com.termux/files/home"));

        String masked = SensitiveDataRedactor.maskKeyForDisplay("AIzaSy1234567890ABCD");
        assertTrue(masked.startsWith("AIza"));
        assertTrue(masked.endsWith("ABCD"));
        assertFalse(masked.contains("567890"));
    }

    @Test
    public void testCredentialManagerSaveReadUpdateRemoveLifecycle() {
        KeystoreCredentialManager creds = new KeystoreCredentialManager(mContext);
        creds.removeApiKey(AIProviderType.GEMINI);
        creds.removeApiKey(AIProviderType.OPENROUTER);

        assertFalse(creds.hasApiKey(AIProviderType.GEMINI));
        assertNull(creds.getApiKey(AIProviderType.GEMINI));

        creds.saveApiKey(AIProviderType.GEMINI, "AIzaSyTestKeyValue1234567890abcd");
        creds.saveApiKey(AIProviderType.OPENROUTER, "sk-or-v1-testvalue1234567890abcdef");

        assertTrue(creds.hasApiKey(AIProviderType.GEMINI));
        assertTrue(creds.hasApiKey(AIProviderType.OPENROUTER));
        assertEquals("AIzaSyTestKeyValue1234567890abcd", creds.getApiKey(AIProviderType.GEMINI));
        assertEquals("sk-or-v1-testvalue1234567890abcdef", creds.getApiKey(AIProviderType.OPENROUTER));
        assertTrue(creds.getMaskedApiKey(AIProviderType.GEMINI).startsWith("AIza"));

        // Update key
        creds.saveApiKey(AIProviderType.GEMINI, "AIzaSyUpdatedKey999999999999999");
        assertEquals("AIzaSyUpdatedKey999999999999999", creds.getApiKey(AIProviderType.GEMINI));

        // Remove key
        creds.removeApiKey(AIProviderType.GEMINI);
        assertFalse(creds.hasApiKey(AIProviderType.GEMINI));
        assertNull(creds.getApiKey(AIProviderType.GEMINI));
    }

    @Test
    public void testContextManagerRespectsPrivacyTogglesAndBounds() throws Exception {
        AISettingsManager settings = new AISettingsManager(mContext);
        settings.setIncludeCwdEnabled(true);
        settings.setIncludeShellEnabled(true);
        settings.setIncludeLastCommandEnabled(true);
        settings.setIncludeRecentOutputEnabled(false); // Default OFF
        settings.setIncludeToolsEnabled(false);

        File attachedFile = new File(tempFolder.getRoot(), "config.sh");
        try (FileWriter fw = new FileWriter(attachedFile)) {
            fw.write("API_TOKEN=ghp_1234567890abcdefghijklmnopqrstuvwxyz\necho hello\n");
        }
        AIContextSnapshot.AttachedFile attached = AIContextManager.readProjectFileSafely(attachedFile);
        assertNotNull(attached);

        AIContextSnapshot snapshot = new AIContextSnapshot(
            "/data/data/com.termux/files/home/proj",
            "bash",
            "pytest -q",
            "secret-output-should-stay-excluded",
            Arrays.asList("git", "python3"),
            Collections.singletonList(attached)
        );

        String payloadWithoutOutput = AIContextManager.buildContextPayload(settings, snapshot, false);
        assertTrue(payloadWithoutOutput.contains("Working Directory: /data/data/com.termux/files/home/proj"));
        assertTrue(payloadWithoutOutput.contains("Shell: bash"));
        assertTrue(payloadWithoutOutput.contains("Last Command: pytest -q"));
        assertFalse(payloadWithoutOutput.contains("Available Tools:"));
        assertFalse(payloadWithoutOutput.contains("secret-output-should-stay-excluded"));
        assertFalse(payloadWithoutOutput.contains("ghp_1234567890"));
        assertTrue(payloadWithoutOutput.contains("echo hello"));

        // Explicit override (e.g. Fix Last Error quick chip) includes recent output
        String payloadWithOverride = AIContextManager.buildContextPayload(settings, snapshot, true);
        assertTrue(payloadWithOverride.contains("secret-output-should-stay-excluded"));
    }
}
