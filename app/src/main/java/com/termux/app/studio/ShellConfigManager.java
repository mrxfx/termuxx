package com.termux.app.studio;

import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Atomic, idempotent shell and ~/.termux configuration manager with automatic backups and restore.
 * Never deletes user custom configuration lines outside explicit TERMUX_AI_STUDIO marker blocks.
 */
public class ShellConfigManager {

    private static final String PREFIX_HOME = "/data/data/com.termux/files/home";
    private static final String PREFIX_USR = "/data/data/com.termux/files/usr";

    private final File mHomeDir;
    private final File mPrefixDir;
    private final File mTermuxDir;
    private final File mBackupDir;

    public ShellConfigManager() {
        this(new File(PREFIX_HOME), new File(PREFIX_USR));
    }

    public ShellConfigManager(@NonNull File homeDir, @NonNull File prefixDir) {
        mHomeDir = homeDir;
        mPrefixDir = prefixDir;
        mTermuxDir = new File(homeDir, ".termux");
        mBackupDir = new File(mTermuxDir, "studio_backups");
    }

    @NonNull
    public File getHomeDir() {
        return mHomeDir;
    }

    @NonNull
    public File getPrefixDir() {
        return mPrefixDir;
    }

    @NonNull
    public File getTermuxDir() {
        return mTermuxDir;
    }

    @NonNull
    public File getBackupDir() {
        return mBackupDir;
    }

    @NonNull
    public File getBashRcFile() {
        return new File(mHomeDir, ".bashrc");
    }

    @NonNull
    public File getZshRcFile() {
        return new File(mHomeDir, ".zshrc");
    }

    @NonNull
    public File getFishConfigFile() {
        return new File(new File(new File(mHomeDir, ".config"), "fish"), "config.fish");
    }

    @NonNull
    public File getColorsPropertiesFile() {
        return new File(mTermuxDir, "colors.properties");
    }

    @NonNull
    public File getTermuxPropertiesFile() {
        return new File(mTermuxDir, "termux.properties");
    }

    @NonNull
    public File getShellSymlinkFile() {
        return new File(mTermuxDir, "shell");
    }

    @NonNull
    public File getRcFileForShell(@NonNull String shellName) {
        String norm = shellName.trim().toLowerCase(Locale.ROOT);
        if ("zsh".equals(norm)) return getZshRcFile();
        if ("fish".equals(norm)) return getFishConfigFile();
        return getBashRcFile();
    }

    @NonNull
    public static String beginMarker(@NonNull String moduleId) {
        return "# >>> TERMUX_AI_STUDIO:" + moduleId + " >>>";
    }

    @NonNull
    public static String endMarker(@NonNull String moduleId) {
        return "# <<< TERMUX_AI_STUDIO:" + moduleId + " <<<";
    }

