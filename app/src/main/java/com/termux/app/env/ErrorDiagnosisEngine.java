package com.termux.app.env;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.security.SensitiveDataRedactor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, Evidence-Based Automatic Error Diagnosis Engine for Termux AI.
 * Classifies terminal errors, identifies missing dependencies or Android/Termux platform mismatches,
 * and produces all 6 mandatory fields:
 * 1. Original error
 * 2. Probable cause
 * 3. Required dependency
 * 4. Recommended fix
 * 5. Exact repair command
 * 6. Verification command
 *
 * Never invents a root cause when the available evidence is insufficient.
 */
public final class ErrorDiagnosisEngine {

    public enum ErrorCategory {
        COMMAND_NOT_FOUND("Missing Runtime or CLI Tool"),
        DESKTOP_LINUX_MISMATCH("Desktop Linux vs Android Termux Mismatch"),
        PATH_OR_SHEBANG_ERROR("PATH or Script Shebang Mismatch"),
        PYTHON_MISSING_MODULE("Missing Python Module"),
        PYTHON_NATIVE_WHEEL_FAILURE("Python Native Wheel / Compiler Failure on Android"),
        NODE_DEPENDENCY_OR_CONFLICT("Node.js Module or Dependency Conflict"),
        JVM_GRADLE_ANDROID_BUILD_ERROR("Java / Kotlin / Gradle / Android Build Error"),
        COMPILED_LANGUAGE_TOOLCHAIN_ERROR("C / C++ / Rust / Go Compiler or Linker Error"),
        PERMISSION_OR_STORAGE_ERROR("Permission or Storage Restriction"),
        PACKAGE_MANAGER_OR_REPO_ERROR("Termux Package Manager / Repository Issue"),
        NETWORK_OR_SSL_ERROR("Network / DNS / TLS Connectivity Error"),
        INSUFFICIENT_EVIDENCE("Insufficient Evidence");

        @NonNull public final String label;

        ErrorCategory(@NonNull String label) {
            this.label = label;
        }
    }

    public static final class DiagnosisResult {
        @NonNull public final ErrorCategory category;
        public final boolean actionableRepairAvailable;
        @NonNull public final String originalError;
        @NonNull public final String probableCause;
        @NonNull public final String requiredDependency;
        @NonNull public final String recommendedFix;
        @NonNull public final String exactRepairCommand;
        @NonNull public final String verificationCommand;
        @NonNull public final String termuxCompatibilityNote;

        public DiagnosisResult(
            @NonNull ErrorCategory category,
            boolean actionableRepairAvailable,
            @NonNull String originalError,
            @NonNull String probableCause,
            @NonNull String requiredDependency,
            @NonNull String recommendedFix,
            @NonNull String exactRepairCommand,
            @NonNull String verificationCommand,
            @NonNull String termuxCompatibilityNote
        ) {
            this.category = category;
            this.actionableRepairAvailable = actionableRepairAvailable;
            this.originalError = originalError;
            this.probableCause = probableCause;
            this.requiredDependency = requiredDependency;
            this.recommendedFix = recommendedFix;
            this.exactRepairCommand = exactRepairCommand;
            this.verificationCommand = verificationCommand;
            this.termuxCompatibilityNote = termuxCompatibilityNote;
        }
    }

