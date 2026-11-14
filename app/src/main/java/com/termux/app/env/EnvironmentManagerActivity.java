package com.termux.app.env;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.context.AIContextSnapshot;
import com.termux.app.ai.firebase.AppCheckInit;
import com.termux.app.ai.security.SensitiveDataRedactor;
import com.termux.app.ai.ui.AIAssistantActivity;
import com.termux.app.studio.ShellConfigManager;
import com.termux.app.ui.OneUINavHelper;
import com.termux.app.workspace.ProfileRepository;
import com.termux.app.workspace.WorkspaceItem;
import com.termux.app.workspace.WorkspaceRepository;
import com.termux.app.workspace.WorkspaceUiFactory;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Universal Developer Environment & Error Fix Activity in Samsung One UI Dark Black & White theme.
 * Provides:
 * 1. Automatic Error Diagnosis (all 6 required fields) + One-Click Safe Repair + AI Debugging
 * 2. Real Runtime & Dependency Manager (14 language/tool families + system/dpkg/storage health)
 * 3. Project Environment Scanner (15 manifest types + conflict & wheel detection)
 * 4. Termux Compatibility & Limitations Guide + Persistent Repair Audit Log
 */
public class EnvironmentManagerActivity extends AppCompatActivity {

    public static final String EXTRA_INITIAL_TAB = "extra_env_initial_tab";
    public static final String EXTRA_INITIAL_ERROR_TEXT = "extra_env_initial_error_text";
    public static final String EXTRA_INITIAL_PROJECT_PATH = "extra_env_initial_project_path";

    public static final int TAB_ERROR_DIAGNOSIS = 0;
    public static final int TAB_RUNTIMES = 1;
    public static final int TAB_PROJECT_SCANNER = 2;
    public static final int TAB_LOGS_AND_LIMITS = 3;

    private RuntimeEnvironmentScanner mRuntimeScanner;
    private EnvironmentRepairManager mRepairManager;
    private RuntimeEnvironmentScanner.EnvironmentSnapshot mSnapshot;

    private LinearLayout mContentContainer;
    private TextView mSubtitleView;
    private int mActiveTab = TAB_ERROR_DIAGNOSIS;
    private String mCurrentErrorInput = "";
    private String mCurrentProjectPath = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCheckInit.configureAppCheck(this, getIntent());
        setContentView(R.layout.activity_workspace_screen_shell);

        ShellConfigManager configManager = new ShellConfigManager();
        // In production on device, scan without blocking UI for long; version probing uses 1.5s cap
        mRuntimeScanner = new RuntimeEnvironmentScanner(
            configManager.getPrefixDir(),
            configManager.getHomeDir(),
            false
        );
        mRepairManager = new EnvironmentRepairManager(this);

        TextView titleView = findViewById(R.id.text_workspace_screen_title);
        mSubtitleView = findViewById(R.id.text_workspace_screen_subtitle);
        Button primaryBtn = findViewById(R.id.btn_workspace_top_action);
        mContentContainer = findViewById(R.id.workspace_content_container);

        titleView.setText(R.string.title_environment_manager);
        primaryBtn.setText("Rescan");
        primaryBtn.setVisibility(View.VISIBLE);
        primaryBtn.setOnClickListener(v -> rescanAndRender());

        findViewById(R.id.btn_workspace_back).setOnClickListener(v -> finish());

