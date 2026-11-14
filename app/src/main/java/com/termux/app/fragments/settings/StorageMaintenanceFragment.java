package com.termux.app.fragments.settings;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.studio.ShellConfigManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Inspects real on-device disk usage for Termux directories, AI conversation stores,
 * autocomplete history, and Terminal Studio configuration backups.
 */
@Keep
public class StorageMaintenanceFragment extends PreferenceFragmentCompat {

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;
        setPreferencesFromResource(R.xml.storage_preferences, rootKey);

        refreshDiskUsage(context);

        Preference clearCachesPref = findPreference("storage_clear_caches");
        if (clearCachesPref != null) {
            clearCachesPref.setOnPreferenceClickListener(p -> {
                showClearCachesConfirmation(context);
                return true;
            });
        }

        Preference backupGuidePref = findPreference("storage_backup_guide");
        if (backupGuidePref != null) {
            backupGuidePref.setOnPreferenceClickListener(p -> {
                showBackupGuideDialog(context);
                return true;
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
        mMainHandler.removeCallbacksAndMessages(null);
    }

    private void refreshDiskUsage(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        mExecutor.submit(() -> {
            ShellConfigManager studioConfig = new ShellConfigManager();
            long homeBytes = computeDirectorySizeSafe(studioConfig.getHomeDir(), 0);
            long prefixBytes = computeDirectorySizeSafe(studioConfig.getPrefixDir(), 0);
            File aiFile = new File(appContext.getFilesDir(), "termux_ai_conversation.json");
            long aiBytes = aiFile.exists() ? aiFile.length() : 0L;
            File acFile = new File(appContext.getFilesDir(), "termux_ai_cmd_history.json");
            long acBytes = acFile.exists() ? acFile.length() : 0L;
            int backupCount = studioConfig.listAllBackups().size();
            long backupBytes = studioConfig.getBackupsTotalSizeBytes();
            long cacheBytes = computeDirectorySizeSafe(appContext.getCacheDir(), 0)
                + computeDirectorySizeSafe(appContext.getCodeCacheDir(), 0);

            mMainHandler.post(() -> {
                if (!isAdded()) return;
                setPrefSummary("storage_home_size", Formatter.formatFileSize(appContext, homeBytes));
                setPrefSummary("storage_prefix_size", Formatter.formatFileSize(appContext, prefixBytes));
                setPrefSummary("storage_ai_history_size", Formatter.formatFileSize(appContext, aiBytes));
                setPrefSummary("storage_ac_history_size", Formatter.formatFileSize(appContext, acBytes));
                setPrefSummary(
                    "storage_studio_backups_size",
                    backupCount + " files · " + Formatter.formatFileSize(appContext, backupBytes)
                );
                setPrefSummary(
                    "storage_clear_caches",
                    "Temporary cache size: " + Formatter.formatFileSize(appContext, cacheBytes)
                );
            });
        });
    }

    private void setPrefSummary(@NonNull String key, @NonNull String summary) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(summary);
        }
    }

    private void showClearCachesConfirmation(@NonNull Context context) {
        new AlertDialog.Builder(context)
            .setTitle(R.string.storage_clear_caches_title)
            .setMessage("Delete temporary Android app cache files? Your $HOME files, packages, and AI keys will not be touched.")
            .setPositiveButton("Clear Caches", (d, w) -> {
                mExecutor.submit(() -> {
                    deleteChildrenSafe(context.getCacheDir());
                    deleteChildrenSafe(context.getCodeCacheDir());
                    mMainHandler.post(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(context, "Temporary caches cleared", Toast.LENGTH_SHORT).show();
                        refreshDiskUsage(context);
                    });
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showBackupGuideDialog(@NonNull Context context) {
        final String backupCmd = "tar -zcf /sdcard/Download/termux-backup.tar.gz -C /data/data/com.termux/files ./home ./usr";
        new AlertDialog.Builder(context)
            .setTitle(R.string.storage_backup_guide_title)
            .setMessage("Run inside Termux to archive your home directory, ~/.termux configs, and installed packages:\n\n" + backupCmd)
            .setPositiveButton("Copy Command", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Termux Backup Command", backupCmd));
                    Toast.makeText(context, R.string.ai_code_copied, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private static long computeDirectorySizeSafe(@NonNull File dir, int depth) {
        if (!dir.exists() || depth > 24) return 0L;
        if (dir.isFile()) return dir.length();
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            try {
                // Skip symlinks to avoid traversing external loops
                if (!child.getAbsolutePath().equals(child.getCanonicalPath())) continue;
                if (child.isFile()) {
                    total += child.length();
                } else if (child.isDirectory()) {
                    total += computeDirectorySizeSafe(child, depth + 1);
                }
            } catch (Exception ignored) {}
        }
        return total;
    }

    private static void deleteChildrenSafe(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                deleteChildrenSafe(child);
            }
            //noinspection ResultOfMethodCallIgnored
            child.delete();
        }
    }
}
