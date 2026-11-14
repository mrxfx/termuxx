package com.termux.app.workspace;

import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Screen 6: Restore Preview (Samsung One UI Dark Black & White).
 * Validates backup schema version & SHA-256 checksum, previews all included items
 * and target overwrite files, and requires explicit user confirmation before restoring.
 */
public class RestorePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_BACKUP_FILE_PATH = "extra_backup_file_path";
    public static final String EXTRA_BACKUP_RAW_JSON = "extra_backup_raw_json";

    private BackupArchiveManager mBackupManager;
    private LinearLayout mContentContainer;
    @Nullable private String mRawBackupJson;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_screen_shell);

        mBackupManager = new BackupArchiveManager(this);

        ImageButton backBtn = findViewById(R.id.btn_workspace_back);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        TextView titleView = findViewById(R.id.text_workspace_screen_title);
        if (titleView != null) titleView.setText(R.string.title_restore_preview);

        TextView subtitleView = findViewById(R.id.text_workspace_screen_subtitle);
        if (subtitleView != null) subtitleView.setText(R.string.subtitle_restore_preview);

        if (getIntent() != null) {
            String path = getIntent().getStringExtra(EXTRA_BACKUP_FILE_PATH);
            String inlineJson = getIntent().getStringExtra(EXTRA_BACKUP_RAW_JSON);
            if (inlineJson != null && !inlineJson.isEmpty()) {
                mRawBackupJson = inlineJson;
            } else if (path != null && !path.isEmpty()) {
                mRawBackupJson = mBackupManager.readBackupFile(new File(path));
            }
        }

        mContentContainer = findViewById(R.id.workspace_content_container);
        renderPreview();
    }

    private void renderPreview() {
        if (mContentContainer == null) return;
        mContentContainer.removeAllViews();

        BackupArchiveManager.RestorePreviewReport report =
            mBackupManager.validateAndPreview(mRawBackupJson);

        // 1. Validation & Checksum Card
        LinearLayout statusCard = WorkspaceUiFactory.createCard(this);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(WorkspaceUiFactory.createTitle(
            this, report.valid ? report.label : "Backup Validation Failed"),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(WorkspaceUiFactory.createBadge(
            this, report.valid ? "VALID v" + report.schemaVersion : "INVALID", report.valid));
        statusCard.addView(header);

        String dateStr = report.createdAtMs > 0
            ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(report.createdAtMs))
            : "Unknown";
        BackupArchiveManager.BackupEntry duplicate = mBackupManager.findDuplicateBackup(mRawBackupJson);
        String dupStatus = duplicate != null ? "Matches saved backup '" + duplicate.label + "'" : "Unique snapshot";

        statusCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Status: " + report.validationMessage + "\n"
                + "Created: " + dateStr + "\n"
                + "SHA-256 Integrity Checksum: " + (report.checksumVerified ? "Verified ✓" : "Failed ✕") + "\n"
                + "Duplicate Check: " + dupStatus));
        mContentContainer.addView(statusCard);

        if (!report.valid || mRawBackupJson == null) {
            return;
        }

        // 2. Archive Contents Summary Card
        LinearLayout contentsCard = WorkspaceUiFactory.createCard(this);
        contentsCard.addView(WorkspaceUiFactory.createTitle(this, "Archive Contents Summary"));
        String shellList = report.includedShellFiles.isEmpty()
            ? "None"
            : String.join(", ", report.includedShellFiles);
        String overwriteList = report.existingFilesToOverwrite.isEmpty()
            ? "None (No existing config files will be overwritten)"
            : String.join(", ", report.existingFilesToOverwrite);

        contentsCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "• Workspaces: " + report.workspaceCount + "\n"
                + "• Terminal Profiles: " + report.profileCount + "\n"
                + "• Command Snippets: " + report.snippetCount + "\n"
                + "• Shell RC Files: " + shellList + "\n"
                + "• Colors & Termux Properties: "
                + ((report.hasColorsProperties || report.hasTermuxProperties) ? "Included" : "Not Included") + "\n"
                + "• Existing Files to Back Up & Overwrite: " + overwriteList));
        mContentContainer.addView(contentsCard);

        // 3. Selective Restore Toggles Card
        LinearLayout optionsCard = WorkspaceUiFactory.createCard(this);
        optionsCard.addView(WorkspaceUiFactory.createTitle(this, "Select Components to Restore"));
        optionsCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "An automatic Pre-Restore Safety Snapshot is created before any selected items are restored."));

        SwitchCompat swWorkspaces = WorkspaceUiFactory.createSwitchRow(
            this, optionsCard, "Restore Workspaces (" + report.workspaceCount + ")", true);
        SwitchCompat swProfiles = WorkspaceUiFactory.createSwitchRow(
            this, optionsCard, "Restore Terminal Profiles (" + report.profileCount + ")", true);
        SwitchCompat swSnippets = WorkspaceUiFactory.createSwitchRow(
            this, optionsCard, "Restore Command Snippets (" + report.snippetCount + ")", true);
        SwitchCompat swShell = WorkspaceUiFactory.createSwitchRow(
            this, optionsCard, "Restore Shell Configs (" + shellList + ")", true);
        SwitchCompat swTheme = WorkspaceUiFactory.createSwitchRow(
            this, optionsCard, "Restore Theme & Termux Properties", true);
        SwitchCompat swPrefs = WorkspaceUiFactory.createSwitchRow(
            this, optionsCard, "Restore Terminal, Autocomplete & AI Preferences", true);

        Button restoreBtn = WorkspaceUiFactory.createPrimaryButton(
            this, getString(R.string.action_confirm_restore));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = WorkspaceUiFactory.dp(this, 14);
        restoreBtn.setLayoutParams(btnLp);
        restoreBtn.setOnClickListener(v -> {
            BackupArchiveManager.RestoreOptions opts = new BackupArchiveManager.RestoreOptions();
            opts.restoreWorkspaces = swWorkspaces.isChecked();
            opts.restoreProfiles = swProfiles.isChecked();
            opts.restoreSnippets = swSnippets.isChecked();
            opts.restoreShellConfigs = swShell.isChecked();
            opts.restoreThemeAndProperties = swTheme.isChecked();
            opts.restorePreferences = swPrefs.isChecked();
            confirmAndExecuteRestore(report, opts);
        });
        optionsCard.addView(restoreBtn);

        mContentContainer.addView(optionsCard);
    }

    private void confirmAndExecuteRestore(
        BackupArchiveManager.RestorePreviewReport report,
        BackupArchiveManager.RestoreOptions options
    ) {
        new AlertDialog.Builder(this)
            .setTitle("Confirm Configuration Restore")
            .setMessage("Restore selected components from '" + report.label + "'?\n\n"
                + "Your current configuration will first be saved as a Pre-Restore Safety Snapshot.")
            .setPositiveButton("Restore Now", (d, w) -> {
                if (mRawBackupJson == null) return;
                boolean ok = mBackupManager.restoreBackup(mRawBackupJson, options);
                if (ok) {
                    Toast.makeText(this, "Configuration restored successfully.", Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    Toast.makeText(this, "Restore failed validation.", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }
}
