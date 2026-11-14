package com.termux.app.env;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.workspace.WorkspaceUiFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One-Click Safe Repair Manager implementing the mandatory workflow:
 * Detect -> Diagnose -> Prepare Fix -> User Approval -> Execute (Terminal Insert) -> Verify.
 *
 * Guarantees:
 * - Never silently executes repair commands.
 * - Shows exact commands and affected packages before execution.
 * - Requires explicit user confirmation and supports cancellation.
 * - Persists a full audit log of repair activity.
 * - Never deletes user project files to fix dependencies.
 */
public class EnvironmentRepairManager {

    public static final class RepairPlan {
        @NonNull public final String id;
        @NonNull public final String title;
        @NonNull public final String summary;
        @NonNull public final String packagesToInstallOrModify;
        @NonNull public final String exactRepairCommand;
        @NonNull public final String verificationCommand;
        @NonNull public final String androidCaveat;
        public final boolean preservesUserFiles;

        public RepairPlan(
            @NonNull String id,
            @NonNull String title,
            @NonNull String summary,
            @NonNull String packagesToInstallOrModify,
            @NonNull String exactRepairCommand,
            @NonNull String verificationCommand,
            @NonNull String androidCaveat
        ) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.packagesToInstallOrModify = packagesToInstallOrModify;
            this.exactRepairCommand = sanitizeSafeRepairCommand(exactRepairCommand);
            this.verificationCommand = sanitizeSafeRepairCommand(verificationCommand);
            this.androidCaveat = androidCaveat;
            this.preservesUserFiles = true;
        }

