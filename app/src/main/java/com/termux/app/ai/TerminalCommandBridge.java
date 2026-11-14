package com.termux.app.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.context.AIContextSnapshot;
import com.termux.app.ai.security.SensitiveDataRedactor;
import com.termux.terminal.TerminalSession;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Safely connects the live Termux terminal session with the on-demand AI Assistant:
 * 1. Captures redacted context snapshots only when AI Assistant is opened/used.
 * 2. Inserts AI-suggested commands into the terminal input buffer WITHOUT auto-execution
 *    (strips any trailing newlines / carriage returns).
 */
public final class TerminalCommandBridge {

    private static WeakReference<TerminalSession> sActiveSessionRef = new WeakReference<>(null);
    private static volatile String sLastCommand = "";
    private static volatile String sRecentTranscript = "";
    private static volatile String sShellName = "bash";
    private static volatile List<String> sAvailableTools = new ArrayList<>();
    private static volatile String sPendingInsertCommand = null;

    private TerminalCommandBridge() {}

    public static void attachSession(@Nullable TerminalSession session) {
        sActiveSessionRef = new WeakReference<>(session);
    }

    public static void updateLastCommand(@Nullable String command) {
        if (command != null && !command.trim().isEmpty()) {
            sLastCommand = SensitiveDataRedactor.redact(command.trim());
        }
    }

    public static void updateRecentTranscript(@Nullable String transcriptTail) {
        if (transcriptTail != null) {
            sRecentTranscript = SensitiveDataRedactor.redact(transcriptTail);
        }
    }

    public static void updateShellName(@Nullable String shellName) {
        if (shellName != null && !shellName.trim().isEmpty()) {
            sShellName = shellName.trim();
        }
    }

    public static void updateAvailableTools(@NonNull List<String> tools) {
        sAvailableTools = new ArrayList<>(tools);
    }

    @NonNull
    public static AIContextSnapshot captureSnapshot(@Nullable List<AIContextSnapshot.AttachedFile> attachedFiles) {
        TerminalSession session = sActiveSessionRef.get();
        String cwd = null;
        if (session != null) {
            try {
                cwd = session.getCwd();
            } catch (Exception ignored) {}
        }
        return new AIContextSnapshot(
            cwd,
            sShellName,
            sLastCommand,
            sRecentTranscript,
            sAvailableTools,
            attachedFiles
        );
    }

    /**
     * Inserts a command into the active terminal session's input line for review.
     * NEVER executes automatically: all CR/LF sequences are stripped/replaced with spaces.
     */
    @NonNull
    public static String sanitizeCommandForInsert(@NonNull String rawCommand) {
        String clean = rawCommand.replace("\r\n", " && ").replace('\n', ' ').replace('\r', ' ');
        // Collapse multi-line trailing whitespace and never emit CR/LF
        return clean.trim();
    }

    public static boolean insertIntoActiveTerminal(@NonNull String rawCommand) {
        String safe = sanitizeCommandForInsert(rawCommand);
        if (safe.isEmpty()) return false;
        TerminalSession session = sActiveSessionRef.get();
        if (session != null && session.isRunning()) {
            session.write(safe);
            return true;
        }
        sPendingInsertCommand = safe;
        return false;
    }

    @Nullable
    public static String consumePendingInsertCommand() {
        String cmd = sPendingInsertCommand;
        sPendingInsertCommand = null;
        return cmd;
    }
}
