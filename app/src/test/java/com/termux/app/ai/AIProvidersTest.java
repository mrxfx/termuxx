package com.termux.app.ai;

import com.termux.app.ai.model.AIError;
import com.termux.app.ai.model.AIMessage;
import com.termux.app.ai.model.AIModelInfo;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIRequest;
import com.termux.app.ai.model.AIResponse;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.CancellationSignalHandle;
import com.termux.app.ai.network.UrlConnectionHttpTransport;
import com.termux.app.ai.provider.AIProvider;
import com.termux.app.ai.provider.GeminiProvider;
import com.termux.app.ai.provider.OpenRouterProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AIProvidersTest {

    interface RouteHandler {
        void handle(String method, String path, Map<String, String> headers, String body, OutputStream out) throws IOException;
    }

    private ServerSocket mServerSocket;
    private ExecutorService mServerPool;
    private volatile boolean mRunning = true;
    private final Map<String, RouteHandler> mRoutes = Collections.synchronizedMap(new LinkedHashMap<>());
    private String mBaseUrl;

    @Before
    public void setUp() throws Exception {
        mServerSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        mServerPool = Executors.newCachedThreadPool();
        mBaseUrl = "http://127.0.0.1:" + mServerSocket.getLocalPort();
        mServerPool.submit(() -> {
            while (mRunning && !mServerSocket.isClosed()) {
                try {
                    Socket client = mServerSocket.accept();
                    mServerPool.submit(() -> handleClient(client));
                } catch (IOException ignored) {
                    break;
                }
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        mRunning = false;
        if (mServerSocket != null && !mServerSocket.isClosed()) {
            mServerSocket.close();
        }
        if (mServerPool != null) {
            mServerPool.shutdownNow();
        }
    }

    private void handleClient(Socket client) {
        try (Socket s = client) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String fullPath = parts.length > 1 ? parts[1] : "/";
            int qIdx = fullPath.indexOf('?');
            String path = qIdx >= 0 ? fullPath.substring(0, qIdx) : fullPath;

            Map<String, String> headers = new LinkedHashMap<>();
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String val = line.substring(colon + 1).trim();
                    headers.put(name, val);
                    if ("content-length".equals(name)) {
                        contentLength = Integer.parseInt(val);
                    }
                }
            }
            char[] bodyChars = new char[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int n = reader.read(bodyChars, totalRead, contentLength - totalRead);
                if (n < 0) break;
                totalRead += n;
            }
            String body = new String(bodyChars, 0, totalRead);

            OutputStream out = s.getOutputStream();
            RouteHandler handler = mRoutes.get(path);
            if (handler != null) {
                handler.handle(method, fullPath, headers, body, out);
            } else {
                writeResponse(out, 404, "application/json", "{\"error\":{\"message\":\"Route not found\"}}");
            }
        } catch (Exception ignored) {}
    }

    private static void writeResponse(OutputStream out, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " OK\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    @Test
    public void testGeminiSyncStreamingModelsAndErrors() throws Exception {
        final AtomicReference<String> seenHeaderKey = new AtomicReference<>();

        mRoutes.put("/v1beta/models/gemini-2.5-flash:generateContent", (method, path, headers, body, out) -> {
            seenHeaderKey.set(headers.get("x-goog-api-key"));
            String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ls -la\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"totalTokenCount\":15}}";
            writeResponse(out, 200, "application/json", json);
        });

        mRoutes.put("/v1beta/models/gemini-2.5-flash:streamGenerateContent", (method, path, headers, body, out) -> {
            String sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello \"}]}}]}\n\n"
                + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Termux\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5}}\n\n";
            writeResponse(out, 200, "text/event-stream", sse);
        });

        mRoutes.put("/v1beta/models", (method, path, headers, body, out) -> {
            String modelsJson = "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"displayName\":\"Gemini 2.5 Flash\",\"supportedGenerationMethods\":[\"generateContent\"],\"inputTokenLimit\":1048576}]}";
            writeResponse(out, 200, "application/json", modelsJson);
        });

        GeminiProvider provider = new GeminiProvider(new UrlConnectionHttpTransport(), mBaseUrl + "/v1beta");
        AIRequest syncReq = new AIRequest(
            Collections.singletonList(new AIMessage(AIMessage.Role.USER, "list files")),
            "You are Termux AI",
            "gemini-2.5-flash",
            0.2f,
            512,
            false,
            null
        );

        AtomicReference<AIResponse> syncResp = new AtomicReference<>();
        provider.sendMessage(syncReq, "AIzaTestKey1234567890", new AIStreamCallback() {
            @Override public void onChunk(String delta, String accumulated) {}
            @Override public void onComplete(AIResponse response) { syncResp.set(response); }
            @Override public void onError(AIError error) { fail("Unexpected error: " + error.getMessage()); }
        }, null);

        assertEquals("AIzaTestKey1234567890", seenHeaderKey.get());
        assertNotNull(syncResp.get());
        assertEquals("ls -la", syncResp.get().getText());
        assertEquals(15, syncResp.get().getUsage().getTotalTokens());

        // SSE Streaming check
        AIRequest streamReq = new AIRequest(
            Collections.singletonList(new AIMessage(AIMessage.Role.USER, "greet")),
            "",
            "gemini-2.5-flash",
            0.2f,
            512,
            true,
            null
        );
        List<String> chunks = new ArrayList<>();
        AtomicReference<AIResponse> streamResp = new AtomicReference<>();
        provider.sendMessage(streamReq, "AIzaTestKey1234567890", new AIStreamCallback() {
            @Override public void onChunk(String delta, String accumulated) { chunks.add(delta); }
            @Override public void onComplete(AIResponse response) { streamResp.set(response); }
            @Override public void onError(AIError error) { fail("Unexpected stream error: " + error.getMessage()); }
        }, null);

        assertEquals(2, chunks.size());
        assertEquals("Hello Termux", streamResp.get().getText());

        // List models check
        List<AIModelInfo> models = provider.listModels("AIzaTestKey1234567890", null);
        assertEquals(1, models.size());
        assertEquals("gemini-2.5-flash", models.get(0).getId());
    }

    @Test
    public void testOpenRouterFreeModelsStreamingAndAuthHeaders() throws Exception {
        final AtomicReference<String> authHeader = new AtomicReference<>();

        mRoutes.put("/api/v1/chat/completions", (method, path, headers, body, out) -> {
            authHeader.set(headers.get("authorization"));
            String sse = ": OPENROUTER PROCESSING\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"git \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"status\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":2,\"total_tokens\":8}}\n\n"
                + "data: [DONE]\n\n";
            writeResponse(out, 200, "text/event-stream", sse);
        });

        mRoutes.put("/api/v1/models", (method, path, headers, body, out) -> {
            String modelsJson = "{\"data\":["
                + "{\"id\":\"meta-llama/llama-3.3-70b-instruct:free\",\"name\":\"Llama 3.3 70B\",\"context_length\":131072,\"pricing\":{\"prompt\":\"0\",\"completion\":\"0\"}},"
                + "{\"id\":\"openai/gpt-4o-mini\",\"name\":\"GPT-4o Mini\",\"context_length\":128000,\"pricing\":{\"prompt\":\"0.00015\",\"completion\":\"0.0006\"}}"
                + "]}";
            writeResponse(out, 200, "application/json", modelsJson);
        });

        OpenRouterProvider provider = new OpenRouterProvider(new UrlConnectionHttpTransport(), mBaseUrl + "/api/v1");
        AIRequest req = new AIRequest(
            Collections.singletonList(new AIMessage(AIMessage.Role.USER, "git check")),
            "system",
            "meta-llama/llama-3.3-70b-instruct:free",
            0.3f,
            1024,
            true,
            null
        );

        AtomicReference<AIResponse> respRef = new AtomicReference<>();
        provider.sendMessage(req, "sk-or-v1-testsecretkey", new AIStreamCallback() {
            @Override public void onChunk(String delta, String accumulated) {}
            @Override public void onComplete(AIResponse response) { respRef.set(response); }
            @Override public void onError(AIError error) { fail("Unexpected error: " + error.getMessage()); }
        }, null);

        assertEquals("Bearer sk-or-v1-testsecretkey", authHeader.get());
        assertEquals("git status", respRef.get().getText());
        assertEquals(8, respRef.get().getUsage().getTotalTokens());

        List<AIModelInfo> models = provider.listModels("sk-or-v1-testsecretkey", null);
        assertEquals(2, models.size());
        assertTrue(models.get(0).isFree());
        assertFalse(models.get(1).isFree());
    }

    @Test
    public void testRequestManagerRespectsStrictExplicitFallbackAndRetries() throws Exception {
        AtomicInteger geminiCalls = new AtomicInteger(0);
        AtomicInteger openRouterCalls = new AtomicInteger(0);

        mRoutes.put("/v1beta/models/gemini-2.5-flash:generateContent", (method, path, headers, body, out) -> {
            geminiCalls.incrementAndGet();
            writeResponse(out, 429, "application/json", "{\"error\":{\"message\":\"Rate limit hit\"}}");
        });
        mRoutes.put("/api/v1/chat/completions", (method, path, headers, body, out) -> {
            openRouterCalls.incrementAndGet();
            writeResponse(out, 200, "application/json", "{\"choices\":[{\"message\":{\"content\":\"fallback worked\"}}]}");
        });

        UrlConnectionHttpTransport transport = new UrlConnectionHttpTransport();
        Map<AIProviderType, AIProvider> providers = new EnumMap<>(AIProviderType.class);
        providers.put(AIProviderType.GEMINI, new GeminiProvider(transport, mBaseUrl + "/v1beta"));
        providers.put(AIProviderType.OPENROUTER, new OpenRouterProvider(transport, mBaseUrl + "/api/v1"));

        AIRequestManager manager = new AIRequestManager(providers, Executors.newSingleThreadExecutor(), 10L);
        AIRequest geminiReq = new AIRequest(Collections.singletonList(new AIMessage(AIMessage.Role.USER, "hi")), "", "gemini-2.5-flash", 0.2f, 256, false, null);
        AIRequest orReq = new AIRequest(Collections.singletonList(new AIMessage(AIMessage.Role.USER, "hi")), "", "meta-llama/llama-3.3-70b-instruct:free", 0.2f, 256, false, null);

        // 1. When allowExplicitFallback is FALSE -> MUST NOT call OpenRouter even with rate-limit
        CountDownLatch noFallbackLatch = new CountDownLatch(1);
        AtomicReference<AIError> noFallbackError = new AtomicReference<>();
        manager.executeRequest(AIProviderType.GEMINI, "key1", geminiReq,
            false, AIProviderType.OPENROUTER, "key2", orReq,
            new AIStreamCallback() {
                @Override public void onChunk(String delta, String accumulated) {}
                @Override public void onComplete(AIResponse response) { fail("Should not succeed"); }
                @Override public void onError(AIError error) {
                    noFallbackError.set(error);
                    noFallbackLatch.countDown();
                }
            }, new CancellationSignalHandle());

        assertTrue(noFallbackLatch.await(5, TimeUnit.SECONDS));
        assertEquals(AIError.Type.RATE_LIMITED, noFallbackError.get().getType());
        assertEquals(2, geminiCalls.get()); // 1 initial + 1 transient retry
        assertEquals(0, openRouterCalls.get());

        // 2. When allowExplicitFallback is TRUE -> falls back to OpenRouter after retries
        CountDownLatch fallbackLatch = new CountDownLatch(1);
        AtomicReference<AIResponse> fallbackResp = new AtomicReference<>();
        manager.executeRequest(AIProviderType.GEMINI, "key1", geminiReq,
            true, AIProviderType.OPENROUTER, "key2", orReq,
            new AIStreamCallback() {
                @Override public void onChunk(String delta, String accumulated) {}
                @Override public void onComplete(AIResponse response) {
                    fallbackResp.set(response);
                    fallbackLatch.countDown();
                }
                @Override public void onError(AIError error) { fail("Fallback should succeed: " + error.getMessage()); }
            }, new CancellationSignalHandle());

        assertTrue(fallbackLatch.await(5, TimeUnit.SECONDS));
        assertEquals("fallback worked", fallbackResp.get().getText());
        assertEquals(AIProviderType.OPENROUTER, fallbackResp.get().getProvider());
        assertEquals(1, openRouterCalls.get());
    }
}
