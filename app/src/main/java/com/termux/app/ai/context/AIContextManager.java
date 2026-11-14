package com.termux.app.ai.context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.AISettingsManager;
import com.termux.app.ai.security.SensitiveDataRedactor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds privacy-safe, size-limited, redacted terminal context strictly according to
 * user opt-in settings and explicitly attached files.
 */
public class AIContextManager {

    public static final int MAX_TOTAL_CONTEXT_CHARS = 8000;
    public static final int MAX_RECENT_OUTPUT_CHARS = 2800;
    public static final int MAX_ATTACHED_FILE_CHARS = 3000;

    @NonNull
    public static String buildContextPayload(
        @NonNull AISettingsManager settings,
        @Nullable AIContextSnapshot snapshot,
        boolean overrideIncludeOutput
    ) {
        if (snapshot == null) return "";

        StringBuilder sb = new StringBuilder();
        if (settings.isIncludeCwdEnabled() && notEmpty(snapshot.cwd)) {
            sb.append("Working Directory: ").append(SensitiveDataRedactor.redact(snapshot.cwd)).append('\n');
        }
        if (settings.isIncludeShellEnabled() && notEmpty(snapshot.shellName)) {
            sb.append("Shell: ").append(SensitiveDataRedactor.redact(snapshot.shellName)).append('\n');
        }
        if (settings.isIncludeLastCommandEnabled() && notEmpty(snapshot.lastCommand)) {
            sb.append("Last Command: ").append(SensitiveDataRedactor.redact(snapshot.lastCommand)).append('\n');
        }
        if (settings.isIncludeToolsEnabled() && !snapshot.installedTools.isEmpty()) {
            sb.append("Available Tools: ");
            int limit = Math.min(25, snapshot.installedTools.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                sb.append(snapshot.installedTools.get(i));
            }
            sb.append('\n');
        }
        if ((settings.isIncludeRecentOutputEnabled() || overrideIncludeOutput) && notEmpty(snapshot.recentOutput)) {
            String redactedOutput = SensitiveDataRedactor.redact(snapshot.recentOutput.trim());
            if (redactedOutput.length() > MAX_RECENT_OUTPUT_CHARS) {
                redactedOutput = "...\n" + redactedOutput.substring(redactedOutput.length() - MAX_RECENT_OUTPUT_CHARS);
            }
            sb.append("\nRecent Terminal Output:\n```\n").append(redactedOutput).append("\n```\n");
        }

        for (AIContextSnapshot.AttachedFile file : snapshot.attachedFiles) {
            String content = SensitiveDataRedactor.redact(file.content);
            if (content.length() > MAX_ATTACHED_FILE_CHARS) {
                content = content.substring(0, MAX_ATTACHED_FILE_CHARS) + "\n...[truncated]";
            }
            sb.append("\nAttached File (").append(SensitiveDataRedactor.redact(file.name)).append("):\n```\n")
                .append(content).append("\n```\n");
        }

        String out = sb.toString().trim();
        if (out.length() > MAX_TOTAL_CONTEXT_CHARS) {
            out = out.substring(0, MAX_TOTAL_CONTEXT_CHARS) + "\n...[context truncated]";
        }
        return out;
    }

    @Nullable
    public static AIContextSnapshot.AttachedFile readProjectFileSafely(@NonNull File file) throws IOException {
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            return null;
        }
        int maxBytes = MAX_ATTACHED_FILE_CHARS * 2;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[1024];
            int total = 0;
            int read;
            while (total < maxBytes && (read = fis.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
                baos.write(buf, 0, read);
                total += read;
            }
        }
        String raw = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        String redacted = SensitiveDataRedactor.redact(raw);
        return new AIContextSnapshot.AttachedFile(file.getName(), file.getAbsolutePath(), redacted);
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.trim().isEmpty();
    }
}
