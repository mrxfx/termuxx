package com.termux.app.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.activities.CommandHistoryActivity;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.ai.AIManager;
import com.termux.app.ai.AISettingsManager;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.autocomplete.CommandHistoryStore;
import com.termux.app.studio.TerminalStudioActivity;
import com.termux.app.workspace.BackupManagerActivity;
import com.termux.app.workspace.CommandSnippetActivity;
import com.termux.app.workspace.ProfileManagerActivity;
import com.termux.app.workspace.WorkspaceListActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * Modular One UI top-bar dialogs, session picker, and real Workspace Overview helper for TermuxActivity.
 * Displays strictly verified real runtime data (sessions, history, active AI config, detected CLI tools).
 */
public final class TermuxOneUIWorkspaceHelper {

    private TermuxOneUIWorkspaceHelper() {}

    public static void showSessionSwitcherDialog(@NonNull TermuxActivity activity) {
        TermuxService service = activity.getTermuxService();
        if (service == null) return;
        List<TermuxSession> sessions = service.getTermuxSessions();
        if (sessions.isEmpty()) {
            activity.getTermuxTerminalSessionClient().addNewSession(false, null);
            return;
        }

        TerminalSession current = activity.getCurrentSession();
        String[] labels = new String[sessions.size()];
        int checked = -1;
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession s = sessions.get(i).getTerminalSession();
            String name = s != null && !TextUtils.isEmpty(s.mSessionName) ? s.mSessionName : null;
            String title = s != null && !TextUtils.isEmpty(s.getTitle()) ? s.getTitle() : "Shell";
            String state = (s != null && s.isRunning()) ? activity.getString(R.string.terminal_session_running) : activity.getString(R.string.terminal_session_exited);
            labels[i] = "[" + (i + 1) + "] " + (name != null ? name + " · " : "") + title + " (" + state + ")";
            if (s == current) {
                checked = i;
            }
        }

