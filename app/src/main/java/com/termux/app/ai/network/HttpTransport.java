package com.termux.app.ai.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.model.CancellationSignalHandle;

import java.io.IOException;
import java.util.Map;

public interface HttpTransport {

    interface LineConsumer {
        void onLine(@NonNull String line);
    }

    final class HttpResult {
        public final int statusCode;
        public final String body;

        public HttpResult(int statusCode, @NonNull String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    @NonNull
    HttpResult get(
        @NonNull String url,
        @NonNull Map<String, String> headers,
        @Nullable CancellationSignalHandle cancelHandle
    ) throws IOException;

    @NonNull
    HttpResult postJson(
        @NonNull String url,
        @NonNull Map<String, String> headers,
        @NonNull String jsonBody,
        @Nullable CancellationSignalHandle cancelHandle
    ) throws IOException;

    @NonNull
    HttpResult postJsonStream(
        @NonNull String url,
        @NonNull Map<String, String> headers,
        @NonNull String jsonBody,
        @NonNull LineConsumer lineConsumer,
        @Nullable CancellationSignalHandle cancelHandle
    ) throws IOException;
}
