package com.termux.app.autocomplete;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;

@Keep
public class AutocompleteSettingsFragment extends PreferenceFragmentCompat {

    private AutocompleteSettingsManager mSettings;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        mSettings = new AutocompleteSettingsManager(context);
        getPreferenceManager().setPreferenceDataStore(new AutocompleteDataStore(mSettings));
        setPreferencesFromResource(R.xml.autocomplete_preferences, rootKey);

        ListPreference maxPref = findPreference(AutocompleteSettingsManager.KEY_MAX_SUGGESTIONS);
        if (maxPref != null) {
            CharSequence[] entries = new CharSequence[]{"4 suggestions", "6 suggestions", "8 suggestions", "10 suggestions"};
            CharSequence[] values = new CharSequence[]{"4", "6", "8", "10"};
            maxPref.setEntries(entries);
            maxPref.setEntryValues(values);
            maxPref.setValue(String.valueOf(mSettings.getMaxSuggestions()));
        }

        ListPreference acceptKeyPref = findPreference(AutocompleteSettingsManager.KEY_ACCEPT_KEY);
        if (acceptKeyPref != null) {
            acceptKeyPref.setEntries(new CharSequence[]{
                "Tab key",
                "Right Arrow key",
                "Both Tab and Right Arrow"
            });
            acceptKeyPref.setEntryValues(new CharSequence[]{
                AutocompleteSettingsManager.ACCEPT_KEY_TAB,
                AutocompleteSettingsManager.ACCEPT_KEY_RIGHT,
                AutocompleteSettingsManager.ACCEPT_KEY_BOTH
            });
            acceptKeyPref.setValue(mSettings.getAcceptKey());
        }

        Preference clearHistoryPref = findPreference("ac_clear_history");
        if (clearHistoryPref != null) {
            clearHistoryPref.setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(context)
                    .setTitle(R.string.ac_clear_history_title)
                    .setMessage("Clear offline command history learned by Smart Autocomplete?")
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        new CommandHistoryStore(context).clearHistory();
                        Toast.makeText(context, "Autocomplete command history cleared", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            });
        }
    }

    private static final class AutocompleteDataStore extends PreferenceDataStore {
        private final AutocompleteSettingsManager mSettings;

        AutocompleteDataStore(@NonNull AutocompleteSettingsManager settings) {
            mSettings = settings;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            if (key == null) return;
            switch (key) {
                case AutocompleteSettingsManager.KEY_ENABLED:
                    mSettings.setEnabled(value);
                    break;
                case AutocompleteSettingsManager.KEY_GHOST_TEXT:
                    mSettings.setGhostTextEnabled(value);
                    break;
                case AutocompleteSettingsManager.KEY_DROPDOWN:
                    mSettings.setDropdownEnabled(value);
                    break;
                case AutocompleteSettingsManager.KEY_HISTORY_ENABLED:
                    mSettings.setHistoryEnabled(value);
                    break;
                case AutocompleteSettingsManager.KEY_FILES_ENABLED:
                    mSettings.setFileSuggestionsEnabled(value);
                    break;
                case AutocompleteSettingsManager.KEY_PACKAGES_ENABLED:
                    mSettings.setPackageSuggestionsEnabled(value);
                    break;
                default:
                    break;
            }
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            if (key == null) return defValue;
            switch (key) {
                case AutocompleteSettingsManager.KEY_ENABLED:
                    return mSettings.isEnabled();
                case AutocompleteSettingsManager.KEY_GHOST_TEXT:
                    return mSettings.isGhostTextEnabled();
                case AutocompleteSettingsManager.KEY_DROPDOWN:
                    return mSettings.isDropdownEnabled();
                case AutocompleteSettingsManager.KEY_HISTORY_ENABLED:
                    return mSettings.isHistoryEnabled();
                case AutocompleteSettingsManager.KEY_FILES_ENABLED:
                    return mSettings.isFileSuggestionsEnabled();
                case AutocompleteSettingsManager.KEY_PACKAGES_ENABLED:
                    return mSettings.isPackageSuggestionsEnabled();
                default:
                    return defValue;
            }
        }

        @Override
        public void putString(String key, String value) {
            if (key == null || value == null) return;
            if (AutocompleteSettingsManager.KEY_MAX_SUGGESTIONS.equals(key)) {
                try {
                    mSettings.setMaxSuggestions(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {}
            } else if (AutocompleteSettingsManager.KEY_ACCEPT_KEY.equals(key)) {
                mSettings.setAcceptKey(value);
            }
        }

        @Override
        public String getString(String key, String defValue) {
            if (key == null) return defValue;
            if (AutocompleteSettingsManager.KEY_MAX_SUGGESTIONS.equals(key)) {
                return String.valueOf(mSettings.getMaxSuggestions());
            } else if (AutocompleteSettingsManager.KEY_ACCEPT_KEY.equals(key)) {
                return mSettings.getAcceptKey();
            }
            return defValue;
        }
    }
}
