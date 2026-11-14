package com.termux.app.env;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real Runtime & Dependency Scanner for Termux AI.
 * Inspects actual filesystem binaries ($PREFIX/bin and $PATH), permissions, storage space,
 * dpkg/apt health, and executes bounded version probes only when binaries are physically installed.
 * Never claims support for a language or tool unless its runtime binary is installed and executable.
 */
public class RuntimeEnvironmentScanner {

    public static final String DEFAULT_PREFIX_PATH = "/data/data/com.termux/files/usr";
    public static final String DEFAULT_HOME_PATH = "/data/data/com.termux/files/home";

    public static final class RuntimeSpec {
        @NonNull public final String id;
        @NonNull public final String displayName;
        @NonNull public final String fileExtensions;
        @NonNull public final String primaryBinary;
        @NonNull public final List<String> companionBinaries;
        @NonNull public final String termuxPackage;
        @NonNull public final String installCommand;
        @NonNull public final String verifyCommand;
        @NonNull public final String versionArg;
        @NonNull public final String androidCompatibilityNote;

        public RuntimeSpec(
            @NonNull String id,
            @NonNull String displayName,
            @NonNull String fileExtensions,
            @NonNull String primaryBinary,
            @NonNull List<String> companionBinaries,
            @NonNull String termuxPackage,
            @NonNull String installCommand,
            @NonNull String verifyCommand,
            @NonNull String versionArg,
            @NonNull String androidCompatibilityNote
        ) {
            this.id = id;
            this.displayName = displayName;
            this.fileExtensions = fileExtensions;
            this.primaryBinary = primaryBinary;
            this.companionBinaries = Collections.unmodifiableList(new ArrayList<>(companionBinaries));
            this.termuxPackage = termuxPackage;
            this.installCommand = installCommand;
            this.verifyCommand = verifyCommand;
            this.versionArg = versionArg;
            this.androidCompatibilityNote = androidCompatibilityNote;
        }
    }

    public static final class RuntimeStatus {
        @NonNull public final RuntimeSpec spec;
        public final boolean installed;
        public final boolean executable;
        public final boolean inPath;
        @Nullable public final String binaryPath;
        @Nullable public final String detectedVersion;
        @NonNull public final List<String> installedCompanions;
        @NonNull public final List<String> missingCompanions;

        public RuntimeStatus(
            @NonNull RuntimeSpec spec,
            boolean installed,
            boolean executable,
            boolean inPath,
            @Nullable String binaryPath,
            @Nullable String detectedVersion,
            @NonNull List<String> installedCompanions,
            @NonNull List<String> missingCompanions
        ) {
            this.spec = spec;
            this.installed = installed;
            this.executable = executable;
            this.inPath = inPath;
            this.binaryPath = binaryPath;
            this.detectedVersion = detectedVersion;
            this.installedCompanions = Collections.unmodifiableList(new ArrayList<>(installedCompanions));
            this.missingCompanions = Collections.unmodifiableList(new ArrayList<>(missingCompanions));
        }

        public boolean isFunctional() {
            return installed && executable;
        }
    }

    public static final class SystemHealthReport {
        @NonNull public final String cpuAbi;
        public final int androidSdkInt;
        public final boolean prefixExists;
        public final boolean prefixWritable;
        public final boolean homeExists;
        public final boolean homeWritable;
        public final boolean prefixBinInPath;
        public final long usableStorageMb;
        public final boolean lowStorageWarning;
        public final boolean dpkgInterrupted;
        public final boolean dpkgLockPresent;
        @NonNull public final List<String>installedCoreUtilities;
        @NonNull public final List<String> missingCoreUtilities;
        @NonNull public final List<String> healthWarnings;

