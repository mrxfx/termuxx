package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.app.ai.firebase.AppCheckInit;
import com.termux.app.models.UserAction;
import com.termux.app.studio.TerminalStudioActivity;
import com.termux.app.ui.OneUINavHelper;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.models.ReportInfo;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;

public class SettingsActivity extends AppCompatActivity {

    public static final String EXTRA_INITIAL_FRAGMENT = "initial_fragment";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCheckInit.configureAppCheck(this, getIntent());

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            Fragment initialFragment = resolveInitialFragment(getIntent().getStringExtra(EXTRA_INITIAL_FRAGMENT));
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, initialFragment)
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        BottomNavigationView bottomNav = findViewById(R.id.oneui_bottom_nav);
        if (bottomNav != null) {
            OneUINavHelper.setupBottomNav(this, bottomNav, R.id.nav_settings);
        }
    }

    @NonNull
    private Fragment resolveInitialFragment(String fragmentName) {
        if ("com.termux.app.ai.ui.AISettingsFragment".equals(fragmentName)) {
            return new com.termux.app.ai.ui.AISettingsFragment();
        } else if ("com.termux.app.autocomplete.AutocompleteSettingsFragment".equals(fragmentName)) {
            return new com.termux.app.autocomplete.AutocompleteSettingsFragment();
        } else if ("com.termux.app.fragments.settings.StorageMaintenanceFragment".equals(fragmentName)) {
            return new com.termux.app.fragments.settings.StorageMaintenanceFragment();
        }
        return new RootPreferencesFragment();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            configureShortcutPreferences(context);
            configureLicensesPreference(context);

            new Thread() {
                @Override
                public void run() {
                    configureTermuxAPIPreference(context);
                    configureTermuxFloatPreference(context);
                    configureTermuxTaskerPreference(context);
                    configureTermuxWidgetPreference(context);
                    configureAboutPreference(context);
                    configureDonatePreference(context);
                }
            }.start();
        }

        private void configureShortcutPreferences(@NonNull Context context) {
            Preference envPref = findPreference("environment_manager_shortcut");
            if (envPref != null) {
                envPref.setOnPreferenceClickListener(p -> {
                    context.startActivity(new Intent(context, com.termux.app.env.EnvironmentManagerActivity.class));
                    return true;
                });
            }
            Preference wsPref = findPreference("workspace_manager_shortcut");
            if (wsPref != null) {
                wsPref.setOnPreferenceClickListener(p -> {
                    context.startActivity(new Intent(context, com.termux.app.workspace.WorkspaceListActivity.class));
                    return true;
                });
            }
            Preference profPref = findPreference("profile_manager_shortcut");
            if (profPref != null) {
                profPref.setOnPreferenceClickListener(p -> {
                    context.startActivity(new Intent(context, com.termux.app.workspace.ProfileManagerActivity.class));
                    return true;
                });
            }
            Preference snipPref = findPreference("snippet_manager_shortcut");
            if (snipPref != null) {
                snipPref.setOnPreferenceClickListener(p -> {
                    context.startActivity(new Intent(context, com.termux.app.workspace.CommandSnippetActivity.class));
                    return true;
                });
            }
            Preference backupPref = findPreference("backup_manager_shortcut");
            if (backupPref != null) {
                backupPref.setOnPreferenceClickListener(p -> {
                    context.startActivity(new Intent(context, com.termux.app.workspace.BackupManagerActivity.class));
                    return true;
                });
            }
            Preference studioPref = findPreference("terminal_studio_shortcut");
            if (studioPref != null) {
                studioPref.setOnPreferenceClickListener(p -> {
                    context.startActivity(new Intent(context, TerminalStudioActivity.class));
                    return true;
                });
            }
            Preference historyPref = findPreference("command_history_shortcut");
            if (historyPref != null) {
                historyPref.setOnPreferenceClickListener(p -> {
                    context.startActivity(new Intent(context, CommandHistoryActivity.class));
                    return true;
                });
            }
        }

        private void configureLicensesPreference(@NonNull Context context) {
            Preference licensesPref = findPreference("open_source_licenses");
            if (licensesPref != null) {
                licensesPref.setOnPreferenceClickListener(p -> {
                    String details = "Termux AI — Open Source Attribution\n\n"
                        + "• Termux Terminal Core & Bootstrap (GNU GPLv3)\n"
                        + "  https://github.com/termux/termux-app\n\n"
                        + "• Termux-OS Inspiration (GNU GPL-3.0, Raj Aryan / H4Ck3R0)\n"
                        + "  https://github.com/h4ck3r0/Termux-os\n"
                        + "  Terminal Studio implements shell switching, color themes, prompt themes, startup banners, and optional prompt lock natively inside Termux AI.\n\n"
                        + "• Markwon Markdown Renderer (Apache-2.0)\n"
                        + "• AndroidX, Material Components & Firebase AI Logic";
                    new AlertDialog.Builder(context)
                        .setTitle(R.string.open_source_licenses_title)
                        .setMessage(details)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return true;
                });
            }
        }

        private void configureTermuxAPIPreference(@NonNull Context context) {
            Preference termuxAPIPreference = findPreference("termux_api");
            if (termuxAPIPreference != null) {
                TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context, false);
                termuxAPIPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxFloatPreference(@NonNull Context context) {
            Preference termuxFloatPreference = findPreference("termux_float");
            if (termuxFloatPreference != null) {
                TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context, false);
                termuxFloatPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxTaskerPreference(@NonNull Context context) {
            Preference termuxTaskerPreference = findPreference("termux_tasker");
            if (termuxTaskerPreference != null) {
                TermuxTaskerAppSharedPreferences preferences = TermuxTaskerAppSharedPreferences.build(context, false);
                termuxTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxWidgetPreference(@NonNull Context context) {
            Preference termuxWidgetPreference = findPreference("termux_widget");
            if (termuxWidgetPreference != null) {
                TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context, false);
                termuxWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = "About";

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                            aboutString.append("\n\n### Termux-OS Attribution (GPL-3.0)\nTerminal Studio includes native integration inspired by [h4ck3r0/Termux-os](https://github.com/h4ck3r0/Termux-os).");
                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
                            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();

                            ReportInfo reportInfo = new ReportInfo(userActionName,
                                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title);
                            reportInfo.setReportString(aboutString.toString());
                            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                            ReportActivity.startReportActivity(context, reportInfo);
                        }
                    }.start();

                    return true;
                });
            }
        }

        private void configureDonatePreference(@NonNull Context context) {
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
                if (signingCertificateSHA256Digest != null) {
                    String apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest);
                    if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false);
                        return;
                    } else {
                        donatePreference.setVisible(true);
                    }
                }

                donatePreference.setOnPreferenceClickListener(preference -> {
                    ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL);
                    return true;
                });
            }
        }
    }

}
