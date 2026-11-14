package com.termux.app.workspace;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;
import com.termux.app.studio.ShellConfigManager;
import com.termux.app.studio.StudioColorThemes;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Screen 2: Workspace Editor (Samsung One UI Dark Black & White).
 * Configures workspace name, real existing working directory (strictly validated; never creates fake dirs),
 * shell (Bash/Zsh/Fish), color theme, font size, environment notes, autocomplete preferences,
 * and linked customization profile.
 */
public class WorkspaceEditorActivity extends AppCompatActivity {

    public static final String EXTRA_WORKSPACE_ID = "extra_workspace_id";

    private WorkspaceRepository mWorkspaceRepo;
    private ProfileRepository mProfileRepo;
    private ShellConfigManager mConfigManager;

    private LinearLayout mContentContainer;
    private EditText mNameInput;
    private EditText mDirectoryInput;
    private EditText mFontSizeInput;
    private EditText mEnvNotesInput;
    private TextView mValidationStatusText;
    private SwitchCompat mSwitchAutocomplete;
    private SwitchCompat mSwitchGhostText;
    private SwitchCompat mSwitchDropdown;

    @Nullable private WorkspaceItem mExistingWorkspace;
    private String mSelectedShell = "bash";
    private String mSelectedThemeId = "default_dark";
    @Nullable private String mSelectedProfileId = "profile_default_dark";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_screen_shell);

        mConfigManager = new ShellConfigManager();
        mProfileRepo = new ProfileRepository(this);
        mWorkspaceRepo = new WorkspaceRepository(
            this,
            new File(getApplicationContext().getFilesDir(), "termux_ai_workspaces.json"),
            mProfileRepo
        );

        String editId = getIntent() != null ? getIntent().getStringExtra(EXTRA_WORKSPACE_ID) : null;
        if (editId != null) {
            mExistingWorkspace = mWorkspaceRepo.findById(editId);
        }

        if (mExistingWorkspace != null) {
            mSelectedShell = mExistingWorkspace.shell;
            mSelectedThemeId = mExistingWorkspace.colorThemeId;
            mSelectedProfileId = mExistingWorkspace.profileId;
        }

        ImageButton backBtn = findViewById(R.id.btn_workspace_back);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        TextView titleView = findViewById(R.id.text_workspace_screen_title);
        if (titleView != null) {
            titleView.setText(mExistingWorkspace != null ? "Edit Workspace" : getString(R.string.title_workspace_editor));
        }
        TextView subtitleView = findViewById(R.id.text_workspace_screen_subtitle);
        if (subtitleView != null) {
            subtitleView.setText(R.string.subtitle_workspace_editor);
        }

        mContentContainer = findViewById(R.id.workspace_content_container);
        renderEditor();
    }

    private void renderEditor() {
        if (mContentContainer == null) return;
        mContentContainer.removeAllViews();

        String currentName = mNameInput != null
            ? mNameInput.getText().toString()
            : (mExistingWorkspace != null ? mExistingWorkspace.name : "");
        String defaultDir = getDefaultExistingDirectory();
        String currentDir = mDirectoryInput != null
            ? mDirectoryInput.getText().toString()
            : (mExistingWorkspace != null ? mExistingWorkspace.workingDirectory : defaultDir);
        String currentFontSize = mFontSizeInput != null
            ? mFontSizeInput.getText().toString()
            : String.valueOf(mExistingWorkspace != null ? mExistingWorkspace.fontSize : 28);
        String currentEnvNotes = mEnvNotesInput != null
            ? mEnvNotesInput.getText().toString()
            : (mExistingWorkspace != null ? mExistingWorkspace.environmentNotes : "");

        boolean acEnabled = mSwitchAutocomplete != null
            ? mSwitchAutocomplete.isChecked()
            : (mExistingWorkspace == null || mExistingWorkspace.autocompleteEnabled);
        boolean ghostEnabled = mSwitchGhostText != null
            ? mSwitchGhostText.isChecked()
            : (mExistingWorkspace == null || mExistingWorkspace.ghostTextEnabled);
        boolean dropdownEnabled = mSwitchDropdown != null
            ? mSwitchDropdown.isChecked()
            : (mExistingWorkspace == null || mExistingWorkspace.dropdownEnabled);

        // 1. Identity & Working Directory Card
        LinearLayout dirCard = WorkspaceUiFactory.createCard(this);
        dirCard.addView(WorkspaceUiFactory.createTitle(this, "Workspace Identity & Directory"));
        dirCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Specify a workspace title and an existing directory on this device. Termux AI validates the path and never creates fake project directories."));

        mNameInput = WorkspaceUiFactory.createInput(this, "Workspace Name (e.g. Android Build, Dotfiles)", currentName, false);
        dirCard.addView(mNameInput);

        mDirectoryInput = WorkspaceUiFactory.createInput(this, "Working Directory Path", currentDir, true);
        dirCard.addView(mDirectoryInput);

        HorizontalScrollView pathScroll = new HorizontalScrollView(this);
        pathScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);

        File homeDir = new File("/data/data/com.termux/files/home");
        if (homeDir.exists() && homeDir.isDirectory()) {
            addQuickPathChip(pathRow, "~ ($HOME)", homeDir.getAbsolutePath());
        }
        File usrDir = new File("/data/data/com.termux/files/usr");
        if (usrDir.exists() && usrDir.isDirectory()) {
            addQuickPathChip(pathRow, "$PREFIX", usrDir.getAbsolutePath());
        }
        addQuickPathChip(pathRow, "App Files", getApplicationContext().getFilesDir().getAbsolutePath());

        Button validateBtn = WorkspaceUiFactory.createSecondaryButton(this, getString(R.string.action_validate_directory));
        validateBtn.setOnClickListener(v -> updateValidationStatus());
        pathRow.addView(validateBtn);
        pathScroll.addView(pathRow);
        dirCard.addView(pathScroll);

        mValidationStatusText = WorkspaceUiFactory.createSubtitle(this, "");
        dirCard.addView(mValidationStatusText);
        updateValidationStatus();

        dirCard.addView(WorkspaceUiFactory.createSubtitle(this, "Terminal Font Size (14-48 sp)"));
        mFontSizeInput = WorkspaceUiFactory.createInput(this, "Font Size (e.g. 28)", currentFontSize, false);
        mFontSizeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        dirCard.addView(mFontSizeInput);

        dirCard.addView(WorkspaceUiFactory.createSubtitle(this, "Environment Configuration & Workspace Notes"));
        mEnvNotesInput = WorkspaceUiFactory.createInput(
            this, "Optional non-secret environment notes (e.g. JAVA_HOME, PYTHONvenv)", currentEnvNotes, true);
        dirCard.addView(mEnvNotesInput);

        mContentContainer.addView(dirCard);

        // 2. Shell Selection Card (Bash, Zsh, Fish)
        LinearLayout shellCard = WorkspaceUiFactory.createCard(this);
        shellCard.addView(WorkspaceUiFactory.createTitle(this, "Workspace Shell"));
        shellCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Select Bash, Zsh, or Fish for this workspace."));

        LinearLayout shellRow = new LinearLayout(this);
        shellRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams shellRowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shellRowLp.topMargin = WorkspaceUiFactory.dp(this, 10);
        shellRow.setLayoutParams(shellRowLp);

        String[] shells = {"bash", "zsh", "fish"};
        for (int i = 0; i < shells.length; i++) {
            final String sh = shells[i];
            boolean selected = sh.equalsIgnoreCase(mSelectedShell);
            Button btn = selected
                ? WorkspaceUiFactory.createPrimaryButton(this, sh.toUpperCase())
                : WorkspaceUiFactory.createSecondaryButton(this, sh.toUpperCase());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < shells.length - 1) lp.rightMargin = WorkspaceUiFactory.dp(this, 8);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                mSelectedShell = sh;
                renderEditor();
            });
            shellRow.addView(btn);
        }
        shellCard.addView(shellRow);
        mContentContainer.addView(shellCard);

        // 3. Terminal Color Theme Card
        LinearLayout themeCard = WorkspaceUiFactory.createCard(this);
        themeCard.addView(WorkspaceUiFactory.createTitle(this, "Terminal Color Theme"));
        themeCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Assign a curated color palette applied to ~/.termux/colors.properties."));

        for (StudioColorThemes.ColorThemePreset preset : StudioColorThemes.getPresets()) {
            boolean isCurrent = preset.id.equals(mSelectedThemeId);
            Button themeBtn = isCurrent
                ? WorkspaceUiFactory.createPrimaryButton(this, "✓ " + preset.name + " — " + preset.subtitle)
                : WorkspaceUiFactory.createSecondaryButton(this, preset.name + " — " + preset.subtitle);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = WorkspaceUiFactory.dp(this, 8);
            themeBtn.setLayoutParams(lp);
            themeBtn.setOnClickListener(v -> {
                mSelectedThemeId = preset.id;
                renderEditor();
            });
            themeCard.addView(themeBtn);
        }
        mContentContainer.addView(themeCard);

        // 4. Autocomplete Preferences Card
        LinearLayout acCard = WorkspaceUiFactory.createCard(this);
        acCard.addView(WorkspaceUiFactory.createTitle(this, "Autocomplete Preferences"));
        acCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Configure offline command autocomplete behavior when this workspace is active."));
        mSwitchAutocomplete = WorkspaceUiFactory.createSwitchRow(
            this, acCard, "Enable Smart Autocomplete", acEnabled);
        mSwitchGhostText = WorkspaceUiFactory.createSwitchRow(
            this, acCard, "Show Inline Ghost Text", ghostEnabled);
        mSwitchDropdown = WorkspaceUiFactory.createSwitchRow(
            this, acCard, "Show Suggestions Dropdown", dropdownEnabled);
        mContentContainer.addView(acCard);

        // 5. Assigned Shell Customization Profile Card
        LinearLayout profCard = WorkspaceUiFactory.createCard(this);
        profCard.addView(WorkspaceUiFactory.createTitle(this, "Shell Customization Profile"));
        profCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Link a reusable Terminal Profile (font size, cursor, Extra Keys, prompt theme, syntax highlighting)."));

        List<TerminalProfile> profiles = mProfileRepo.getAllProfiles();
        for (TerminalProfile prof : profiles) {
            boolean chosen = prof.id.equals(mSelectedProfileId);
            Button pBtn = chosen
                ? WorkspaceUiFactory.createPrimaryButton(this,
                    "✓ " + prof.name + " (" + prof.shell.toUpperCase() + ", " + prof.fontSize + "sp)")
                : WorkspaceUiFactory.createSecondaryButton(this,
                    prof.name + " (" + prof.shell.toUpperCase() + ", " + prof.fontSize + "sp)");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = WorkspaceUiFactory.dp(this, 8);
            pBtn.setLayoutParams(lp);
            pBtn.setOnClickListener(v -> {
                mSelectedProfileId = prof.id;
                renderEditor();
            });
            profCard.addView(pBtn);
        }
        mContentContainer.addView(profCard);

        // 6. Save & Apply Action Buttons
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = WorkspaceUiFactory.dp(this, 8);
        actLp.bottomMargin = WorkspaceUiFactory.dp(this, 24);
        actionRow.setLayoutParams(actLp);

        Button saveOnlyBtn = WorkspaceUiFactory.createSecondaryButton(this, "Save Workspace");
        LinearLayout.LayoutParams s1Lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        s1Lp.rightMargin = WorkspaceUiFactory.dp(this, 8);
        saveOnlyBtn.setLayoutParams(s1Lp);
        saveOnlyBtn.setOnClickListener(v -> saveWorkspace(false));
        actionRow.addView(saveOnlyBtn);

        Button saveAndApplyBtn = WorkspaceUiFactory.createPrimaryButton(this, "Save & Apply");
        saveAndApplyBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        saveAndApplyBtn.setOnClickListener(v -> saveWorkspace(true));
        actionRow.addView(saveAndApplyBtn);

        mContentContainer.addView(actionRow);
    }

    private void addQuickPathChip(@NonNull LinearLayout row, @NonNull String label, @NonNull String path) {
        Button chip = WorkspaceUiFactory.createSecondaryButton(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = WorkspaceUiFactory.dp(this, 8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            if (mDirectoryInput != null) {
                mDirectoryInput.setText(path);
                updateValidationStatus();
            }
        });
        row.addView(chip);
    }

    private void updateValidationStatus() {
        if (mDirectoryInput == null || mValidationStatusText == null) return;
        String path = mDirectoryInput.getText().toString();
        WorkspaceRepository.ValidationResult res = WorkspaceRepository.validateDirectory(path);
        mValidationStatusText.setText(res.valid ? "✓ " + res.message + ": " + res.normalizedPath : "✕ " + res.message);
        mValidationStatusText.setTextColor(res.valid
            ? Color.parseColor("#4CAF50")
            : Color.parseColor("#FF5252"));
    }

    private void saveWorkspace(boolean applyImmediately) {
        String name = mNameInput != null ? mNameInput.getText().toString().trim() : "";
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a workspace name.", Toast.LENGTH_SHORT).show();
            return;
        }
        String rawDir = mDirectoryInput != null ? mDirectoryInput.getText().toString().trim() : "";
        WorkspaceRepository.ValidationResult dirCheck = WorkspaceRepository.validateDirectory(rawDir);
        if (!dirCheck.valid || dirCheck.normalizedPath == null) {
            updateValidationStatus();
            Toast.makeText(this, dirCheck.message, Toast.LENGTH_LONG).show();
            return;
        }

        int fontSize = 28;
        if (mFontSizeInput != null) {
            try { fontSize = Integer.parseInt(mFontSizeInput.getText().toString().trim()); } catch (Exception ignored) {}
        }
        String envNotes = mEnvNotesInput != null ? mEnvNotesInput.getText().toString().trim() : "";

        String id = mExistingWorkspace != null
            ? mExistingWorkspace.id
            : "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        WorkspaceItem item = new WorkspaceItem(
            id,
            name,
            dirCheck.normalizedPath,
            mSelectedShell,
            mSelectedThemeId,
            fontSize,
            mSwitchAutocomplete == null || mSwitchAutocomplete.isChecked(),
            mSwitchGhostText == null || mSwitchGhostText.isChecked(),
            mSwitchDropdown == null || mSwitchDropdown.isChecked(),
            mSelectedProfileId,
            envNotes,
            System.currentTimeMillis()
        );

        WorkspaceRepository.ValidationResult saveRes = mWorkspaceRepo.createOrUpdateWorkspace(item);
        if (!saveRes.valid) {
            Toast.makeText(this, saveRes.message, Toast.LENGTH_LONG).show();
            return;
        }

        if (applyImmediately) {
            mWorkspaceRepo.selectAndApplyWorkspace(item.id, mConfigManager);
            Toast.makeText(this, "Workspace '" + item.name + "' saved & applied.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Workspace '" + item.name + "' saved.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @NonNull
    private String getDefaultExistingDirectory() {
        File termuxHome = new File("/data/data/com.termux/files/home");
        if (termuxHome.exists() && termuxHome.isDirectory()) {
            return termuxHome.getAbsolutePath();
        }
        return getApplicationContext().getFilesDir().getAbsolutePath();
    }
}
