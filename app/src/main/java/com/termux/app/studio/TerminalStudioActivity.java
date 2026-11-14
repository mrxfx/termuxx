package com.termux.app.studio;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.ai.AIManager;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.firebase.AppCheckInit;
import com.termux.app.ai.model.AIError;
import com.termux.app.ai.model.AIMessage;
import com.termux.app.ai.model.AIResponse;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.CancellationSignalHandle;
import com.termux.app.ai.ui.AIAssistantActivity;
import com.termux.app.ai.ui.MarkdownCodeBlockParser;
import com.termux.app.autocomplete.AutocompleteSettingsManager;
import com.termux.app.ui.OneUINavHelper;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native Android Terminal Studio implementing safe, idempotent Termux-OS customization
 * in Samsung One UI dark monochrome aesthetic.
 */
public class TerminalStudioActivity extends AppCompatActivity {

    private static final String STUDIO_PREFS = "termux_studio_prefs";
    private static final String PREF_PROMPT_SHELL = "prompt_shell";
    private static final String PREF_PROMPT_THEME = "prompt_theme";
    private static final String PREF_BANNER_USER = "banner_user";
    private static final String PREF_BANNER_STYLE = "banner_style";
    private static final String PREF_EXTRA_KEYS_ID = "extra_keys_id";

    private ShellConfigManager mConfigManager;
    private StudioPackageHelper mPackageHelper;
    private StudioShellCustomizer mCustomizer;
    private AutocompleteSettingsManager mAutocompleteSettings;
    private SharedPreferences mStudioPrefs;
    @Nullable
    private TermuxAppSharedPreferences mTermuxPrefs;

    private TextView mSubtitleStatus;
    private LinearLayout mShellListContainer;
    private MaterialButton mRestoreShellButton;
    private LinearLayout mColorThemesContainer;
    private MaterialButton mRestoreColorsButton;
    private TextView mPromptShellBash;
    private TextView mPromptShellZsh;
    private TextView mPromptShellFish;
    private LinearLayout mPromptThemesContainer;
    private LinearLayout mSyntaxListContainer;
    private SwitchCompat mPrecedenceGhostSwitch;
    private LinearLayout mAutosuggestListContainer;
    private SwitchCompat mExtraKeysSwitch;
    private LinearLayout mExtraKeysContainer;
    private EditText mBannerUsernameInput;
    private TextView mBannerStyleMinimal;
    private TextView mBannerStyleDouble;
    private TextView mBannerStyleCompact;
    private TextView mBannerStyleNone;
    private TextView mBannerPreviewText;
    private TextView mLockStatusText;
    private MaterialButton mLockDisableButton;
    private TextView mBackupSummaryText;

    private String mSelectedPromptShell = "bash";
    private StudioShellCustomizer.BannerStyle mSelectedBannerStyle = StudioShellCustomizer.BannerStyle.MINIMAL_BOX;
    @Nullable
    private CancellationSignalHandle mAiHandle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCheckInit.configureAppCheck(this, getIntent());
        setContentView(R.layout.activity_terminal_studio);

        mConfigManager = new ShellConfigManager();
        mPackageHelper = new StudioPackageHelper(mConfigManager);
        mCustomizer = new StudioShellCustomizer(mConfigManager, mPackageHelper);
        mAutocompleteSettings = new AutocompleteSettingsManager(this);
        mStudioPrefs = getSharedPreferences(STUDIO_PREFS, MODE_PRIVATE);
        mTermuxPrefs = TermuxAppSharedPreferences.build(this, true);

        bindViews();
        restoreUiSelections();
        wireStaticListeners();

        BottomNavigationView bottomNav = findViewById(R.id.oneui_bottom_nav);
        OneUINavHelper.setupBottomNav(this, bottomNav, R.id.nav_studio);

