package com.termux.app.env;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project Environment Scanner & Dependency Conflict Detector for Termux AI.
 * Inspects a real project directory for all 15 supported project manifests,
 * identifies missing runtimes/dependencies, detects lockfile and version conflicts,
 * flags Android/Termux native wheel constraints, and prepares non-destructive setup plans.
 * Never installs dependencies automatically just because a project file exists.
 */
public final class ProjectEnvironmentScanner {

    public static final List<String> SUPPORTED_MANIFESTS = Collections.unmodifiableList(Arrays.asList(
        "requirements.txt",
        "pyproject.toml",
        "Pipfile",
        "package.json",
        "package-lock.json",
        "yarn.lock",
        "pnpm-lock.yaml",
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "Cargo.toml",
        "go.mod",
        "composer.json",
        "Gemfile",
        "Makefile"
    ));

    private static final Map<String, String> PYTHON_TERMUX_NATIVE_PACKAGES;
    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("numpy", "python-numpy");
        map.put("scipy", "python-scipy");
        map.put("pandas", "python-pandas");
        map.put("cryptography", "python-cryptography");
        map.put("pillow", "python-pillow");
        map.put("pil", "python-pillow");
        map.put("lxml", "python-lxml");
        map.put("matplotlib", "python-matplotlib");
        map.put("grpcio", "python-grpcio");
        map.put("bcrypt", "python-bcrypt");
        map.put("pynacl", "python-pynacl");
        PYTHON_TERMUX_NATIVE_PACKAGES = Collections.unmodifiableMap(map);
    }

    public static final class ProjectEnvironmentReport {
        @NonNull public final String projectPath;
        public final boolean directoryValid;
        @NonNull public final List<String> detectedManifests;
        @NonNull public final List<String> detectedEcosystems;
        @NonNull public final List<String> declaredDependencies;
        @NonNull public final List<String> missingRuntimes;
        @NonNull public final List<String> missingProjectDependencies;
        @NonNull public final List<String> dependencyConflicts;
        @NonNull public final List<String> androidCompatibilityNotes;
        @NonNull public final List<String> recommendedSetupCommands;
        @NonNull public final List<String> verificationCommands;

        public ProjectEnvironmentReport(
            @NonNull String projectPath,
            boolean directoryValid,
            @NonNull List<String> detectedManifests,
            @NonNull List<String> detectedEcosystems,
            @NonNull List<String> declaredDependencies,
            @NonNull List<String> missingRuntimes,
            @NonNull List<String> missingProjectDependencies,
            @NonNull List<String> dependencyConflicts,
            @NonNull List<String> androidCompatibilityNotes,
            @NonNull List<String> recommendedSetupCommands,
            @NonNull List<String> verificationCommands
        ) {
            this.projectPath = projectPath;
            this.directoryValid = directoryValid;
            this.detectedManifests = Collections.unmodifiableList(new ArrayList<>(detectedManifests));
            this.detectedEcosystems = Collections.unmodifiableList(new ArrayList<>(detectedEcosystems));
            this.declaredDependencies = Collections.unmodifiableList(new ArrayList<>(declaredDependencies));
            this.missingRuntimes = Collections.unmodifiableList(new ArrayList<>(missingRuntimes));
            this.missingProjectDependencies = Collections.unmodifiableList(new ArrayList<>(missingProjectDependencies));
            this.dependencyConflicts = Collections.unmodifiableList(new ArrayList<>(dependencyConflicts));
            this.androidCompatibilityNotes = Collections.unmodifiableList(new ArrayList<>(androidCompatibilityNotes));
            this.recommendedSetupCommands = Collections.unmodifiableList(new ArrayList<>(recommendedSetupCommands));
            this.verificationCommands = Collections.unmodifiableList(new ArrayList<>(verificationCommands));
        }

        public boolean hasIssues() {
            return !missingRuntimes.isEmpty()
                || !missingProjectDependencies.isEmpty()
                || !dependencyConflicts.isEmpty();
        }
    }

    private ProjectEnvironmentScanner() {}

    @NonNull
    public static ProjectEnvironmentReport scanProject(
        @Nullable File projectDir,
        @NonNull RuntimeEnvironmentScanner.EnvironmentSnapshot envSnapshot
    ) {
        if (projectDir == null || !projectDir.exists() || !projectDir.isDirectory() || !projectDir.canRead()) {
            return new ProjectEnvironmentReport(
                projectDir != null ? projectDir.getAbsolutePath() : "",
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
            );
        }

        List<String> manifests = new ArrayList<>();
        for (String name : SUPPORTED_MANIFESTS) {
            File f = new File(projectDir, name);
            if (f.exists() && f.isFile()) {
                manifests.add(name);
            }
        }

        Set<String> ecosystems = new LinkedHashSet<>();
        List<String> declaredDeps = new ArrayList<>();
        Set<String> missingRuntimes = new LinkedHashSet<>();
        List<String> missingProjectDeps = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> androidNotes = new ArrayList<>();
        Set<String> setupCommands = new LinkedHashSet<>();
        Set<String> verifyCommands = new LinkedHashSet<>();

        // 1. Python ecosystem (requirements.txt, pyproject.toml, Pipfile)
        boolean hasReqTxt = manifests.contains("requirements.txt");
        boolean hasPyproject = manifests.contains("pyproject.toml");
        boolean hasPipfile = manifests.contains("Pipfile");
        if (hasReqTxt || hasPyproject || hasPipfile) {
            ecosystems.add("Python");
            if (!envSnapshot.isRuntimeFunctional("python")) {
                missingRuntimes.add("Python 3 (`pkg install python`)");
                setupCommands.add("pkg install -y python");
                verifyCommands.add("python3 --version && pip --version");
            }

            File venvDir = new File(projectDir, ".venv");
            File altVenvDir = new File(projectDir, "venv");
            boolean hasVenv = (venvDir.exists() && venvDir.isDirectory()) || (altVenvDir.exists() && altVenvDir.isDirectory());
            if (!hasVenv) {
                missingProjectDeps.add("Python virtual environment (`.venv`) is not initialized in project directory");
            }

            if (hasReqTxt) {
                inspectRequirementsTxt(
                    new File(projectDir, "requirements.txt"),
                    hasVenv,
                    declaredDeps,
                    conflicts,
                    androidNotes,
                    setupCommands,
                    verifyCommands
                );
            }
            if (hasPyproject && !hasReqTxt) {
                setupCommands.add("python3 -m venv --system-site-packages .venv && . .venv/bin/activate && pip install -e .");
                verifyCommands.add("python3 -m pip check");
            }
            if (hasPipfile) {
                declaredDeps.add("Pipfile (pipenv)");
                if (!hasReqTxt) {
                    setupCommands.add("python3 -m venv .venv && . .venv/bin/activate && pip install pipenv && pipenv install");
                }
            }
        }

        // 2. Node.js ecosystem (package.json, package-lock.json, yarn.lock, pnpm-lock.yaml)
        boolean hasPkgJson = manifests.contains("package.json");
        boolean hasNpmLock = manifests.contains("package-lock.json");
        boolean hasYarnLock = manifests.contains("yarn.lock");
        boolean hasPnpmLock = manifests.contains("pnpm-lock.yaml");
        if (hasPkgJson || hasNpmLock || hasYarnLock || hasPnpmLock) {
            ecosystems.add("Node.js");
            RuntimeEnvironmentScanner.RuntimeStatus nodeStatus = envSnapshot.getRuntime("nodejs");
            if (nodeStatus == null || !nodeStatus.isFunctional()) {
                missingRuntimes.add("Node.js & npm (`pkg install nodejs-lts`)");
                setupCommands.add("pkg install -y nodejs-lts");
                verifyCommands.add("node --version && npm --version");
            }

            int lockCount = (hasNpmLock ? 1 : 0) + (hasYarnLock ? 1 : 0) + (hasPnpmLock ? 1 : 0);
            if (lockCount > 1) {
                List<String> presentLocks = new ArrayList<>();
                if (hasNpmLock) presentLocks.add("package-lock.json");
                if (hasYarnLock) presentLocks.add("yarn.lock");
                if (hasPnpmLock) presentLocks.add("pnpm-lock.yaml");
                conflicts.add("Lockfile inconsistency: multiple Node lockfiles present ("
                    + String.join(", ", presentLocks)
                    + "). Keep one package manager lockfile to avoid version drift.");
            }

            File nodeModules = new File(projectDir, "node_modules");
            if (hasPkgJson && (!nodeModules.exists() || !nodeModules.isDirectory())) {
                missingProjectDeps.add("`node_modules/` is missing (project dependencies not installed)");
                if (hasNpmLock) {
                    setupCommands.add("npm ci");
                } else if (hasYarnLock) {
                    setupCommands.add("yarn install --frozen-lockfile");
                } else if (hasPnpmLock) {
                    setupCommands.add("pnpm install --frozen-lockfile");
                } else {
                    setupCommands.add("npm install");
                }
                verifyCommands.add("npm ls --depth=0");
            }

            if (hasPkgJson) {
                inspectPackageJson(
                    new File(projectDir, "package.json"),
                    nodeStatus,
                    declaredDeps,
                    conflicts,
                    androidNotes
                );
            }
        }

        // 3. JVM / Gradle / Maven (build.gradle, build.gradle.kts, pom.xml)
        boolean hasGradle = manifests.contains("build.gradle") || manifests.contains("build.gradle.kts");
        boolean hasMaven = manifests.contains("pom.xml");
        if (hasGradle || hasMaven) {
            ecosystems.add(hasGradle ? "Java / Kotlin (Gradle)" : "Java (Maven)");
            if (!envSnapshot.isRuntimeFunctional("java")) {
                missingRuntimes.add("OpenJDK (`pkg install openjdk-17`)");
                setupCommands.add("pkg install -y openjdk-17");
                verifyCommands.add("java -version && javac -version");
            }
            if (hasGradle) {
                File gradlew = new File(projectDir, "gradlew");
                if (gradlew.exists() && gradlew.isFile()) {
                    if (!gradlew.canExecute()) {
                        missingProjectDeps.add("`./gradlew` wrapper is not marked executable (`chmod +x gradlew`)");
                        setupCommands.add("chmod +x gradlew && termux-fix-shebang gradlew");
                    }
                    androidNotes.add("On Android/Termux, `./gradlew` may require `termux-fix-shebang gradlew` and custom AAPT2 override on ARM64.");
                } else if (!envSnapshot.isRuntimeFunctional("gradle")) {
                    missingRuntimes.add("Gradle (`pkg install gradle`)");
                    setupCommands.add("pkg install -y gradle");
                }
                verifyCommands.add("gradle --version");
            }
            if (hasMaven) {
                setupCommands.add("pkg install -y maven");
                verifyCommands.add("mvn --version");
            }
        }

        // 4. Rust (Cargo.toml)
        if (manifests.contains("Cargo.toml")) {
            ecosystems.add("Rust (Cargo)");
            if (!envSnapshot.isRuntimeFunctional("rust")) {
                missingRuntimes.add("Rust & Cargo (`pkg install rust clang`)");
                setupCommands.add("pkg install -y rust clang");
                verifyCommands.add("rustc --version && cargo --version");
            } else if (!envSnapshot.isRuntimeFunctional("c_cpp")) {
                missingRuntimes.add("Clang linker (`pkg install clang`) required by Cargo on Android");
                setupCommands.add("pkg install -y clang");
            }
            inspectCargoToml(new File(projectDir, "Cargo.toml"), declaredDeps);
            verifyCommands.add("cargo check");
        }

        // 5. Go (go.mod)
        if (manifests.contains("go.mod")) {
            ecosystems.add("Go (Modules)");
            RuntimeEnvironmentScanner.RuntimeStatus goStatus = envSnapshot.getRuntime("go");
            if (goStatus == null || !goStatus.isFunctional()) {
                missingRuntimes.add("Go compiler (`pkg install golang`)");
                setupCommands.add("pkg install -y golang");
                verifyCommands.add("go version");
            }
            inspectGoMod(new File(projectDir, "go.mod"), goStatus, declaredDeps, conflicts);
            verifyCommands.add("go mod verify");
        }

        // 6. PHP (composer.json)
        if (manifests.contains("composer.json")) {
            ecosystems.add("PHP (Composer)");
            if (!envSnapshot.isRuntimeFunctional("php")) {
                missingRuntimes.add("PHP & Composer (`pkg install php composer`)");
                setupCommands.add("pkg install -y php composer");
                verifyCommands.add("php --version && composer --version");
            }
            File vendorDir = new File(projectDir, "vendor");
            if (!vendorDir.exists()) {
                missingProjectDeps.add("Composer `vendor/` directory is not installed");
                setupCommands.add("composer install");
            }
        }

        // 7. Ruby (Gemfile)
        if (manifests.contains("Gemfile")) {
            ecosystems.add("Ruby (Bundler)");
            if (!envSnapshot.isRuntimeFunctional("ruby")) {
                missingRuntimes.add("Ruby (`pkg install ruby clang make`)");
                setupCommands.add("pkg install -y ruby clang make libffi");
                verifyCommands.add("ruby --version && bundle --version");
            } else {
                setupCommands.add("bundle check || bundle install");
            }
        }

        // 8. C / C++ / Make (Makefile)
        if (manifests.contains("Makefile")) {
            ecosystems.add("C / C++ / Make");
            RuntimeEnvironmentScanner.RuntimeStatus cStatus = envSnapshot.getRuntime("c_cpp");
            if (cStatus == null || !cStatus.isFunctional()) {
                missingRuntimes.add("Clang & Make (`pkg install clang make`)");
                setupCommands.add("pkg install -y clang make");
                verifyCommands.add("clang --version && make --version");
            } else if (cStatus.missingCompanions.contains("make")) {
                missingRuntimes.add("GNU Make (`pkg install make`)");
                setupCommands.add("pkg install -y make");
            }
        }

        return new ProjectEnvironmentReport(
            projectDir.getAbsolutePath(),
            true,
            manifests,
            new ArrayList<>(ecosystems),
            declaredDeps,
            new ArrayList<>(missingRuntimes),
            missingProjectDeps,
            conflicts,
            androidNotes,
            new ArrayList<>(setupCommands),
            new ArrayList<>(verifyCommands)
        );
    }

    private static void inspectRequirementsTxt(
        @NonNull File reqFile,
        boolean hasVenv,
        @NonNull List<String> declaredDeps,
        @NonNull List<String> conflicts,
        @NonNull List<String> androidNotes,
        @NonNull Set<String> setupCommands,
        @NonNull Set<String> verifyCommands
    ) {
        String content = readTextSafely(reqFile, 32768);
        Map<String, String> seenPackages = new LinkedHashMap<>();
        Set<String> nativeTermuxPkgs = new LinkedHashSet<>();

        String[] lines = content.split("\\r?\\n");
        Pattern pkgPattern = Pattern.compile("^([A-Za-z0-9_.\\-]+)\\s*(([=<>!~]{1,2}.*)?)$");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            if (raw.startsWith("-r ") || raw.startsWith("--")) continue;

            int commentIdx = raw.indexOf('#');
            if (commentIdx >= 0) {
                raw = raw.substring(0, commentIdx).trim();
            }

            Matcher m = pkgPattern.matcher(raw);
            if (!m.matches()) {
                conflicts.add("Invalid requirement syntax in requirements.txt (line " + (i + 1) + "): `" + raw + "`");
                continue;
            }
            String pkgName = m.group(1).toLowerCase(Locale.US);
            String versionSpec = m.group(2) != null ? m.group(2).trim() : "";

            if (seenPackages.containsKey(pkgName) && !seenPackages.get(pkgName).equals(versionSpec)) {
                conflicts.add("Version conflict in requirements.txt for `" + pkgName + "`: `"
                    + seenPackages.get(pkgName) + "` vs `" + versionSpec + "`");
            }
            seenPackages.put(pkgName, versionSpec);
            declaredDeps.add("pip: " + pkgName + versionSpec);

            String termuxPkg = PYTHON_TERMUX_NATIVE_PACKAGES.get(pkgName);
            if (termuxPkg != null) {
                nativeTermuxPkgs.add(termuxPkg);
            }
        }

        if (!nativeTermuxPkgs.isEmpty()) {
            String pkgList = String.join(" ", nativeTermuxPkgs);
            androidNotes.add("Project requires native Python C/Rust packages ("
                + pkgList
                + "). On Android/Termux, install prebuilt Termux packages (`pkg install "
                + pkgList
                + "`) and create the virtualenv with `--system-site-packages` to avoid source compilation failures.");
            setupCommands.add("pkg install -y " + pkgList);
            setupCommands.add("python3 -m venv --system-site-packages .venv && . .venv/bin/activate && pip install -r requirements.txt");
        } else if (!hasVenv) {
            setupCommands.add("python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt");
        } else {
            setupCommands.add(". .venv/bin/activate && pip install -r requirements.txt");
        }
        verifyCommands.add("python3 -m pip check");
    }

    private static void inspectPackageJson(
        @NonNull File pkgJsonFile,
        @Nullable RuntimeEnvironmentScanner.RuntimeStatus nodeStatus,
        @NonNull List<String> declaredDeps,
        @NonNull List<String> conflicts,
        @NonNull List<String> androidNotes
    ) {
        String raw = readTextSafely(pkgJsonFile, 65536);
        if (raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject engines = root.optJSONObject("engines");
            if (engines != null) {
                String nodeConstraint = engines.optString("node", "").trim();
                if (!nodeConstraint.isEmpty()) {
                    declaredDeps.add("engines.node: " + nodeConstraint);
                    if (nodeStatus != null && nodeStatus.detectedVersion != null) {
                        Integer reqMajor = extractFirstMajorVersion(nodeConstraint);
                        Integer curMajor = extractFirstMajorVersion(nodeStatus.detectedVersion);
                        if (reqMajor != null && curMajor != null && nodeConstraint.startsWith(">=") && curMajor < reqMajor) {
                            conflicts.add("Incompatible Node.js runtime: package.json requires `node "
                                + nodeConstraint + "`, but installed version is `" + nodeStatus.detectedVersion + "`.");
                        }
                    }
                }
            }

            Set<String> depNames = new LinkedHashSet<>();
            collectJsonKeys(root.optJSONObject("dependencies"), depNames);
            collectJsonKeys(root.optJSONObject("devDependencies"), depNames);
            int count = 0;
            for (String d : depNames) {
                if (count < 16) {
                    declaredDeps.add("npm: " + d);
                }
                count++;
                if ("puppeteer".equals(d) || "playwright".equals(d) || "electron".equals(d)) {
                    androidNotes.add("`" + d + "` bundles desktop Linux glibc Chromium/Electron binaries which do not run natively on Android without Termux X11/proot Chromium.");
                } else if ("sharp".equals(d) || "bcrypt".equals(d) || "sqlite3".equals(d) || "canvas".equals(d)) {
                    androidNotes.add("`" + d + "` uses native C/C++ Node addons; ensure `pkg install clang make python` is installed before running `npm install`.");
                }
            }
        } catch (Exception e) {
            conflicts.add("Invalid JSON in package.json: " + e.getMessage());
        }
    }

    private static void inspectCargoToml(@NonNull File cargoFile, @NonNull List<String> declaredDeps) {
        String content = readTextSafely(cargoFile, 32768);
        boolean inDeps = false;
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inDeps = trimmed.equals("[dependencies]") || trimmed.equals("[dev-dependencies]");
                continue;
            }
            if (inDeps && !trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                String crate = trimmed.substring(0, trimmed.indexOf('=')).trim();
                if (!crate.isEmpty()) {
                    declaredDeps.add("cargo: " + crate);
                }
            }
        }
    }

    private static void inspectGoMod(
        @NonNull File goModFile,
        @Nullable RuntimeEnvironmentScanner.RuntimeStatus goStatus,
        @NonNull List<String> declaredDeps,
        @NonNull List<String> conflicts
    ) {
        String content = readTextSafely(goModFile, 32768);
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("go ")) {
                String goVer = trimmed.substring(3).trim();
                declaredDeps.add("go directive: " + goVer);
                if (goStatus != null && goStatus.detectedVersion != null) {
                    Integer reqMinor = extractGoMinorVersion(goVer);
                    Integer curMinor = extractGoMinorVersion(goStatus.detectedVersion);
                    if (reqMinor != null && curMinor != null && curMinor < reqMinor) {
                        conflicts.add("Incompatible Go version: go.mod requires `go "
                            + goVer + "`, but installed runtime is `" + goStatus.detectedVersion + "`.");
                    }
                }
            }
        }
    }

    @Nullable
    private static Integer extractFirstMajorVersion(@NonNull String versionText) {
        Matcher m = Pattern.compile("(\\d+)").matcher(versionText);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    @Nullable
    private static Integer extractGoMinorVersion(@NonNull String versionText) {
        Matcher m = Pattern.compile("1\\.(\\d+)").matcher(versionText);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static void collectJsonKeys(@Nullable JSONObject obj, @NonNull Set<String> target) {
        if (obj == null) return;
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            target.add(it.next());
        }
    }

    @NonNull
    private static String readTextSafely(@NonNull File file, int maxBytes) {
        if (!file.exists() || !file.isFile() || !file.canRead()) return "";
        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int total = 0;
            int n;
            while (total < maxBytes && (n = fis.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
                baos.write(buf, 0, n);
                total += n;
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
