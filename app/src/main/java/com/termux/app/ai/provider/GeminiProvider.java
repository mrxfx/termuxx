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
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Google Gemini REST API provider implementing v1beta generateContent,
 * streamGenerateContent (SSE), and models endpoints.
 */
public class GeminiProvider implements AIProvider {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final HttpTransport mTransport;
    private final String mDefaultBaseUrl;

    public GeminiProvider(@NonNull HttpTransport transport) {
        this(transport, DEFAULT_BASE_URL);
    }

    public GeminiProvider(@NonNull HttpTransport transport, @NonNull String baseUrl) {
        mTransport = transport;
        mDefaultBaseUrl = stripTrailingSlash(baseUrl);
    }

    @NonNull
    @Override
    public AIProviderType getType() {
        return AIProviderType.GEMINI;
    }

    @Override
    public void sendMessage(@NonNull AIRequest request, @NonNull String apiKey,
                            @NonNull AIStreamCallback callback, @Nullable CancellationSignalHandle cancelHandle) {
        if (apiKey.trim().isEmpty()) {
            callback.onError(new AIError(AIError.Type.MISSING_API_KEY, 0,
                "Google Gemini API key is not configured. Add it in AI Settings.",
                AIProviderType.GEMINI, false));
            return;
        }
        if (cancelHandle != null && cancelHandle.isCancelled()) {
            callback.onError(new AIError(AIError.Type.CANCELLED, 0, "Request cancelled.", AIProviderType.GEMINI, false));
            return;
        }

        String model = normalizeModelName(request.getModelId());
        String baseUrl = resolveBaseUrl(request.getCustomProxyUrl());
        String payload;
        try {
            payload = buildGenerateRequestJson(request).toString();
        } catch (JSONException e) {
            callback.onError(new AIError(AIError.Type.MALFORMED_RESPONSE, 0,
                "Failed to serialize Gemini request: " + e.getMessage(), AIProviderType.GEMINI, false));
            return;
        }

        Map<String, String> headers = buildHeaders(apiKey, request.isStream());
        try {
            if (request.isStream()) {
                String url = baseUrl + "/models/" + urlEncode(model) + ":streamGenerateContent?alt=sse";
                final StringBuilder accumulated = new StringBuilder();
                final AIUsage[] lastUsage = new AIUsage[1];
                final String[] lastFinishReason = new String[1];
                final boolean[] jsonParseError = new boolean[1];

                HttpTransport.HttpResult result = mTransport.postJsonStream(url, headers, payload, line -> {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) return;
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) return;
                    try {
                        JSONObject chunk = new JSONObject(data);
                        String delta = extractTextFromResponse(chunk);
                        AIUsage usage = extractUsage(chunk);
                        if (usage != null) lastUsage[0] = usage;
                        String finish = extractFinishReason(chunk);
                        if (finish != null) lastFinishReason[0] = finish;
                        if (!delta.isEmpty()) {
                            accumulated.append(delta);
                            callback.onChunk(delta, accumulated.toString());
                        }
                    } catch (JSONException e) {
                        jsonParseError[0] = true;
                    }
                }, cancelHandle);

                if (cancelHandle != null && cancelHandle.isCancelled()) {
                    callback.onError(new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.GEMINI, false));
                    return;
                }
                if (result.statusCode < 200 || result.statusCode >= 300) {
                    callback.onError(parseHttpError(result.statusCode, result.body));
                    return;
                }
                if (accumulated.length() == 0 && jsonParseError[0]) {
                    callback.onError(new AIError(AIError.Type.MALFORMED_RESPONSE, result.statusCode,
                        "Received malformed streaming response from Gemini.", AIProviderType.GEMINI, false));
                    return;
                }
                callback.onComplete(new AIResponse(accumulated.toString(), lastUsage[0], AIProviderType.GEMINI, model, lastFinishReason[0]));
            } else {
                String url = baseUrl + "/models/" + urlEncode(model) + ":generateContent";
                HttpTransport.HttpResult result = mTransport.postJson(url, headers, payload, cancelHandle);
                if (cancelHandle != null && cancelHandle.isCancelled()) {
                    callback.onError(new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.GEMINI, false));
                    return;
                }
                if (result.statusCode < 200 || result.statusCode >= 300) {
                    callback.onError(parseHttpError(result.statusCode, result.body));
                    return;
                }
                JSONObject json = new JSONObject(result.body);
                String text = extractTextFromResponse(json);
                AIUsage usage = extractUsage(json);
                String finish = extractFinishReason(json);
                callback.onComplete(new AIResponse(text, usage, AIProviderType.GEMINI, model, finish));
            }
        } catch (JSONException e) {
            callback.onError(new AIError(AIError.Type.MALFORMED_RESPONSE, 0,
                "Invalid JSON response from Gemini: " + e.getMessage(), AIProviderType.GEMINI, false));
        } catch (IOException e) {
            callback.onError(mapIoException(e, cancelHandle));
        }
    }

    @NonNull
    @Override
    public List<AIModelInfo> listModels(@NonNull String apiKey, @Nullable String customProxyUrl) throws AIException {
        if (apiKey.trim().isEmpty()) {
            throw new AIException(new AIError(AIError.Type.MISSING_API_KEY, 0,
                "Google Gemini API key is not configured.", AIProviderType.GEMINI, false));
        }
        String url = resolveBaseUrl(customProxyUrl) + "/models?pageSize=100";
        try {
            HttpTransport.HttpResult result = mTransport.get(url, buildHeaders(apiKey, false), null);
            if (result.statusCode < 200 || result.statusCode >= 300) {
                throw new AIException(parseHttpError(result.statusCode, result.body));
            }
            JSONObject json = new JSONObject(result.body);
            JSONArray models = json.optJSONArray("models");
            List<AIModelInfo> out = new ArrayList<>();
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    JSONObject item = models.optJSONObject(i);
                    if (item == null) continue;
                    String fullName = item.optString("name", "");
                    String id = fullName.startsWith("models/") ? fullName.substring("models/".length()) : fullName;
                    if (id.isEmpty()) continue;
                    JSONArray methods = item.optJSONArray("supportedGenerationMethods");
                    boolean supportsGenerate = false;
                    if (methods != null) {
                        for (int m = 0; m < methods.length(); m++) {
                            if ("generateContent".equals(methods.optString(m))) {
                                supportsGenerate = true;
                                break;
                            }
                        }
                    }
                    if (!supportsGenerate) continue;
                    String displayName = item.optString("displayName", id);
                    int inputLimit = item.optInt("inputTokenLimit", 0);
                    out.add(new AIModelInfo(id, displayName, AIProviderType.GEMINI, false, inputLimit));
                }
            }
            return out;
        } catch (JSONException e) {
            throw new AIException(new AIError(AIError.Type.MALFORMED_RESPONSE, 0,
                "Invalid models list from Gemini: " + e.getMessage(), AIProviderType.GEMINI, false), e);
        } catch (IOException e) {
            throw new AIException(mapIoException(e, null), e);
        }
    }

    @NonNull
    @Override
    public AIModelInfo testConnection(@NonNull String apiKey, @NonNull String modelId, @Nullable String customProxyUrl) throws AIException {
        if (apiKey.trim().isEmpty()) {
            throw new AIException(new AIError(AIError.Type.MISSING_API_KEY, 0,
                "Google Gemini API key is not configured.", AIProviderType.GEMINI, false));
        }
        String model = normalizeModelName(modelId);
        String url = resolveBaseUrl(customProxyUrl) + "/models/" + urlEncode(model);
        try {
            HttpTransport.HttpResult result = mTransport.get(url, buildHeaders(apiKey, false), null);
            if (result.statusCode < 200 || result.statusCode >= 300) {
                throw new AIException(parseHttpError(result.statusCode, result.body));
            }
            JSONObject json = new JSONObject(result.body);
            String fullName = json.optString("name", "models/" + model);
            String id = fullName.startsWith("models/") ? fullName.substring("models/".length()) : fullName;
            String displayName = json.optString("displayName", id);
            int inputLimit = json.optInt("inputTokenLimit", 0);
            return new AIModelInfo(id, displayName, AIProviderType.GEMINI, false, inputLimit);
        } catch (JSONException e) {
            throw new AIException(new AIError(AIError.Type.MALFORMED_RESPONSE, 0,
                "Malformed Gemini validation response.", AIProviderType.GEMINI, false), e);
        } catch (IOException e) {
            throw new AIException(mapIoException(e, null), e);
        }
    }

    public static JSONObject buildGenerateRequestJson(@NonNull AIRequest request) throws JSONException {
        JSONObject root = new JSONObject();

        if (!request.getSystemPrompt().trim().isEmpty()) {
            JSONObject sysPart = new JSONObject().put("text", request.getSystemPrompt());
            JSONObject sysInstr = new JSONObject().put("parts", new JSONArray().put(sysPart));
            root.put("systemInstruction", sysInstr);
        }

        JSONArray contents = new JSONArray();
        for (AIMessage msg : request.getMessages()) {
            if (msg.getRole() == AIMessage.Role.SYSTEM || msg.getErrorMessage() != null) continue;
            if (msg.getContent().trim().isEmpty()) continue;
            String role = msg.getRole() == AIMessage.Role.ASSISTANT ? "model" : "user";
            JSONObject part = new JSONObject().put("text", msg.getContent());
            JSONObject contentObj = new JSONObject()
                .put("role", role)
                .put("parts", new JSONArray().put(part));
            contents.put(contentObj);
        }
        root.put("contents", contents);

        JSONObject genConfig = new JSONObject();
        genConfig.put("temperature", (double) request.getTemperature());
        genConfig.put("maxOutputTokens", request.getMaxOutputTokens());
        root.put("generationConfig", genConfig);

        return root;
    }

    public static String extractTextFromResponse(@NonNull JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return "";
        JSONObject first = candidates.optJSONObject(0);
        if (first == null) return "";
        JSONObject content = first.optJSONObject("content");
        if (content == null) return "";
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && !part.optBoolean("thought", false) && part.has("text")) {
                sb.append(part.optString("text", ""));
            }
        }
        return sb.toString();
    }

    @Nullable
    public static AIUsage extractUsage(@NonNull JSONObject root) {
        JSONObject usage = root.optJSONObject("usageMetadata");
        if (usage == null) return null;
        int prompt = usage.optInt("promptTokenCount", 0);
        int candidates = usage.optInt("candidatesTokenCount", 0);
        int total = usage.optInt("totalTokenCount", prompt + candidates);
        return new AIUsage(prompt, candidates, total);
    }

    @Nullable
    private static String extractFinishReason(@NonNull JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return null;
        JSONObject first = candidates.optJSONObject(0);
        return first != null && first.has("finishReason") ? first.optString("finishReason", null) : null;
    }

    private AIError parseHttpError(int statusCode, String body) {
        String message = "";
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject json = new JSONObject(body);
                JSONObject err = json.optJSONObject("error");
                if (err != null) {
                    message = err.optString("message", "");
                    String status = err.optString("status", "");
                    if (!status.isEmpty() && !message.isEmpty()) {
                        message = status + ": " + message;
                    }
                }
            } catch (JSONException ignored) {
                message = body;
            }
        }
        return AIError.fromHttpStatus(statusCode, SensitiveDataRedactor.redact(message), AIProviderType.GEMINI);
    }

    private AIError mapIoException(IOException e, @Nullable CancellationSignalHandle cancelHandle) {
        if (cancelHandle != null && cancelHandle.isCancelled()) {
            return new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.GEMINI, false);
        }
        if (e instanceof SocketTimeoutException) {
            return new AIError(AIError.Type.TIMEOUT, 0, "Request to Gemini timed out.", AIProviderType.GEMINI, true);
        }
        if (e instanceof UnknownHostException) {
            return new AIError(AIError.Type.NO_INTERNET, 0, "No internet connection or cannot reach Gemini API.", AIProviderType.GEMINI, true);
        }
        String msg = e.getMessage() != null ? SensitiveDataRedactor.redact(e.getMessage()) : "Network error";
        if (msg.toLowerCase().contains("cancel")) {
            return new AIError(AIError.Type.CANCELLED, 0, "Generation stopped by user.", AIProviderType.GEMINI, false);
        }
        return new AIError(AIError.Type.NO_INTERNET, 0, "Network error: " + msg, AIProviderType.GEMINI, true);
    }

    private Map<String, String> buildHeaders(String apiKey, boolean sse) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("x-goog-api-key", apiKey.trim());
        headers.put("Accept", sse ? "text/event-stream" : "application/json");
        return headers;
    }

    private String resolveBaseUrl(@Nullable String customProxyUrl) {
        if (customProxyUrl != null && !customProxyUrl.trim().isEmpty()) {
            return stripTrailingSlash(customProxyUrl.trim());
        }
        return mDefaultBaseUrl;
    }

    private static String normalizeModelName(String modelId) {
        String m = modelId == null ? "" : modelId.trim();
        if (m.startsWith("models/")) m = m.substring("models/".length());
        return m.isEmpty() ? AIProviderType.GEMINI.getDefaultModelId() : m;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String urlEncode(String val) {
        try {
            return URLEncoder.encode(val, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return val;
        }
    }
}
