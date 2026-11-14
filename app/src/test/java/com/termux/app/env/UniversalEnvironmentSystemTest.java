package com.termux.app.env;

import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UniversalEnvironmentSystemTest {

    @Rule
    public TemporaryFolder mTempFolder = new TemporaryFolder();

    private Context mContext;
    private File mFakePrefix;
    private File mFakeHome;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.getApplication();
        mFakePrefix = mTempFolder.newFolder("usr");
        mFakeHome = mTempFolder.newFolder("home");
    }

    @Test
    public void testRuntimeScannerNeverClaimsUninstalledToolsAndDetectsRealBinaries() throws Exception {
        File binDir = new File(mFakePrefix, "bin");
        assertTrue(binDir.mkdirs());

        // Initially empty $PREFIX/bin -> 0 functional runtimes
        RuntimeEnvironmentScanner emptyScanner = new RuntimeEnvironmentScanner(mFakePrefix, mFakeHome, false);
        RuntimeEnvironmentScanner.EnvironmentSnapshot emptySnap = emptyScanner.scan();
        assertEquals(14, emptySnap.runtimes.size());
        for (RuntimeEnvironmentScanner.RuntimeStatus st : emptySnap.runtimes) {
            if (!st.spec.primaryBinary.equals("curl") && !st.spec.primaryBinary.equals("bash")) {
                assertFalse("Must never claim " + st.spec.id + " is functional when binary is absent",
                    st.installed && st.executable);
            }
        }

        // Seed real executable binaries for Python, Git, and Clang + interrupted dpkg journal
        File pyBin = new File(binDir, "python3");
        File pipBin = new File(binDir, "pip");
        File gitBin = new File(binDir, "git");
        File clangBin = new File(binDir, "clang");
        assertTrue(pyBin.createNewFile() && pyBin.setExecutable(true));
        assertTrue(pipBin.createNewFile() && pipBin.setExecutable(true));
        assertTrue(gitBin.createNewFile() && gitBin.setExecutable(true));
        assertTrue(clangBin.createNewFile() && clangBin.setExecutable(true));

        File dpkgUpdates = new File(mFakePrefix, "var/lib/dpkg/updates");
        assertTrue(dpkgUpdates.mkdirs());
        assertTrue(new File(dpkgUpdates, "0001").createNewFile());

        RuntimeEnvironmentScanner seededScanner = new RuntimeEnvironmentScanner(mFakePrefix, mFakeHome, false);
        RuntimeEnvironmentScanner.EnvironmentSnapshot seededSnap = seededScanner.scan();

        assertTrue(seededSnap.isRuntimeFunctional("python"));
        assertTrue(seededSnap.isRuntimeFunctional("git"));
        assertTrue(seededSnap.isRuntimeFunctional("c_cpp"));
        assertFalse("Rust was not installed and must report false", seededSnap.isRuntimeFunctional("rust"));
        assertTrue("Interrupted dpkg journal must be detected", seededSnap.health.dpkgInterrupted);
    }

    @Test
    public void testProjectScannerDetectsManifestsConflictsAndAndroidWheelConstraints() throws Exception {
        File projectDir = mTempFolder.newFolder("polyglot_project");

        // Create requirements.txt with conflicting pins and native Android packages (numpy, cryptography)
        String reqTxt = "numpy==1.26.0\n"
            + "cryptography>=41.0.0\n"
            + "requests==2.31.0\n"
            + "requests==2.28.0\n";
        Files.write(new File(projectDir, "requirements.txt").toPath(), reqTxt.getBytes(StandardCharsets.UTF_8));

        // Create package.json + conflicting lockfiles (package-lock.json AND yarn.lock)
        String pkgJson = "{\n"
            + "  \"name\": \"sample\",\n"
            + "  \"engines\": { \"node\": \">=20.0.0\" },\n"
            + "  \"dependencies\": { \"express\": \"^4.18.2\", \"sharp\": \"^0.33.0\" }\n"
            + "}";
        Files.write(new File(projectDir, "package.json").toPath(), pkgJson.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "package-lock.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "yarn.lock").toPath(), "# yarn".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "Cargo.toml").toPath(), "[dependencies]\nserde = \"1.0\"\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(projectDir, "Makefile").toPath(), "all:\n\tclang main.c\n".getBytes(StandardCharsets.UTF_8));

        RuntimeEnvironmentScanner scanner = new RuntimeEnvironmentScanner(mFakePrefix, mFakeHome, false);
        ProjectEnvironmentScanner.ProjectEnvironmentReport report =
            ProjectEnvironmentScanner.scanProject(projectDir, scanner.scan());

        assertTrue(report.directoryValid);
        assertTrue(report.detectedManifests.contains("requirements.txt"));
        assertTrue(report.detectedManifests.contains("package.json"));
        assertTrue(report.detectedManifests.contains("package-lock.json"));
        assertTrue(report.detectedManifests.contains("yarn.lock"));
        assertTrue(report.detectedManifests.contains("Cargo.toml"));
        assertTrue(report.detectedManifests.contains("Makefile"));

        assertTrue("Must detect lockfile conflict between package-lock.json and yarn.lock",
            report.dependencyConflicts.stream().anyMatch(s -> s.contains("multiple Node lockfiles")));
        assertTrue("Must detect conflicting requests version pins in requirements.txt",
            report.dependencyConflicts.stream().anyMatch(s -> s.contains("requests")));
        assertTrue("Must advise Termux prebuilt python-numpy / python-cryptography packages",
            report.androidCompatibilityNotes.stream().anyMatch(s -> s.contains("python-numpy")));
        assertFalse("Scanning must never auto-create .venv or node_modules",
            new File(projectDir, ".venv").exists() || new File(projectDir, "node_modules").exists());
    }

    @Test
    public void testErrorDiagnosisEngineCoversAllCategoriesAndNeverFabricatesWhenEvidenceInsufficient() {
        // 1. Insufficient evidence test
        ErrorDiagnosisEngine.DiagnosisResult noErr = ErrorDiagnosisEngine.diagnose("Hello world\nBuild completed in 2s");
        assertEquals(ErrorDiagnosisEngine.ErrorCategory.INSUFFICIENT_EVIDENCE, noErr.category);
        assertFalse(noErr.actionableRepairAvailable);

        // 2. Python native module missing (numpy -> python-numpy)
        ErrorDiagnosisEngine.DiagnosisResult numpyErr = ErrorDiagnosisEngine.diagnose(
            "Traceback (most recent call last):\n  File \"main.py\", line 1, in <module>\nModuleNotFoundError: No module named 'numpy'"
        );
        assertEquals(ErrorDiagnosisEngine.ErrorCategory.PYTHON_MISSING_MODULE, numpyErr.category);
        assertTrue(numpyErr.exactRepairCommand.contains("pkg install -y python-numpy"));
        assertFalse(numpyErr.verificationCommand.isEmpty());

        // 3. Desktop sudo apt-get on Termux
        ErrorDiagnosisEngine.DiagnosisResult sudoErr = ErrorDiagnosisEngine.diagnose(
            "$ sudo apt-get install -y nodejs\nbash: sudo: command not found"
        );
        assertEquals(ErrorDiagnosisEngine.ErrorCategory.DESKTOP_LINUX_MISMATCH, sudoErr.category);
        assertTrue(sudoErr.exactRepairCommand.contains("pkg install -y nodejs"));

        // 4. Hardcoded /bin/bash shebang on Android
        ErrorDiagnosisEngine.DiagnosisResult shebangErr = ErrorDiagnosisEngine.diagnose(
            "bash: ./deploy.sh: /bin/bash: bad interpreter: No such file or directory"
        );
        assertEquals(ErrorDiagnosisEngine.ErrorCategory.PATH_OR_SHEBANG_ERROR, shebangErr.category);
        assertTrue(shebangErr.exactRepairCommand.contains("termux-fix-shebang"));

        // 5. Interrupted dpkg state
        ErrorDiagnosisEngine.DiagnosisResult dpkgErr = ErrorDiagnosisEngine.diagnose(
            "E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem."
        );
        assertEquals(ErrorDiagnosisEngine.ErrorCategory.PACKAGE_MANAGER_OR_REPO_ERROR, dpkgErr.category);
        assertTrue(dpkgErr.exactRepairCommand.contains("dpkg --configure -a"));

        // 6. Missing command (cargo -> pkg install -y rust)
        ErrorDiagnosisEngine.DiagnosisResult cargoErr = ErrorDiagnosisEngine.diagnose(
            "zsh: command not found: cargo"
        );
        assertEquals(ErrorDiagnosisEngine.ErrorCategory.COMMAND_NOT_FOUND, cargoErr.category);
        assertTrue(cargoErr.exactRepairCommand.contains("pkg install -y rust"));

        // 7. Missing C header (openssl/ssl.h -> pkg install -y openssl)
        ErrorDiagnosisEngine.DiagnosisResult headerErr = ErrorDiagnosisEngine.diagnose(
            "src/tls.c:4:10: fatal error: 'openssl/ssl.h' file not found"
        );
        assertEquals(ErrorDiagnosisEngine.ErrorCategory.COMPILED_LANGUAGE_TOOLCHAIN_ERROR, headerErr.category);
        assertTrue(headerErr.exactRepairCommand.contains("pkg install -y openssl"));
    }

    @Test
    public void testEnvironmentRepairManagerSafetyAndActivityLaunch() throws Exception {
        File logFile = new File(mTempFolder.getRoot(), "repair_logs.json");
        EnvironmentRepairManager repairMgr = new EnvironmentRepairManager(logFile);

        // Ensure destructive commands are blocked
        assertEquals("", EnvironmentRepairManager.sanitizeSafeRepairCommand("rm -rf /data/data/com.termux/files/home/project"));
        assertEquals("", EnvironmentRepairManager.sanitizeSafeRepairCommand("git reset --hard HEAD"));
        assertEquals("pkg install -y python", EnvironmentRepairManager.sanitizeSafeRepairCommand("pkg install -y python\n"));

        ErrorDiagnosisEngine.DiagnosisResult diag = ErrorDiagnosisEngine.diagnose("bash: jq: command not found");
        EnvironmentRepairManager.RepairPlan plan = repairMgr.preparePlanFromDiagnosis(diag);
        assertTrue(plan.preservesUserFiles);
        assertEquals("pkg install -y jq", plan.exactRepairCommand);

        repairMgr.recordRepairAction(plan, "APPROVED_INSERTED", plan.buildCombinedRepairAndVerifyCommand());
        List<EnvironmentRepairManager.RepairLogEntry> logs = repairMgr.getRepairLogs();
        assertEquals(1, logs.size());
        assertEquals("APPROVED_INSERTED", logs.get(0).status);

        // Verify EnvironmentManagerActivity launches cleanly on all 4 tabs
        for (int tab = 0; tab <= 3; tab++) {
            Intent intent = new Intent(mContext, EnvironmentManagerActivity.class);
            intent.putExtra(EnvironmentManagerActivity.EXTRA_INITIAL_TAB, tab);
            intent.putExtra(EnvironmentManagerActivity.EXTRA_INITIAL_ERROR_TEXT, "bash: jq: command not found");
            EnvironmentManagerActivity act =
                Robolectric.buildActivity(EnvironmentManagerActivity.class, intent).setup().get();
            assertNotNull(act);
        }
    }
}
