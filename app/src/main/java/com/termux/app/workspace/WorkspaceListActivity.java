package com.termux.app.workspace;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.context.AIContextSnapshot;
import com.termux.app.studio.ShellConfigManager;
import com.termux.app.studio.StudioColorThemes;
import com.termux.app.studio.TerminalStudioActivity;
import com.termux.app.ui.OneUINavHelper;

import java.util.List;

/**
 * Screen 1: Workspace List & V2 Hub (Samsung One UI Dark Black & White).
 * Supports searching, creating, duplicating, setting default, selecting/applying workspaces,
 * restoring previous workspace settings, renaming, editing, deleting with confirmation,
 * and clearly distinguishing workspace settings from live terminal session state.
 */
public class WorkspaceListActivity extends AppCompatActivity {

    private WorkspaceRepository mWorkspaceRepo;
    private ProfileRepository mProfileRepo;
    private ShellConfigManager mConfigManager;
    private LinearLayout mContentContainer;
    private LinearLayout mWorkspaceListContainer;
    private String mSearchQuery = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_screen_shell);

        mConfigManager = new ShellConfigManager();
        mProfileRepo = new ProfileRepository(this);
        mWorkspaceRepo = new WorkspaceRepository(
            this,
            new java.io.File(getApplicationContext().getFilesDir(), "termux_ai_workspaces.json"),
            mProfileRepo
        );

        ImageButton backBtn = findViewById(R.id.btn_workspace_back);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        TextView titleView = findViewById(R.id.text_workspace_screen_title);
        if (titleView != null) {
            titleView.setText(R.string.title_workspace_manager);
        }
        TextView subtitleView = findViewById(R.id.text_workspace_screen_subtitle);
        if (subtitleView != null) {
            subtitleView.setText(R.string.subtitle_workspace_manager);
        }

        Button topActionBtn = findViewById(R.id.btn_workspace_top_action);
        if (topActionBtn != null) {
            topActionBtn.setVisibility(View.VISIBLE);
            topActionBtn.setText(R.string.action_new_workspace);
            topActionBtn.setOnClickListener(v ->
                startActivity(new Intent(this, WorkspaceEditorActivity.class))
            );
        }

        View navDivider = findViewById(R.id.workspace_bottom_nav_divider);
        if (navDivider != null) navDivider.setVisibility(View.VISIBLE);
        BottomNavigationView bottomNav = findViewById(R.id.workspace_bottom_nav);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
            OneUINavHelper.setupBottomNav(this, bottomNav, R.id.nav_studio);
        }

        mContentContainer = findViewById(R.id.workspace_content_container);
        renderContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BottomNavigationView bottomNav = findViewById(R.id.workspace_bottom_nav);
        if (bottomNav != null && bottomNav.getSelectedItemId() != R.id.nav_studio) {
            bottomNav.setSelectedItemId(R.id.nav_studio);
        }
        renderContent();
    }

    private void renderContent() {
        if (mContentContainer == null) return;
        mContentContainer.removeAllViews();

        // 1. Quick Navigation Strip to Profiles, Snippets, Backups, and Terminal Studio
        mContentContainer.addView(WorkspaceUiFactory.createSectionHeader(this, "V2 Management Suite"));
        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);

        addSuiteButton(navRow, "Profiles", () ->
            startActivity(new Intent(this, ProfileManagerActivity.class)));
        addSuiteButton(navRow, "Command Snippets", () ->
            startActivity(new Intent(this, CommandSnippetActivity.class)));
        addSuiteButton(navRow, "Backup & Restore", () ->
            startActivity(new Intent(this, BackupManagerActivity.class)));
        addSuiteButton(navRow, "Terminal Studio", () ->
            startActivity(new Intent(this, TerminalStudioActivity.class)));

        navScroll.addView(navRow);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.bottomMargin = WorkspaceUiFactory.dp(this, 12);
        mContentContainer.addView(navScroll, scrollLp);

        // 2. Active Workspace & Live Session Status Card
        WorkspaceItem activeWs = mWorkspaceRepo.getActiveWorkspace();
        AIContextSnapshot liveSession = TerminalCommandBridge.captureSnapshot(null);
        String liveSessionInfo = (liveSession.cwd != null && !liveSession.cwd.isEmpty())
            ? "Live Session CWD: " + liveSession.cwd + " (" + liveSession.shellName + ")"
            : "Live Session: Preserved in background (No sessions terminated on switch)";

        LinearLayout statusCard = WorkspaceUiFactory.createCard(this);
        statusCard.addView(WorkspaceUiFactory.createTitle(this,
            activeWs != null ? "Active Workspace: " + activeWs.name : "Active Workspace"));
        if (activeWs != null) {
            statusCard.addView(WorkspaceUiFactory.createSubtitle(this,
                "Configured Dir: " + activeWs.workingDirectory + "\n"
                    + "Shell: " + activeWs.shell.toUpperCase()
                    + " · Theme: " + resolveThemeTitle(activeWs.colorThemeId)
                    + " · Font: " + activeWs.fontSize + "sp\n"
                    + liveSessionInfo
            ));
        }

        LinearLayout statusBtnRow = new LinearLayout(this);
        statusBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statusRowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusRowLp.topMargin = WorkspaceUiFactory.dp(this, 12);
        statusBtnRow.setLayoutParams(statusRowLp);

        Button createBtn = WorkspaceUiFactory.createPrimaryButton(this, "+ Create Workspace");
        LinearLayout.LayoutParams btnWeight = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnWeight.rightMargin = WorkspaceUiFactory.dp(this, 8);
        createBtn.setLayoutParams(btnWeight);
        createBtn.setOnClickListener(v ->
            startActivity(new Intent(this, WorkspaceEditorActivity.class)));
        statusBtnRow.addView(createBtn);

        Button restoreSettingsBtn = WorkspaceUiFactory.createSecondaryButton(
            this, getString(R.string.action_restore_workspace_settings));
        restoreSettingsBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        restoreSettingsBtn.setOnClickListener(v -> confirmRestorePreviousSettings());
        statusBtnRow.addView(restoreSettingsBtn);

        statusCard.addView(statusBtnRow);
        mContentContainer.addView(statusCard);

        // 3. Search Workspaces Input
        EditText searchInput = WorkspaceUiFactory.createInput(
            this, "Search workspaces by name, directory, or shell...", mSearchQuery, false);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mSearchQuery = s != null ? s.toString() : "";
                refreshWorkspaceCards();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        mContentContainer.addView(searchInput);

        mWorkspaceListContainer = new LinearLayout(this);
        mWorkspaceListContainer.setOrientation(LinearLayout.VERTICAL);
        mContentContainer.addView(mWorkspaceListContainer);
        refreshWorkspaceCards();
    }

    private void refreshWorkspaceCards() {
        if (mWorkspaceListContainer == null) return;
        mWorkspaceListContainer.removeAllViews();

        List<WorkspaceItem> workspaces = mWorkspaceRepo.searchWorkspaces(mSearchQuery);
        String activeId = mWorkspaceRepo.getActiveWorkspaceId();
        String defaultId = mWorkspaceRepo.getDefaultWorkspaceId();
        mWorkspaceListContainer.addView(WorkspaceUiFactory.createSectionHeader(
            this, "Workspaces (" + workspaces.size() + ")"));

        for (WorkspaceItem ws : workspaces) {
            mWorkspaceListContainer.addView(buildWorkspaceCard(
                ws, ws.id.equals(activeId), ws.id.equals(defaultId)));
        }
    }

    private void addSuiteButton(@NonNull LinearLayout row, @NonNull String label, @NonNull Runnable action) {
        Button btn = WorkspaceUiFactory.createSecondaryButton(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = WorkspaceUiFactory.dp(this, 8);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        row.addView(btn);
    }

    @NonNull
    private View buildWorkspaceCard(@NonNull WorkspaceItem ws, boolean isActive, boolean isDefault) {
        LinearLayout card = WorkspaceUiFactory.createCard(this);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        String titleLabel = ws.name + (isDefault ? " ★" : "");
        TextView title = WorkspaceUiFactory.createTitle(this, titleLabel);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(title, titleLp);

        headerRow.addView(WorkspaceUiFactory.createBadge(
            this, isActive ? "ACTIVE" : ws.shell.toUpperCase(), isActive));
        card.addView(headerRow);

        TerminalProfile linkedProfile = mProfileRepo.findById(ws.profileId);
        String profileLabel = linkedProfile != null ? linkedProfile.name : "Custom";
        WorkspaceRepository.ValidationResult dirStatus = WorkspaceRepository.validateDirectory(ws.workingDirectory);

        StringBuilder metaText = new StringBuilder();
        metaText.append("Working Directory: ").append(ws.workingDirectory)
            .append(dirStatus.valid ? " (Verified)" : " (Missing)").append('\n')
            .append("Shell: ").append(ws.shell.toUpperCase())
            .append(" · Theme: ").append(resolveThemeTitle(ws.colorThemeId))
            .append(" · Font: ").append(ws.fontSize).append("sp\n")
            .append("Profile: ").append(profileLabel)
            .append(" · Autocomplete: ").append(ws.autocompleteEnabled ? "Enabled" : "Disabled");
        if (!ws.environmentNotes.isEmpty()) {
            metaText.append("\nEnv/Notes: ").append(ws.environmentNotes);
        }

        card.addView(WorkspaceUiFactory.createSubtitle(this, metaText.toString()));

        // Action row 1: Select / Apply & Insert cd
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = WorkspaceUiFactory.dp(this, 12);
        row1.setLayoutParams(r1Lp);

        Button applyBtn = isActive
            ? WorkspaceUiFactory.createSecondaryButton(this, "Re-Apply Workspace")
            : WorkspaceUiFactory.createPrimaryButton(this, "Select Workspace");
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w1.rightMargin = WorkspaceUiFactory.dp(this, 8);
        applyBtn.setLayoutParams(w1);
        applyBtn.setOnClickListener(v -> confirmApplyWorkspace(ws));
        row1.addView(applyBtn);

        Button cdBtn = WorkspaceUiFactory.createSecondaryButton(this, "Insert cd Path");
        cdBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cdBtn.setOnClickListener(v -> {
            String safeCmd = "cd '" + ws.workingDirectory.replace("'", "'\\''") + "'";
            boolean inserted = TerminalCommandBridge.insertIntoActiveTerminal(safeCmd);
            Toast.makeText(this,
                inserted ? "Inserted into terminal (not executed)" : "Queued cd command for terminal input",
                Toast.LENGTH_SHORT).show();
            if (inserted) finish();
        });
        row1.addView(cdBtn);
        card.addView(row1);

        // Action row 2: Duplicate, Set Default, Edit, Rename, Delete
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = WorkspaceUiFactory.dp(this, 8);
        row2.setLayoutParams(r2Lp);

        Button editBtn = WorkspaceUiFactory.createSecondaryButton(this, "Edit");
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        editLp.rightMargin = WorkspaceUiFactory.dp(this, 6);
        editBtn.setLayoutParams(editLp);
        editBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, WorkspaceEditorActivity.class);
            intent.putExtra(WorkspaceEditorActivity.EXTRA_WORKSPACE_ID, ws.id);
            startActivity(intent);
        });
        row2.addView(editBtn);

        Button dupBtn = WorkspaceUiFactory.createSecondaryButton(this, "Duplicate");
        LinearLayout.LayoutParams dupLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dupLp.rightMargin = WorkspaceUiFactory.dp(this, 6);
        dupBtn.setLayoutParams(dupLp);
        dupBtn.setOnClickListener(v -> showDuplicateWorkspaceDialog(ws));
        row2.addView(dupBtn);

        Button renameBtn = WorkspaceUiFactory.createSecondaryButton(this, "Rename");
        LinearLayout.LayoutParams renLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        renLp.rightMargin = WorkspaceUiFactory.dp(this, 6);
        renameBtn.setLayoutParams(renLp);
        renameBtn.setOnClickListener(v -> showRenameWorkspaceDialog(ws));
        row2.addView(renameBtn);

        Button deleteBtn = WorkspaceUiFactory.createDangerButton(this, "Delete");
        deleteBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        deleteBtn.setOnClickListener(v -> confirmDeleteWorkspace(ws));
        row2.addView(deleteBtn);

        card.addView(row2);

        if (!isDefault) {
            Button setDefBtn = WorkspaceUiFactory.createSecondaryButton(this, "★ Set as Default Workspace");
            LinearLayout.LayoutParams defLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            defLp.topMargin = WorkspaceUiFactory.dp(this, 6);
            setDefBtn.setLayoutParams(defLp);
            setDefBtn.setOnClickListener(v -> {
                mWorkspaceRepo.setDefaultWorkspace(ws.id);
                Toast.makeText(this, "'" + ws.name + "' set as default workspace.", Toast.LENGTH_SHORT).show();
                refreshWorkspaceCards();
            });
            card.addView(setDefBtn);
        }

        return card;
    }

    private void confirmApplyWorkspace(@NonNull WorkspaceItem ws) {
        new AlertDialog.Builder(this)
            .setTitle("Apply Workspace: " + ws.name)
            .setMessage("Switch active workspace to '" + ws.name + "'?\n\n"
                + "• Shell: " + ws.shell.toUpperCase() + "\n"
                + "• Working Directory: " + ws.workingDirectory + "\n"
                + "• Color Theme: " + resolveThemeTitle(ws.colorThemeId) + "\n\n"
                + "Existing terminal sessions are preserved and no commands will run automatically.")
            .setPositiveButton("Apply", (d, w) -> {
                boolean ok = mWorkspaceRepo.selectAndApplyWorkspace(ws.id, mConfigManager);
                Toast.makeText(this,
                    ok ? "Workspace '" + ws.name + "' applied." : "Failed to apply workspace.",
                    Toast.LENGTH_SHORT).show();
                renderContent();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void confirmRestorePreviousSettings() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.action_restore_workspace_settings)
            .setMessage("Restore previous terminal settings snapshot captured before the last workspace or profile change?")
            .setPositiveButton("Restore", (d, w) -> {
                boolean ok = mWorkspaceRepo.restorePreviousWorkspaceSettings(mConfigManager);
                Toast.makeText(this,
                    ok ? "Previous workspace settings restored." : "No previous settings snapshot available yet.",
                    Toast.LENGTH_SHORT).show();
                renderContent();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showDuplicateWorkspaceDialog(@NonNull WorkspaceItem ws) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText input = WorkspaceUiFactory.createInput(this, "Duplicate workspace name", ws.name + " (Copy)", false);
        box.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("Duplicate Workspace")
            .setView(box)
            .setPositiveButton("Duplicate", (d, w) -> {
                mWorkspaceRepo.duplicateWorkspace(ws.id, input.getText().toString().trim());
                refreshWorkspaceCards();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showRenameWorkspaceDialog(@NonNull WorkspaceItem ws) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText input = WorkspaceUiFactory.createInput(this, "Workspace name", ws.name, false);
        box.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("Rename Workspace")
            .setView(box)
            .setPositiveButton("Rename", (d, w) -> {
                String newName = input.getText().toString().trim();
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                mWorkspaceRepo.renameWorkspace(ws.id, newName);
                refreshWorkspaceCards();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void confirmDeleteWorkspace(@NonNull WorkspaceItem ws) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Workspace")
            .setMessage("Delete workspace '" + ws.name + "'? Your directories and terminal sessions will not be touched.")
            .setPositiveButton("Delete", (d, w) -> {
                boolean deleted = mWorkspaceRepo.deleteWorkspace(ws.id);
                if (!deleted) {
                    Toast.makeText(this, "At least one workspace must remain.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Deleted workspace '" + ws.name + "'", Toast.LENGTH_SHORT).show();
                    refreshWorkspaceCards();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    @NonNull
    private static String resolveThemeTitle(@NonNull String themeId) {
        StudioColorThemes.ColorThemePreset preset = StudioColorThemes.findById(themeId);
        return preset != null ? preset.name : themeId;
    }
}
