package com.termux.app.ui;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.CommandHistoryActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.ai.ui.AIAssistantActivity;
import com.termux.app.workspace.WorkspaceListActivity;

/**
 * Shared Samsung One UI bottom navigation coordinator across primary Termux AI destinations:
 * Terminal, AI Assistant, Workspaces, History, and Settings.
 */
public final class OneUINavHelper {

    private OneUINavHelper() {}

    public static void setupBottomNav(
        @NonNull Activity activity,
        @NonNull BottomNavigationView bottomNav,
        @IdRes int currentTabId
    ) {
        bottomNav.setSelectedItemId(currentTabId);
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == currentTabId) {
                return true;
            }
            if (itemId == R.id.nav_terminal) {
                if (activity instanceof TermuxActivity) {
                    return true;
                }
                Intent intent = new Intent(activity, TermuxActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(intent);
                activity.overridePendingTransition(0, 0);
                activity.finish();
                return true;
            } else if (itemId == R.id.nav_ai) {
                if (activity instanceof TermuxActivity) {
                    ((TermuxActivity) activity).openAIAssistant(null, null);
                } else {
                    Intent intent = new Intent(activity, AIAssistantActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    activity.startActivity(intent);
                    activity.overridePendingTransition(0, 0);
                    activity.finish();
                }
                return true;
            } else if (itemId == R.id.nav_studio) {
                Intent intent = new Intent(activity, WorkspaceListActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
                activity.overridePendingTransition(0, 0);
                if (!(activity instanceof TermuxActivity)) {
                    activity.finish();
                }
                return true;
            } else if (itemId == R.id.nav_history) {
                Intent intent = new Intent(activity, CommandHistoryActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
                activity.overridePendingTransition(0, 0);
                if (!(activity instanceof TermuxActivity)) {
                    activity.finish();
                }
                return true;
            } else if (itemId == R.id.nav_settings) {
                Intent intent = new Intent(activity, SettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
                activity.overridePendingTransition(0, 0);
                if (!(activity instanceof TermuxActivity)) {
                    activity.finish();
                }
                return true;
            }
            return false;
        });
    }
}