        refreshAllSections();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllSections();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAiHandle != null) {
            mAiHandle.cancel();
            mAiHandle = null;
        }
    }

    private void bindViews() {
        mSubtitleStatus = findViewById(R.id.studio_subtitle_status);
        mShellListContainer = findViewById(R.id.studio_shell_list_container);
        mRestoreShellButton = findViewById(R.id.studio_restore_shell_button);
        mColorThemesContainer = findViewById(R.id.studio_color_themes_container);
        mRestoreColorsButton = findViewById(R.id.studio_restore_colors_button);
        mPromptShellBash = findViewById(R.id.studio_prompt_shell_bash);
        mPromptShellZsh = findViewById(R.id.studio_prompt_shell_zsh);
        mPromptShellFish = findViewById(R.id.studio_prompt_shell_fish);
        mPromptThemesContainer = findViewById(R.id.studio_prompt_themes_container);
        mSyntaxListContainer = findViewById(R.id.studio_syntax_list_container);
        mPrecedenceGhostSwitch = findViewById(R.id.studio_precedence_ghost_switch);
        mAutosuggestListContainer = findViewById(R.id.studio_autosuggest_list_container);
        mExtraKeysSwitch = findViewById(R.id.studio_extrakeys_switch);
        mExtraKeysContainer = findViewById(R.id.studio_extrakeys_container);
        mBannerUsernameInput = findViewById(R.id.studio_banner_username);
        mBannerStyleMinimal = findViewById(R.id.studio_banner_style_minimal);
        mBannerStyleDouble = findViewById(R.id.studio_banner_style_double);
        mBannerStyleCompact = findViewById(R.id.studio_banner_style_compact);
        mBannerStyleNone = findViewById(R.id.studio_banner_style_none);
        mBannerPreviewText = findViewById(R.id.studio_banner_preview);
        mLockStatusText = findViewById(R.id.studio_lock_status_text);
        mLockDisableButton = findViewById(R.id.studio_lock_disable_button);
        mBackupSummaryText = findViewById(R.id.studio_backup_summary_text);
    }

    private void restoreUiSelections() {
        String activeShell = mConfigManager.detectActiveDefaultShell();
        String savedPromptShell = mStudioPrefs.getString(PREF_PROMPT_SHELL, activeShell);
        if ("zsh".equals(savedPromptShell) || "fish".equals(savedPromptShell)) {
            mSelectedPromptShell = savedPromptShell;
        } else {
            mSelectedPromptShell = "bash";
        }

        String savedUser = mStudioPrefs.getString(PREF_BANNER_USER, "developer");
        mBannerUsernameInput.setText(savedUser);

        String savedStyleId = mStudioPrefs.getString(PREF_BANNER_STYLE, StudioShellCustomizer.BannerStyle.MINIMAL_BOX.id);
        for (StudioShellCustomizer.BannerStyle st : StudioShellCustomizer.BannerStyle.values()) {
            if (st.id.equals(savedStyleId)) {
                mSelectedBannerStyle = st;
                break;
            }
        }
    }

    private void wireStaticListeners() {
        findViewById(R.id.studio_back_button).setOnClickListener(v -> finish());
        findViewById(R.id.studio_top_restore_button).setOnClickListener(v -> showRestoreBackupDialog());
        findViewById(R.id.studio_restore_any_backup_button).setOnClickListener(v -> showRestoreBackupDialog());

        mRestoreShellButton.setOnClickListener(v -> {
            String prev = mConfigManager.getPreviousSavedShell();
            if (prev != null && mConfigManager.switchDefaultShell(prev)) {
                Toast.makeText(this, "Restored default shell to " + prev, Toast.LENGTH_SHORT).show();
                refreshAllSections();
            } else {
                Toast.makeText(this, "Previous shell binary is not available", Toast.LENGTH_SHORT).show();
            }
        });

        mRestoreColorsButton.setOnClickListener(v -> {
            if (mConfigManager.restoreLatestBackup(mConfigManager.getColorsPropertiesFile())) {
                TermuxActivity.updateTermuxActivityStyling(this, false);
                Toast.makeText(this, "Restored previous terminal colors", Toast.LENGTH_SHORT).show();
                refreshAllSections();
            }
        });

        mPromptShellBash.setOnClickListener(v -> selectPromptShell("bash"));
        mPromptShellZsh.setOnClickListener(v -> selectPromptShell("zsh"));
        mPromptShellFish.setOnClickListener(v -> selectPromptShell("fish"));

        findViewById(R.id.studio_clear_prompt_button).setOnClickListener(v -> {
            if (mCustomizer.clearPromptTheme(mSelectedPromptShell)) {
                mStudioPrefs.edit().remove(PREF_PROMPT_THEME).apply();
                Toast.makeText(this, "Removed Studio prompt block from " + mSelectedPromptShell + " rc", Toast.LENGTH_SHORT).show();
                refreshPromptSection();
            }
        });

        mPrecedenceGhostSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            mAutocompleteSettings.setGhostTextEnabled(isChecked);
            Toast.makeText(
                this,
                isChecked
                    ? "Termux AI offline ghost text set as primary"
                    : "Offline ghost text paused to prefer shell inline suggestions",
                Toast.LENGTH_SHORT
            ).show();
        });

        mExtraKeysSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed() || mTermuxPrefs == null) return;
            if (mTermuxPrefs.shouldShowTerminalToolbar() != isChecked) {
                mTermuxPrefs.setShowTerminalToolbar(isChecked);
                TermuxActivity.updateTermuxActivityStyling(this, false);
            }
        });

        mBannerStyleMinimal.setOnClickListener(v -> selectBannerStyle(StudioShellCustomizer.BannerStyle.MINIMAL_BOX));
        mBannerStyleDouble.setOnClickListener(v -> selectBannerStyle(StudioShellCustomizer.BannerStyle.DOUBLE_RULE));
        mBannerStyleCompact.setOnClickListener(v -> selectBannerStyle(StudioShellCustomizer.BannerStyle.COMPACT_TAG));
        mBannerStyleNone.setOnClickListener(v -> selectBannerStyle(StudioShellCustomizer.BannerStyle.NONE));

        mBannerUsernameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateBannerPreview();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.studio_apply_banner_button).setOnClickListener(v -> {
            String user = mBannerUsernameInput.getText().toString();
            mStudioPrefs.edit()
                .putString(PREF_BANNER_USER, user)
                .putString(PREF_BANNER_STYLE, mSelectedBannerStyle.id)
                .apply();
            boolean ok = mCustomizer.applyBanner(mSelectedPromptShell, user, mSelectedBannerStyle);
            if (ok) {
                Toast.makeText(this, R.string.studio_banner_saved, Toast.LENGTH_SHORT).show();
                refreshBackupSummary();
            } else {
                Toast.makeText(this, "Could not update startup banner", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.studio_lock_enable_button).setOnClickListener(v -> showSetShellLockDialog());
        mLockDisableButton.setOnClickListener(v -> {
            if (mCustomizer.disableShellLock()) {
                Toast.makeText(this, "Shell prompt lock removed", Toast.LENGTH_SHORT).show();
                refreshSecuritySection();
                refreshBackupSummary();
            }
        });

        findViewById(R.id.studio_ai_explain_button).setOnClickListener(v -> openAiToExplainConfigs());
        findViewById(R.id.studio_ai_propose_button).setOnClickListener(v -> showProposeSnippetDialog());
        findViewById(R.id.studio_attribution_button).setOnClickListener(v -> showAttributionDialog());
    }

    private void refreshAllSections() {
        String activeShell = mConfigManager.detectActiveDefaultShell();
        StudioColorThemes.ColorThemePreset activeTheme = StudioColorThemes.detectActiveTheme(mConfigManager);
        String themeLabel = activeTheme != null ? activeTheme.name : "Custom";
        mSubtitleStatus.setText("Shell: " + activeShell + " · Theme: " + themeLabel);

        refreshShellSection();
        refreshColorThemesSection(activeTheme);
        refreshPromptSection();
        refreshSyntaxSection();
        refreshAutosuggestSection();
        refreshExtraKeysSection();
        updateBannerStyleChips();
        updateBannerPreview();
        refreshSecuritySection();
        refreshBackupSummary();
    }

    private void refreshShellSection() {
        mShellListContainer.removeAllViews();
        List<StudioPackageHelper.ShellStatus> shells = mPackageHelper.inspectShells();
        for (StudioPackageHelper.ShellStatus st : shells) {
            LinearLayout row = buildSecondaryItemRow();
            LinearLayout textCol = buildItemTextColumn(
                st.name.toUpperCase() + (st.active ? "  ·  Active" : ""),
                st.installed
                    ? "Installed at $PREFIX/bin/" + st.name
                    : "Not installed (install via pkg to activate)"
            );
            row.addView(textCol);

            TextView actionBtn = buildPillActionButton(
                st.active ? "Active" : (st.installed ? "Set Default" : "Install"),
                st.active
            );
            actionBtn.setOnClickListener(v -> {
                if (st.active) return;
                if (st.installed) {
                    if (mConfigManager.switchDefaultShell(st.name)) {
                        Toast.makeText(this, R.string.studio_shell_switched, Toast.LENGTH_SHORT).show();
                        refreshAllSections();
                    } else {
                        Toast.makeText(this, "Failed to update ~/.termux/shell symlink", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    showPackageInstallApprovalDialog(
                        "Install " + st.name,
                        StudioPackageHelper.buildInstallCommandForShell(st.packageName)
                    );
                }
            });
            row.addView(actionBtn);
            mShellListContainer.addView(row);
        }

        String prev = mConfigManager.getPreviousSavedShell();
        mRestoreShellButton.setVisibility(prev != null && !prev.equals(mConfigManager.detectActiveDefaultShell())
            ? View.VISIBLE : View.GONE);
    }

    private void refreshColorThemesSection(@Nullable StudioColorThemes.ColorThemePreset activeTheme) {
        mColorThemesContainer.removeAllViews();
        for (StudioColorThemes.ColorThemePreset preset : StudioColorThemes.getPresets()) {
            boolean isActive = activeTheme != null && activeTheme.id.equals(preset.id);
            LinearLayout card = buildSecondaryItemRow();

            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(this);
            title.setText(preset.name);
            title.setTextColor(ContextCompat.getColor(this, R.color.oneui_text_primary));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(null, Typeface.BOLD);
            leftCol.addView(title);

            TextView sub = new TextView(this);
            sub.setText(preset.subtitle);
            sub.setTextColor(ContextCompat.getColor(this, R.color.oneui_text_secondary));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setPadding(0, dp(2), dp(8), dp(6));
            leftCol.addView(sub);

            LinearLayout swatchRow = new LinearLayout(this);
            swatchRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int color : preset.swatches) {
                View dot = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
                lp.setMarginEnd(dp(6));
                dot.setLayoutParams(lp);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(color);
                bg.setStroke(dp(1), ContextCompat.getColor(this, R.color.oneui_border));
                dot.setBackground(bg);
                swatchRow.addView(dot);
            }
            leftCol.addView(swatchRow);

            card.addView(leftCol);

            TextView applyBtn = buildPillActionButton(isActive ? "Active" : "Apply", isActive);
            applyBtn.setOnClickListener(v -> {
                if (mConfigManager.writeFileAtomicallyWithBackup(mConfigManager.getColorsPropertiesFile(), preset.propertiesContent)) {
                    TermuxActivity.updateTermuxActivityStyling(this, false);
                    Toast.makeText(this, R.string.studio_theme_applied, Toast.LENGTH_SHORT).show();
                    refreshAllSections();
                } else {
                    Toast.makeText(this, "Could not write colors.properties", Toast.LENGTH_SHORT).show();
                }
            });
            card.addView(applyBtn);
            mColorThemesContainer.addView(card);
        }

        boolean hasColorBackup = mConfigManager.findLatestBackup(mConfigManager.getColorsPropertiesFile()) != null;
        mRestoreColorsButton.setVisibility(hasColorBackup ? View.VISIBLE : View.GONE);
    }

    private void selectPromptShell(@NonNull String shellName) {
        mSelectedPromptShell = shellName;
        mStudioPrefs.edit().putString(PREF_PROMPT_SHELL, shellName).apply();
        refreshPromptSection();
    }

    private void refreshPromptSection() {
        styleChoiceChip(mPromptShellBash, "bash".equals(mSelectedPromptShell));
        styleChoiceChip(mPromptShellZsh, "zsh".equals(mSelectedPromptShell));
        styleChoiceChip(mPromptShellFish, "fish".equals(mSelectedPromptShell));

        mPromptThemesContainer.removeAllViews();
        String currentRc = mConfigManager.readFileSafely(mConfigManager.getRcFileForShell(mSelectedPromptShell));
        String appliedId = mStudioPrefs.getString(PREF_PROMPT_THEME + "_" + mSelectedPromptShell, "");
        boolean hasPromptBlock = ShellConfigManager.hasManagedBlock(currentRc, StudioShellCustomizer.MODULE_PROMPT_THEME);

        for (StudioShellCustomizer.PromptThemePreset preset : StudioShellCustomizer.getPromptThemes()) {
            boolean isActive = hasPromptBlock && preset.id.equals(appliedId);
            LinearLayout card = buildSecondaryItemRow();
            LinearLayout col = buildItemTextColumn(preset.name, preset.description);
            card.addView(col);

            TextView btn = buildPillActionButton(isActive ? "Applied" : "Apply", isActive);
            btn.setOnClickListener(v -> {
                if (mCustomizer.applyPromptTheme(mSelectedPromptShell, preset.id)) {
                    mStudioPrefs.edit().putString(PREF_PROMPT_THEME + "_" + mSelectedPromptShell, preset.id).apply();
                    Toast.makeText(this, preset.name + " written to " + mSelectedPromptShell + " rc", Toast.LENGTH_SHORT).show();
                    refreshPromptSection();
                    refreshBackupSummary();
                }
            });
            card.addView(btn);
            mPromptThemesContainer.addView(card);
        }

        boolean hasAliases = ShellConfigManager.hasManagedBlock(currentRc, StudioShellCustomizer.MODULE_SHELL_ALIASES);
        LinearLayout aliasCard = buildSecondaryItemRow();
        aliasCard.addView(buildItemTextColumn(
            "Developer Shell Aliases (" + mSelectedPromptShell + ")",
            "Idempotent ll, la, gs, gl, gd, .., ... aliases in " + mSelectedPromptShell + " rc"
        ));
        TextView aliasBtn = buildPillActionButton(hasAliases ? "Enabled" : "Enable", hasAliases);
        aliasBtn.setOnClickListener(v -> {
            boolean ok = hasAliases
                ? mCustomizer.clearDeveloperAliases(mSelectedPromptShell)
                : mCustomizer.applyDeveloperAliases(mSelectedPromptShell);
            if (ok) {
                Toast.makeText(
                    this,
                    (hasAliases ? "Removed" : "Applied") + " developer aliases in " + mSelectedPromptShell + " rc",
                    Toast.LENGTH_SHORT
                ).show();
                refreshPromptSection();
                refreshBackupSummary();
            }
        });
        aliasCard.addView(aliasBtn);
        mPromptThemesContainer.addView(aliasCard);
    }

    private void refreshSyntaxSection() {
        populatePluginList(mSyntaxListContainer, mPackageHelper.inspectSyntaxHighlighting());
    }

    private void refreshAutosuggestSection() {
        mPrecedenceGhostSwitch.setChecked(mAutocompleteSettings.isGhostTextEnabled());
        populatePluginList(mAutosuggestListContainer, mPackageHelper.inspectAutosuggestions());
    }

    private void populatePluginList(
        @NonNull LinearLayout container,
        @NonNull List<StudioPackageHelper.PluginStatus> plugins
    ) {
        container.removeAllViews();
        for (StudioPackageHelper.PluginStatus plugin : plugins) {
            LinearLayout row = buildSecondaryItemRow();
            String subtitle = plugin.installed
                ? (plugin.enabledInRc ? "Enabled in " + plugin.shell + " rc" : "Installed (" + plugin.sourcePath + ")")
                : "Not installed on device";
            LinearLayout col = buildItemTextColumn(plugin.title, subtitle);
            row.addView(col);

            String label = !plugin.installed
                ? "Install"
                : (plugin.enabledInRc ? "Disable" : "Enable");
            TextView btn = buildPillActionButton(label, plugin.installed && plugin.enabledInRc);
            btn.setOnClickListener(v -> {
                if (!plugin.installed) {
                    if (plugin.installCommand != null) {
                        showPackageInstallApprovalDialog("Install " + plugin.title, plugin.installCommand);
                    }
                    return;
                }
                boolean targetState = !plugin.enabledInRc;
                if (mCustomizer.setPluginEnabled(plugin.id, targetState)) {
                    Toast.makeText(
                        this,
                        plugin.title + (targetState ? " enabled" : " disabled"),
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshSyntaxSection();
                    refreshAutosuggestSection();
                    refreshBackupSummary();
                } else {
                    Toast.makeText(this, "Could not update shell rc file", Toast.LENGTH_SHORT).show();
                }
            });
            row.addView(btn);
            container.addView(row);
        }
    }

    private void refreshExtraKeysSection() {
        if (mTermuxPrefs != null) {
            mExtraKeysSwitch.setChecked(mTermuxPrefs.shouldShowTerminalToolbar());
        }
        mExtraKeysContainer.removeAllViews();
        String currentVal = mConfigManager.readTermuxProperty("extra-keys");
        String savedId = mStudioPrefs.getString(PREF_EXTRA_KEYS_ID, "");

        for (StudioShellCustomizer.ExtraKeysPreset preset : StudioShellCustomizer.getExtraKeysPresets()) {
            boolean isActive = (currentVal != null && currentVal.equals(preset.propertyValue))
                || (currentVal == null && "default".equals(preset.id) && savedId.isEmpty());
            LinearLayout row = buildSecondaryItemRow();
            LinearLayout col = buildItemTextColumn(preset.name, preset.description);
            row.addView(col);

            TextView btn = buildPillActionButton(isActive ? "Active" : "Apply", isActive);
            btn.setOnClickListener(v -> {
                if (mConfigManager.upsertTermuxProperty("extra-keys", preset.propertyValue)) {
                    mStudioPrefs.edit().putString(PREF_EXTRA_KEYS_ID, preset.id).apply();
                    TermuxActivity.updateTermuxActivityStyling(this, false);
                    Toast.makeText(this, R.string.studio_extrakeys_saved, Toast.LENGTH_SHORT).show();
                    refreshExtraKeysSection();
                    refreshBackupSummary();
                } else {
                    Toast.makeText(this, "Failed to update termux.properties", Toast.LENGTH_SHORT).show();
                }
            });
            row.addView(btn);
            mExtraKeysContainer.addView(row);
        }
    }

    private void selectBannerStyle(@NonNull StudioShellCustomizer.BannerStyle style) {
        mSelectedBannerStyle = style;
        updateBannerStyleChips();
        updateBannerPreview();
    }

    private void updateBannerStyleChips() {
        styleChoiceChip(mBannerStyleMinimal, mSelectedBannerStyle == StudioShellCustomizer.BannerStyle.MINIMAL_BOX);
        styleChoiceChip(mBannerStyleDouble, mSelectedBannerStyle == StudioShellCustomizer.BannerStyle.DOUBLE_RULE);
        styleChoiceChip(mBannerStyleCompact, mSelectedBannerStyle == StudioShellCustomizer.BannerStyle.COMPACT_TAG);
        styleChoiceChip(mBannerStyleNone, mSelectedBannerStyle == StudioShellCustomizer.BannerStyle.NONE);
    }

    private void updateBannerPreview() {
        String user = mBannerUsernameInput.getText().toString();
        String preview = StudioShellCustomizer.buildBannerPreview(user, mSelectedBannerStyle);
        if (preview.isEmpty()) {
            mBannerPreviewText.setText("(Banner disabled)");
        } else {
            mBannerPreviewText.setText(preview);
        }
    }

    private void refreshSecuritySection() {
        boolean enabled = mCustomizer.isShellLockEnabled();
        mLockStatusText.setText(enabled
            ? "Status: Enabled for interactive non-SSH bash/zsh sessions (SHA-256 salted)."
            : "Status: Disabled (recommended default).");
        mLockDisableButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void refreshBackupSummary() {
        List<File> backups = mConfigManager.listAllBackups();
        long kb = Math.max(0L, mConfigManager.getBackupsTotalSizeBytes() / 1024L);
        mBackupSummaryText.setText("Stored configuration backups: " + backups.size() + " files (" + kb + " KB) in ~/.termux/studio_backups/");
    }

    private void showPackageInstallApprovalDialog(@NonNull String title, @NonNull String command) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Review the package installation command before inserting it into your terminal. Nothing is executed automatically.\n\n" + command)
            .setPositiveButton("Insert into Terminal", (d, w) -> {
                TerminalCommandBridge.insertIntoActiveTerminal(command);
                Toast.makeText(this, "Command inserted into terminal input (press Enter to run)", Toast.LENGTH_LONG).show();
                finish();
            })
            .setNeutralButton("Copy Command", (d, w) -> copyTextToClipboard("Termux Package Command", command))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showSetShellLockDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter shell passcode (minimum 4 chars)");
        int pad = dp(16);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(pad, dp(8), pad, 0);
        holder.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
            .setTitle("Optional Shell Prompt Lock")
            .setMessage("Stores only a random 16-byte salted SHA-256 digest in ~/.termux/studio_lock.json and guards interactive non-SSH shells.")
            .setView(holder)
            .setPositiveButton("Enable Lock", (d, w) -> {
                String pass = input.getText().toString();
                if (pass.trim().length() < 4) {
                    Toast.makeText(this, "Passcode must be at least 4 characters", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (mCustomizer.enableShellLock(pass)) {
                    Toast.makeText(this, "Shell prompt lock enabled", Toast.LENGTH_SHORT).show();
                    refreshSecuritySection();
                    refreshBackupSummary();
                } else {
                    Toast.makeText(this, "Could not enable shell lock", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showRestoreBackupDialog() {
        List<File> backups = mConfigManager.listAllBackups();
        if (backups.isEmpty()) {
            Toast.makeText(this, "No Terminal Studio backups created yet", Toast.LENGTH_SHORT).show();
            return;
        }
        File[] candidates = new File[]{
            mConfigManager.getBashRcFile(),
            mConfigManager.getZshRcFile(),
            mConfigManager.getFishConfigFile(),
            mConfigManager.getColorsPropertiesFile(),
            mConfigManager.getTermuxPropertiesFile()
        };
        List<File> restorable = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (File target : candidates) {
            File latest = mConfigManager.findLatestBackup(target);
            if (latest != null) {
                restorable.add(target);
                labels.add(target.getName() + " (" + latest.getName() + ")");
            }
        }
        if (restorable.isEmpty()) {
            Toast.makeText(this, "No restorable config backups found", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.studio_restore_backups)
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                File chosen = restorable.get(which);
                if (mConfigManager.restoreLatestBackup(chosen)) {
                    TermuxActivity.updateTermuxActivityStyling(this, false);
                    Toast.makeText(this, "Restored " + chosen.getName(), Toast.LENGTH_SHORT).show();
                    refreshAllSections();
                } else {
                    Toast.makeText(this, "Restore failed for " + chosen.getName(), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void openAiToExplainConfigs() {
        String activeShell = mConfigManager.detectActiveDefaultShell();
        StudioColorThemes.ColorThemePreset theme = StudioColorThemes.detectActiveTheme(mConfigManager);
        String extraKeys = mConfigManager.readTermuxProperty("extra-keys");
        String prompt = "Explain my current Termux configuration and suggest safe improvements:\n"
            + "- Active default shell: " + activeShell + "\n"
            + "- Color theme: " + (theme != null ? theme.name : "Custom colors.properties") + "\n"
            + "- Shell lock enabled: " + mCustomizer.isShellLockEnabled() + "\n"
            + "- Custom extra-keys set: " + (extraKeys != null && !extraKeys.isEmpty());

        Intent intent = new Intent(this, AIAssistantActivity.class);
        intent.putExtra(AIAssistantActivity.EXTRA_INITIAL_PROMPT, prompt);
        startActivity(intent);
    }

    private void showProposeSnippetDialog() {
        final EditText input = new EditText(this);
        input.setHint("e.g. Useful git & docker aliases, or clean directory ls alias");
        int pad = dp(16);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(pad, dp(8), pad, 0);
        holder.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
            .setTitle(R.string.studio_ask_ai_propose)
            .setMessage("Describe a shell alias, function, or prompt tweak for " + mSelectedPromptShell + ". Nothing is written without your review.")
            .setView(holder)
            .setPositiveButton("Generate Proposal", (d, w) -> {
                String goal = input.getText().toString().trim();
                if (!goal.isEmpty()) {
                    requestAiRcProposal(goal);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void requestAiRcProposal(@NonNull String goal) {
        AIManager ai = AIManager.getInstance(this);
        AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle("Generating " + mSelectedPromptShell + " Snippet")
            .setMessage("Asking " + ai.getSettings().getActiveProvider().getDisplayName() + " for a clean, safe rc snippet…")
            .setNegativeButton(android.R.string.cancel, (d, w) -> {
                if (mAiHandle != null) mAiHandle.cancel();
            })
            .create();
        progressDialog.show();

        String ask = "Generate ONLY one safe, minimal " + mSelectedPromptShell + " rc snippet (inside a single fenced code block) for Termux accomplishing: "
            + goal + ". Do not include destructive commands or telemetry.";
        AIMessage msg = new AIMessage(AIMessage.Role.USER, ask);

        mAiHandle = ai.sendConversation(Collections.singletonList(msg), null, new AIStreamCallback() {
            @Override
            public void onChunk(@NonNull String delta, @NonNull String accumulated) {}

            @Override
            public void onComplete(@NonNull AIResponse response) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressDialog.dismiss();
                    showProposedSnippetReviewDialog(response.getText());
                });
            }

            @Override
            public void onError(@NonNull AIError error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressDialog.dismiss();
                    if (error.getType() != AIError.Type.CANCELLED) {
                        Toast.makeText(TerminalStudioActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showProposedSnippetReviewDialog(@NonNull String rawResponse) {
        List<MarkdownCodeBlockParser.Segment> segments = MarkdownCodeBlockParser.parse(rawResponse);
        String codeBlock = null;
        for (MarkdownCodeBlockParser.Segment seg : segments) {
            if (seg.kind == MarkdownCodeBlockParser.Segment.Kind.CODE_BLOCK && !seg.content.trim().isEmpty()) {
                codeBlock = seg.content.trim();
                break;
            }
        }
        final String candidate = codeBlock != null ? codeBlock : rawResponse.trim();
        File targetRc = mConfigManager.getRcFileForShell(mSelectedPromptShell);

        new AlertDialog.Builder(this)
            .setTitle("Review Proposed Snippet (" + targetRc.getName() + ")")
            .setMessage(candidate)
            .setPositiveButton("Apply to " + targetRc.getName(), (d, w) -> {
                if (mConfigManager.upsertModuleInRc(targetRc, "AI_CUSTOM_SNIPPET", candidate)) {
                    Toast.makeText(this, "Snippet safely written with backup to " + targetRc.getName(), Toast.LENGTH_SHORT).show();
                    refreshBackupSummary();
                } else {
                    Toast.makeText(this, "Failed to write snippet", Toast.LENGTH_SHORT).show();
                }
            })
            .setNeutralButton("Copy", (d, w) -> copyTextToClipboard("AI RC Snippet", candidate))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showAttributionDialog() {
        String text = "Termux AI — Terminal Studio\n\n"
            + "Based on Termux (GPLv3) and directly inspired by h4ck3r0/Termux-os (Raj Aryan / H4Ck3R0), licensed under GNU General Public License v3.0 (GPL-3.0).\n\n"
            + "This built-in Android integration re-implements shell customization, color themes, prompt themes, startup banners, and optional shell prompt locking natively with atomic file writes, idempotent marker blocks, and automatic config backups in ~/.termux/studio_backups/.\n\n"
            + "Upstream reference:\nhttps://github.com/h4ck3r0/Termux-os";
        new AlertDialog.Builder(this)
            .setTitle(R.string.studio_open_licenses)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void copyTextToClipboard(@NonNull String label, @NonNull String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(this, R.string.ai_code_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    private LinearLayout buildSecondaryItemRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_oneui_secondary_card);
        int pad = dp(12);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    @NonNull
    private LinearLayout buildItemTextColumn(@NonNull String titleText, @NonNull String subtitleText) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.setPadding(0, 0, dp(8), 0);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(ContextCompat.getColor(this, R.color.oneui_text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(null, Typeface.BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitleText);
        sub.setTextColor(ContextCompat.getColor(this, R.color.oneui_text_secondary));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);
        return col;
    }

    @NonNull
    private TextView buildPillActionButton(@NonNull String label, boolean highlighted) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setMinHeight(dp(40));
        btn.setMinWidth(dp(76));
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (highlighted) {
            btn.setBackgroundResource(R.drawable.bg_oneui_pill_white);
            btn.setTextColor(ContextCompat.getColor(this, R.color.oneui_on_accent));
        } else {
            btn.setBackgroundResource(R.drawable.bg_oneui_chip);
            btn.setTextColor(ContextCompat.getColor(this, R.color.oneui_text_primary));
        }
        return btn;
    }

    private void styleChoiceChip(@NonNull TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_oneui_pill_white : R.drawable.bg_oneui_chip);
        chip.setTextColor(ContextCompat.getColor(
            this, selected ? R.color.oneui_on_accent : R.color.oneui_text_primary));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