        public SystemHealthReport(
            @NonNull String cpuAbi,
            int androidSdkInt,
            boolean prefixExists,
            boolean prefixWritable,
            boolean homeExists,
            boolean homeWritable,
            boolean prefixBinInPath,
            long usableStorageMb,
            boolean lowStorageWarning,
            boolean dpkgInterrupted,
            boolean dpkgLockPresent,
            @NonNull List<String> installedCoreUtilities,
            @NonNull List<String> missingCoreUtilities,
            @NonNull List<String> healthWarnings
        ) {
            this.cpuAbi = cpuAbi;
            this.androidSdkInt = androidSdkInt;
            this.prefixExists = prefixExists;
            this.prefixWritable = prefixWritable;
            this.homeExists = homeExists;
            this.homeWritable = homeWritable;
            this.prefixBinInPath = prefixBinInPath;
            this.usableStorageMb = usableStorageMb;
            this.lowStorageWarning = lowStorageWarning;
            this.dpkgInterrupted = dpkgInterrupted;
            this.dpkgLockPresent = dpkgLockPresent;
            this.installedCoreUtilities = Collections.unmodifiableList(new ArrayList<>(installedCoreUtilities));
            this.missingCoreUtilities = Collections.unmodifiableList(new ArrayList<>(missingCoreUtilities));
            this.healthWarnings = Collections.unmodifiableList(new ArrayList<>(healthWarnings));
        }
    }

    public static final class EnvironmentSnapshot {
        @NonNull public final File prefixDir;
        @NonNull public final File homeDir;
        @NonNull public final SystemHealthReport health;
        @NonNull public final List<RuntimeStatus> runtimes;
        @NonNull public final Map<String, RuntimeStatus> runtimeMap;
        public final long scannedAtMs;

        public EnvironmentSnapshot(
            @NonNull File prefixDir,
            @NonNull File homeDir,
            @NonNull SystemHealthReport health,
            @NonNull List<RuntimeStatus> runtimes,
            long scannedAtMs
        ) {
            this.prefixDir = prefixDir;
            this.homeDir = homeDir;
            this.health = health;
            this.runtimes = Collections.unmodifiableList(new ArrayList<>(runtimes));
            Map<String, RuntimeStatus> map = new LinkedHashMap<>();
            for (RuntimeStatus st : runtimes) {
                map.put(st.spec.id, st);
            }
            this.runtimeMap = Collections.unmodifiableMap(map);
            this.scannedAtMs = scannedAtMs;
        }

        @Nullable
        public RuntimeStatus getRuntime(@NonNull String id) {
            return runtimeMap.get(id);
        }

        public boolean isRuntimeFunctional(@NonNull String id) {
            RuntimeStatus st = runtimeMap.get(id);
            return st != null && st.isFunctional();
        }
    }

    public static final List<String> CORE_LINUX_UTILITIES = Collections.unmodifiableList(Arrays.asList(
        "pkg", "apt", "dpkg", "curl", "wget", "jq", "tar", "unzip", "ssh", "grep", "sed", "awk", "find"
    ));

