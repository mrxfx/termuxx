package com.termux.app.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline filesystem suggester for files and directories.
 * Supports relative paths, absolute paths, ~/ home expansion, hidden files on explicit '.',
 * directory trailing slashes, and shell space escaping.
 */
public class FileAndDirectorySuggester {

    private static final int MAX_DIR_ENTRIES = 400;
    private final String mHomeDir;

    public FileAndDirectorySuggester() {
        this("/data/data/com.termux/files/home");
    }

    public FileAndDirectorySuggester(@NonNull String homeDir) {
        mHomeDir = homeDir;
    }

    @NonNull
    public List<SuggestionItem> suggest(
        @NonNull String rawToken,
        @Nullable String cwd,
        boolean directoriesOnly,
        int limit
    ) {
        String effectiveCwd = (cwd != null && !cwd.isEmpty()) ? cwd : mHomeDir;
        boolean quotePrefix = rawToken.startsWith("\"") || rawToken.startsWith("'");
        char quoteChar = quotePrefix ? rawToken.charAt(0) : '\0';

        String unquoted = quotePrefix ? rawToken.substring(1) : unescapeSpaces(rawToken);
        int lastSlash = unquoted.lastIndexOf('/');
        String dirPart = lastSlash >= 0 ? unquoted.substring(0, lastSlash + 1) : "";
        String namePrefix = lastSlash >= 0 ? unquoted.substring(lastSlash + 1) : unquoted;

        File searchDir = resolveDirectory(dirPart, effectiveCwd);
        if (searchDir == null || !searchDir.isDirectory() || !searchDir.canRead()) {
            return Collections.emptyList();
        }

        File[] children = searchDir.listFiles();
        if (children == null || children.length == 0) {
            return Collections.emptyList();
        }

        boolean showHidden = namePrefix.startsWith(".");
        List<SuggestionItem> items = new ArrayList<>();
        int scanned = 0;
        for (File child : children) {
            if (++scanned > MAX_DIR_ENTRIES) break;
            String name = child.getName();
            if (!showHidden && name.startsWith(".")) continue;
            if (!name.startsWith(namePrefix)) continue;

            boolean isDir = child.isDirectory();
            if (directoriesOnly && !isDir) continue;

            String suffixName = name.substring(namePrefix.length());
            String completedRawSuffix = quotePrefix
                ? suffixName + (isDir ? "/" : String.valueOf(quoteChar))
                : escapeSpaces(suffixName) + (isDir ? "/" : "");

            if (completedRawSuffix.isEmpty() && isDir) {
                completedRawSuffix = "/";
            }
            if (completedRawSuffix.isEmpty()) continue;

            String display = dirPart + name + (isDir ? "/" : "");
            int score = isDir ? 78 : 70;
            if (namePrefix.isEmpty()) score -= 5;
            items.add(new SuggestionItem(
                display,
                completedRawSuffix,
                isDir ? "Directory" : formatFileHint(child),
                isDir ? SuggestionType.DIRECTORY : SuggestionType.FILE,
                score,
                false
            ));
        }

        items.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.getScore(), a.getScore());
            return scoreCmp != 0 ? scoreCmp : a.getDisplayText().compareToIgnoreCase(b.getDisplayText());
        });

        return items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items;
    }

    @Nullable
    private File resolveDirectory(@NonNull String dirPart, @NonNull String cwd) {
        if (dirPart.isEmpty()) {
            return new File(cwd);
        }
        if ("~/".equals(dirPart)) {
            return new File(mHomeDir);
        }
        if (dirPart.startsWith("~/")) {
            return new File(mHomeDir, dirPart.substring(2));
        }
        if (dirPart.startsWith("/")) {
            return new File(dirPart);
        }
        return new File(cwd, dirPart);
    }

    @NonNull
    public static String escapeSpaces(@NonNull String text) {
        return text.replace(" ", "\\ ");
    }

    @NonNull
    public static String unescapeSpaces(@NonNull String text) {
        return text.replace("\\ ", " ");
    }

    @NonNull
    private static String formatFileHint(@NonNull File file) {
        long len = file.length();
        if (len < 1024) return len + " B";
        if (len < 1024 * 1024) return (len / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", len / (1024.0 * 1024.0));
    }
}
