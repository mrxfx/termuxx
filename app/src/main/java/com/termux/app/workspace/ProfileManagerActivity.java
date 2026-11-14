package com.termux.app.workspace;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;
import com.termux.app.studio.ShellConfigManager;
import com.termux.app.studio.StudioColorThemes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Screen 3: Profile Manager (Samsung One UI Dark Black & White).
 * Supports creating, editing, duplicating, renaming, deleting, importing (SAF),
 * exporting (SAF), and applying reusable TerminalProfiles with confirmation.
 */
public class ProfileManagerActivity extends AppCompatActivity {

    private ProfileRepository mProfileRepo;
    private ShellConfigManager mConfigManager;
    private LinearLayout mContentContainer;

    @Nullable private TerminalProfile mPendingExportProfile;

    private final ActivityResultLauncher<String[]> mImportLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportUriSelected);

    private final ActivityResultLauncher<String> mExportLauncher =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::onExportUriSelected);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_screen_shell);

        mProfileRepo = new ProfileRepository(this);
        mConfigManager = new ShellConfigManager();

        ImageButton backBtn = findViewById(R.id.btn_workspace_back);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        TextView titleView = findViewById(R.id.text_workspace_screen_title);
        if (titleView != null) titleView.setText(R.string.title_profile_manager);

        TextView subtitleView = findViewById(R.id.text_workspace_screen_subtitle);
        if (subtitleView != null) subtitleView.setText(R.string.subtitle_profile_manager);

        Button topBtn = findViewById(R.id.btn_workspace_top_action);
        if (topBtn != null) {
            topBtn.setVisibility(View.VISIBLE);
            topBtn.setText(R.string.action_new_profile);
            topBtn.setOnClickListener(v -> showProfileEditorDialog(null));
        }

        mContentContainer = findViewById(R.id.workspace_content_container);
        renderProfiles();
    }

    private void renderProfiles() {
        if (mContentContainer == null) return;
        mContentContainer.removeAllViews();

        // Top toolbar card: Create, Import JSON, Restore Previous Snapshot
        LinearLayout topCard = WorkspaceUiFactory.createCard(this);
        topCard.addView(WorkspaceUiFactory.createTitle(this, "Reusable Terminal Profiles"));
        topCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "Bundle shell preference, color scheme, font size, cursor style, Extra Keys, autocomplete, prompt theme, syntax highlighting, and autosuggestions."));

        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = WorkspaceUiFactory.dp(this, 12);
        barRow.setLayoutParams(barLp);

        Button newBtn = WorkspaceUiFactory.createPrimaryButton(this, "+ Create Profile");
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1Lp.rightMargin = WorkspaceUiFactory.dp(this, 8);
        newBtn.setLayoutParams(b1Lp);
        newBtn.setOnClickListener(v -> showProfileEditorDialog(null));
        barRow.addView(newBtn);

        Button importBtn = WorkspaceUiFactory.createSecondaryButton(this, "Import JSON");
        importBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        importBtn.setOnClickListener(v -> mImportLauncher.launch(new String[]{"application/json", "text/*"}));
        barRow.addView(importBtn);

        topCard.addView(barRow);
        mContentContainer.addView(topCard);

        List<TerminalProfile> profiles = mProfileRepo.getAllProfiles();
        String activeId = mProfileRepo.getActiveProfileId();
        mContentContainer.addView(WorkspaceUiFactory.createSectionHeader(
            this, "Saved Profiles (" + profiles.size() + ")"));

        for (TerminalProfile prof : profiles) {
            mContentContainer.addView(buildProfileCard(prof, prof.id.equals(activeId)));
        }
    }

    @NonNull
    private View buildProfileCard(@NonNull TerminalProfile prof, boolean isActive) {
        LinearLayout card = WorkspaceUiFactory.createCard(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = WorkspaceUiFactory.createTitle(this, prof.name);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(WorkspaceUiFactory.createBadge(
            this, isActive ? "ACTIVE" : prof.shell.toUpperCase(), isActive));
        card.addView(header);

        StudioColorThemes.ColorThemePreset theme = StudioColorThemes.findById(prof.colorSchemeId);
        String themeName = theme != null ? theme.name : prof.colorSchemeId;

        TextView details = WorkspaceUiFactory.createSubtitle(this,
            "Shell: " + prof.shell.toUpperCase()
                + " · Color: " + themeName
                + " · Font: " + prof.fontSize + "sp\n"
                + "Cursor: " + prof.cursorStyle + " (" + (prof.cursorBlinkRate == 0 ? "Steady" : prof.cursorBlinkRate + "ms") + ")"
                + " · Extra Keys: " + prof.extraKeysPresetId + (prof.showExtraKeys ? " (Shown)" : " (Hidden)") + "\n"
                + "Autocomplete: " + (prof.autocompleteEnabled ? "ON" : "OFF")
                + " · Prompt: " + prof.shellPromptThemeId
                + " · Syntax: " + (prof.syntaxHighlightingEnabled ? "ON" : "OFF")
                + " · Autosuggest: " + (prof.autosuggestionsEnabled ? "ON" : "OFF")
        );
        card.addView(details);

        // Row 1: Apply, Edit, Duplicate
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = WorkspaceUiFactory.dp(this, 12);
        row1.setLayoutParams(r1Lp);

        Button applyBtn = isActive
            ? WorkspaceUiFactory.createSecondaryButton(this, "Re-Apply")
            : WorkspaceUiFactory.createPrimaryButton(this, getString(R.string.action_apply_profile));
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w1.rightMargin = WorkspaceUiFactory.dp(this, 6);
        applyBtn.setLayoutParams(w1);
        applyBtn.setOnClickListener(v -> confirmApplyProfile(prof));
        row1.addView(applyBtn);

        Button editBtn = WorkspaceUiFactory.createSecondaryButton(this, "Edit");
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w2.rightMargin = WorkspaceUiFactory.dp(this, 6);
        editBtn.setLayoutParams(w2);
        editBtn.setOnClickListener(v -> showProfileEditorDialog(prof));
        row1.addView(editBtn);

        Button dupBtn = WorkspaceUiFactory.createSecondaryButton(this, "Duplicate");
        dupBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dupBtn.setOnClickListener(v -> showDuplicateDialog(prof));
        row1.addView(dupBtn);
        card.addView(row1);

        // Row 2: Rename, Export, Delete
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = WorkspaceUiFactory.dp(this, 8);
        row2.setLayoutParams(r2Lp);

        Button renameBtn = WorkspaceUiFactory.createSecondaryButton(this, "Rename");
        LinearLayout.LayoutParams rw1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rw1.rightMargin = WorkspaceUiFactory.dp(this, 6);
        renameBtn.setLayoutParams(rw1);
        renameBtn.setOnClickListener(v -> showRenameDialog(prof));
        row2.addView(renameBtn);

        Button exportBtn = WorkspaceUiFactory.createSecondaryButton(this, "Export");
        LinearLayout.LayoutParams rw2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rw2.rightMargin = WorkspaceUiFactory.dp(this, 6);
        exportBtn.setLayoutParams(rw2);
        exportBtn.setOnClickListener(v -> {
            mPendingExportProfile = prof;
            String fileName = "termux_profile_" + prof.name.toLowerCase().replaceAll("[^a-z0-9]+", "_") + ".json";
            mExportLauncher.launch(fileName);
        });
        row2.addView(exportBtn);

        Button delBtn = WorkspaceUiFactory.createDangerButton(this, "Delete");
        delBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        delBtn.setOnClickListener(v -> confirmDeleteProfile(prof));
        row2.addView(delBtn);

        card.addView(row2);
        return card;
    }

    private void confirmApplyProfile(@NonNull TerminalProfile prof) {
        new AlertDialog.Builder(this)
            .setTitle("Apply Profile: " + prof.name)
            .setMessage("Apply profile '" + prof.name + "' to your terminal?\n\n"
                + "A snapshot of your existing settings will be saved automatically before applying.")
            .setPositiveButton("Apply Profile", (d, w) -> {
                mProfileRepo.applyProfile(prof, mConfigManager);
                Toast.makeText(this, "Profile '" + prof.name + "' applied.", Toast.LENGTH_SHORT).show();
                renderProfiles();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showRenameDialog(@NonNull TerminalProfile prof) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText input = WorkspaceUiFactory.createInput(this, "Profile name", prof.name, false);
        box.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("Rename Profile")
            .setView(box)
            .setPositiveButton("Rename", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    mProfileRepo.renameProfile(prof.id, name);
                    renderProfiles();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showDuplicateDialog(@NonNull TerminalProfile prof) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText input = WorkspaceUiFactory.createInput(this, "Duplicate name", prof.name + " (Copy)", false);
        box.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("Duplicate Profile")
            .setView(box)
            .setPositiveButton("Duplicate", (d, w) -> {
                mProfileRepo.duplicateProfile(prof.id, input.getText().toString());
                renderProfiles();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void confirmDeleteProfile(@NonNull TerminalProfile prof) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage("Delete profile '" + prof.name + "'?")
            .setPositiveButton("Delete", (d, w) -> {
                if (!mProfileRepo.deleteProfile(prof.id)) {
                    Toast.makeText(this, "At least one profile must remain.", Toast.LENGTH_SHORT).show();
                } else {
                    renderProfiles();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showProfileEditorDialog(@Nullable TerminalProfile existing) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        form.setPadding(pad, pad / 2, pad, pad / 2);

        EditText nameEt = WorkspaceUiFactory.createInput(
            this, "Profile Name", existing != null ? existing.name : "Custom Profile", false);
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Profile Name"));
        form.addView(nameEt);

        String[] shells = {"bash", "zsh", "fish"};
        Spinner shellSpinner = createSpinner(shells, existing != null ? existing.shell : "bash");
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Preferred Shell"));
        form.addView(shellSpinner);

        String[] themeIds = {"default_dark", "dracula", "nord", "monokai", "solarized_dark"};
        Spinner themeSpinner = createSpinner(themeIds, existing != null ? existing.colorSchemeId : "default_dark");
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Color Scheme"));
        form.addView(themeSpinner);

        EditText fontEt = WorkspaceUiFactory.createInput(
            this, "Font Size (14-48)", String.valueOf(existing != null ? existing.fontSize : 28), false);
        fontEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Font Size (sp)"));
        form.addView(fontEt);

        String[] cursors = {"block", "bar", "underline"};
        Spinner cursorSpinner = createSpinner(cursors, existing != null ? existing.cursorStyle : "block");
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Cursor Style"));
        form.addView(cursorSpinner);

        String[] blinks = {"0", "500"};
        Spinner blinkSpinner = createSpinner(blinks, String.valueOf(existing != null ? existing.cursorBlinkRate : 0));
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Cursor Blink Rate (ms, 0=Steady)"));
        form.addView(blinkSpinner);

        String[] extraKeysPresets = {"default", "developer", "vim_nav"};
        Spinner ekSpinner = createSpinner(extraKeysPresets, existing != null ? existing.extraKeysPresetId : "default");
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Extra Keys Layout"));
        form.addView(ekSpinner);

        String[] promptThemes = {"clean_mono", "dev_git", "minimal_dir", "powerline_dark", "none"};
        Spinner promptSpinner = createSpinner(promptThemes, existing != null ? existing.shellPromptThemeId : "clean_mono");
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Shell Prompt Theme"));
        form.addView(promptSpinner);

        SwitchCompat swExtraKeys = WorkspaceUiFactory.createSwitchRow(
            this, form, "Show Extra Keys Row", existing == null || existing.showExtraKeys);
        SwitchCompat swAc = WorkspaceUiFactory.createSwitchRow(
            this, form, "Enable Autocomplete", existing == null || existing.autocompleteEnabled);
        SwitchCompat swGhost = WorkspaceUiFactory.createSwitchRow(
            this, form, "Inline Ghost Text", existing == null || existing.ghostTextEnabled);
        SwitchCompat swDrop = WorkspaceUiFactory.createSwitchRow(
            this, form, "Suggestions Dropdown", existing == null || existing.dropdownEnabled);
        SwitchCompat swSyntax = WorkspaceUiFactory.createSwitchRow(
            this, form, "Shell Syntax Highlighting", existing != null && existing.syntaxHighlightingEnabled);
        SwitchCompat swSuggest = WorkspaceUiFactory.createSwitchRow(
            this, form, "Shell Autosuggestions", existing != null && existing.autosuggestionsEnabled);

        scroll.addView(form);

        new AlertDialog.Builder(this)
            .setTitle(existing != null ? "Edit Profile" : "Create Profile")
            .setView(scroll)
            .setPositiveButton("Save", (d, w) -> {
                String name = nameEt.getText().toString().trim();
                if (name.isEmpty()) name = "Custom Profile";
                int fontSize = 28;
                try { fontSize = Integer.parseInt(fontEt.getText().toString().trim()); } catch (Exception ignored) {}
                int blinkRate = 0;
                try { blinkRate = Integer.parseInt(blinkSpinner.getSelectedItem().toString()); } catch (Exception ignored) {}

                String id = existing != null
                    ? existing.id
                    : "prof_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

                TerminalProfile saved = new TerminalProfile(
                    id,
                    name,
                    shellSpinner.getSelectedItem().toString(),
                    themeSpinner.getSelectedItem().toString(),
                    fontSize,
                    cursorSpinner.getSelectedItem().toString(),
                    blinkRate,
                    ekSpinner.getSelectedItem().toString(),
                    swExtraKeys.isChecked(),
                    swAc.isChecked(),
                    swGhost.isChecked(),
                    swDrop.isChecked(),
                    promptSpinner.getSelectedItem().toString(),
                    swSyntax.isChecked(),
                    swSuggest.isChecked(),
                    System.currentTimeMillis()
                );
                mProfileRepo.saveProfile(saved);
                renderProfiles();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    @NonNull
    private Spinner createSpinner(@NonNull String[] items, @NonNull String selectedValue) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        spinner.setAdapter(adapter);
        for (int i = 0; i < items.length; i++) {
            if (items[i].equalsIgnoreCase(selectedValue)) {
                spinner.setSelection(i);
                break;
            }
        }
        return spinner;
    }

    private void onImportUriSelected(@Nullable Uri uri) {
        if (uri == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            TerminalProfile imported = mProfileRepo.importProfileFromJson(json);
            Toast.makeText(this, "Imported profile: " + imported.name, Toast.LENGTH_SHORT).show();
            renderProfiles();
        } catch (Exception e) {
            Toast.makeText(this, "Invalid profile JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onExportUriSelected(@Nullable Uri uri) {
        if (uri == null || mPendingExportProfile == null) return;
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) return;
            String json = mProfileRepo.exportProfileToJson(mPendingExportProfile);
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            Toast.makeText(this, "Exported profile: " + mPendingExportProfile.name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to export profile: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