    private static final List<RuntimeSpec> SPECS = Collections.unmodifiableList(Arrays.asList(
        new RuntimeSpec(
            "python",
            "Python",
            ".py",
            "python3",
            Arrays.asList("pip", "python"),
            "python",
            "pkg update && pkg install -y python",
            "python3 --version && pip --version",
            "--version",
            "Prebuilt PyPI manylinux wheels do not run on Android Bionic libc. For packages with C/Rust extensions (numpy, cryptography, pillow, lxml), install via `pkg install python-numpy python-cryptography python-pillow` or install `clang libffi openssl rust` first."
        ),
        new RuntimeSpec(
            "bash",
            "Bash Shell",
            ".sh",
            "bash",
            Arrays.asList("sh"),
            "bash",
            "pkg install -y bash",
            "bash --version",
            "--version",
            "Android has no /bin/bash FHS path; scripts with `#!/bin/bash` should use `#!/usr/bin/env bash` or be fixed with `termux-fix-shebang <script.sh>`."
        ),
        new RuntimeSpec(
            "nodejs",
            "Node.js & TypeScript",
            ".js, .mjs, .ts",
            "node",
            Arrays.asList("npm", "npx"),
            "nodejs-lts",
            "pkg install -y nodejs-lts",
            "node --version && npm --version",
            "--version",
            "Native Node gyp addons require `pkg install clang make python` on Android."
        ),
        new RuntimeSpec(
            "java",
            "Java (OpenJDK)",
            ".java",
            "java",
            Arrays.asList("javac", "jar"),
            "openjdk-17",
            "pkg install -y openjdk-17",
            "java -version && javac -version",
            "-version",
            "Termux provides OpenJDK 17/21 compiled for Android Bionic."
        ),
        new RuntimeSpec(
            "kotlin",
            "Kotlin Compiler",
            ".kt",
            "kotlinc",
            Arrays.asList("kotlin", "java"),
            "kotlin",
            "pkg install -y openjdk-17 kotlin",
            "kotlinc -version",
            "-version",
            "Requires `openjdk-17` or `openjdk-21` installed in Termux."
        ),
        new RuntimeSpec(
            "c_cpp",
            "C / C++ Toolchain (Clang)",
            ".c, .cpp",
            "clang",
            Arrays.asList("clang++", "make", "cmake"),
            "clang make cmake",
            "pkg install -y clang make cmake",
            "clang --version && make --version",
            "--version",
            "Termux uses LLVM `clang` / `clang++` (and aliases `gcc`/`g++` to clang) targeting Android Bionic libc."
        ),
        new RuntimeSpec(
            "php",
            "PHP",
            ".php",
            "php",
            Arrays.asList("composer"),
            "php",
            "pkg install -y php",
            "php --version",
            "--version",
            "Composer can be added via `pkg install composer` if project uses composer.json."
        ),
        new RuntimeSpec(
            "ruby",
            "Ruby",
            ".rb",
            "ruby",
            Arrays.asList("gem", "bundle"),
            "ruby",
            "pkg install -y ruby",
            "ruby --version && gem --version",
            "--version",
            "Native Ruby gems require `pkg install clang make libffi libyaml` to compile."
        ),
        new RuntimeSpec(
            "go",
            "Go (Golang)",
            ".go",
            "go",
            Collections.emptyList(),
            "golang",
            "pkg install -y golang",
            "go version",
            "version",
            "Go builds native Android binaries cleanly; CGO requires `clang`."
        ),
        new RuntimeSpec(
            "rust",
            "Rust & Cargo",
            ".rs",
            "rustc",
            Arrays.asList("cargo"),
            "rust",
            "pkg install -y rust clang",
            "rustc --version && cargo --version",
            "--version",
            "Use Termux `pkg install rust` rather than `rustup` so crates link properly against Android Bionic."
        ),
        new RuntimeSpec(
            "git",
            "Git Version Control",
            ".git",
            "git",
            Arrays.asList("ssh"),
            "git",
            "pkg install -y git openssh",
            "git --version",
            "--version",
            "Fully supported in Termux; shared storage (/sdcard) may require `git config --global --add safe.directory '*'`."
        ),
        new RuntimeSpec(
            "gradle",
            "Gradle Build Tool",
            ".gradle, .gradle.kts",
            "gradle",
            Arrays.asList("java"),
            "gradle",
            "pkg install -y openjdk-17 gradle",
            "gradle --version",
            "--version",
            "Requires OpenJDK (`pkg install openjdk-17`) and sufficient free RAM/storage."
        ),
        new RuntimeSpec(
            "android_tools",
            "Android Development Tools",
            ".apk, .aab",
            "adb",
            Arrays.asList("aapt", "aapt2", "apksigner"),
            "android-tools aapt aapt2 apksigner",
            "pkg install -y android-tools aapt aapt2 apksigner",
            "adb --version",
            "--version",
            "Local on-device ADB connects via Wireless Debugging (`adb pair` / `adb connect`)."
        ),
        new RuntimeSpec(
            "linux_cli",
            "Common Linux CLI Utilities",
            ".tar, .json, .sh",
            "curl",
            Arrays.asList("wget", "jq", "tar", "unzip", "grep", "sed", "awk", "find"),
            "curl wget jq tar unzip grep sed gawk findutils",
            "pkg install -y curl wget jq tar unzip grep sed gawk findutils",
            "curl --version && jq --version",
            "--version",
            "Termux uses BusyBox/GNU coreutils without root or systemd services."
        )
    ));

