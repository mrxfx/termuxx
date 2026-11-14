package com.termux.app.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexes locally installed executable commands from PATH directories plus shell builtins.
 * Caches by PATH string and directory modification timestamps; runs offline only.
 */
public class CommandIndexCache {

    public static final class CommandEntry {
        @NonNull public final String name;
        @NonNull public final String description;
        public final boolean builtin;

        public CommandEntry(@NonNull String name, @NonNull String description, boolean builtin) {
            this.name = name;
            this.description = description;
            this.builtin = builtin;
        }
    }

    private static final Map<String, String> SHELL_BUILTINS = new LinkedHashMap<>();
    private static final Map<String, String> KNOWN_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        SHELL_BUILTINS.put("cd", "Change working directory");
        SHELL_BUILTINS.put("ls", "List directory contents");
        SHELL_BUILTINS.put("pwd", "Print current directory");
        SHELL_BUILTINS.put("export", "Set environment variable");
        SHELL_BUILTINS.put("alias", "Define command alias");
        SHELL_BUILTINS.put("echo", "Print arguments");
        SHELL_BUILTINS.put("source", "Execute commands from file");
        SHELL_BUILTINS.put("exit", "Exit shell session");
        SHELL_BUILTINS.put("history", "Show command history");
        SHELL_BUILTINS.put("clear", "Clear terminal screen");
        SHELL_BUILTINS.put("unset", "Remove variable or function");
        SHELL_BUILTINS.put("which", "Locate a command");
        SHELL_BUILTINS.put("type", "Display command type");

