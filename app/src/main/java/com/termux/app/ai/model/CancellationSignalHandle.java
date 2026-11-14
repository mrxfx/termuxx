package com.termux.app.ai.model;

import androidx.annotation.Nullable;

import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class CancellationSignalHandle {

    private final AtomicBoolean mCancelled = new AtomicBoolean(false);
    private final AtomicReference<HttpURLConnection> mActiveConnection = new AtomicReference<>(null);
    private final AtomicReference<Runnable> mOnCancelHook = new AtomicReference<>(null);

    public boolean isCancelled() {
        return mCancelled.get();
    }

    public void setActiveConnection(@Nullable HttpURLConnection connection) {
        mActiveConnection.set(connection);
        if (mCancelled.get() && connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {}
        }
    }

    public void setOnCancelHook(@Nullable Runnable onCancelHook) {
        mOnCancelHook.set(onCancelHook);
        if (mCancelled.get() && onCancelHook != null) {
            onCancelHook.run();
        }
    }

    public void cancel() {
        if (mCancelled.compareAndSet(false, true)) {
            HttpURLConnection conn = mActiveConnection.getAndSet(null);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
            Runnable hook = mOnCancelHook.getAndSet(null);
            if (hook != null) {
                try {
                    hook.run();
                } catch (Exception ignored) {}
            }
        }
    }
}