    private final File mPrefixDir;
    private final File mHomeDir;
    private final boolean mProbeVersions;

    public RuntimeEnvironmentScanner() {
        this(new File(DEFAULT_PREFIX_PATH), new File(DEFAULT_HOME_PATH), true);
    }

    public RuntimeEnvironmentScanner(@NonNull File prefixDir, @NonNull File homeDir, boolean probeVersions) {
        mPrefixDir = prefixDir;
        mHomeDir = homeDir;
        mProbeVersions = probeVersions;
    }

    @NonNull
    public static List<RuntimeSpec> getAllSpecs() {
        return SPECS;
    }

    @NonNull
    public EnvironmentSnapshot scan() {
        File binDir = new File(mPrefixDir, "bin");
        String envPath = System.getenv("PATH");
        boolean prefixBinInPath = envPath != null && envPath.contains(binDir.getAbsolutePath());

        List<RuntimeStatus> statuses = new ArrayList<>();
        for (RuntimeSpec spec : SPECS) {
            File binFile = resolveExecutable(spec.primaryBinary, binDir, envPath);
            boolean installed = binFile != null && binFile.exists() && binFile.isFile();
            boolean executable = installed && binFile.canExecute();
            boolean inPath = installed && (prefixBinInPath || isBinaryInSystemPath(spec.primaryBinary, envPath));

            String version = null;
            if (executable && mProbeVersions) {
                version = probeBinaryVersion(binFile, spec.versionArg);
            }

            List<String> installedComp = new ArrayList<>();
            List<String> missingComp = new ArrayList<>();
            for (String comp : spec.companionBinaries) {
                File compFile = resolveExecutable(comp, binDir, envPath);
                if (compFile != null && compFile.exists() && compFile.canExecute()) {
                    installedComp.add(comp);
                } else {
                    missingComp.add(comp);
                }
            }

            statuses.add(new RuntimeStatus(
                spec,
                installed,
                executable,
                inPath,
                binFile != null ? binFile.getAbsolutePath() : null,
                version,
                installedComp,
                missingComp
            ));
        }

        SystemHealthReport health = inspectSystemHealth(binDir, prefixBinInPath, envPath);
        return new EnvironmentSnapshot(mPrefixDir, mHomeDir, health, statuses, System.currentTimeMillis());
    }

