package com.termux.app.ai.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.AIError;
import com.termux.app.ai.model.AIException;
import com.termux.app.ai.model.AIMessage;
import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIRequest;
import com.termux.app.ai.model.AIResponse;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.AIUsage;
import com.termux.app.ai.model.CancellationSignalHandle;
import com.termux.app.ai.network.HttpTransport;
import com.termux.app.ai.security.SensitiveDataRedactor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real OpenRouter REST API provider implementing OpenAI-compatible chat completions,
 * SSE streaming, and models listing (including free models).
 */
public class OpenRouterProvider implements AIProvider {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

    private final HttpTransport mTransport;
    private final String mDefaultBaseUrl;

    public OpenRouterProvider(@NonNull HttpTransport transport) {
        this(transport, DEFAULT_BASE_URL);
    }

    public OpenRouterProvider(@NonNull HttpTransport transport, @NonNull String baseUrl) {
        mTransport = transport;
        mDefaultBaseUrl = stripTrailingSlash(baseUrl);
    }

    @NonNull
    @Override
    public AIProviderType getType() {
        return AIProviderType.OPENROUTER;
    }

    @Override
    public void sendMessage(@NonNull AIRequest request, @NonNull String apiKey,
                            @NonNull AIStreamCallback callback, @Nullable CancellationSignalHandle cancelHandle) {
        if (apiKey.trim().isEmpty()) {
            callback.onError(new AIError(AIError.Type.MISSING_API_KEY, 0,
                "OpenRouter API key is not configured. Add it in AI Settings.",
                AIProviderType.OPENROUTER, false));
            return;
        }
        if (cancelHandle != null && cancelHandle.isCancelled()) {
            callback.onError(new AIError(AIError.Type.CANCELLED, 0, "Request cancelled.", AIProviderType.OPENROUTER, false));
            return;
        }

        String modelId = request.getModelId().trim().isEmpty()
            ? AIProviderType.OPENROUTER.getDefaultModelId() : request.getModelId().trim();
        String baseUrl = resolveBaseUrl(request.getCustomProxyUrl());
        String url = baseUrl + "/chat/completions";

        String payload;
        try {
            payload = buildChatRequestJson(request, modelId).toString();
        } catch (JSONException e) {
            callback.onError(new AIError(AIError.Type.MALFORMED_RESPONSE, 0,
                "Failed to serialize OpenRouter request: " + e.getMessage(), AIProviderType.OPENROUTER, false));
            return;
        }

        Map<String, String> headers = buildHeaders(apiKey, request.isStream());
        try {
            if (request.isStream()) {
                final StringBuilder accumulated = new StringBuilder();
                final AIUsage[] lastUsage = new AIUsage[1];
                final String[] lastFinish = new String[1];
                final boolean[] jsonParseError = new boolean[1];
                final AIError[] inlineStreamError = new AIError[1];

                HttpTransport.HttpResult result = mTransport.postJsonStream(url, headers, payload, line -> {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) return; // SSE keepalive comment
                    if (!trimmed.startsWith("data:")) return;
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) return;

                    try {
                        JSONObject chunk = new JSONObject(data);
                        if (chunk.has("error")) {
                            JSONObject err = chunk.optJSONObject("error");
                            int code = err != null ? err.optInt("code", 500) : 500;
                            String msg = err != null ? err.optString("message", "OpenRouter stream error") : "OpenRouter stream error";
                            inlineStreamError[0] = AIError.fromHttpStatus(code, msg, AIProviderType.OPENROUTER);
                            return;
                        }
                        AIUsage usage = extractUsage(chunk);
                        if (usage != null) lastUsage[0] = usage;
                        String finish = extractFinishReason(chunk);
                        if (finish != null) lastFinish[0] = finish;
                        String delta = extractDeltaText(chunk);
                        if (!delta.isEmpty()) {
                            accumulated.append(delta);
                            callback.onChunk(delta, accumulated.toString());
                        }
                    } catch (JSONException e) {
                        jsonParseError[0] = true;
                    }
                }, cancelHandle);

                if (cancelHandle != null && cancelHandle.isCancelled()) {
                    callback.onError(new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.OPENROUTER, false));
                    return;
                }
                if (result.statusCode < 200 || result.statusCode >= 300) {
                    callback.onError(parseHttpError(result.statusCode, result.body));
                    return;
                }
                if (inlineStreamError[0] != null) {
                    callback.onError(inlineStreamError[0]);
                    return;
                }
                if (accumulated.length() == 0 && jsonParseError[0]) {
                    callback.onError(new AIError(AIError.Type.MALFORMED_RESPONSE, result.statusCode,
                        "Received malformed streaming response from OpenRouter.", AIProviderType.OPENROUTER, false));
                    return;
                }
                callback.onComplete(new AIResponse(accumulated.toString(), lastUsage[0], AIProviderType.OPENROUTER, modelId, lastFinish[0]));
            } else {
                HttpTransport.HttpResult result = mTransport.postJson(url, headers, payload, cancelHandle);
                if (cancelHandle != null && cancelHandle.isCancelled()) {
                    callback.onError(new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.OPENROUTER, false));
                    return;
                }
                if (result.statusCode < 200 || result.statusCode >= 300) {
                    callback.onError(parseHttpError(result.statusCode, result.body));
                    return;
                }
                JSONObject json = new JSONObject(result.body);
                String text = extractNonStreamText(json);
                AIUsage usage = extractUsage(json);
                String finish = extractFinishReason(json);
                callback.onComplete(new AIResponse(text, usage, AIProviderType.OPENROUTER, modelId, finish));
            }
        } catch (JSONException e) {
            callback.onError(new AIError(AIError.Type.MALFORMED_RESPONSE, 0,
                "Invalid JSON response from OpenRouter: " + e.getMessage(), AIProviderType.OPENROUTER, false));
        } catch (IOException e) {
            callback.onError(mapIoException(e, cancelHandle));
        }
    }

    @NonNull
    @Override
    public List<AIModelInfo> listModels(@NonNull String apiKey, @Nullable String customProxyUrl) throws AIException {
        if (apiKey.trim().isEmpty()) {
            throw new AIException(new AIError(AIError.Type.MISSING_API_KEY, 0,
                "OpenRouter API key is not configured.", AIProviderType.OPENROUTER, false));
        }
        String url = resolveBaseUrl(customProxyUrl) + "/models";
        try {
            HttpTransport.HttpResult result = mTransport.get(url, buildHeaders(apiKey, false), null);
            if (result.statusCode < 200 || result.statusCode >= 300) {
                throw new AIException(parseHttpError(result.statusCode, result.body));
            }
            JSONObject json = new JSONObject(result.body);
            JSONArray data = json.optJSONArray("data");
            List<AIModelInfo> out = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (id.isEmpty()) continue;
                    String name = item.optString("name", id);
                    int contextLength = item.optInt("context_length", 0);
                    boolean isFree = id.endsWith(":free");
                    if (!isFree) {
                        JSONObject pricing = item.optJSONObject("pricing");
                        if (pricing != null) {
                            String promptCost = pricing.optString("prompt", "");
                            String completionCost = pricing.optString("completion", "");
                            isFree = "0".equals(promptCost) && "0".equals(completionCost);
                        }
                    }
                    out.add(new AIModelInfo(id, name, AIProviderType.OPENROUTER, isFree, contextLength));
                }
            }
            return out;
        } catch (JSONException e) {
            throw new AIException(new AIError(AIError.Type.MALFORMED_RESPONSE, 0,
                "Invalid models list from OpenRouter: " + e.getMessage(), AIProviderType.OPENROUTER, false), e);
        } catch (IOException e) {
            throw new AIException(mapIoException(e, null), e);
        }
    }

    @NonNull
    @Override
    public AIModelInfo testConnection(@NonNull String apiKey, @NonNull String modelId, @Nullable String customProxyUrl) throws AIException {
        if (apiKey.trim().isEmpty()) {
            throw new AIException(new AIError(AIError.Type.MISSING_API_KEY, 0,
                "OpenRouter API key is not configured.", AIProviderType.OPENROUTER, false));
        }
        // Validate auth key against OpenRouter /auth/key or fallback to /models
        String authUrl = resolveBaseUrl(customProxyUrl) + "/auth/key";
        try {
            HttpTransport.HttpResult result = mTransport.get(authUrl, buildHeaders(apiKey, false), null);
            if (result.statusCode == 404) {
                List<AIModelInfo> models = listModels(apiKey, customProxyUrl);
                for (AIModelInfo info : models) {
                    if (info.getId().equals(modelId)) return info;
                }
                return new AIModelInfo(modelId, modelId, AIProviderType.OPENROUTER, modelId.endsWith(":free"), 0);
            }
            if (result.statusCode < 200 || result.statusCode >= 300) {
                throw new AIException(parseHttpError(result.statusCode, result.body));
            }
            return new AIModelInfo(modelId, modelId, AIProviderType.OPENROUTER, modelId.endsWith(":free"), 0);
        } catch (IOException e) {
            throw new AIException(mapIoException(e, null), e);
        }
    }

    public static JSONObject buildChatRequestJson(@NonNull AIRequest request, @NonNull String modelId) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("model", modelId);
        root.put("stream", request.isStream());
        root.put("temperature", (double) request.getTemperature());
        root.put("max_tokens", request.getMaxOutputTokens());

        JSONArray messages = new JSONArray();
        if (!request.getSystemPrompt().trim().isEmpty()) {
            messages.put(new JSONObject()
                .put("role", "system")
                .put("content", request.getSystemPrompt()));
        }
        for (AIMessage msg : request.getMessages()) {
            if (msg.getRole() == AIMessage.Role.SYSTEM || msg.getErrorMessage() != null) continue;
            if (msg.getContent().trim().isEmpty()) continue;
            String role = msg.getRole() == AIMessage.Role.ASSISTANT ? "assistant" : "user";
            messages.put(new JSONObject()
                .put("role", role)
                .put("content", msg.getContent()));
        }
        root.put("messages", messages);
        return root;
    }

    public static String extractDeltaText(@NonNull JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject first = choices.optJSONObject(0);
        if (first == null) return "";
        JSONObject delta = first.optJSONObject("delta");
        if (delta == null) return "";
        Object content = delta.opt("content");
        return content instanceof String ? (String) content : "";
    }

    public static String extractNonStreamText(@NonNull JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject first = choices.optJSONObject(0);
        if (first == null) return "";
        JSONObject message = first.optJSONObject("message");
        if (message == null) return "";
        Object content = message.opt("content");
        return content instanceof String ? (String) content : "";
    }

    @Nullable
    public static AIUsage extractUsage(@NonNull JSONObject root) {
        JSONObject usage = root.optJSONObject("usage");
        if (usage == null) return null;
        int prompt = usage.optInt("prompt_tokens", 0);
        int completion = usage.optInt("completion_tokens", 0);
        int total = usage.optInt("total_tokens", prompt + completion);
        return new AIUsage(prompt, completion, total);
    }

    @Nullable
    private static String extractFinishReason(@NonNull JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject first = choices.optJSONObject(0);
        if (first == null || first.isNull("finish_reason")) return null;
        return first.optString("finish_reason", null);
    }

    private AIError parseHttpError(int statusCode, String body) {
        String message = "";
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject json = new JSONObject(body);
                JSONObject err = json.optJSONObject("error");
                if (err != null) {
                    message = err.optString("message", "");
                }
            } catch (JSONException ignored) {
                message = body;
            }
        }
        return AIError.fromHttpStatus(statusCode, SensitiveDataRedactor.redact(message), AIProviderType.OPENROUTER);
    }

    private AIError mapIoException(IOException e, @Nullable CancellationSignalHandle cancelHandle) {
        if (cancelHandle != null && cancelHandle.isCancelled()) {
            return new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.OPENROUTER, false);
        }
        if (e instanceof SocketTimeoutException) {
            return new AIError(AIError.Type.TIMEOUT, 0, "Request to OpenRouter timed out.", AIProviderType.OPENROUTER, true);
        }
        if (e instanceof UnknownHostException) {
            return new AIError(AIError.Type.NO_INTERNET, 0, "No internet connection or cannot reach OpenRouter.", AIProviderType.OPENROUTER, true);
        }
        String msg = e.getMessage() != null ? SensitiveDataRedactor.redact(e.getMessage()) : "Network error";
        if (msg.toLowerCase().contains("cancel")) {
            return new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.OPENROUTER, false);
        }
        return new AIError(AIError.Type.NO_INTERNET, 0, "Network error: " + msg, AIProviderType.OPENROUTER, true);
    }

    private Map<String, String> buildHeaders(String apiKey, boolean sse) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("Authorization", "Bearer " + apiKey.trim());
        headers.put("HTTP-Referer", "https://termux.ai.local");
        headers.put("X-Title", "Termux AI");
        headers.put("Accept", sse ? "text/event-stream" : "application/json");
        return headers;
    }

    private String resolveBaseUrl(@Nullable String customProxyUrl) {
        if (customProxyUrl != null && !customProxyUrl.trim().isEmpty()) {
            return stripTrailingSlash(customProxyUrl.trim());
        }
        return mDefaultBaseUrl;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
