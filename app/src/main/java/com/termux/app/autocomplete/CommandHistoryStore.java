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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final long SHELL_REFRESH_COOLDOWN_MS = 5000L;
    private static final AtomicLong sClearGeneration = new AtomicLong(0L);

    private final File mStoreFile;
    private final File mDeletedFile;
    private final List<File> mShellHistoryFiles;
    private final Map<String, HistoryEntry> mEntries = new LinkedHashMap<>();
    private final Map<String, Long> mLoadedShellTimestamps = new LinkedHashMap<>();
    private final Set<String> mDeletedCommands = new HashSet<>();
    private boolean mStoreLoaded = false;
    private long mLastShellRefreshMs = 0L;
    private long mObservedClearGen = sClearGeneration.get();

    public CommandHistoryStore(@NonNull Context context) {
        this(
            new File(context.getApplicationContext().getFilesDir(), "termux_ai_cmd_history.json"),
            defaultShellHistoryFiles()
        );
    }

    public CommandHistoryStore(@NonNull File storeFile, @Nullable List<File> shellHistoryFiles) {
        mStoreFile = storeFile;
        mDeletedFile = new File(storeFile.getParentFile(), storeFile.getName() + ".deleted.json");
        mShellHistoryFiles = shellHistoryFiles != null ? new ArrayList<>(shellHistoryFiles) : Collections.emptyList();
    }

    private void ensureLoadedLocked() {
        long currentClearGen = sClearGeneration.get();
        if (mObservedClearGen != currentClearGen) {
            mEntries.clear();
            mLoadedShellTimestamps.clear();
            mLastShellRefreshMs = 0L;
            mObservedClearGen = currentClearGen;
            mStoreLoaded = true;
            return;
        }
        if (!mStoreLoaded) {
            mStoreLoaded = true;
            loadFromStoreFile();
        }
    }

    public synchronized void recordCommand(@Nullable String rawCommand) {
        ensureLoadedLocked();
        if (rawCommand == null) return;
        String trimmed = rawCommand.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
        // Respect bash ignorespace convention & never persist probable inline API secrets
        if (rawCommand.startsWith(" ") || SensitiveDataRedactor.containsLikelySecret(trimmed)) return;

        if (mDeletedCommands.remove(trimmed)) {
            saveDeletedFile();
        }
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
    public synchronized List<HistoryEntry> getAllEntriesSorted() {
        return searchEntries(null);
    }

    @NonNull
    public synchronized List<HistoryEntry> searchEntries(@Nullable String query) {
        ensureLoadedLocked();
        long now = System.currentTimeMillis();
        if (now - mLastShellRefreshMs >= SHELL_REFRESH_COOLDOWN_MS) {
            refreshShellHistoryFiles();
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<HistoryEntry> result = new ArrayList<>();
        for (HistoryEntry entry : mEntries.values()) {
            if (mDeletedCommands.contains(entry.command)) continue;
            if (needle.isEmpty() || entry.command.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(new HistoryEntry(entry.command, entry.lastUsedMs, entry.count));
            }
        }
        result.sort((a, b) -> {
            int tsCmp = Long.compare(b.lastUsedMs, a.lastUsedMs);
            return tsCmp != 0 ? tsCmp : Integer.compare(b.count, a.count);
        });
        return result;
    }

    public synchronized boolean deleteEntry(@NonNull String command) {
        ensureLoadedLocked();
        boolean removed = mEntries.remove(command) != null;
        boolean tombstoned = mDeletedCommands.add(command);
        if (removed) {
            saveToStoreFile();
        }
        if (tombstoned) {
            saveDeletedFile();
        }
        return removed || tombstoned;
    }

    @NonNull
    public synchronized List<SuggestionItem> matchPrefix(@NonNull String linePrefix, int limit) {
        if (linePrefix.trim().isEmpty()) return Collections.emptyList();
        ensureLoadedLocked();
        long now = System.currentTimeMillis();
        if (now - mLastShellRefreshMs >= SHELL_REFRESH_COOLDOWN_MS) {
            refreshShellHistoryFiles();
        }

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
        mDeletedCommands.clear();
        mLastShellRefreshMs = 0L;
        mStoreLoaded = true;
        mObservedClearGen = sClearGeneration.incrementAndGet();
        if (mStoreFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mStoreFile.delete();
        }
        if (mDeletedFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mDeletedFile.delete();
        }
    }

    public synchronized int size() {
        ensureLoadedLocked();
        return mEntries.size();
    }

    public synchronized void refreshShellHistoryFiles() {
        ensureLoadedLocked();
        mLastShellRefreshMs = System.currentTimeMillis();
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
                    if (cmd == null || cmd.isEmpty() || mDeletedCommands.contains(cmd) || SensitiveDataRedactor.containsLikelySecret(cmd)) continue;
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
        if (mDeletedFile.exists() && mDeletedFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(mDeletedFile)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                JSONArray delArray = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
                for (int i = 0; i < delArray.length(); i++) {
                    String cmd = delArray.optString(i, "");
                    if (!cmd.isEmpty()) mDeletedCommands.add(cmd);
                }
            } catch (Exception ignored) {}
        }
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
                if (cmd.isEmpty() || mDeletedCommands.contains(cmd)) continue;
                long ts = obj.optLong("ts", 0L);
                int count = obj.optInt("count", 1);
                mEntries.put(cmd, new HistoryEntry(cmd, ts, count));
            }
        } catch (Exception ignored) {}
    }

    private void saveDeletedFile() {
        try {
            File parent = mDeletedFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JSONArray arr = new JSONArray();
            for (String cmd : mDeletedCommands) {
                arr.put(cmd);
            }
            try (FileOutputStream fos = new FileOutputStream(mDeletedFile)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception ignored) {}
    }

    private void saveToStoreFile() {
        File tmpFile = null;
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
            tmpFile = new File(mStoreFile.getParentFile(), mStoreFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Exception ignored) {}
            }
            if (!tmpFile.renameTo(mStoreFile)) {
                if (mStoreFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    mStoreFile.delete();
                }
                if (!tmpFile.renameTo(mStoreFile)) {
                    try (FileOutputStream fos = new FileOutputStream(mStoreFile)) {
                        fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (tmpFile != null && tmpFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }
        }
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