    private static final Map<String, String> COMMAND_TO_TERMUX_PKG;
    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("python", "python");
        map.put("python3", "python");
        map.put("pip", "python");
        map.put("pip3", "python");
        map.put("node", "nodejs-lts");
        map.put("npm", "nodejs-lts");
        map.put("npx", "nodejs-lts");
        map.put("java", "openjdk-17");
        map.put("javac", "openjdk-17");
        map.put("jar", "openjdk-17");
        map.put("kotlin", "kotlin");
        map.put("kotlinc", "kotlin");
        map.put("clang", "clang");
        map.put("clang++", "clang");
        map.put("gcc", "clang");
        map.put("g++", "clang");
        map.put("cc", "clang");
        map.put("make", "make");
        map.put("cmake", "cmake");
        map.put("php", "php");
        map.put("composer", "composer");
        map.put("ruby", "ruby");
        map.put("gem", "ruby");
        map.put("bundle", "ruby");
        map.put("go", "golang");
        map.put("rustc", "rust");
        map.put("cargo", "rust");
        map.put("git", "git");
        map.put("gradle", "gradle");
        map.put("mvn", "maven");
        map.put("adb", "android-tools");
        map.put("fastboot", "android-tools");
        map.put("aapt", "aapt");
        map.put("aapt2", "aapt2");
        map.put("apksigner", "apksigner");
        map.put("jq", "jq");
        map.put("wget", "wget");
        map.put("curl", "curl");
        map.put("ssh", "openssh");
        map.put("scp", "openssh");
        map.put("unzip", "unzip");
        map.put("zip", "zip");
        map.put("tar", "tar");
        map.put("nano", "nano");
        map.put("vim", "vim");
        map.put("nvim", "neovim");
        map.put("tmux", "tmux");
        map.put("htop", "htop");
        map.put("tree", "tree");
        map.put("zsh", "zsh");
        map.put("fish", "fish");
        COMMAND_TO_TERMUX_PKG = Collections.unmodifiableMap(map);
    }

    private static final Map<String, String> PYTHON_MODULE_TO_TERMUX_PKG;
    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("numpy", "python-numpy");
        map.put("scipy", "python-scipy");
        map.put("pandas", "python-pandas");
        map.put("cryptography", "python-cryptography");
        map.put("pil", "python-pillow");
        map.put("pillow", "python-pillow");
        map.put("lxml", "python-lxml");
        map.put("matplotlib", "python-matplotlib");
        map.put("grpc", "python-grpcio");
        map.put("cv2", "opencv-python");
        map.put("bcrypt", "python-bcrypt");
        PYTHON_MODULE_TO_TERMUX_PKG = Collections.unmodifiableMap(map);
    }

    private ErrorDiagnosisEngine() {}

    @NonNull
    public static DiagnosisResult diagnose(@Nullable String rawTerminalOutput) {
        if (rawTerminalOutput == null || rawTerminalOutput.trim().isEmpty()) {
            return buildInsufficientEvidence("No terminal error output captured yet.");
        }

        String redacted = SensitiveDataRedactor.redact(rawTerminalOutput.trim());
        String lower = redacted.toLowerCase(Locale.US);

        // 1. Desktop Linux `sudo` / `systemctl` / `service` on Termux
        Matcher sudoPkgMatcher = Pattern.compile("(?m)^.*sudo\\s+(?:apt|apt-get)\\s+install\\s+(-y\\s+)?([A-Za-z0-9_.+\\-\\s]+).*$").matcher(redacted);
        boolean matchedSudoInstall = sudoPkgMatcher.find();
        if (matchedSudoInstall || lower.contains("sudo: command not found") || lower.contains("no superuser binary detected")) {
            String errLine = extractMatchingLine(redacted, "sudo");
            String pkgArg = matchedSudoInstall ? sudoPkgMatcher.group(2).trim() : "<package>";
            return new DiagnosisResult(
                ErrorCategory.DESKTOP_LINUX_MISMATCH,
                !pkgArg.equals("<package>"),
                errLine,
                "Command attempted to invoke `sudo` on Android. Termux runs in an isolated Android app sandbox where packages are installed directly into `$PREFIX` without root or `sudo`.",
                "Termux `pkg` package manager (no `sudo` required)",
                "Run `pkg install` directly without `sudo` or `apt-get`.",
                "pkg install -y " + pkgArg,
                "pkg list-installed",
                "Termux does not use root for package management; writing to `$PREFIX` (/data/data/com.termux/files/usr) is owned by your Termux user."
            );
        }

        if (lower.contains("systemctl:") || lower.contains("system has not been booted with systemd")) {
            String errLine = extractMatchingLine(redacted, "systemd");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "systemctl");
            return new DiagnosisResult(
                ErrorCategory.DESKTOP_LINUX_MISMATCH,
                true,
                errLine,
                "Command attempted to use `systemd` (`systemctl`), which does not exist on the Android kernel.",
                "Termux Services (`termux-services` / `sv`)",
                "Install `termux-services` and manage background daemons with `sv-enable <service>` and `sv up <service>` instead of `systemctl`.",
                "pkg install -y termux-services",
                "sv --version || ls $PREFIX/var/service",
                "Android manages system processes via Binder/init rather than desktop Linux systemd."
            );
        }

        // 2. Interrupted dpkg / lock / repository mirror errors
        if (lower.contains("dpkg was interrupted") || lower.contains("dpkg --configure -a")) {
            String errLine = extractMatchingLine(redacted, "dpkg");
            return new DiagnosisResult(
                ErrorCategory.PACKAGE_MANAGER_OR_REPO_ERROR,
                true,
                errLine,
                "A previous package installation or upgrade was interrupted before `dpkg` finished configuring unpacked archives.",
                "dpkg / apt state recovery",
                "Run `dpkg --configure -a` to finish configuring pending packages, then run `pkg update`.",
                "dpkg --configure -a && apt --fix-broken install -y",
                "dpkg --audit && pkg list-installed | tail -n 5",
                "Never delete `$PREFIX/var/lib/dpkg/status`; `dpkg --configure -a` safely replays pending triggers."
            );
        }

        if (lower.contains("unable to locate package")) {
            String errLine = extractMatchingLine(redacted, "Unable to locate package");
            Matcher pkgMatcher = Pattern.compile("(?i)unable to locate package\\s+([A-Za-z0-9_.+\\-]+)").matcher(redacted);
            String missingPkg = pkgMatcher.find() ? pkgMatcher.group(1) : "package";
            String mapped = mapDesktopPackageNameToTermux(missingPkg);
            String repairCmd = mapped != null
                ? ("pkg update && pkg install -y " + mapped)
                : ("pkg update && pkg search " + missingPkg);
            String recFix = mapped != null
                ? ("Desktop Linux package `" + missingPkg + "` is named `" + mapped + "` in Termux. Refresh repositories and install `" + mapped + "`.")
                : ("Refresh package lists with `pkg update` and search available Termux packages with `pkg search " + missingPkg + "`.");
            return new DiagnosisResult(
                ErrorCategory.PACKAGE_MANAGER_OR_REPO_ERROR,
                true,
                errLine,
                "Package `" + missingPkg + "` was not found in the local apt cache (either package lists are stale or the package uses a different name in Termux).",
                mapped != null ? ("Termux package: `" + mapped + "`") : "Updated Termux apt repository index",
                recFix,
                repairCmd,
                mapped != null ? ("dpkg -s " + mapped) : ("pkg search " + missingPkg),
                "Termux does not use Debian's `-dev` / `-devel` split packages; headers are bundled directly inside the main Termux package."
            );
        }

        if ((lower.contains("failed to fetch") || lower.contains("404  not found") || lower.contains("403  forbidden"))
            && (lower.contains("packages.termux.dev") || lower.contains("dl.bintray.com") || lower.contains("apt") || lower.contains("pkg"))) {
            String errLine = extractMatchingLine(redacted, "Failed to fetch");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "404");
            return new DiagnosisResult(
                ErrorCategory.PACKAGE_MANAGER_OR_REPO_ERROR,
                true,
                errLine,
                "The configured Termux package mirror is unreachable, outdated, or returning HTTP errors.",
                "termux-tools (`termux-change-repo`)",
                "Switch to an active Termux mirror using `termux-change-repo`, then run `pkg update`.",
                "termux-change-repo && pkg update",
                "pkg update",
                "Official Termux mirrors are managed via `termux-change-repo`."
            );
        }

        // 3. Script Shebang / FHS /bin/bash or /usr/bin/env mismatch on Android
        if (lower.contains("bad interpreter:") || (lower.contains("/bin/bash") && lower.contains("not found"))
            || (lower.contains("/usr/bin/env") && lower.contains("not found"))) {
            String errLine = extractMatchingLine(redacted, "interpreter");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "not found");
            Matcher scriptMatcher = Pattern.compile("(?m)^(?:bash:\\s+|zsh:\\s+)?([^:\\s]+\\.(?:sh|py|pl|rb|js)):").matcher(redacted);
            String scriptFile = scriptMatcher.find() ? scriptMatcher.group(1) : "<script>";
            return new DiagnosisResult(
                ErrorCategory.PATH_OR_SHEBANG_ERROR,
                !scriptFile.equals("<script>"),
                errLine,
                "Script uses a hardcoded desktop Linux shebang (`#!/bin/bash` or `#!/usr/bin/env`) that does not exist at `/bin` or `/usr/bin` on Android.",
                "termux-exec / `termux-fix-shebang`",
                "Rewrite the script's shebang to Termux's `$PREFIX/bin` path using `termux-fix-shebang` and ensure `termux-exec` is installed.",
                "pkg install -y termux-exec && termux-fix-shebang " + scriptFile + " && chmod +x " + scriptFile,
                "head -n 1 " + scriptFile,
                "Android does not follow the desktop `/bin` / `/usr/bin` Filesystem Hierarchy Standard; binaries live in `/data/data/com.termux/files/usr/bin`."
            );
        }

        // 4. Python ModuleNotFoundError / ImportError
        Matcher pyModMatcher = Pattern.compile("ModuleNotFoundError:\\s+No module named ['\"]([^'\"]+)['\"]").matcher(redacted);
        if (pyModMatcher.find()) {
            String fullMod = pyModMatcher.group(1);
            String rootMod = fullMod.contains(".") ? fullMod.substring(0, fullMod.indexOf('.')) : fullMod;
            String lowerMod = rootMod.toLowerCase(Locale.US);
            String errLine = pyModMatcher.group(0);
            String termuxNativePkg = PYTHON_MODULE_TO_TERMUX_PKG.get(lowerMod);

            if (termuxNativePkg != null) {
                return new DiagnosisResult(
                    ErrorCategory.PYTHON_MISSING_MODULE,
                    true,
                    errLine,
                    "Python module `" + rootMod + "` is not installed, and requires compiled native C/C++/Rust extensions on Android.",
                    "Termux package `" + termuxNativePkg + "`",
                    "Install the prebuilt Termux native package `" + termuxNativePkg + "` via `pkg` (or use `--system-site-packages` if inside a virtualenv).",
                    "pkg install -y " + termuxNativePkg,
                    "python3 -c \"import " + rootMod + "; print(" + rootMod + ".__file__)\"",
                    "PyPI does not host prebuilt `manylinux` wheels for Android Bionic; Termux provides precompiled `" + termuxNativePkg + "`."
                );
            }

            return new DiagnosisResult(
                ErrorCategory.PYTHON_MISSING_MODULE,
                true,
                errLine,
                "Python package `" + rootMod + "` is not installed in the active Python environment.",
                "Python package `" + rootMod + "` (`pip`)",
                "Install `" + rootMod + "` using `python3 -m pip install " + rootMod + "` (preferably inside an active `.venv`).",
                "python3 -m pip install " + rootMod,
                "python3 -c \"import " + rootMod + "; print('OK')\"",
                "Pure-Python packages install directly from PyPI via `pip` on Termux."
            );
        }

        // 5. Python native wheel build failure (`Failed building wheel`, `command 'clang' failed`, Rust required for cryptography/pydantic-core)
        if (lower.contains("failed building wheel for") || lower.contains("could not build wheels for")
            || (lower.contains("pip") && lower.contains("command 'clang' failed"))) {
            String errLine = extractMatchingLine(redacted, "wheel");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "clang");
            Matcher wheelMatcher = Pattern.compile("(?i)failed building wheel for\\s+([A-Za-z0-9_.\\-]+)").matcher(redacted);
            String pkg = wheelMatcher.find() ? wheelMatcher.group(1).toLowerCase(Locale.US) : "";
            String nativePkg = PYTHON_MODULE_TO_TERMUX_PKG.get(pkg);

            if (nativePkg != null) {
                return new DiagnosisResult(
                    ErrorCategory.PYTHON_NATIVE_WHEEL_FAILURE,
                    true,
                    errLine,
                    "`pip` attempted to compile `" + pkg + "` from C/Rust source because PyPI `manylinux` wheels are incompatible with Android Bionic libc.",
                    "Prebuilt Termux package `" + nativePkg + "`",
                    "Install `" + nativePkg + "` from the Termux repository instead of compiling from PyPI.",
                    "pkg install -y " + nativePkg,
                    "python3 -c \"import " + pkg + "\"",
                    "Always prefer `pkg install python-<name>` on Android for scientific/crypto Python libraries."
                );
            }

            return new DiagnosisResult(
                ErrorCategory.PYTHON_NATIVE_WHEEL_FAILURE,
                true,
                errLine,
                "`pip` tried to build a native C/C++/Rust extension wheel without the required compiler toolchain and headers installed.",
                "`clang`, `make`, `python`, `libffi`, `openssl`, `rust`",
                "Install the Termux C/Rust build toolchain (`clang make libffi openssl rust`) before retrying `pip install`.",
                "pkg install -y clang make pkg-config libffi openssl rust",
                "clang --version && cargo --version",
                "Android requires LLVM `clang` and Bionic-compatible headers to compile native Python wheels."
            );
        }

        // 6. Node.js missing module or ERESOLVE dependency conflict
        Matcher nodeModMatcher = Pattern.compile("Cannot find module ['\"]([^'\"]+)['\"]").matcher(redacted);
        if (nodeModMatcher.find()) {
            String mod = nodeModMatcher.group(1);
            String errLine = nodeModMatcher.group(0);
            boolean isRelative = mod.startsWith("./") || mod.startsWith("../") || mod.startsWith("/");
            if (isRelative) {
                return new DiagnosisResult(
                    ErrorCategory.NODE_DEPENDENCY_OR_CONFLICT,
                    false,
                    errLine,
                    "Node.js could not find local file module `" + mod + "` relative to the script's working directory.",
                    "Local project file `" + mod + "`",
                    "Verify the file path and current working directory (`pwd`), or run the build step (e.g., `npm run build` / `tsc`) if `" + mod + "` is compiled output.",
                    "ls -la",
                    "node -v",
                    "Check case-sensitivity of filenames on Android's Linux filesystem."
                );
            }
            return new DiagnosisResult(
                ErrorCategory.NODE_DEPENDENCY_OR_CONFLICT,
                true,
                errLine,
                "NPM dependency `" + mod + "` is missing from `node_modules/`.",
                "npm package `" + mod + "`",
                "Install the missing dependency using `npm install " + mod + "` (or run `npm install` to install all `package.json` dependencies).",
                "npm install " + mod,
                "node -e \"require.resolve('" + mod + "'); console.log('OK')\"",
                "Preserve existing `package-lock.json` versions where possible."
            );
        }

        if (lower.contains("eresolve unable to resolve dependency tree")) {
            String errLine = extractMatchingLine(redacted, "ERESOLVE");
            return new DiagnosisResult(
                ErrorCategory.NODE_DEPENDENCY_OR_CONFLICT,
                true,
                errLine,
                "Conflicting peerDependency version ranges in `package.json` prevent npm v7+ strict resolution from completing.",
                "Compatible peerDependencies or `--legacy-peer-deps`",
                "Inspect the peer dependency conflict in `package.json`, or run `npm install --legacy-peer-deps` to preserve existing project versions without forcing breaking upgrades.",
                "npm install --legacy-peer-deps",
                "npm ls --depth=0",
                "`--legacy-peer-deps` is safer than `--force` because it does not overwrite lockfile resolutions with incompatible major versions."
            );
        }

        // 7. Command not found (`bash: <cmd>: command not found`, `zsh: command not found: <cmd>`, `/data/...: <cmd>: not found`)
        Matcher cmdNotFound1 = Pattern.compile("(?m)^(?:bash|sh|zsh):\\s+(?:line \\d+:\\s+)?([A-Za-z0-9_.+\\-]+):\\s+(?:command )?not found").matcher(redacted);
        Matcher cmdNotFound2 = Pattern.compile("(?m)command not found:\\s*([A-Za-z0-9_.+\\-]+)").matcher(redacted);
        Matcher cmdNotFound3 = Pattern.compile("(?m)^No command\\s+([A-Za-z0-9_.+\\-]+)\\s+found").matcher(redacted);
        String missingCmd = null;
        String cmdErrLine = null;
        if (cmdNotFound1.find()) {
            missingCmd = cmdNotFound1.group(1);
            cmdErrLine = cmdNotFound1.group(0);
        } else if (cmdNotFound2.find()) {
            missingCmd = cmdNotFound2.group(1);
            cmdErrLine = cmdNotFound2.group(0);
        } else if (cmdNotFound3.find()) {
            missingCmd = cmdNotFound3.group(1);
            cmdErrLine = cmdNotFound3.group(0);
        }

        if (missingCmd != null) {
            String lowerCmd = missingCmd.toLowerCase(Locale.US);
            String termuxPkg = COMMAND_TO_TERMUX_PKG.get(lowerCmd);
            if (termuxPkg != null) {
                return new DiagnosisResult(
                    ErrorCategory.COMMAND_NOT_FOUND,
                    true,
                    cmdErrLine,
                    "Executable `" + missingCmd + "` is not installed in `$PREFIX/bin`.",
                    "Termux package `" + termuxPkg + "`",
                    "Install `" + termuxPkg + "` using Termux's `pkg` manager.",
                    "pkg install -y " + termuxPkg,
                    "command -v " + missingCmd + " && " + missingCmd + " --version",
                    ("gcc".equals(lowerCmd) || "g++".equals(lowerCmd))
                        ? "Termux provides `clang` and sets up `gcc`/`g++` wrappers targeting Android Bionic."
                        : "Installed binaries are placed in `/data/data/com.termux/files/usr/bin`."
                );
            }
            return new DiagnosisResult(
                ErrorCategory.COMMAND_NOT_FOUND,
                true,
                cmdErrLine,
                "Command `" + missingCmd + "` is not installed or not in `$PATH`.",
                "Package providing `" + missingCmd + "`",
                "Search Termux repositories for `" + missingCmd + "` using `pkg search " + missingCmd + "` and verify `$PREFIX/bin` is in `$PATH`.",
                "pkg search " + missingCmd,
                "echo $PATH",
                "Not all desktop Linux utilities exist in Termux's Android repositories."
            );
        }

        // 8. Java / Kotlin / Gradle / Android errors
        if (lower.contains("java_home is not set") || lower.contains("unable to locate a java runtime")) {
            String errLine = extractMatchingLine(redacted, "JAVA_HOME");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "Java");
            return new DiagnosisResult(
                ErrorCategory.JVM_GRADLE_ANDROID_BUILD_ERROR,
                true,
                errLine,
                "No Java Development Kit (JDK) is installed or `JAVA_HOME` is not pointing to Termux's OpenJDK installation.",
                "Termux package `openjdk-17`",
                "Install `openjdk-17` via `pkg` and export `JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk`.",
                "pkg install -y openjdk-17 && export JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk",
                "java -version && javac -version",
                "Termux installs OpenJDK under `$PREFIX/lib/jvm/java-17-openjdk`."
            );
        }

        if (lower.contains("unsupportedclassversionerror")) {
            String errLine = extractMatchingLine(redacted, "UnsupportedClassVersionError");
            return new DiagnosisResult(
                ErrorCategory.JVM_GRADLE_ANDROID_BUILD_ERROR,
                true,
                errLine,
                "The compiled Java bytecode targets a newer JDK class file version than the currently active `java` runtime.",
                "`openjdk-17` or `openjdk-21`",
                "Upgrade to a newer OpenJDK package in Termux (`pkg install openjdk-21` or `openjdk-17`).",
                "pkg install -y openjdk-21 || pkg install -y openjdk-17",
                "java -version",
                "Class file version 61 = Java 17, version 65 = Java 21."
            );
        }

        if (lower.contains("aapt2") && (lower.contains("syntax error") || lower.contains("exec format error") || lower.contains("daemon startup failed"))) {
            String errLine = extractMatchingLine(redacted, "aapt2");
            return new DiagnosisResult(
                ErrorCategory.JVM_GRADLE_ANDROID_BUILD_ERROR,
                true,
                errLine,
                "Android Gradle Plugin downloaded an x86_64 glibc `aapt2` binary from Google Maven, which cannot execute on an ARM64 Android device.",
                "Termux native `aapt2` (`pkg install aapt2`)",
                "Install Termux's native ARM64 `aapt2` package and pass `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2` when running Gradle.",
                "pkg install -y aapt2",
                "aapt2 version",
                "On ARM64 Android devices, always override Maven's x86_64 AAPT2 binary with `$PREFIX/bin/aapt2`."
            );
        }

        // 9. C / C++ / Rust / Go compiler & linker errors
        Matcher headerMatcher = Pattern.compile("fatal error:\\s+['\"<]([^'\">]+\\.h)['\">]\\s+file not found").matcher(redacted);
        if (headerMatcher.find()) {
            String header = headerMatcher.group(1);
            String errLine = headerMatcher.group(0);
            String pkg = mapHeaderToTermuxPackage(header);
            return new DiagnosisResult(
                ErrorCategory.COMPILED_LANGUAGE_TOOLCHAIN_ERROR,
                true,
                errLine,
                "C/C++ header file `<" + header + ">` is missing during compilation.",
                pkg != null ? ("Termux package `" + pkg + "`") : ("Library providing `" + header + "`"),
                pkg != null
                    ? ("Install `" + pkg + "` via `pkg install -y " + pkg + "` (Termux bundles headers inside main library packages).")
                    : ("Search for the library providing `" + header + "` with `pkg search " + header.replace(".h", "") + "`."),
                pkg != null ? ("pkg install -y " + pkg) : ("pkg install -y clang make pkg-config"),
                "clang -v",
                "Unlike Debian/Ubuntu, Termux does not split headers into `-dev` packages; installing the library package installs its `.h` headers into `$PREFIX/include`."
            );
        }

        if (lower.contains("linker `cc` not found") || lower.contains("linker 'cc' not found")) {
            String errLine = extractMatchingLine(redacted, "linker");
            return new DiagnosisResult(
                ErrorCategory.COMPILED_LANGUAGE_TOOLCHAIN_ERROR,
                true,
                errLine,
                "Rust (`cargo`/`rustc`) invoked the system C linker (`cc`), which is not installed.",
                "Termux package `clang`",
                "Install `clang` in Termux so `cc` and `lld` are available in `$PREFIX/bin`.",
                "pkg install -y clang",
                "cc --version && cargo --version",
                "Cargo on Android links against Bionic libc using Termux's `clang`."
            );
        }

        // 10. Permission denied / Storage restrictions
        if (lower.contains("permission denied") || lower.contains("operation not permitted")) {
            String errLine = extractMatchingLine(redacted, "Permission denied");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "Operation not permitted");
            if (errLine.contains("/sdcard") || errLine.contains("/storage/emulated")) {
                return new DiagnosisResult(
                    ErrorCategory.PERMISSION_OR_STORAGE_ERROR,
                    true,
                    errLine,
                    "Android blocks `chmod +x` and direct binary/script execution on shared FAT/FUSE storage (`/sdcard` / `/storage/emulated/0`), or storage permission has not been granted.",
                    "`termux-setup-storage` and execution inside `$HOME`",
                    "Move project scripts into `$HOME` (`/data/data/com.termux/files/home`) to execute them with `chmod +x`, or invoke the interpreter explicitly (`bash script.sh` / `python3 script.py`). If shared storage is inaccessible, run `termux-setup-storage`.",
                    "termux-setup-storage",
                    "ls -la $HOME",
                    "Android mounts `/storage/emulated/0` with `noexec`; executables and git repositories with hooks should reside inside `$HOME`."
                );
            }
            Matcher scriptPerm = Pattern.compile("(?m)^(?:bash|zsh|sh):\\s+([^:\\s]+):\\s+Permission denied").matcher(redacted);
            String targetFile = scriptPerm.find() ? scriptPerm.group(1) : "<file>";
            return new DiagnosisResult(
                ErrorCategory.PERMISSION_OR_STORAGE_ERROR,
                !targetFile.equals("<file>"),
                errLine,
                "The target script or binary does not have the executable (`+x`) permission bit set, or the path requires privileges unavailable to unprivileged Android apps.",
                "Executable permission (`chmod +x`)",
                "Grant execute permission on the file inside `$HOME` (`chmod +x " + targetFile + "`) and verify its shebang with `termux-fix-shebang`.",
                "chmod +x " + targetFile,
                "ls -l " + targetFile,
                "Unprivileged Termux sessions cannot read `/data/data` of other Android apps or bind ports below 1024."
            );
        }

        // 11. Network / DNS / SSL errors
        if (lower.contains("could not resolve host") || lower.contains("temporary failure in name resolution")
            || lower.contains("ssl certificate problem") || lower.contains("connection timed out")) {
            String errLine = extractMatchingLine(redacted, "resolve");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "SSL");
            if (errLine.isEmpty()) errLine = extractMatchingLine(redacted, "timed out");
            boolean isSsl = lower.contains("ssl certificate problem");
            return new DiagnosisResult(
                ErrorCategory.NETWORK_OR_SSL_ERROR,
                isSsl,
                errLine,
                isSsl
                    ? "CA root certificates bundle (`$PREFIX/etc/tls/cert.pem`) is missing or outdated."
                    : "Network request failed due to offline device state, DNS resolution failure, or unreachable remote host.",
                isSsl ? "Termux package `ca-certificates`" : "Active Internet connection & DNS",
                isSsl
                    ? "Reinstall or update `ca-certificates` and `openssl-tool` in Termux."
                    : "Check Android Wi-Fi/cellular connectivity and test DNS resolution.",
                isSsl ? "pkg install -y ca-certificates openssl-tool" : "ping -c 2 8.8.8.8",
                "curl -I https://packages.termux.dev",
                "Termux TLS tools read CA certificates from `$PREFIX/etc/tls/cert.pem` provided by `ca-certificates`."
            );
        }

        // 12. Insufficient evidence — never fabricate a root cause
        return buildInsufficientEvidence(
            "Insufficient evidence in the provided output to determine a specific root cause without guessing. "
            + "Capture the full error message or stacktrace (e.g., command exit code, compiler output, or traceback) or use 'Ask AI Debugger' for contextual analysis."
        );
    }

    @NonNull
    private static DiagnosisResult buildInsufficientEvidence(@NonNull String reason) {
        return new DiagnosisResult(
            ErrorCategory.INSUFFICIENT_EVIDENCE,
            false,
            "(No specific error pattern detected in terminal output)",
            reason,
            "None identified from current evidence",
            "Run the failing command with verbose flags (`--verbose`, `--stacktrace`, or `bash -x`) and re-run Diagnosis.",
            "",
            "echo $?",
            "Termux AI never fabricates root causes or repairs when terminal output lacks concrete failure evidence."
        );
    }

    @Nullable
    private static String mapDesktopPackageNameToTermux(@NonNull String rawPkg) {
        String p = rawPkg.toLowerCase(Locale.US);
        if (p.equals("build-essential") || p.equals("gcc") || p.equals("g++")) return "clang make pkg-config";
        if (p.equals("python3-pip") || p.equals("python3-venv") || p.equals("python3-dev") || p.equals("python3")) return "python";
        if (p.equals("default-jdk") || p.equals("openjdk-17-jdk") || p.equals("openjdk-11-jdk")) return "openjdk-17";
        if (p.equals("libssl-dev")) return "openssl";
        if (p.equals("libffi-dev")) return "libffi";
        if (p.equals("zlib1g-dev")) return "zlib";
        if (p.equals("libxml2-dev")) return "libxml2";
        if (p.equals("libxslt1-dev")) return "libxslt";
        if (p.equals("libjpeg-dev")) return "libjpeg-turbo";
        if (p.equals("pkg-config")) return "pkg-config";
        if (p.equals("openssh-client") || p.equals("openssh-server")) return "openssh";
        if (p.endsWith("-dev")) return p.substring(0, p.length() - 4);
        if (p.endsWith("-devel")) return p.substring(0, p.length() - 6);
        return null;
    }

    @Nullable
    private static String mapHeaderToTermuxPackage(@NonNull String header) {
        String h = header.toLowerCase(Locale.US);
        if (h.contains("openssl/")) return "openssl";
        if (h.equals("ffi.h")) return "libffi";
        if (h.equals("zlib.h")) return "zlib";
        if (h.contains("python.h")) return "python";
        if (h.contains("curl/")) return "libcurl";
        if (h.contains("sqlite3.h")) return "libsqlite";
        if (h.contains("png.h")) return "libpng";
        if (h.contains("jpeglib.h")) return "libjpeg-turbo";
        if (h.contains("xmlversion.h") || h.contains("libxml/")) return "libxml2";
        return null;
    }

    @NonNull
    private static String extractMatchingLine(@NonNull String text, @NonNull String needle) {
        String lowerNeedle = needle.toLowerCase(Locale.US);
        String[] lines = text.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.toLowerCase(Locale.US).contains(lowerNeedle)) {
                return line.length() > 220 ? line.substring(0, 220) + "…" : line;
            }
        }
        return lines.length > 0 ? lines[lines.length - 1].trim() : text;
    }
}
