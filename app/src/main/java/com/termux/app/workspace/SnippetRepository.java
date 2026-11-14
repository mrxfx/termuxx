package com.termux.app.workspace;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent Command Snippet store with search, category filter, favorites filter,
 * tags search, duplicate, JSON import/export, and CRUD.
 */
public class SnippetRepository {

    private final File mStoreFile;
    private final Map<String, CommandSnippet> mSnippets = new LinkedHashMap<>();
    private boolean mLoaded = false;

    public SnippetRepository(@NonNull Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "termux_ai_snippets.json"));
    }

    public SnippetRepository(@NonNull File storeFile) {
        mStoreFile = storeFile;
    }

    private synchronized void ensureLoaded() {
        if (mLoaded) return;
        mLoaded = true;
        loadFromDisk();
        if (mSnippets.isEmpty()) {
            seedStarterSnippets();
            saveToDisk();
        }
    }

    private void seedStarterSnippets() {
        long now = System.currentTimeMillis();
        addInternal(new CommandSnippet(
            "snip_linux_pkg",
            "Update Termux Packages",
            "pkg update && pkg upgrade",
            "Linux",
            "Refresh package lists and upgrade installed Termux packages",
            "pkg,apt,upgrade",
            true,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_git_status",
            "Compact Git Status & Branch",
            "git status -sb && git log --oneline -n 5",
            "Git",
            "Show short branch status and last 5 commits",
            "git,status,log",
            true,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_adb_devices",
            "ADB Wireless & Connected Devices",
            "adb devices -l",
            "ADB",
            "List connected Android debugging targets with device details",
            "adb,android,debug",
            false,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_python_venv",
            "Create & Activate Python venv",
            "python3 -m venv .venv && source .venv/bin/activate",
            "Python",
            "Initialize an isolated Python virtual environment in .venv",
            "python,venv,pip",
            false,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_node_init",
            "Inspect Node & NPM Versions",
            "node -v && npm -v",
            "Node.js",
            "Verify installed Node.js and npm toolchain versions",
            "node,npm,javascript",
            false,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_web_server",
            "Start Local Static HTTP Server",
            "python3 -m http.server 8080",
            "Web Development",
            "Serve current directory over HTTP on localhost:8080",
            "http,web,server",
            false,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_android_props",
            "Inspect Android Device Properties",
            "getprop ro.product.model && getprop ro.build.version.release && getprop ro.product.cpu.abi",
            "Android",
            "Print device model, Android OS release version, and primary CPU ABI",
            "android,getprop,abi",
            false,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_gradle_test",
            "Run Gradle Unit Tests",
            "gradle testDebugUnitTest --stacktrace",
            "Gradle",
            "Execute local unit test suite with stacktraces",
            "gradle,test,build",
            false,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_net_ports",
            "Check Active Network Interfaces",
            "ip addr show",
            "Networking",
            "Display network interfaces and assigned IP addresses",
            "ip,network,lan",
            false,
            now
        ));
        addInternal(new CommandSnippet(
            "snip_sys_disk",
            "Check Disk & Inode Usage",
            "df -h $HOME $PREFIX && du -sh $HOME/*",
            "System Administration",
            "Inspect free space and largest folders in $HOME",
            "disk,df,du,storage",
            true,
            now
        ));
    }

    private void addInternal(@NonNull CommandSnippet snippet) {
        mSnippets.put(snippet.id, snippet);
    }

    @NonNull
    public synchronized List<CommandSnippet> getAllSnippets() {
        ensureLoaded();
        return new ArrayList<>(mSnippets.values());
    }

    @Nullable
    public synchronized CommandSnippet findById(@Nullable String id) {
        if (id == null) return null;
        ensureLoaded();
        return mSnippets.get(id);
    }

    @NonNull
    public synchronized List<CommandSnippet> querySnippets(
        @Nullable String searchQuery,
        @Nullable String categoryFilter,
        boolean favoritesOnly
    ) {
        ensureLoaded();
        String needle = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        String cat = (categoryFilter == null || "All".equalsIgnoreCase(categoryFilter))
            ? null : categoryFilter.trim();

        List<CommandSnippet> out = new ArrayList<>();
        for (CommandSnippet s : mSnippets.values()) {
            if (favoritesOnly && !s.favorite) continue;
            if (cat != null && !s.category.equalsIgnoreCase(cat)) continue;
            if (!needle.isEmpty()) {
                boolean match = s.title.toLowerCase(Locale.ROOT).contains(needle)
                    || s.command.toLowerCase(Locale.ROOT).contains(needle)
                    || s.category.toLowerCase(Locale.ROOT).contains(needle)
                    || s.description.toLowerCase(Locale.ROOT).contains(needle)
                    || s.tags.toLowerCase(Locale.ROOT).contains(needle);
                if (!match) continue;
            }
            out.add(s);
        }
        out.sort((a, b) -> {
            if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
            return Long.compare(b.updatedAtMs, a.updatedAtMs);
        });
        return out;
    }

    public synchronized void saveSnippet(@NonNull CommandSnippet snippet) {
        ensureLoaded();
        mSnippets.put(snippet.id, snippet);
        saveToDisk();
    }

    @Nullable
    public synchronized CommandSnippet duplicateSnippet(@NonNull String id, @NonNull String duplicateTitle) {
        ensureLoaded();
        CommandSnippet existing = mSnippets.get(id);
        if (existing == null) return null;
        String title = duplicateTitle.trim().isEmpty() ? existing.title + " (Copy)" : duplicateTitle.trim();
        CommandSnippet copy = existing.duplicateAs(title);
        mSnippets.put(copy.id, copy);
        saveToDisk();
        return copy;
    }

    @Nullable
    public synchronized CommandSnippet toggleFavorite(@NonNull String id) {
        ensureLoaded();
        CommandSnippet existing = mSnippets.get(id);
        if (existing == null) return null;
        CommandSnippet updated = existing.withFavorite(!existing.favorite);
        mSnippets.put(id, updated);
        saveToDisk();
        return updated;
    }

    public synchronized boolean deleteSnippet(@NonNull String id) {
        ensureLoaded();
        if (mSnippets.remove(id) != null) {
            saveToDisk();
            return true;
        }
        return false;
    }

    @NonNull
    public synchronized String exportSnippetsToJson() throws JSONException {
        ensureLoaded();
        JSONArray arr = new JSONArray();
        for (CommandSnippet s : mSnippets.values()) {
            arr.put(s.toJson());
        }
        return arr.toString(2);
    }

    public synchronized int importSnippetsFromJson(@NonNull String rawJson) throws JSONException {
        ensureLoaded();
        String trimmed = rawJson.trim();
        int count = 0;
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    CommandSnippet parsed = CommandSnippet.fromJson(obj);
                    CommandSnippet toSave = mSnippets.containsKey(parsed.id)
                        ? parsed.duplicateAs(parsed.title)
                        : parsed;
                    mSnippets.put(toSave.id, toSave);
                    count++;
                }
            }
        } else {
            JSONObject obj = new JSONObject(trimmed);
            CommandSnippet parsed = CommandSnippet.fromJson(obj);
            CommandSnippet toSave = mSnippets.containsKey(parsed.id)
                ? parsed.duplicateAs(parsed.title)
                : parsed;
            mSnippets.put(toSave.id, toSave);
            count = 1;
        }
        if (count > 0) {
            saveToDisk();
        }
        return count;
    }

    public synchronized void replaceAllSnippets(@NonNull List<CommandSnippet> snippets) {
        mLoaded = true;
        mSnippets.clear();
        for (CommandSnippet s : snippets) {
            mSnippets.put(s.id, s);
        }
        saveToDisk();
    }

    private void loadFromDisk() {
        if (!mStoreFile.exists() || !mStoreFile.isFile()) return;
        try (FileInputStream fis = new FileInputStream(mStoreFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    try {
                        CommandSnippet s = CommandSnippet.fromJson(obj);
                        mSnippets.put(s.id, s);
                    } catch (JSONException ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveToDisk() {
        try {
            File parent = mStoreFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            JSONArray arr = new JSONArray();
            for (CommandSnippet s : mSnippets.values()) {
                arr.put(s.toJson());
            }
            File tmp = new File(parent, mStoreFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (!tmp.renameTo(mStoreFile)) {
                try (FileOutputStream fos = new FileOutputStream(mStoreFile)) {
                    fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }
}