        KNOWN_DESCRIPTIONS.put("pkg", "Termux package manager");
        KNOWN_DESCRIPTIONS.put("apt", "APT package manager");
        KNOWN_DESCRIPTIONS.put("dpkg", "Debian package manager");
        KNOWN_DESCRIPTIONS.put("git", "Distributed version control");
        KNOWN_DESCRIPTIONS.put("python", "Python interpreter");
        KNOWN_DESCRIPTIONS.put("python3", "Python 3 interpreter");
        KNOWN_DESCRIPTIONS.put("pip", "Python package installer");
        KNOWN_DESCRIPTIONS.put("pip3", "Python 3 package installer");
        KNOWN_DESCRIPTIONS.put("node", "Node.js JavaScript runtime");
        KNOWN_DESCRIPTIONS.put("npm", "Node package manager");
        KNOWN_DESCRIPTIONS.put("npx", "Run npm package binary");
        KNOWN_DESCRIPTIONS.put("ssh", "OpenSSH remote login");
        KNOWN_DESCRIPTIONS.put("scp", "Secure file copy over SSH");
        KNOWN_DESCRIPTIONS.put("curl", "Transfer data from URLs");
        KNOWN_DESCRIPTIONS.put("wget", "Download files from web");
        KNOWN_DESCRIPTIONS.put("grep", "Search text patterns");
        KNOWN_DESCRIPTIONS.put("rg", "Ripgrep fast search");
        KNOWN_DESCRIPTIONS.put("find", "Search files in hierarchy");
        KNOWN_DESCRIPTIONS.put("cat", "Concatenate and print files");
        KNOWN_DESCRIPTIONS.put("head", "Output first lines of files");
        KNOWN_DESCRIPTIONS.put("tail", "Output last lines of files");
        KNOWN_DESCRIPTIONS.put("chmod", "Change file permissions");
        KNOWN_DESCRIPTIONS.put("chown", "Change file owner and group");
        KNOWN_DESCRIPTIONS.put("mkdir", "Create directories");
        KNOWN_DESCRIPTIONS.put("rm", "Remove files or directories");
        KNOWN_DESCRIPTIONS.put("cp", "Copy files and directories");
        KNOWN_DESCRIPTIONS.put("mv", "Move or rename files");
        KNOWN_DESCRIPTIONS.put("tar", "Tape archive utility");
        KNOWN_DESCRIPTIONS.put("unzip", "Extract ZIP archives");
        KNOWN_DESCRIPTIONS.put("zip", "Package and compress files");
        KNOWN_DESCRIPTIONS.put("nano", "Simple terminal text editor");
        KNOWN_DESCRIPTIONS.put("vim", "Vi IMproved text editor");
        KNOWN_DESCRIPTIONS.put("nvim", "Neovim text editor");
        KNOWN_DESCRIPTIONS.put("tmux", "Terminal multiplexer");
        KNOWN_DESCRIPTIONS.put("htop", "Interactive process viewer");
        KNOWN_DESCRIPTIONS.put("ps", "Report process snapshot");
        KNOWN_DESCRIPTIONS.put("kill", "Send signal to process");
        KNOWN_DESCRIPTIONS.put("termux-setup-storage", "Configure shared storage symlinks");
        KNOWN_DESCRIPTIONS.put("termux-reload-settings", "Reload Termux properties and style");
    }

    private static final long STAT_COOLDOWN_MS = 3000L;

    private final List<String> mCustomPathDirs;
    private String mCachedPathKey = "";
    private final Map<String, Long> mDirTimestamps = new LinkedHashMap<>();
    private Map<String, CommandEntry> mIndexedCommands = Collections.emptyMap();
    private long mLastStatCheckMs = 0L;

    public CommandIndexCache() {
        this(null);
    }

    public CommandIndexCache(@Nullable List<String> overridePathDirs) {
        mCustomPathDirs = overridePathDirs != null ? new ArrayList<>(overridePathDirs) : null;
    }

    @NonNull
    public synchronized Map<String, CommandEntry> getIndex() {
        List<String> dirs = resolvePathDirs();
        String pathKey = String.join(File.pathSeparator, dirs);
        long now = System.currentTimeMillis();
        if (mCachedPathKey.isEmpty() || !pathKey.equals(mCachedPathKey)) {
            rebuildIndex(dirs, pathKey);
            mLastStatCheckMs = now;
        } else if (now - mLastStatCheckMs >= STAT_COOLDOWN_MS) {
            mLastStatCheckMs = now;
            if (hasAnyDirectoryChanged(dirs)) {
                rebuildIndex(dirs, pathKey);
            }
        }
        return mIndexedCommands;
    }

    public synchronized void invalidate() {
        mCachedPathKey = "";
        mLastStatCheckMs = 0L;
        mDirTimestamps.clear();
    }

    @NonNull
    public List<SuggestionItem> matchCommands(@NonNull String prefix, int limit) {
        if (prefix.isEmpty()) return Collections.emptyList();
        Map<String, CommandEntry> index = getIndex();
        List<SuggestionItem> matches = new ArrayList<>();
        for (CommandEntry entry : index.values()) {
            if (entry.name.startsWith(prefix) && !entry.name.equals(prefix)) {
                String suffix = entry.name.substring(prefix.length());
                int score = entry.builtin ? 75 : (KNOWN_DESCRIPTIONS.containsKey(entry.name) ? 80 : 60);
                if (entry.name.length() <= prefix.length() + 4) score += 5;
                matches.add(new SuggestionItem(entry.name, suffix, entry.description, SuggestionType.COMMAND, score, false));
            }
        }
        matches.sort((a, b) -> {
            int scoreCmp = Integer.compare(b.getScore(), a.getScore());
            if (scoreCmp != 0) return scoreCmp;
            int lenCmp = Integer.compare(a.getDisplayText().length(), b.getDisplayText().length());
            return lenCmp != 0 ? lenCmp : a.getDisplayText().compareTo(b.getDisplayText());
        });
        return matches.size() > limit ? new ArrayList<>(matches.subList(0, limit)) : matches;
    }

    public synchronized boolean isCommandInstalled(@NonNull String command) {
        return getIndex().containsKey(command);
    }

    @NonNull
    public synchronized List<String> getAvailableKnownTools() {
        Map<String, CommandEntry> idx = getIndex();
        List<String> tools = new ArrayList<>();
        for (String tool : KNOWN_DESCRIPTIONS.keySet()) {
            if (idx.containsKey(tool)) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private List<String> resolvePathDirs() {
        if (mCustomPathDirs != null) return mCustomPathDirs;
        List<String> dirs = new ArrayList<>();
        String envPath = System.getenv("PATH");
        if (envPath != null && !envPath.isEmpty()) {
            dirs.addAll(Arrays.asList(envPath.split(File.pathSeparator)));
        }
        String termuxBin = "/data/data/com.termux/files/usr/bin";
        if (!dirs.contains(termuxBin)) dirs.add(0, termuxBin);
        if (!dirs.contains("/system/bin")) dirs.add("/system/bin");
        return dirs;
    }

    private boolean hasAnyDirectoryChanged(List<String> dirs) {
        for (String dirPath : dirs) {
            File dir = new File(dirPath);
            long currentMod = dir.isDirectory() ? dir.lastModified() : -1L;
            Long prev = mDirTimestamps.get(dirPath);
            if (prev == null || prev != currentMod) {
                return true;
            }
        }
        return false;
    }

    private void rebuildIndex(List<String> dirs, String pathKey) {
        Map<String, CommandEntry> built = new LinkedHashMap<>();
        mDirTimestamps.clear();

        for (Map.Entry<String, String> entry : SHELL_BUILTINS.entrySet()) {
            built.put(entry.getKey(), new CommandEntry(entry.getKey(), entry.getValue(), true));
        }

        for (String dirPath : dirs) {
            File dir = new File(dirPath);
            long mod = dir.isDirectory() ? dir.lastModified() : -1L;
            mDirTimestamps.put(dirPath, mod);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && f.canExecute()) {
                    String name = f.getName();
                    if (!built.containsKey(name)) {
                        String desc = KNOWN_DESCRIPTIONS.containsKey(name) ? KNOWN_DESCRIPTIONS.get(name) : "Executable";
                        built.put(name, new CommandEntry(name, desc, false));
                    }
                }
            }
        }

        mCachedPathKey = pathKey;
        mIndexedCommands = Collections.unmodifiableMap(built);
    }
}
