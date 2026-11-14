package com.termux.app.ai;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.app.ai.model.AIMessage;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIUsage;
import com.termux.app.ai.security.SensitiveDataRedactor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists AI Assistant chat history privately in app storage with automatic redaction.
 */
public class AIConversationStore {

    private static final String FILE_NAME = "termux_ai_conversation.json";
    private static final int MAX_STORED_MESSAGES = 100;

    private final File mStoreFile;

    public AIConversationStore(@NonNull Context context) {
        mStoreFile = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    @NonNull
    public synchronized List<AIMessage> loadMessages() {
        List<AIMessage> messages = new ArrayList<>();
        if (!mStoreFile.exists() || !mStoreFile.isFile()) return messages;
        try (FileInputStream fis = new FileInputStream(mStoreFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            JSONArray array = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                AIMessage.Role role = AIMessage.Role.valueOf(obj.optString("role", "USER"));
                String content = obj.optString("content", "");
                long ts = obj.optLong("timestamp", System.currentTimeMillis());
                AIMessage msg = id.isEmpty() ? new AIMessage(role, content) : new AIMessage(id, role, content, ts);
                String providerId = obj.optString("provider", "");
                if (!providerId.isEmpty()) msg.setProvider(AIProviderType.fromId(providerId));
                String modelId = obj.optString("modelId", "");
                if (!modelId.isEmpty()) msg.setModelId(modelId);
                String err = obj.optString("error", "");
                if (!err.isEmpty()) msg.setErrorMessage(err);
                if (obj.has("totalTokens")) {
                    msg.setUsage(new AIUsage(
                        obj.optInt("promptTokens", 0),
                        obj.optInt("completionTokens", 0),
                        obj.optInt("totalTokens", 0)
                    ));
                }
                messages.add(msg);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return messages;
    }

    public synchronized void saveMessages(@NonNull List<AIMessage> messages) {
        try {
            JSONArray array = new JSONArray();
            int start = Math.max(0, messages.size() - MAX_STORED_MESSAGES);
            for (int i = start; i < messages.size(); i++) {
                AIMessage msg = messages.get(i);
                if (msg.isStreaming()) continue;
                JSONObject obj = new JSONObject();
                obj.put("id", msg.getId());
                obj.put("role", msg.getRole().name());
                obj.put("content", SensitiveDataRedactor.redact(msg.getContent()));
                obj.put("timestamp", msg.getTimestampMs());
                if (msg.getProvider() != null) obj.put("provider", msg.getProvider().getId());
                if (msg.getModelId() != null) obj.put("modelId", msg.getModelId());
                if (msg.getErrorMessage() != null) obj.put("error", msg.getErrorMessage());
                if (msg.getUsage() != null) {
                    obj.put("promptTokens", msg.getUsage().getPromptTokens());
                    obj.put("completionTokens", msg.getUsage().getCompletionTokens());
                    obj.put("totalTokens", msg.getUsage().getTotalTokens());
                }
                array.put(obj);
            }
            try (FileOutputStream fos = new FileOutputStream(mStoreFile)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public synchronized void clearHistory() {
        if (mStoreFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mStoreFile.delete();
        }
    }
}
