package com.termux.app.workspace;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Reusable command snippet with title, category, description, tags, favorite status,
 * duplicate helper, and safe non-auto-executing terminal insertion.
 */
public final class CommandSnippet {

    public static final List<String> CATEGORIES = Arrays.asList(
        "Linux",
        "Git",
        "Android",
        "ADB",
        "Python",
        "Node.js",
        "Web Development",
        "Gradle",
        "Networking",
        "System Administration",
        "Custom"
    );

    @NonNull public final String id;
    @NonNull public final String title;
    @NonNull public final String command;
    @NonNull public final String category;
    @NonNull public final String description;
    @NonNull public final String tags;
    public final boolean favorite;
    public final long updatedAtMs;

    public CommandSnippet(
        @NonNull String id,
        @NonNull String title,
        @NonNull String command,
        @NonNull String category,
        @NonNull String description,
        boolean favorite,
        long updatedAtMs
    ) {
        this(id, title, command, category, description, "", favorite, updatedAtMs);
    }

    public CommandSnippet(
        @NonNull String id,
        @NonNull String title,
        @NonNull String command,
        @NonNull String category,
        @NonNull String description,
        @Nullable String tags,
        boolean favorite,
        long updatedAtMs
    ) {
        this.id = id.trim().isEmpty() ? UUID.randomUUID().toString() : id.trim();
        this.title = title.trim().isEmpty() ? "Untitled Snippet" : title.trim();
        this.command = command.trim();
        this.category = normalizeCategory(category);
        this.description = description.trim();
        this.tags = tags == null ? "" : tags.trim();
        this.favorite = favorite;
        this.updatedAtMs = updatedAtMs > 0 ? updatedAtMs : System.currentTimeMillis();
    }

    @NonNull
    public static String normalizeCategory(@NonNull String raw) {
        for (String c : CATEGORIES) {
            if (c.equalsIgnoreCase(raw.trim())) return c;
        }
        return "Custom";
    }

    @NonNull
    public CommandSnippet withFavorite(boolean newFavorite) {
        return new CommandSnippet(
            id, title, command, category, description, tags, newFavorite, System.currentTimeMillis()
        );
    }

    @NonNull
    public CommandSnippet duplicateAs(@NonNull String duplicateTitle) {
        String newId = "snip_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return new CommandSnippet(
            newId, duplicateTitle, command, category, description, tags, favorite, System.currentTimeMillis()
        );
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("title", title);
        obj.put("command", command);
        obj.put("category", category);
        obj.put("description", description);
        obj.put("tags", tags);
        obj.put("favorite", favorite);
        obj.put("updatedAtMs", updatedAtMs);
        return obj;
    }

    @NonNull
    public static CommandSnippet fromJson(@NonNull JSONObject obj) throws JSONException {
        String title = obj.optString("title", "").trim();
        String cmd = obj.optString("command", "").trim();
        if (title.isEmpty() || cmd.isEmpty()) {
            throw new JSONException("Snippet title and command are required");
        }
        return new CommandSnippet(
            obj.optString("id", UUID.randomUUID().toString()),
            title,
            cmd,
            obj.optString("category", "Custom"),
            obj.optString("description", ""),
            obj.optString("tags", ""),
            obj.optBoolean("favorite", false),
            obj.optLong("updatedAtMs", System.currentTimeMillis())
        );
    }
}
