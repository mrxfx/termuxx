package com.termux.app.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passively reads Termux's dpkg status database ($PREFIX/var/lib/dpkg/status) to suggest
 * locally installed packages without executing apt/pkg commands.
 */
public class InstalledPackageSuggester {

    public static final class PackageInfo {
        @NonNull public final String name;
        @NonNull public final String summary;

        public PackageInfo(@NonNull String name, @NonNull String summary) {
            this.name = name;
            this.summary = summary;
        }
    }

    private final File mDpkgStatusFile;
    private long mLastModified = -1L;
    private Map<String, PackageInfo> mInstalledPackages = Collections.emptyMap();

    public InstalledPackageSuggester() {
        this(new File("/data/data/com.termux/files/usr/var/lib/dpkg/status"));
    }

    public InstalledPackageSuggester(@NonNull File dpkgStatusFile) {
        mDpkgStatusFile = dpkgStatusFile;
    }

    @NonNull
    public synchronized Map<String, PackageInfo> getInstalledPackages() {
        if (!mDpkgStatusFile.exists() || !mDpkgStatusFile.isFile() || !mDpkgStatusFile.canRead()) {
            return mInstalledPackages;
        }
        long mod = mDpkgStatusFile.lastModified();
        if (mod != mLastModified) {
            mInstalledPackages = parseDpkgStatus(mDpkgStatusFile);
            mLastModified = mod;
        }
        return mInstalledPackages;
    }

    @NonNull
    public List<SuggestionItem> suggestPackages(@NonNull String prefix, int limit) {
        Map<String, PackageInfo> pkgs = getInstalledPackages();
        if (pkgs.isEmpty()) return Collections.emptyList();

        List<SuggestionItem> items = new ArrayList<>();
        for (PackageInfo pkg : pkgs.values()) {
            if (pkg.name.startsWith(prefix) && !pkg.name.equals(prefix)) {
                String suffix = pkg.name.substring(prefix.length());
                items.add(new SuggestionItem(
                    pkg.name,
                    suffix,
                    pkg.summary.isEmpty() ? "Installed package" : pkg.summary,
                    SuggestionType.PACKAGE,
                    72,
                    false
                ));
            }
        }
        items.sort((a, b) -> a.getDisplayText().compareTo(b.getDisplayText()));
        return items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items;
    }

    @NonNull
    public static Map<String, PackageInfo> parseDpkgStatus(@NonNull File statusFile) {
        Map<String, PackageInfo> map = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            String currentPkg = null;
            boolean isInstalled = false;
            String description = "";

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    if (currentPkg != null && !currentPkg.isEmpty() && isInstalled) {
                        map.put(currentPkg, new PackageInfo(currentPkg, description));
                    }
                    currentPkg = null;
                    isInstalled = false;
                    description = "";
                    continue;
                }
                if (line.startsWith("Package:")) {
                    currentPkg = line.substring("Package:".length()).trim();
                } else if (line.startsWith("Status:")) {
                    String status = line.substring("Status:".length()).trim().toLowerCase();
                    isInstalled = status.contains("installed") && !status.contains("not-installed");
                } else if (line.startsWith("Description:") && description.isEmpty()) {
                    description = line.substring("Description:".length()).trim();
                }
            }
            if (currentPkg != null && !currentPkg.isEmpty() && isInstalled) {
                map.put(currentPkg, new PackageInfo(currentPkg, description));
            }
        } catch (Exception ignored) {}
        return Collections.unmodifiableMap(map);
    }

    public static boolean acceptsInstalledPackageArg(@Nullable String command, @Nullable String subcommand) {
        if (command == null) return false;
        String cmd = command.toLowerCase();
        if ("dpkg".equals(cmd)) {
            return "-L".equals(subcommand) || "-r".equals(subcommand) || "-P".equals(subcommand)
                || "-s".equals(subcommand) || "--remove".equals(subcommand) || "--listfiles".equals(subcommand);
        }
        if ("pkg".equals(cmd) || "apt".equals(cmd) || "apt-get".equals(cmd)) {
            if (subcommand == null) return false;
            String sub = subcommand.toLowerCase();
            return "show".equals(sub) || "remove".equals(sub) || "uninstall".equals(sub)
                || "reinstall".equals(sub) || "purge".equals(sub) || "files".equals(sub)
                || "upgrade".equals(sub);
        }
        return false;
    }
}
