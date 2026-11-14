package com.termux.app.workspace;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Screen 5: Backup History & Manager (Samsung One UI Dark Black & White).
 * Generates, exports (SAF), imports (SAF), and manages versioned configuration backups
 * while strictly excluding private API keys, signing keystores, passwords, and sensitive env vars.
 */
public class BackupManagerActivity extends AppCompatActivity {

    private BackupArchiveManager mBackupManager;
    private LinearLayout mContentContainer;
    @Nullable private File mPendingExportFile;

    private final ActivityResultLauncher<String[]> mImportLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportUriSelected);

    private final ActivityResultLauncher<String> mExportLauncher =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::onExportUriSelected);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_screen_shell);

        mBackupManager = new BackupArchiveManager(this);

        ImageButton backBtn = findViewById(R.id.btn_workspace_back);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        TextView titleView = findViewById(R.id.text_workspace_screen_title);
        if (titleView != null) titleView.setText(R.string.title_backup_manager);

        TextView subtitleView = findViewById(R.id.text_workspace_screen_subtitle);
        if (subtitleView != null) subtitleView.setText(R.string.subtitle_backup_manager);

        Button topBtn = findViewById(R.id.btn_workspace_top_action);
        if (topBtn != null) {
            topBtn.setVisibility(View.VISIBLE);
            topBtn.setText(R.string.action_create_backup);
            topBtn.setOnClickListener(v -> showCreateBackupDialog());
        }

        mContentContainer = findViewById(R.id.workspace_content_container);
        renderBackups();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderBackups();
    }

    private void renderBackups() {
        if (mContentContainer == null) return;
        mContentContainer.removeAllViews();

        // 1. Security & Scope Card
        LinearLayout securityCard = WorkspaceUiFactory.createCard(this);
        securityCard.addView(WorkspaceUiFactory.createTitle(this, "Secure Configuration Backup"));
        securityCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Includes: Shell configs (.bashrc, .zshrc, config.fish), Termux theme & properties, "
                + "Terminal preferences, Workspaces, Profiles, Command Snippets, Autocomplete settings, "
                + "and non-secret AI model preferences.\n\n"
                + "Strictly Excludes: Private API keys, Android Keystore credentials, signing keystores, "
                + "passwords, sensitive environment variables, and unselected personal files."));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = WorkspaceUiFactory.dp(this, 12);
        actionRow.setLayoutParams(actLp);

        Button createBtn = WorkspaceUiFactory.createPrimaryButton(this, "+ Create Backup");
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cLp.rightMargin = WorkspaceUiFactory.dp(this, 8);
        createBtn.setLayoutParams(cLp);
        createBtn.setOnClickListener(v -> showCreateBackupDialog());
        actionRow.addView(createBtn);

        Button importBtn = WorkspaceUiFactory.createSecondaryButton(this, "Import Backup (SAF)");
        importBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        importBtn.setOnClickListener(v -> mImportLauncher.launch(new String[]{"application/json", "text/*"}));
        actionRow.addView(importBtn);

        securityCard.addView(actionRow);
        mContentContainer.addView(securityCard);

        // 2. Saved Backup History
        List<BackupArchiveManager.BackupEntry> backups = mBackupManager.listSavedBackups();
        mContentContainer.addView(WorkspaceUiFactory.createSectionHeader(
            this, "Backup History (" + backups.size() + ")"));

        if (backups.isEmpty()) {
            LinearLayout emptyCard = WorkspaceUiFactory.createCard(this);
            emptyCard.addView(WorkspaceUiFactory.createTitle(this, "No local backups created yet"));
            emptyCard.addView(WorkspaceUiFactory.createSubtitle(
                this, "Tap '+ Create Backup' to capture your current workspaces, profiles, snippets, and shell configuration."));
            mContentContainer.addView(emptyCard);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        for (BackupArchiveManager.BackupEntry entry : backups) {
            mContentContainer.addView(buildBackupCard(entry, sdf.format(new Date(entry.createdAtMs))));
        }
    }

    @NonNull
    private View buildBackupCard(@NonNull BackupArchiveManager.BackupEntry entry, @NonNull String formattedDate) {
        LinearLayout card = WorkspaceUiFactory.createCard(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = WorkspaceUiFactory.createTitle(this, entry.label);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(WorkspaceUiFactory.createBadge(this, (entry.sizeBytes / 1024 + 1) + " KB", false));
        card.addView(header);

        card.addView(WorkspaceUiFactory.createSubtitle(this,
            "Created: " + formattedDate + "\n"
                + "Workspaces: " + entry.workspaceCount
                + " · Profiles: " + entry.profileCount
                + " · Snippets: " + entry.snippetCount));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = WorkspaceUiFactory.dp(this, 12);
        btnRow.setLayoutParams(rowLp);

        Button previewBtn = WorkspaceUiFactory.createPrimaryButton(this, "Preview & Restore");
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        p1.rightMargin = WorkspaceUiFactory.dp(this, 6);
        previewBtn.setLayoutParams(p1);
        previewBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, RestorePreviewActivity.class);
            intent.putExtra(RestorePreviewActivity.EXTRA_BACKUP_FILE_PATH, entry.file.getAbsolutePath());
            startActivity(intent);
        });
        btnRow.addView(previewBtn);

        Button exportBtn = WorkspaceUiFactory.createSecondaryButton(this, "Export");
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);
        p2.rightMargin = WorkspaceUiFactory.dp(this, 6);
        exportBtn.setLayoutParams(p2);
        exportBtn.setOnClickListener(v -> {
            mPendingExportFile = entry.file;
            mExportLauncher.launch(entry.file.getName());
        });
        btnRow.addView(exportBtn);

        Button delBtn = WorkspaceUiFactory.createDangerButton(this, "Delete");
        delBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        delBtn.setOnClickListener(v -> confirmDeleteBackup(entry));
        btnRow.addView(delBtn);

        card.addView(btnRow);
        return card;
    }

    private void showCreateBackupDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText labelEt = WorkspaceUiFactory.createInput(this, "Backup Label", "Termux AI Snapshot", false);
        box.addView(labelEt);

        new AlertDialog.Builder(this)
            .setTitle("Create Configuration Backup")
            .setMessage("Capture workspaces, profiles, snippets, shell configs, and terminal settings (secrets excluded)?")
            .setView(box)
            .setPositiveButton("Create Backup", (d, w) -> {
                try {
                    BackupArchiveManager.BackupEntry created =
                        mBackupManager.createBackup(labelEt.getText().toString().trim());
                    Toast.makeText(this, "Created backup: " + created.label, Toast.LENGTH_SHORT).show();
                    renderBackups();
                } catch (Exception e) {
                    Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void confirmDeleteBackup(@NonNull BackupArchiveManager.BackupEntry entry) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Backup Archive")
            .setMessage("Delete saved backup '" + entry.label + "'?")
            .setPositiveButton("Delete", (d, w) -> {
                mBackupManager.deleteBackupFile(entry.file);
                renderBackups();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void onImportUriSelected(@Nullable Uri uri) {
        if (uri == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            String rawJson = new String(baos.toByteArray(), StandardCharsets.UTF_8);

            Intent intent = new Intent(this, RestorePreviewActivity.class);
            intent.putExtra(RestorePreviewActivity.EXTRA_BACKUP_RAW_JSON, rawJson);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to read backup file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onExportUriSelected(@Nullable Uri uri) {
        if (uri == null || mPendingExportFile == null) return;
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) return;
            String json = mBackupManager.readBackupFile(mPendingExportFile);
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            Toast.makeText(this, "Backup exported successfully.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