        BottomNavigationView bottomNav = findViewById(R.id.workspace_bottom_nav);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
            OneUINavHelper.setupBottomNav(this, bottomNav, R.id.nav_studio);
        }

        initializeDefaultsFromContext(getIntent());
        rescanAndRender();
    }

    private void initializeDefaultsFromContext(@Nullable Intent intent) {
        AIContextSnapshot ctx = TerminalCommandBridge.captureSnapshot(Collections.emptyList());
        if (ctx.recentOutput != null && !ctx.recentOutput.trim().isEmpty()) {
            mCurrentErrorInput = SensitiveDataRedactor.redact(ctx.recentOutput.trim());
        }
        if (ctx.cwd != null && !ctx.cwd.trim().isEmpty()) {
            mCurrentProjectPath = ctx.cwd.trim();
        } else {
            WorkspaceRepository wsRepo = new WorkspaceRepository(this);
            WorkspaceItem activeWs = wsRepo.getActiveWorkspace();
            if (activeWs != null && !activeWs.workingDirectory.isEmpty()) {
                mCurrentProjectPath = activeWs.workingDirectory;
            } else {
                mCurrentProjectPath = new ShellConfigManager().getHomeDir().getAbsolutePath();
            }
        }

        if (intent != null) {
            mActiveTab = intent.getIntExtra(EXTRA_INITIAL_TAB, TAB_ERROR_DIAGNOSIS);
            String passedErr = intent.getStringExtra(EXTRA_INITIAL_ERROR_TEXT);
            if (passedErr != null && !passedErr.trim().isEmpty()) {
                mCurrentErrorInput = SensitiveDataRedactor.redact(passedErr.trim());
            }
            String passedPath = intent.getStringExtra(EXTRA_INITIAL_PROJECT_PATH);
            if (passedPath != null && !passedPath.trim().isEmpty()) {
                mCurrentProjectPath = passedPath.trim();
            }
        }
    }

    private void rescanAndRender() {
        mSnapshot = mRuntimeScanner.scan();
        int installedCount = 0;
        for (RuntimeEnvironmentScanner.RuntimeStatus st : mSnapshot.runtimes) {
            if (st.isFunctional()) installedCount++;
        }
        mSubtitleView.setText(
            "ABI: " + mSnapshot.health.cpuAbi
                + " · " + installedCount + "/" + mSnapshot.runtimes.size() + " runtimes installed"
        );
        renderActiveTab();
    }

    private void renderActiveTab() {
        mContentContainer.removeAllViews();
        mContentContainer.addView(buildTabSelectorRow());

        switch (mActiveTab) {
            case TAB_ERROR_DIAGNOSIS:
                renderErrorDiagnosisTab();
                break;
            case TAB_RUNTIMES:
                renderRuntimesTab();
                break;
            case TAB_PROJECT_SCANNER:
                renderProjectScannerTab();
                break;
            case TAB_LOGS_AND_LIMITS:
                renderLogsAndLimitsTab();
                break;
        }
    }

    @NonNull
    private LinearLayout buildTabSelectorRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = WorkspaceUiFactory.dp(this, 12);
        row.setLayoutParams(lp);

        String[] titles = new String[]{"Error Fix", "Runtimes", "Project", "Logs & Info"};
        for (int i = 0; i < titles.length; i++) {
            final int tabIdx = i;
            boolean selected = (mActiveTab == tabIdx);
            Button btn = selected
                ? WorkspaceUiFactory.createPrimaryButton(this, titles[i])
                : WorkspaceUiFactory.createSecondaryButton(this, titles[i]);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, WorkspaceUiFactory.dp(this, 48), 1f);
            if (i > 0) btnLp.leftMargin = WorkspaceUiFactory.dp(this, 6);
            btn.setLayoutParams(btnLp);
            btn.setOnClickListener(v -> {
                mActiveTab = tabIdx;
                renderActiveTab();
            });
            row.addView(btn);
        }
        return row;
    }

    private void renderErrorDiagnosisTab() {
        // 1. Terminal Error Input Card
        LinearLayout inputCard = WorkspaceUiFactory.createCard(this);
        inputCard.addView(WorkspaceUiFactory.createTitle(this, "Automatic Error Diagnosis"));
        inputCard.addView(WorkspaceUiFactory.createSubtitle(
            this,
            "Inspects recent terminal output or any pasted error/traceback. Never fabricates a root cause when evidence is insufficient."
        ));

        EditText errorInput = WorkspaceUiFactory.createInput(
            this,
            "Paste command failure, compiler error, or stacktrace here...",
            mCurrentErrorInput,
            true
        );
        errorInput.setSingleLine(false);
        errorInput.setMaxLines(6);
        inputCard.addView(errorInput);

        LinearLayout inputBtnRow = new LinearLayout(this);
        inputBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = WorkspaceUiFactory.dp(this, 8);
        inputBtnRow.setLayoutParams(rowLp);

        Button analyzeBtn = WorkspaceUiFactory.createPrimaryButton(this, "Diagnose Error");
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, WorkspaceUiFactory.dp(this, 48), 1f);
        analyzeBtn.setLayoutParams(pLp);
        analyzeBtn.setOnClickListener(v -> {
            mCurrentErrorInput = errorInput.getText().toString();
            renderActiveTab();
        });
        inputBtnRow.addView(analyzeBtn);

        Button pullTerminalBtn = WorkspaceUiFactory.createSecondaryButton(this, "Pull Terminal Output");
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, WorkspaceUiFactory.dp(this, 48), 1f);
        sLp.leftMargin = WorkspaceUiFactory.dp(this, 8);
        pullTerminalBtn.setLayoutParams(sLp);
        pullTerminalBtn.setOnClickListener(v -> {
            AIContextSnapshot snap = TerminalCommandBridge.captureSnapshot(Collections.emptyList());
            if (snap.recentOutput != null && !snap.recentOutput.trim().isEmpty()) {
                mCurrentErrorInput = SensitiveDataRedactor.redact(snap.recentOutput.trim());
                renderActiveTab();
            } else {
                Toast.makeText(this, "No recent terminal output captured yet", Toast.LENGTH_SHORT).show();
            }
        });
        inputBtnRow.addView(pullTerminalBtn);

        inputCard.addView(inputBtnRow);
        mContentContainer.addView(inputCard);

        // 2. Structured 6-Field Diagnosis Result Card
        ErrorDiagnosisEngine.DiagnosisResult diag = ErrorDiagnosisEngine.diagnose(mCurrentErrorInput);
        LinearLayout diagCard = WorkspaceUiFactory.createCard(this);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView catTitle = WorkspaceUiFactory.createTitle(this, diag.category.label);
        headerRow.addView(catTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(WorkspaceUiFactory.createBadge(
            this,
            diag.actionableRepairAvailable ? "Repair Ready" : "Manual / Info",
            diag.actionableRepairAvailable
        ));
        diagCard.addView(headerRow);

        diagCard.addView(WorkspaceUiFactory.createSectionHeader(this, "1. Original Error"));
        diagCard.addView(WorkspaceUiFactory.createMonospaceBlock(this, diag.originalError));

        diagCard.addView(WorkspaceUiFactory.createSectionHeader(this, "2. Probable Cause"));
        diagCard.addView(WorkspaceUiFactory.createSubtitle(this, diag.probableCause));

        diagCard.addView(WorkspaceUiFactory.createSectionHeader(this, "3. Required Dependency"));
        diagCard.addView(WorkspaceUiFactory.createMonospaceBlock(this, diag.requiredDependency));

        diagCard.addView(WorkspaceUiFactory.createSectionHeader(this, "4. Recommended Fix"));
        diagCard.addView(WorkspaceUiFactory.createSubtitle(this, diag.recommendedFix));

        diagCard.addView(WorkspaceUiFactory.createSectionHeader(this, "5. Exact Repair Command"));
        diagCard.addView(WorkspaceUiFactory.createMonospaceBlock(
            this,
            diag.exactRepairCommand.isEmpty() ? "(No automated command — see Recommended Fix)" : diag.exactRepairCommand
        ));

        diagCard.addView(WorkspaceUiFactory.createSectionHeader(this, "6. Verification Command"));
        diagCard.addView(WorkspaceUiFactory.createMonospaceBlock(this, diag.verificationCommand));

        diagCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Termux / Android Compatibility Note"));
        diagCard.addView(WorkspaceUiFactory.createSubtitle(this, diag.termuxCompatibilityNote));

        // Action buttons: One-Click Safe Repair, Verify, Copy, Ask AI Debugger
        if (!diag.exactRepairCommand.isEmpty()) {
            Button repairBtn = WorkspaceUiFactory.createPrimaryButton(
                this,
                "Repair Environment (Review & Approve)"
            );
            LinearLayout.LayoutParams repLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, WorkspaceUiFactory.dp(this, 48));
            repLp.topMargin = WorkspaceUiFactory.dp(this, 12);
            repairBtn.setLayoutParams(repLp);
            repairBtn.setOnClickListener(v -> {
                EnvironmentRepairManager.RepairPlan plan = mRepairManager.preparePlanFromDiagnosis(diag);
                mRepairManager.showSafeRepairApprovalDialog(this, plan, this::finish);
            });
            diagCard.addView(repairBtn);
        }

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = WorkspaceUiFactory.dp(this, 8);
        bottomRow.setLayoutParams(brLp);

        Button askAiBtn = WorkspaceUiFactory.createSecondaryButton(this, "Ask AI Debugger");
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(0, WorkspaceUiFactory.dp(this, 48), 1f);
        askAiBtn.setLayoutParams(aiLp);
        askAiBtn.setOnClickListener(v -> launchAiDebuggerForDiagnosis(diag));
        bottomRow.addView(askAiBtn);

        if (!diag.exactRepairCommand.isEmpty()) {
            Button copyBtn = WorkspaceUiFactory.createSecondaryButton(this, "Copy Command");
            LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(0, WorkspaceUiFactory.dp(this, 48), 1f);
            cpLp.leftMargin = WorkspaceUiFactory.dp(this, 8);
            copyBtn.setLayoutParams(cpLp);
            copyBtn.setOnClickListener(v -> copyToClipboard("Termux Repair Command", diag.exactRepairCommand));
            bottomRow.addView(copyBtn);
        }

        diagCard.addView(bottomRow);
        mContentContainer.addView(diagCard);
    }

    private void renderRuntimesTab() {
        // 1. System Health Banner
        RuntimeEnvironmentScanner.SystemHealthReport h = mSnapshot.health;
        LinearLayout healthCard = WorkspaceUiFactory.createCard(this);
        healthCard.addView(WorkspaceUiFactory.createTitle(this, "System & Package Manager Health"));
        String healthSummary = "CPU ABI: " + h.cpuAbi
            + " · Android SDK: " + h.androidSdkInt
            + "\n$PREFIX Writable: " + (h.prefixWritable ? "Yes" : "No")
            + " · $HOME Writable: " + (h.homeWritable ? "Yes" : "No")
            + " · Free Storage: " + h.usableStorageMb + " MB"
            + "\nCore CLI Utilities Installed: " + h.installedCoreUtilities.size()
            + "/" + RuntimeEnvironmentScanner.CORE_LINUX_UTILITIES.size();
        healthCard.addView(WorkspaceUiFactory.createSubtitle(this, healthSummary));

        if (!h.healthWarnings.isEmpty()) {
            healthCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Detected System Issues"));
            for (String warn : h.healthWarnings) {
                healthCard.addView(WorkspaceUiFactory.createSubtitle(this, "• " + warn));
            }
        }

        if (h.dpkgInterrupted) {
            Button fixDpkgBtn = WorkspaceUiFactory.createPrimaryButton(this, "Repair Interrupted dpkg State");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, WorkspaceUiFactory.dp(this, 48));
            lp.topMargin = WorkspaceUiFactory.dp(this, 10);
            fixDpkgBtn.setLayoutParams(lp);
            fixDpkgBtn.setOnClickListener(v -> {
                EnvironmentRepairManager.RepairPlan plan = new EnvironmentRepairManager.RepairPlan(
                    "plan_dpkg_recover",
                    "Recover Interrupted dpkg / apt State",
                    "Completes interrupted package configurations and repairs broken dependencies without deleting user files.",
                    "dpkg, apt",
                    "dpkg --configure -a && apt --fix-broken install -y",
                    "dpkg --audit",
                    "Safe standard dpkg journal recovery for Termux."
                );
                mRepairManager.showSafeRepairApprovalDialog(this, plan, this::finish);
            });
            healthCard.addView(fixDpkgBtn);
        }
        mContentContainer.addView(healthCard);

        // 2. 14 Universal Language & Tool Families
        mContentContainer.addView(WorkspaceUiFactory.createSectionHeader(
            this,
            "Universal Language & Tool Support (Real Detection)"
        ));

        for (RuntimeEnvironmentScanner.RuntimeStatus st : mSnapshot.runtimes) {
            LinearLayout card = WorkspaceUiFactory.createCard(this);
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = WorkspaceUiFactory.createTitle(
                this,
                st.spec.displayName + " (" + st.spec.fileExtensions + ")"
            );
            topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String badgeText = st.isFunctional()
                ? (st.detectedVersion != null ? "Installed · " + st.detectedVersion : "Installed")
                : "Not Installed";
            topRow.addView(WorkspaceUiFactory.createBadge(this, badgeText, st.isFunctional()));
            card.addView(topRow);

            StringBuilder details = new StringBuilder();
            if (st.isFunctional()) {
                details.append("Executable: ").append(st.binaryPath);
                if (!st.installedCompanions.isEmpty()) {
                    details.append("\nAvailable tools: ").append(String.join(", ", st.installedCompanions));
                }
                if (!st.missingCompanions.isEmpty()) {
                    details.append("\nOptional missing tools: ").append(String.join(", ", st.missingCompanions));
                }
            } else {
                details.append("Runtime binary `").append(st.spec.primaryBinary)
                    .append("` is not installed in $PREFIX/bin.\nRequired Termux package: `")
                    .append(st.spec.termuxPackage).append("`");
            }
            details.append("\nAndroid/Termux Note: ").append(st.spec.androidCompatibilityNote);
            card.addView(WorkspaceUiFactory.createSubtitle(this, details.toString()));

            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brLp.topMargin = WorkspaceUiFactory.dp(this, 10);
            btnRow.setLayoutParams(brLp);

            Button actionBtn = st.isFunctional()
                ? WorkspaceUiFactory.createSecondaryButton(this, "Verify / Reinstall")
                : WorkspaceUiFactory.createPrimaryButton(this, "Prepare Install (" + st.spec.termuxPackage + ")");
            actionBtn.setLayoutParams(new LinearLayout.LayoutParams(0, WorkspaceUiFactory.dp(this, 48), 1f));
            actionBtn.setOnClickListener(v -> {
                EnvironmentRepairManager.RepairPlan plan = mRepairManager.preparePlanForRuntime(st.spec);
                mRepairManager.showSafeRepairApprovalDialog(this, plan, this::finish);
            });
            btnRow.addView(actionBtn);
            card.addView(btnRow);

            mContentContainer.addView(card);
        }
    }

    private void renderProjectScannerTab() {
        LinearLayout scanCard = WorkspaceUiFactory.createCard(this);
        scanCard.addView(WorkspaceUiFactory.createTitle(this, "Project Environment Scanner"));
        scanCard.addView(WorkspaceUiFactory.createSubtitle(
            this,
            "Detects all 15 project manifest types, identifies missing dependencies and lockfile conflicts, and prepares safe setup commands without auto-installing."
        ));

        EditText pathInput = WorkspaceUiFactory.createInput(
            this,
            "Project directory path",
            mCurrentProjectPath,
            true
        );
        scanCard.addView(pathInput);

        Button scanBtn = WorkspaceUiFactory.createPrimaryButton(this, "Scan Project Directory");
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, WorkspaceUiFactory.dp(this, 48));
        sLp.topMargin = WorkspaceUiFactory.dp(this, 8);
        scanBtn.setLayoutParams(sLp);
        scanBtn.setOnClickListener(v -> {
            mCurrentProjectPath = pathInput.getText().toString().trim();
            renderActiveTab();
        });
        scanCard.addView(scanBtn);
        mContentContainer.addView(scanCard);

        ProjectEnvironmentScanner.ProjectEnvironmentReport report =
            ProjectEnvironmentScanner.scanProject(new File(mCurrentProjectPath), mSnapshot);

        LinearLayout reportCard = WorkspaceUiFactory.createCard(this);
        reportCard.addView(WorkspaceUiFactory.createTitle(this, "Project Environment Report"));

        if (!report.directoryValid) {
            reportCard.addView(WorkspaceUiFactory.createSubtitle(
                this,
                "Directory `" + mCurrentProjectPath + "` does not exist or is not readable."
            ));
            mContentContainer.addView(reportCard);
            return;
        }

        reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Detected Manifests"));
        reportCard.addView(WorkspaceUiFactory.createMonospaceBlock(
            this,
            report.detectedManifests.isEmpty()
                ? "(No manifest files found in this directory)"
                : String.join(", ", report.detectedManifests)
        ));

        if (!report.detectedEcosystems.isEmpty()) {
            reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Detected Ecosystems"));
            reportCard.addView(WorkspaceUiFactory.createSubtitle(this, String.join(" · ", report.detectedEcosystems)));
        }

        if (!report.missingRuntimes.isEmpty()) {
            reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Missing Runtimes / Compilers"));
            for (String mr : report.missingRuntimes) {
                reportCard.addView(WorkspaceUiFactory.createSubtitle(this, "• " + mr));
            }
        }

        if (!report.missingProjectDependencies.isEmpty()) {
            reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Missing Project Dependencies"));
            for (String md : report.missingProjectDependencies) {
                reportCard.addView(WorkspaceUiFactory.createSubtitle(this, "• " + md));
            }
        }

        if (!report.dependencyConflicts.isEmpty()) {
            reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Dependency & Lockfile Conflicts"));
            for (String cf : report.dependencyConflicts) {
                reportCard.addView(WorkspaceUiFactory.createSubtitle(this, "• " + cf));
            }
        }

        if (!report.androidCompatibilityNotes.isEmpty()) {
            reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Android / Termux Compatibility Notes"));
            for (String note : report.androidCompatibilityNotes) {
                reportCard.addView(WorkspaceUiFactory.createSubtitle(this, "• " + note));
            }
        }

        if (!report.declaredDependencies.isEmpty()) {
            reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Declared Dependencies Summary"));
            reportCard.addView(WorkspaceUiFactory.createMonospaceBlock(
                this,
                String.join("\n", report.declaredDependencies)
            ));
        }

        EnvironmentRepairManager.RepairPlan projectPlan = mRepairManager.preparePlanForProject(report);
        if (projectPlan != null) {
            reportCard.addView(WorkspaceUiFactory.createSectionHeader(this, "Recommended Setup Commands"));
            reportCard.addView(WorkspaceUiFactory.createMonospaceBlock(this, projectPlan.exactRepairCommand));

            Button prepareBtn = WorkspaceUiFactory.createPrimaryButton(
                this,
                "Prepare Project Setup (Review & Approve)"
            );
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, WorkspaceUiFactory.dp(this, 48));
            pLp.topMargin = WorkspaceUiFactory.dp(this, 12);
            prepareBtn.setLayoutParams(pLp);
            prepareBtn.setOnClickListener(v ->
                mRepairManager.showSafeRepairApprovalDialog(this, projectPlan, this::finish));
            reportCard.addView(prepareBtn);
        }

        mContentContainer.addView(reportCard);
    }

    private void renderLogsAndLimitsTab() {
        // 1. Termux vs Desktop Linux Compatibility & Limitations Card
        LinearLayout limitsCard = WorkspaceUiFactory.createCard(this);
        limitsCard.addView(WorkspaceUiFactory.createTitle(this, "Termux Android Compatibility & Limitations"));
        limitsCard.addView(WorkspaceUiFactory.createSubtitle(
            this,
            "Termux runs inside an unprivileged Android app sandbox linked against Android Bionic libc rather than desktop GNU/Linux glibc:\n\n"
                + "• Package Management: Always use `pkg install <pkg>` or `apt` without `sudo`. Debian `-dev` header packages are merged into the main package in Termux.\n"
                + "• Filesystem Paths: `/bin` and `/usr/bin` do not exist on Android; binaries reside in `$PREFIX/bin` (`/data/data/com.termux/files/usr/bin`). Use `termux-fix-shebang` for scripts with hardcoded shebangs.\n"
                + "• Shared Storage (`/sdcard`): Mounted with `noexec` by Android. Keep code repositories, virtualenvs (`.venv`), and `node_modules` inside `$HOME`.\n"
                + "• Python Native Wheels: PyPI `manylinux` wheels do not run on Android Bionic. Install scientific/crypto packages via `pkg install python-numpy python-scipy python-pandas python-cryptography python-pillow`.\n"
                + "• System Daemons: Android does not run `systemd` (`systemctl`) or Docker Engine without root/VM. Use `termux-services` (`sv`) for user-space background daemons."
        ));
        mContentContainer.addView(limitsCard);

        // 2. Persistent Repair Activity Audit Log
        LinearLayout logCard = WorkspaceUiFactory.createCard(this);
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(
            WorkspaceUiFactory.createTitle(this, "Repair Activity Log"),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        );

        List<EnvironmentRepairManager.RepairLogEntry> logs = mRepairManager.getRepairLogs();
        if (!logs.isEmpty()) {
            Button clearBtn = WorkspaceUiFactory.createDangerButton(this, "Clear Log");
            clearBtn.setOnClickListener(v -> {
                mRepairManager.clearRepairLogs();
                renderActiveTab();
            });
            headerRow.addView(clearBtn);
        }
        logCard.addView(headerRow);

        if (logs.isEmpty()) {
            logCard.addView(WorkspaceUiFactory.createSubtitle(this, "No environment repair actions recorded yet."));
        } else {
            for (EnvironmentRepairManager.RepairLogEntry entry : logs) {
                LinearLayout box = WorkspaceUiFactory.createSecondarySurfaceBox(this);
                box.addView(WorkspaceUiFactory.createTitle(this, entry.title + " · " + entry.status));
                box.addView(WorkspaceUiFactory.createSubtitle(
                    this,
                    entry.formatTimestamp() + " · Affected: " + entry.packagesAffected
                ));
                if (!entry.command.isEmpty()) {
                    box.addView(WorkspaceUiFactory.createMonospaceBlock(this, entry.command));
                }
                logCard.addView(box);
            }
        }
        mContentContainer.addView(logCard);
    }

    private void launchAiDebuggerForDiagnosis(@NonNull ErrorDiagnosisEngine.DiagnosisResult diag) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Help me debug and fix this Termux environment / script failure safely:\n\n");
        if (!mCurrentErrorInput.trim().isEmpty()) {
            String safeErr = SensitiveDataRedactor.redact(mCurrentErrorInput.trim());
            if (safeErr.length() > 1800) {
                safeErr = safeErr.substring(safeErr.length() - 1800);
            }
            prompt.append("Terminal Error Output:\n```\n").append(safeErr).append("\n```\n\n");
        }
        prompt.append("Local Diagnosis Category: ").append(diag.category.label).append('\n');
        prompt.append("Probable Cause: ").append(diag.probableCause).append('\n');
        if (!diag.exactRepairCommand.isEmpty()) {
            prompt.append("Candidate Repair Command: `").append(diag.exactRepairCommand).append("`\n");
        }
        prompt.append("\nExplain why this failed on Android Termux, provide a corrected command or dependency installation plan, and include a verification step.");

        Intent intent = new Intent(this, AIAssistantActivity.class);
        intent.putExtra(AIAssistantActivity.EXTRA_INITIAL_PROMPT, prompt.toString());
        startActivity(intent);
    }

    private void copyToClipboard(@NonNull String label, @NonNull String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, R.string.ai_code_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