        new AlertDialog.Builder(activity)
            .setTitle(R.string.terminal_sessions_header)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                dialog.dismiss();
                activity.getTermuxTerminalSessionClient().switchToSession(which);
            })
            .setPositiveButton(R.string.action_new_session, (dialog, which) ->
                activity.getTermuxTerminalSessionClient().addNewSession(false, null))
            .setNeutralButton(R.string.title_rename_session, (dialog, which) -> {
                if (current != null) {
                    activity.getTermuxTerminalSessionClient().renameSession(current);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    public static void showTopOverflowMenu(@NonNull TermuxActivity activity, @NonNull View anchor) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        final int ID_WORKSPACES = 100;
        final int ID_PROFILES = 108;
        final int ID_SNIPPETS = 109;
        final int ID_BACKUPS = 110;
        final int ID_ENV_MANAGER = 111;
        final int ID_STUDIO = 101;
        final int ID_HISTORY = 102;
        final int ID_OVERVIEW = 103;
        final int ID_EXTRA_KEYS = 104;
        final int ID_RESET = 105;
        final int ID_SETTINGS = 106;
        final int ID_HELP = 107;

        popup.getMenu().add(0, ID_ENV_MANAGER, 1, R.string.title_environment_manager);
        popup.getMenu().add(0, ID_WORKSPACES, 2, R.string.title_workspace_manager);
        popup.getMenu().add(0, ID_PROFILES, 3, R.string.title_profile_manager);
        popup.getMenu().add(0, ID_SNIPPETS, 4, R.string.title_snippet_manager);
        popup.getMenu().add(0, ID_BACKUPS, 5, R.string.title_backup_manager);
        popup.getMenu().add(0, ID_STUDIO, 6, R.string.studio_title);
        popup.getMenu().add(0, ID_HISTORY, 7, R.string.history_title);
        popup.getMenu().add(0, ID_OVERVIEW, 8, R.string.workspace_overview_action);
        popup.getMenu().add(0, ID_EXTRA_KEYS, 9, R.string.studio_section_extrakeys);
        popup.getMenu().add(0, ID_RESET, 10, R.string.action_reset_terminal);
        popup.getMenu().add(0, ID_SETTINGS, 11, R.string.action_open_settings);
        popup.getMenu().add(0, ID_HELP, 12, R.string.action_open_help);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case ID_ENV_MANAGER:
                    ActivityUtils.startActivity(activity, new Intent(activity, com.termux.app.env.EnvironmentManagerActivity.class));
                    return true;
                case ID_WORKSPACES:
                    ActivityUtils.startActivity(activity, new Intent(activity, WorkspaceListActivity.class));
                    return true;
                case ID_PROFILES:
                    ActivityUtils.startActivity(activity, new Intent(activity, ProfileManagerActivity.class));
                    return true;
                case ID_SNIPPETS:
                    ActivityUtils.startActivity(activity, new Intent(activity, CommandSnippetActivity.class));
                    return true;
                case ID_BACKUPS:
                    ActivityUtils.startActivity(activity, new Intent(activity, BackupManagerActivity.class));
                    return true;
                case ID_STUDIO:
                    ActivityUtils.startActivity(activity, new Intent(activity, TerminalStudioActivity.class));
                    return true;
                case ID_HISTORY:
                    ActivityUtils.startActivity(activity, new Intent(activity, CommandHistoryActivity.class));
                    return true;
                case ID_OVERVIEW:
                    showWorkspaceOverviewDialog(activity);
                    return true;
                case ID_EXTRA_KEYS:
                    activity.toggleTerminalToolbar();
                    return true;
                case ID_RESET: {
                    TerminalSession session = activity.getCurrentSession();
                    if (session != null) {
                        session.reset();
                        activity.showToast(activity.getString(R.string.msg_terminal_reset), true);
                        activity.getTermuxTerminalSessionClient().onResetTerminalSession();
                    }
                    return true;
                }
                case ID_SETTINGS:
                    ActivityUtils.startActivity(activity, new Intent(activity, SettingsActivity.class));
                    return true;
                case ID_HELP:
                    ActivityUtils.startActivity(activity, new Intent(activity, HelpActivity.class));
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    public static void showWorkspaceOverviewDialog(@NonNull TermuxActivity activity) {
        StringBuilder sb = new StringBuilder();

        // 1. Active Sessions (real)
        TermuxService service = activity.getTermuxService();
        int sessionCount = service != null ? service.getTermuxSessionsSize() : 0;
        sb.append("Active Sessions: ").append(sessionCount).append('\n');
        TerminalSession current = activity.getCurrentSession();
        if (current != null) {
            String cwd = null;
            try {
                cwd = current.getCwd();
            } catch (Exception ignored) {}
            sb.append("Current Directory: ").append(cwd != null ? cwd : "~").append('\n');
        }

        // 2. Active AI Engine (real)
        AIManager aiManager = AIManager.getInstance(activity);
        AISettingsManager aiSettings = aiManager.getSettings();
        AIProviderType provider = aiSettings.getActiveProvider();
        String model = aiSettings.getModelForProvider(provider);
        sb.append("\n").append(activity.getString(R.string.workspace_active_ai)).append(":\n");
        sb.append("  ").append(provider.getDisplayName()).append(" · ").append(model).append('\n');

        // 3. Recent Commands (real from CommandHistoryStore)
        sb.append("\n").append(activity.getString(R.string.workspace_recent_commands)).append(":\n");
        List<CommandHistoryStore.HistoryEntry> entries = new CommandHistoryStore(activity).getAllEntriesSorted();
        if (entries.isEmpty()) {
            sb.append("  ").append(activity.getString(R.string.workspace_no_recent_commands)).append('\n');
        } else {
            int limit = Math.min(5, entries.size());
            for (int i = 0; i < limit; i++) {
                CommandHistoryStore.HistoryEntry e = entries.get(i);
                sb.append("  • ").append(e.command);
                if (e.count > 1) sb.append(" (").append(e.count).append("x)");
                sb.append('\n');
            }
        }

        // 4. Detected CLI Tools (real from AutocompleteEngine)
        sb.append("\n").append(activity.getString(R.string.workspace_installed_tools)).append(":\n");
        List<String> tools = activity.getAutocompleteController() != null
            ? activity.getAutocompleteController().getEngine().getAvailableKnownToolsSnapshot()
            : java.util.Collections.emptyList();
        if (tools.isEmpty()) {
            sb.append("  ").append(activity.getString(R.string.workspace_no_tools_detected)).append('\n');
        } else {
            int limit = Math.min(18, tools.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                else sb.append("  ");
                sb.append(tools.get(i));
            }
            if (tools.size() > limit) {
                sb.append(" (+" + (tools.size() - limit) + " more)");
            }
        }

        new AlertDialog.Builder(activity)
            .setTitle(R.string.workspace_overview_title)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.history_title, (d, w) -> {
                if (!entries.isEmpty()) {
                    ActivityUtils.startActivity(activity, new Intent(activity, CommandHistoryActivity.class));
                } else {
                    Toast.makeText(activity, R.string.workspace_no_recent_commands, Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }
}
