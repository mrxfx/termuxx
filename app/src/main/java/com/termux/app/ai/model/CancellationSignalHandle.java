package com.termux.app.ai.model;

import android.os.Looper;

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
            disconnectOffMainThread(connection);
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
                disconnectOffMainThread(conn);
            }
            Runnable hook = mOnCancelHook.getAndSet(null);
            if (hook != null) {
                try {
                    hook.run();
                } catch (Exception ignored) {}
            }
        }
    }

    private static void disconnectOffMainThread(HttpURLConnection conn) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread t = new Thread(() -> {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }, "termux-ai-cancel");
            t.setDaemon(true);
            t.start();
        } else {
            try {
                conn.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