    @NonNull
    public String readFileSafely(@NonNull File file) {
        if (!file.exists() || !file.isFile()) return "";
        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public boolean writeFileAtomicallyWithBackup(@NonNull File target, @NonNull String content) {
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            if (target.exists() && target.isFile()) {
                createBackup(target);
            }
            File tmp = new File(parent, target.getName() + ".studio_tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Exception ignored) {}
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Nullable
    public File createBackup(@NonNull File source) {
        if (!source.exists() || !source.isFile()) return null;
        if (!mBackupDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mBackupDir.mkdirs();
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String safeName = source.getName() + "." + ts + ".bak";
        File backup = new File(mBackupDir, safeName);
        String original = readFileSafely(source);
        try (FileOutputStream fos = new FileOutputStream(backup)) {
            fos.write(original.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            return backup;
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    public File findLatestBackup(@NonNull File target) {
        if (!mBackupDir.exists() || !mBackupDir.isDirectory()) return null;
        String prefix = target.getName() + ".";
        File[] files = mBackupDir.listFiles((dir, name) -> name.startsWith(prefix) && name.endsWith(".bak"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files[0];
    }

    public boolean restoreLatestBackup(@NonNull File target) {
        File latest = findLatestBackup(target);
        if (latest == null) return false;
        String content = readFileSafely(latest);
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @NonNull
    public List<File> listAllBackups() {
        if (!mBackupDir.exists() || !mBackupDir.isDirectory()) return new ArrayList<>();
        File[] files = mBackupDir.listFiles((dir, name) -> name.endsWith(".bak"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new ArrayList<>(Arrays.asList(files));
    }

    public long getBackupsTotalSizeBytes() {
        long total = 0L;
        for (File f : listAllBackups()) {
            total += f.length();
        }
        return total;
    }

    /**
     * Upserts an idempotent managed block cleanly without duplicating markers or touching surrounding lines.
     */
    @NonNull
    public static String upsertManagedBlock(
        @NonNull String existingContent,
        @NonNull String moduleId,
        @NonNull String blockBody
    ) {
        String begin = beginMarker(moduleId);
        String end = endMarker(moduleId);
        String normalizedBody = blockBody.trim();
        String newBlock = begin + "\n" + normalizedBody + "\n" + end;

        int startIdx = existingContent.indexOf(begin);
        int endIdx = startIdx >= 0 ? existingContent.indexOf(end, startIdx) : -1;
        if (startIdx >= 0 && endIdx > startIdx) {
            int afterEnd = endIdx + end.length();
            String before = existingContent.substring(0, startIdx);
            String after = existingContent.substring(afterEnd);
            return before + newBlock + after;
        }

        if (existingContent.trim().isEmpty()) {
            return newBlock + "\n";
        }
        String suffix = existingContent.endsWith("\n") ? "\n" : "\n\n";
        return existingContent + suffix + newBlock + "\n";
    }

    /**
     * Removes an existing managed block cleanly while preserving surrounding user lines.
     */
    @NonNull
    public static String removeManagedBlock(@NonNull String existingContent, @NonNull String moduleId) {
        String begin = beginMarker(moduleId);
        String end = endMarker(moduleId);
        int startIdx = existingContent.indexOf(begin);
        if (startIdx < 0) return existingContent;
        int endIdx = existingContent.indexOf(end, startIdx);
        if (endIdx < 0) return existingContent;

        int afterEnd = endIdx + end.length();
        if (afterEnd < existingContent.length() && existingContent.charAt(afterEnd) == '\n') {
            afterEnd++;
        }
        String before = existingContent.substring(0, startIdx);
        String after = existingContent.substring(afterEnd);
        return (before + after).replaceAll("\n{3,}", "\n\n");
    }

    public static boolean hasManagedBlock(@NonNull String content, @NonNull String moduleId) {
        String begin = beginMarker(moduleId);
        String end = endMarker(moduleId);
        int startIdx = content.indexOf(begin);
        return startIdx >= 0 && content.indexOf(end, startIdx) > startIdx;
    }

    public boolean upsertModuleInRc(@NonNull File rcFile, @NonNull String moduleId, @NonNull String blockBody) {
        String existing = readFileSafely(rcFile);
        String updated = upsertManagedBlock(existing, moduleId, blockBody);
        return writeFileAtomicallyWithBackup(rcFile, updated);
    }

    public boolean removeModuleFromRc(@NonNull File rcFile, @NonNull String moduleId) {
        if (!rcFile.exists()) return true;
        String existing = readFileSafely(rcFile);
        if (!hasManagedBlock(existing, moduleId)) return true;
        String updated = removeManagedBlock(existing, moduleId);
        return writeFileAtomicallyWithBackup(rcFile, updated);
    }

    /**
     * Updates or inserts a key=value line inside ~/.termux/termux.properties while preserving all other entries.
     */
    public boolean upsertTermuxProperty(@NonNull String key, @NonNull String value) {
        File propsFile = getTermuxPropertiesFile();
        String existing = readFileSafely(propsFile);
        String[] lines = existing.isEmpty() ? new String[0] : existing.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.startsWith("#") && (trimmed.startsWith(key + "=") || trimmed.startsWith(key + " =") || trimmed.startsWith(key + ":"))) {
                if (!replaced) {
                    sb.append(key).append(" = ").append(value);
                    replaced = true;
                }
            } else {
                sb.append(line);
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        if (!replaced) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append(key).append(" = ").append(value).append('\n');
        }
        return writeFileAtomicallyWithBackup(propsFile, sb.toString());
    }

    @Nullable
    public String readTermuxProperty(@NonNull String key) {
        String content = readFileSafely(getTermuxPropertiesFile());
        if (content.isEmpty()) return null;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }

    @NonNull
    public String detectActiveDefaultShell() {
        File symlink = getShellSymlinkFile();
        if (symlink.exists()) {
            try {
                String canonical = symlink.getCanonicalPath();
                String name = new File(canonical).getName();
                if (!name.isEmpty()) return name;
            } catch (IOException ignored) {}
        }
        if (new File(mPrefixDir, "bin/bash").exists()) return "bash";
        return "sh";
    }

    public boolean switchDefaultShell(@NonNull String shellName) {
        File bin = new File(new File(mPrefixDir, "bin"), shellName);
        if (!bin.exists() || !bin.canExecute()) return false;
        if (!mTermuxDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mTermuxDir.mkdirs();
        }
        File symlink = getShellSymlinkFile();
        String previous = detectActiveDefaultShell();
        File prevRecord = new File(mBackupDir, "previous_shell.txt");
        if (!previous.equals(shellName)) {
            writeFileAtomicallyWithBackup(prevRecord, previous);
        }
        try {
            if (symlink.exists() || java.nio.file.Files.isSymbolicLink(symlink.toPath())) {
                //noinspection ResultOfMethodCallIgnored
                symlink.delete();
            }
            try {
                Os.symlink(bin.getAbsolutePath(), symlink.getAbsolutePath());
                if (symlink.exists()) return true;
            } catch (Throwable ignored) {}
            java.nio.file.Files.createSymbolicLink(symlink.toPath(), bin.toPath());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    public String getPreviousSavedShell() {
        File prevRecord = new File(mBackupDir, "previous_shell.txt");
        if (!prevRecord.exists()) return null;
        String val = readFileSafely(prevRecord).trim();
        return val.isEmpty() ? null : val;
    }
}