    @NonNull
    private SystemHealthReport inspectSystemHealth(
        @NonNull File binDir,
        boolean prefixBinInPath,
        @Nullable String envPath
    ) {
        String abi = "arm64-v8a";
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 && Build.SUPPORTED_ABIS[0] != null) {
                abi = Build.SUPPORTED_ABIS[0];
            }
        } catch (Throwable ignored) {}

        boolean prefixExists = mPrefixDir.exists() && mPrefixDir.isDirectory();
        boolean prefixWritable = prefixExists && mPrefixDir.canWrite();
        boolean homeExists = mHomeDir.exists() && mHomeDir.isDirectory();
        boolean homeWritable = homeExists && mHomeDir.canWrite();

        long usableBytes = 0L;
        if (mHomeDir.exists()) {
            usableBytes = mHomeDir.getUsableSpace();
        } else if (mPrefixDir.exists()) {
            usableBytes = mPrefixDir.getUsableSpace();
        }
        long usableMb = usableBytes > 0 ? (usableBytes / (1024L * 1024L)) : 0L;
        boolean lowStorage = (prefixExists || homeExists) && usableBytes > 0 && usableMb < 250L;

        File dpkgUpdatesDir = new File(mPrefixDir, "var/lib/dpkg/updates");
        boolean dpkgInterrupted = false;
        if (dpkgUpdatesDir.exists() && dpkgUpdatesDir.isDirectory()) {
            File[] updates = dpkgUpdatesDir.listFiles();
            if (updates != null) {
                for (File f : updates) {
                    if (f.isFile() && f.getName().matches("\\d+")) {
                        dpkgInterrupted = true;
                        break;
                    }
                }
            }
        }

        File dpkgLock = new File(mPrefixDir, "var/lib/dpkg/lock-frontend");
        File dpkgLockLegacy = new File(mPrefixDir, "var/lib/dpkg/lock");
        boolean dpkgLockPresent = dpkgInterrupted && (dpkgLock.exists() || dpkgLockLegacy.exists());

        List<String> installedCore = new ArrayList<>();
        List<String> missingCore = new ArrayList<>();
        for (String util : CORE_LINUX_UTILITIES) {
            File f = resolveExecutable(util, binDir, envPath);
            if (f != null && f.exists() && f.canExecute()) {
                installedCore.add(util);
            } else {
                missingCore.add(util);
            }
        }

        List<String> warnings = new ArrayList<>();
        if (!prefixExists) {
            warnings.add("Termux $PREFIX (" + mPrefixDir.getAbsolutePath() + ") is not initialized yet.");
        } else if (!prefixWritable) {
            warnings.add("Termux $PREFIX is not writable (check app storage permissions).");
        }
        if (!homeExists) {
            warnings.add("Termux $HOME (" + mHomeDir.getAbsolutePath() + ") does not exist.");
        } else if (!homeWritable) {
            warnings.add("Termux $HOME is not writable.");
        }
        if (lowStorage) {
            warnings.add("Low available disk space (" + usableMb + " MB free). Package builds and installations may fail.");
        }
        if (dpkgInterrupted) {
            warnings.add("Interrupted dpkg package state detected in $PREFIX/var/lib/dpkg/updates. Run `dpkg --configure -a` to repair.");
        }

        return new SystemHealthReport(
            abi,
            Build.VERSION.SDK_INT,
            prefixExists,
            prefixWritable,
            homeExists,
            homeWritable,
            prefixBinInPath,
            usableMb,
            lowStorage,
            dpkgInterrupted,
            dpkgLockPresent,
            installedCore,
            missingCore,
            warnings
        );
    }

    @Nullable
    private File resolveExecutable(@NonNull String binaryName, @NonNull File prefixBinDir, @Nullable String envPath) {
        File direct = new File(prefixBinDir, binaryName);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }
        // Fallback for python -> python3
        if ("python3".equals(binaryName)) {
            File py = new File(prefixBinDir, "python");
            if (py.exists() && py.isFile()) {
                return py;
            }
        }
        if (envPath != null && !envPath.isEmpty() && prefixBinDir.getAbsolutePath().startsWith(DEFAULT_PREFIX_PATH)) {
            String[] parts = envPath.split(File.pathSeparator);
            for (String p : parts) {
                if (p == null || p.trim().isEmpty()) continue;
                File candidate = new File(p.trim(), binaryName);
                if (candidate.exists() && candidate.isFile()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isBinaryInSystemPath(@NonNull String binaryName, @Nullable String envPath) {
        if (envPath == null || envPath.isEmpty()) return false;
        for (String dir : envPath.split(File.pathSeparator)) {
            if (dir == null || dir.trim().isEmpty()) continue;
            File f = new File(dir.trim(), binaryName);
            if (f.exists() && f.canExecute()) return true;
        }
        return false;
    }

    @Nullable
    private String probeBinaryVersion(@NonNull File binary, @NonNull String versionArg) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), versionArg);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PREFIX", mPrefixDir.getAbsolutePath());
            env.put("HOME", mHomeDir.getAbsolutePath());
            String existingPath = env.get("PATH");
            File binDir = new File(mPrefixDir, "bin");
            env.put("PATH", binDir.getAbsolutePath() + (existingPath != null ? File.pathSeparator + existingPath : ""));

            process = pb.start();
            boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        return trimmed.length() > 72 ? trimmed.substring(0, 72) : trimmed;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return null;
    }
}