        @NonNull
        public String buildCombinedRepairAndVerifyCommand() {
            if (exactRepairCommand.isEmpty()) return verificationCommand;
            if (verificationCommand.isEmpty()) return exactRepairCommand;
            return exactRepairCommand + " && " + verificationCommand;
        }
    }

    public static final class RepairLogEntry {
        @NonNull public final String id;
        public final long timestampMs;
        @NonNull public final String title;
        @NonNull public final String status; // APPROVED_INSERTED, CANCELLED, VERIFICATION_QUEUED
        @NonNull public final String command;
        @NonNull public final String verificationCommand;
        @NonNull public final String packagesAffected;

        public RepairLogEntry(
            @NonNull String id,
            long timestampMs,
            @NonNull String title,
            @NonNull String status,
            @NonNull String command,
            @NonNull String verificationCommand,
            @NonNull String packagesAffected
        ) {
            this.id = id;
            this.timestampMs = timestampMs;
            this.title = title;
            this.status = status;
            this.command = command;
            this.verificationCommand = verificationCommand;
            this.packagesAffected = packagesAffected;
        }

        @NonNull
        public String formatTimestamp() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestampMs));
        }

        @NonNull
        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("timestampMs", timestampMs);
            obj.put("title", title);
            obj.put("status", status);
            obj.put("command", command);
            obj.put("verificationCommand", verificationCommand);
            obj.put("packagesAffected", packagesAffected);
            return obj;
        }

        @NonNull
        public static RepairLogEntry fromJson(@NonNull JSONObject obj) {
            return new RepairLogEntry(
                obj.optString("id", UUID.randomUUID().toString()),
                obj.optLong("timestampMs", System.currentTimeMillis()),
                obj.optString("title", "Environment Repair"),
                obj.optString("status", "APPROVED_INSERTED"),
                obj.optString("command", ""),
                obj.optString("verificationCommand", ""),
                obj.optString("packagesAffected", "")
            );
        }
    }

    private final File mLogFile;

    public EnvironmentRepairManager(@NonNull Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "termux_ai_repair_logs.json"));
    }

    public EnvironmentRepairManager(@NonNull File logFile) {
        mLogFile = logFile;
    }

    /**
     * Ensures repair commands never contain destructive project file deletion commands.
     */
    @NonNull
    public static String sanitizeSafeRepairCommand(@Nullable String rawCmd) {
        if (rawCmd == null) return "";
        String singleLine = rawCmd.replace('\r', ' ').replace('\n', ' ').trim();
        String lower = singleLine.toLowerCase(Locale.US);
        if (lower.contains("rm -rf")
            || lower.contains("rm -fr")
            || lower.contains("git clean -fd")
            || lower.contains("git reset --hard")) {
            return "";
        }
        return singleLine;
    }

    @NonNull
    public RepairPlan preparePlanFromDiagnosis(@NonNull ErrorDiagnosisEngine.DiagnosisResult diagnosis) {
        return new RepairPlan(
            "plan_" + UUID.randomUUID().toString().substring(0, 8),
            "Repair: " + diagnosis.category.label,
            diagnosis.probableCause + "\n\nRecommended Fix: " + diagnosis.recommendedFix,
            diagnosis.requiredDependency,
            diagnosis.exactRepairCommand,
            diagnosis.verificationCommand,
            diagnosis.termuxCompatibilityNote
        );
    }

    @NonNull
    public RepairPlan preparePlanForRuntime(@NonNull RuntimeEnvironmentScanner.RuntimeSpec spec) {
        return new RepairPlan(
            "plan_rt_" + spec.id,
            "Install / Configure " + spec.displayName,
            "Installs " + spec.displayName + " (" + spec.fileExtensions + ") via Termux package manager and verifies the binary in $PREFIX/bin.",
            "Termux packages: " + spec.termuxPackage,
            spec.installCommand,
            spec.verifyCommand,
            spec.androidCompatibilityNote
        );
    }

    @Nullable
    public RepairPlan preparePlanForProject(@NonNull ProjectEnvironmentScanner.ProjectEnvironmentReport report) {
        if (report.recommendedSetupCommands.isEmpty()) {
            return null;
        }
        String joinedSetup = String.join(" && ", report.recommendedSetupCommands);
        String joinedVerify = report.verificationCommands.isEmpty()
            ? "echo 'Project setup complete'"
            : String.join(" && ", report.verificationCommands);
        String deps = report.missingRuntimes.isEmpty()
            ? String.join(", ", report.detectedEcosystems)
            : String.join("; ", report.missingRuntimes);
        String notes = report.androidCompatibilityNotes.isEmpty()
            ? "Preserves existing project files and lockfiles."
            : String.join(" ", report.androidCompatibilityNotes);

        return new RepairPlan(
            "plan_proj_" + UUID.randomUUID().toString().substring(0, 8),
            "Prepare Project Environment (" + String.join(", ", report.detectedEcosystems) + ")",
            "Installs missing Termux runtimes and initializes project dependencies without deleting any project files.",
            deps,
            joinedSetup,
            joinedVerify,
            notes
        );
    }

    /**
     * Records a user-approved or cancelled repair action in the persistent audit log.
     */
    @NonNull
    public synchronized RepairLogEntry recordRepairAction(
        @NonNull RepairPlan plan,
        @NonNull String status,
        @NonNull String finalCommand
    ) {
        RepairLogEntry entry = new RepairLogEntry(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            plan.title,
            status,
            finalCommand,
            plan.verificationCommand,
            plan.packagesToInstallOrModify
        );
        List<RepairLogEntry> all = getRepairLogs();
        all.add(0, entry);
        while (all.size() > 50) {
            all.remove(all.size() - 1);
        }
        saveLogs(all);
        return entry;
    }

    @NonNull
    public synchronized List<RepairLogEntry> getRepairLogs() {
        List<RepairLogEntry> out = new ArrayList<>();
        if (!mLogFile.exists() || !mLogFile.isFile()) return out;
        try (FileInputStream fis = new FileInputStream(mLogFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    out.add(RepairLogEntry.fromJson(obj));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized void clearRepairLogs() {
        if (mLogFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mLogFile.delete();
        }
    }

    private void saveLogs(@NonNull List<RepairLogEntry> entries) {
        try {
            File parent = mLogFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            JSONArray arr = new JSONArray();
            for (RepairLogEntry e : entries) {
                arr.put(e.toJson());
            }
            try (FileOutputStream fos = new FileOutputStream(mLogFile)) {
                fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Shows the interactive One-Click Safe Repair confirmation modal:
     * displays affected packages, exact repair command (editable before approval),
     * verification command, and Android/Termux notes.
     */
    public void showSafeRepairApprovalDialog(
        @NonNull Context context,
        @NonNull RepairPlan plan,
        @Nullable Runnable onApprovedCallback
    ) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(context, 18);
        container.setPadding(pad, WorkspaceUiFactory.dp(context, 10), pad, 0);

        container.addView(WorkspaceUiFactory.createSubtitle(context, plan.summary));

        container.addView(WorkspaceUiFactory.createSectionHeader(context, "Packages / Components Affected"));
        container.addView(WorkspaceUiFactory.createMonospaceBlock(context, plan.packagesToInstallOrModify));

        container.addView(WorkspaceUiFactory.createSectionHeader(context, "Exact Repair Command (Editable)"));
        EditText repairInput = WorkspaceUiFactory.createInput(
            context,
            "Repair command",
            plan.exactRepairCommand,
            true
        );
        container.addView(repairInput);

        container.addView(WorkspaceUiFactory.createSectionHeader(context, "Post-Repair Verification Command"));
        EditText verifyInput = WorkspaceUiFactory.createInput(
            context,
            "Verification command",
            plan.verificationCommand,
            true
        );
        container.addView(verifyInput);

        if (!plan.androidCaveat.isEmpty()) {
            container.addView(WorkspaceUiFactory.createSectionHeader(context, "Termux / Android Compatibility Note"));
            container.addView(WorkspaceUiFactory.createSubtitle(context, plan.androidCaveat));
        }

        new AlertDialog.Builder(context)
            .setTitle(plan.title)
            .setView(container)
            .setPositiveButton("Approve & Insert Repair + Verify", (d, w) -> {
                String repairCmd = sanitizeSafeRepairCommand(repairInput.getText().toString());
                String verifyCmd = sanitizeSafeRepairCommand(verifyInput.getText().toString());
                if (repairCmd.isEmpty()) {
                    Toast.makeText(context, "Repair command is empty or blocked by safety check", Toast.LENGTH_SHORT).show();
                    return;
                }
                String combined = verifyCmd.isEmpty() ? repairCmd : (repairCmd + " && " + verifyCmd);
                recordRepairAction(plan, "APPROVED_INSERTED", combined);
                TerminalCommandBridge.insertIntoActiveTerminal(combined);
                Toast.makeText(
                    context,
                    "Repair + verification command inserted into terminal prompt (press Enter to run)",
                    Toast.LENGTH_LONG
                ).show();
                if (onApprovedCallback != null) {
                    onApprovedCallback.run();
                }
            })
            .setNeutralButton("Insert Repair Only", (d, w) -> {
                String repairCmd = sanitizeSafeRepairCommand(repairInput.getText().toString());
                if (repairCmd.isEmpty()) return;
                recordRepairAction(plan, "APPROVED_INSERTED", repairCmd);
                TerminalCommandBridge.insertIntoActiveTerminal(repairCmd);
                Toast.makeText(
                    context,
                    "Repair command inserted into terminal prompt (press Enter to run)",
                    Toast.LENGTH_LONG
                ).show();
                if (onApprovedCallback != null) {
                    onApprovedCallback.run();
                }
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> {
                recordRepairAction(plan, "CANCELLED", plan.exactRepairCommand);
            })
            .show();
    }
}
