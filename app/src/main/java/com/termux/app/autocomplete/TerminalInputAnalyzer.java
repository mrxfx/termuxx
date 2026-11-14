package com.termux.app.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts active command line and token state safely from the visible terminal row,
 * without mutating terminal buffers or interfering with interactive full-screen apps.
 */
public final class TerminalInputAnalyzer {

    public static final class ParsedInput {
        @NonNull public final String commandLine;
        @NonNull public final List<String> completedTokens;
        @NonNull public final String currentToken;
        public final boolean endsWithSpace;
        public final boolean atEndOfLine;

        public ParsedInput(
            @NonNull String commandLine,
            @NonNull List<String> completedTokens,
            @NonNull String currentToken,
            boolean endsWithSpace,
            boolean atEndOfLine
        ) {
            this.commandLine = commandLine;
            this.completedTokens = completedTokens;
            this.currentToken = currentToken;
            this.endsWithSpace = endsWithSpace;
            this.atEndOfLine = atEndOfLine;
        }

        public boolean isCommandPosition() {
            return completedTokens.isEmpty() && !endsWithSpace;
        }

        @NonNull
        public String getCommandName() {
            if (!completedTokens.isEmpty()) {
                return completedTokens.get(0);
            }
            return currentToken;
        }
    }

    // Matches common shell prompts: "~/dir $ ", "user@host:/path# ", "$ ", "# ", "% ", "> ", "❯ "
    private static final Pattern PROMPT_PATTERN = Pattern.compile(
        "^(?:.*?[\\$#%>❯]\\s+)"
    );

    private static final Set<String> INTERACTIVE_TITLES = new HashSet<>(Arrays.asList(
        "vim", "nvim", "vi", "nano", "emacs", "pico", "less", "more", "man",
        "top", "htop", "btop", "ssh", "sftp", "ftp", "tmux", "screen", "fzf",
        "gdb", "lldb", "tig", "mc", "ranger", "nnn", "irssi", "mutt", "weechat"
    ));

    private TerminalInputAnalyzer() {}

    public static boolean isInteractiveMode(@Nullable TerminalEmulator emulator, int shellPid) {
        if (emulator == null) return true;
        if (emulator.isAlternateBufferActive() || emulator.isMouseTrackingActive()) {
            return true;
        }
        String title = emulator.getTitle();
        if (title != null) {
            String lowerTitle = title.trim().toLowerCase();
            String firstWord = lowerTitle.split("\\s+")[0];
            if (INTERACTIVE_TITLES.contains(firstWord)) {
                return true;
            }
        }
        if (shellPid > 0 && hasInteractiveForegroundChild(shellPid)) {
            return true;
        }
        return false;
    }

    @Nullable
    public static ParsedInput analyzeEmulatorCursorLine(@Nullable TerminalEmulator emulator) {
        if (emulator == null) return null;
        TerminalBuffer screen = emulator.getScreen();
        if (screen == null) return null;
        int cursorRow = emulator.getCursorRow();
        int cursorCol = emulator.getCursorCol();
        if (cursorRow < 0 || cursorRow >= emulator.mRows || cursorCol < 0) return null;

        int internalRow = screen.externalToInternalRow(cursorRow);
        TerminalRow rowObj = screen.allocateFullLineIfNecessary(internalRow);
        if (rowObj == null || rowObj.mText == null) return null;

        int used = Math.min(rowObj.getSpaceUsed(), rowObj.mText.length);
        String fullRow = new String(rowObj.mText, 0, used);
        int safeCol = Math.min(cursorCol, fullRow.length());
        String beforeCursor = fullRow.substring(0, safeCol);
        String afterCursor = fullRow.substring(safeCol);

        boolean atEndOfLine = afterCursor.trim().isEmpty();
        return parsePromptLine(beforeCursor, atEndOfLine);
    }

    @Nullable
    public static ParsedInput parsePromptLine(@Nullable String beforeCursor, boolean atEndOfLine) {
        if (beforeCursor == null) return null;
        String commandText = extractCommandAfterPrompt(beforeCursor);
        if (commandText == null) return null;

        // Ignore comments
        String trimmedStart = commandText.replaceFirst("^\\s+", "");
        if (trimmedStart.startsWith("#")) return null;

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaping = false;

        for (int i = 0; i < commandText.length(); i++) {
            char c = commandText.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\' && !inSingleQuote) {
                escaping = true;
                current.append(c);
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
                continue;
            }
            if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        boolean endsWithSpace = commandText.length() > 0
            && Character.isWhitespace(commandText.charAt(commandText.length() - 1))
            && !inSingleQuote && !inDoubleQuote && !escaping;

        String currentToken = endsWithSpace ? "" : current.toString();

        // Handle chained commands cleanly (`cmd1 && cmd2`, `cmd1 | cmd2`, `cmd1 ; cmd2`)
        int chainIdx = -1;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String t = tokens.get(i);
            if ("&&".equals(t) || "||".equals(t) || "|".equals(t) || ";".equals(t)) {
                chainIdx = i;
                break;
            }
        }
        if (chainIdx >= 0) {
            tokens = new ArrayList<>(tokens.subList(chainIdx + 1, tokens.size()));
        }

        return new ParsedInput(trimmedStart, tokens, currentToken, endsWithSpace, atEndOfLine);
    }

    @Nullable
    public static String extractCommandAfterPrompt(@NonNull String line) {
        if (line.trim().isEmpty()) return null;
        Matcher m = PROMPT_PATTERN.matcher(line);
        if (m.find()) {
            return line.substring(m.end());
        }
        // Fallback: if no classic prompt delimiter, check if line starts directly without prompt
        if (line.startsWith("$ ") || line.startsWith("# ")) {
            return line.substring(2);
        }
        return null;
    }

    private static boolean hasInteractiveForegroundChild(int shellPid) {
        File statFile = new File("/proc/" + shellPid + "/stat");
        if (!statFile.exists() || !statFile.canRead()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(statFile))) {
            String statLine = br.readLine();
            if (statLine == null) return false;
            int rparen = statLine.lastIndexOf(')');
            if (rparen < 0 || rparen + 2 >= statLine.length()) return false;
            String[] fields = statLine.substring(rparen + 2).trim().split("\\s+");
            // After comm: state(0), ppid(1), pgrp(2), session(3), tty_nr(4), tpgid(5)
            if (fields.length > 5) {
                int pgrp = Integer.parseInt(fields[2]);
                int tpgid = Integer.parseInt(fields[5]);
                if (tpgid > 0 && pgrp > 0 && tpgid != pgrp) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }
}
