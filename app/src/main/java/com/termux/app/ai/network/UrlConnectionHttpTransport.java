package com.termux.app.ai.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.CancellationSignalHandle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class UrlConnectionHttpTransport implements HttpTransport {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;

    private final int mConnectTimeoutMs;
    private final int mReadTimeoutMs;

    public UrlConnectionHttpTransport() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public UrlConnectionHttpTransport(int connectTimeoutMs, int readTimeoutMs) {
        mConnectTimeoutMs = connectTimeoutMs;
        mReadTimeoutMs = readTimeoutMs;
    }

    @NonNull
    @Override
    public HttpResult get(@NonNull String url, @NonNull Map<String, String> headers, @Nullable CancellationSignalHandle cancelHandle) throws IOException {
        if (cancelHandle != null && cancelHandle.isCancelled()) {
            throw new IOException("Request cancelled");
        }
        HttpURLConnection conn = openConnection(url, "GET", headers, cancelHandle);
        try {
            int code = conn.getResponseCode();
            String body = readFully(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            return new HttpResult(code, body);
        } finally {
            if (cancelHandle != null) cancelHandle.setActiveConnection(null);
            conn.disconnect();
        }
    }

    @NonNull
    @Override
    public HttpResult postJson(@NonNull String url, @NonNull Map<String, String> headers, @NonNull String jsonBody, @Nullable CancellationSignalHandle cancelHandle) throws IOException {
        if (cancelHandle != null && cancelHandle.isCancelled()) {
            throw new IOException("Request cancelled");
        }
        HttpURLConnection conn = openConnection(url, "POST", headers, cancelHandle);
        try {
            writeBody(conn, jsonBody);
            int code = conn.getResponseCode();
            String body = readFully(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            return new HttpResult(code, body);
        } finally {
            if (cancelHandle != null) cancelHandle.setActiveConnection(null);
            conn.disconnect();
        }
    }

    @NonNull
    @Override
    public HttpResult postJsonStream(@NonNull String url, @NonNull Map<String, String> headers, @NonNull String jsonBody, @NonNull LineConsumer lineConsumer, @Nullable CancellationSignalHandle cancelHandle) throws IOException {
        if (cancelHandle != null && cancelHandle.isCancelled()) {
            throw new IOException("Request cancelled");
        }
        HttpURLConnection conn = openConnection(url, "POST", headers, cancelHandle);
        try {
            writeBody(conn, jsonBody);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream is = conn.getInputStream();
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (cancelHandle != null && cancelHandle.isCancelled()) {
                                throw new IOException("Request cancelled");
                            }
                            lineConsumer.onLine(line);
                        }
                    }
                }
                return new HttpResult(code, "");
            } else {
                String errorBody = readFully(conn.getErrorStream());
                return new HttpResult(code, errorBody);
            }
        } finally {
            if (cancelHandle != null) cancelHandle.setActiveConnection(null);
            conn.disconnect();
        }
    }

    private HttpURLConnection openConnection(String urlStr, String method, Map<String, String> headers, @Nullable CancellationSignalHandle cancelHandle) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(mConnectTimeoutMs);
        conn.setReadTimeout(mReadTimeoutMs);
        conn.setUseCaches(false);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        if ("POST".equals(method)) {
            conn.setDoOutput(true);
        }
        if (cancelHandle != null) {
            cancelHandle.setActiveConnection(conn);
        }
        return conn;
    }

    private static void writeBody(HttpURLConnection conn, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
    }

    private static String readFully(@Nullable InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
