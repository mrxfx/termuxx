package com.termux.app.autocomplete;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.security.SensitiveDataRedactor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline persistent command history combining shell history files (~/.bash_history, ~/.zsh_history)
 * and Termux-tracked submitted commands with deduplication and recency/frequency ranking.
 */
public class CommandHistoryStore {

    public static final class HistoryEntry {
        @NonNull public final String command;
        public long lastUsedMs;
        public int count;

        public HistoryEntry(@NonNull String command, long lastUsedMs, int count) {
            this.command = command;
            this.lastUsedMs = lastUsedMs;
            this.count = count;
        }
    }

    private static final int MAX_ENTRIES = 500;
    private final File mStoreFile;
    private final List<File> mShellHistoryFiles;
    private final Map<String, HistoryEntry> mEntries = new LinkedHashMap<>();
    private final Map<String, Long> mLoadedShellTimestamps = new LinkedHashMap<>();

    public CommandHistoryStore(@NonNull Context context) {
        this(
            new File(context.getApplicationContext().getFilesDir(), "termux_ai_cmd_history.json"),
            defaultShellHistoryFiles()
        );
    }

    public CommandHistoryStore(@NonNull File storeFile, @Nullable List<File> shellHistoryFiles) {
        mStoreFile = storeFile;
        mShellHistoryFiles = shellHistoryFiles != null ? new ArrayList<>(shellHistoryFiles) : Collections.emptyList();
        loadFromStoreFile();
        refreshShellHistoryFiles();
    }

    public synchronized void recordCommand(@Nullable String rawCommand) {
        if (rawCommand == null) return;
        String trimmed = rawCommand.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
        // Respect bash ignorespace convention & never persist probable inline API secrets
        if (rawCommand.startsWith(" ") || SensitiveDataRedactor.containsLikelySecret(trimmed)) return;

        long now = System.currentTimeMillis();
        HistoryEntry existing = mEntries.get(trimmed);
        if (existing != null) {
            existing.lastUsedMs = now;
            existing.count += 1;
            mEntries.remove(trimmed);
            mEntries.put(trimmed, existing);
        } else {
            mEntries.put(trimmed, new HistoryEntry(trimmed, now, 1));
            trimToMaxEntries();
        }
        saveToStoreFile();
    }

    @NonNull
    public synchronized List<SuggestionItem> matchPrefix(@NonNull String linePrefix, int limit) {
        if (linePrefix.trim().isEmpty()) return Collections.emptyList();
        refreshShellHistoryFiles();

        List<HistoryEntry> matched = new ArrayList<>();
        for (HistoryEntry entry : mEntries.values()) {
            if (entry.command.startsWith(linePrefix) && !entry.command.equals(linePrefix)) {
                matched.add(entry);
            }
        }
        // Most recent + frequent first
        matched.sort((a, b) -> {
            int tsCmp = Long.compare(b.lastUsedMs, a.lastUsedMs);
            return tsCmp != 0 ? tsCmp : Integer.compare(b.count, a.count);
        });

        List<SuggestionItem> items = new ArrayList<>();
        int max = Math.min(limit, matched.size());
        for (int i = 0; i < max; i++) {
            HistoryEntry entry = matched.get(i);
            String suffix = entry.command.substring(linePrefix.length());
            int score = 90 + Math.min(9, entry.count);
            items.add(new SuggestionItem(
                entry.command,
                suffix,
                "History" + (entry.count > 1 ? " (" + entry.count + "x)" : ""),
                SuggestionType.HISTORY,
                score,
                true
            ));
        }
        return items;
    }

    public synchronized void clearHistory() {
        mEntries.clear();
        mLoadedShellTimestamps.clear();
        if (mStoreFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mStoreFile.delete();
        }
    }

    public synchronized int size() {
        return mEntries.size();
    }

    public synchronized void refreshShellHistoryFiles() {
        boolean changed = false;
        for (File file : mShellHistoryFiles) {
            if (!file.exists() || !file.isFile() || !file.canRead()) continue;
            long mod = file.lastModified();
            Long prevMod = mLoadedShellTimestamps.get(file.getAbsolutePath());
            if (prevMod != null && prevMod == mod) continue;

            mLoadedShellTimestamps.put(file.getAbsolutePath(), mod);
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                long orderTs = mod;
                while ((line = br.readLine()) != null) {
                    String cmd = normalizeHistoryLine(line);
                    if (cmd == null || cmd.isEmpty() || SensitiveDataRedactor.containsLikelySecret(cmd)) continue;
                    orderTs += 1;
                    HistoryEntry existing = mEntries.get(cmd);
                    if (existing == null) {
                        mEntries.put(cmd, new HistoryEntry(cmd, orderTs, 1));
                    } else if (orderTs > existing.lastUsedMs) {
                        existing.lastUsedMs = orderTs;
                    }
                    changed = true;
                }
            } catch (Exception ignored) {}
        }
        if (changed) {
            trimToMaxEntries();
        }
    }

    @Nullable
    public static String normalizeHistoryLine(@Nullable String rawLine) {
        if (rawLine == null) return null;
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;
        // Strip zsh extended history prefix: ": 1700000000:0;cmd"
        if (trimmed.startsWith(": ")) {
            int semi = trimmed.indexOf(';');
            if (semi > 0 && semi + 1 < trimmed.length()) {
                trimmed = trimmed.substring(semi + 1).trim();
            }
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void trimToMaxEntries() {
        if (mEntries.size() <= MAX_ENTRIES) return;
        List<HistoryEntry> sorted = new ArrayList<>(mEntries.values());
        sorted.sort((a, b) -> Long.compare(a.lastUsedMs, b.lastUsedMs));
        int toRemove = sorted.size() - MAX_ENTRIES;
        for (int i = 0; i < toRemove; i++) {
            mEntries.remove(sorted.get(i).command);
        }
    }

    private void loadFromStoreFile() {
        if (!mStoreFile.exists() || !mStoreFile.isFile()) return;
        try (FileInputStream fis = new FileInputStream(mStoreFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            JSONArray array = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String cmd = obj.optString("cmd", "");
                if (cmd.isEmpty()) continue;
                long ts = obj.optLong("ts", 0L);
                int count = obj.optInt("count", 1);
                mEntries.put(cmd, new HistoryEntry(cmd, ts, count));
            }
        } catch (Exception ignored) {}
    }

    private void saveToStoreFile() {
        try {
            File parent = mStoreFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JSONArray array = new JSONArray();
            for (HistoryEntry e : mEntries.values()) {
                JSONObject obj = new JSONObject();
                obj.put("cmd", e.command);
                obj.put("ts", e.lastUsedMs);
                obj.put("count", e.count);
                array.put(obj);
            }
            try (FileOutputStream fos = new FileOutputStream(mStoreFile)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static List<File> defaultShellHistoryFiles() {
        List<File> files = new ArrayList<>();
        String home = "/data/data/com.termux/files/home";
        files.add(new File(home, ".bash_history"));
        files.add(new File(home, ".zsh_history"));
        files.add(new File(home, ".sh_history"));
        return files;
    }
}
