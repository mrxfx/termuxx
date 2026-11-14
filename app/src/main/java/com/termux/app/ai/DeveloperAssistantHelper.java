package com.termux.app.ai;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.ai.security.SensitiveDataRedactor;
import com.termux.app.workspace.CommandSnippet;
import com.termux.app.workspace.SnippetRepository;
import com.termux.app.workspace.WorkspaceUiFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * V3 Advanced AI Developer Assistant workflows, opt-in privacy-safe Project Structure Inspector,
 * Review-Before-Insert dialog, and explicit Save-as-Snippet integration.
 */
public final class DeveloperAssistantHelper {

    public static final int MAX_PROJECT_STRUCTURE_CHARS = 2600;
    private static final int MAX_TREE_DEPTH = 3;
    private static final int MAX_ENTRIES_PER_DIR = 24;

    public static final class DeveloperWorkflow {
        @NonNull public final String id;
        @NonNull public final String title;
        @NonNull public final String category;
        @NonNull public final String promptTemplate;
        public final boolean includeRecentOutput;

        public DeveloperWorkflow(
            @NonNull String id,
            @NonNull String title,
            @NonNull String category,
            @NonNull String promptTemplate,
            boolean includeRecentOutput
        ) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.promptTemplate = promptTemplate;
            this.includeRecentOutput = includeRecentOutput;
        }
    }

    private DeveloperAssistantHelper() {}

    /**
     * Returns all 16 V3 Developer AI Workflows.
     */
    @NonNull
    public static List<DeveloperWorkflow> getWorkflows() {
        List<DeveloperWorkflow> list = new ArrayList<>();
        list.add(new DeveloperWorkflow(
            "nl_to_cmd",
            "1. Natural Language → Terminal Command",
            "Terminal",
            "Translate my goal into a safe, idiomatic Termux command, explain each flag, and highlight any side effects:\nGoal: ",
            false
        ));
        list.add(new DeveloperWorkflow(
            "cmd_explain",
            "2. Command Explanation",
            "Terminal",
            "Explain this terminal command step-by-step, including all flags, pipes, exit codes, and safety considerations:\n```bash\n\n```",
            false
        ));
        list.add(new DeveloperWorkflow(
            "error_diagnosis",
            "3. Error Diagnosis",
            "Debugging",
            "Analyze the recent terminal error output, identify the root cause, explain why it happened, and suggest a safe command to fix it.",
            true
        ));
        list.add(new DeveloperWorkflow(
            "build_error",
            "4. Build Error Analysis",
            "Build",
            "Analyze the compiler/linker/build failure in my terminal output, explain the failing module or symbol, and propose exact fix steps.",
            true
        ));
        list.add(new DeveloperWorkflow(
            "gradle_troubleshoot",
            "5. Gradle Troubleshooting",
            "Android / JVM",
            "Diagnose this Gradle build or dependency resolution issue in Termux, and recommend safe Gradle flags or configuration fixes.",
            true
        ));
        list.add(new DeveloperWorkflow(
            "android_dev",
            "6. Android Development Assistance",
            "Android",
            "Assist with Android SDK / APK / ADB / Jetpack / manifest / build configuration in Termux:\nTask: ",
            false
        ));
        list.add(new DeveloperWorkflow(
            "java_kotlin",
            "7. Java & Kotlin Assistance",
            "JVM",
            "Help write, refactor, or debug Java/Kotlin code and command-line compilation/execution in Termux:\nQuestion: ",
            false
        ));
        list.add(new DeveloperWorkflow(
            "python_dev",
            "8. Python Assistance",
            "Python",
            "Help with Python scripting, virtualenv/pip package issues, or debugging tracebacks in Termux:\nTask: ",
            true
        ));
        list.add(new DeveloperWorkflow(
            "node_js",
            "9. Node.js & JavaScript Assistance",
            "Node.js",
            "Help with Node.js, npm/pnpm scripts, package.json configuration, or runtime errors in Termux:\nTask: ",
            true
        ));
        list.add(new DeveloperWorkflow(
            "git_assist",
            "10. Git Assistance",
            "Git",
            "Suggest safe, non-destructive Git commands for my current branch/staging state and explain how to recover if needed:\nGoal: ",
            true
        ));
        list.add(new DeveloperWorkflow(
            "shell_script",
            "11. Shell Script Generation",
            "Shell",
            "Write a POSIX/Bash shell script for Termux using `set -euo pipefail`, safe quoting, and clear comments for:\nTask: ",
            false
        ));
        list.add(new DeveloperWorkflow(
            "code_review",
            "12. Code Review",
            "Quality",
            "Review the attached code or snippet for bugs, security risks, edge cases, and performance improvements.",
            false
        ));
        list.add(new DeveloperWorkflow(
            "log_analysis",
            "13. Log Analysis",
            "Debugging",
            "Analyze the recent log output or attached log file, summarize key warnings/errors, and pinpoint the first failure event.",
            true
        ));
        list.add(new DeveloperWorkflow(
            "project_structure",
            "14. Project Structure Explanation",
            "Architecture",
            "Inspect the attached project directory structure, explain the architecture and entry points, and suggest safe build/test commands.",
            false
        ));
        list.add(new DeveloperWorkflow(
            "config_assist",
            "15. Configuration File Assistance",
            "Config",
            "Help me write, validate, or troubleshoot a configuration file (.bashrc, .zshrc, ~/.termux/termux.properties, JSON/YAML/TOML):\nConfig goal: ",
            false
        ));
        list.add(new DeveloperWorkflow(
            "workflow_suggestions",
            "16. Development Workflow Suggestions",
            "Productivity",
            "Based on my current Termux workspace and tools, suggest high-leverage aliases, snippets, and workflow optimizations.",
            false
        ));
        return Collections.unmodifiableList(list);
    }

    /**
     * Checks whether a file or directory must be excluded from AI context inspection
     * to protect secrets, credentials, keystores, and private git internals.
     */
    public static boolean isSensitiveOrExcludedFile(@Nullable File file) {
        if (file == null) return true;
        String name = file.getName().toLowerCase(Locale.US);
        if (name.equals(".git")
            || name.equals(".env")
            || name.startsWith(".env.")
            || name.equals("local.properties")
            || name.equals("studio_lock.json")
            || name.equals("node_modules")
            || name.equals(".gradle")
            || name.equals("build")
            || name.equals("__pycache__")
            || name.equals(".ssh")
            || name.equals(".gnupg")) {
            return true;
        }
        return name.endsWith(".keystore")
            || name.endsWith(".jks")
            || name.endsWith(".pem")
            || name.endsWith(".key")
            || name.endsWith(".p12")
            || name.endsWith(".pfx")
            || name.endsWith(".der")
            || name.endsWith(".crt")
            || name.contains("secret")
            || name.contains("credential")
            || name.contains("id_rsa")
            || name.contains("id_ed25519");
    }

    /**
     * Generates a privacy-safe, size-limited directory tree summary for opt-in project context.
     * Never reads file contents and strictly excludes sensitive files/folders.
     */
    @NonNull
    public static String inspectSafeProjectStructure(@Nullable File rootDir) {
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory() || !rootDir.canRead()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Project Root: ").append(SensitiveDataRedactor.redact(rootDir.getAbsolutePath())).append('\n');
        appendDirectoryTree(rootDir, "", 0, sb);
        String out = SensitiveDataRedactor.redact(sb.toString().trim());
        if (out.length() > MAX_PROJECT_STRUCTURE_CHARS) {
            out = out.substring(0, MAX_PROJECT_STRUCTURE_CHARS) + "\n...[project tree truncated]";
        }
        return out;
    }

    private static void appendDirectoryTree(@NonNull File dir, @NonNull String indent, int depth, @NonNull StringBuilder sb) {
        if (depth >= MAX_TREE_DEPTH || sb.length() >= MAX_PROJECT_STRUCTURE_CHARS) return;
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return;

        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        int count = 0;
        for (File child : children) {
            if (isSensitiveOrExcludedFile(child)) {
                continue;
            }
            if (count >= MAX_ENTRIES_PER_DIR || sb.length() >= MAX_PROJECT_STRUCTURE_CHARS) {
                sb.append(indent).append("└── ... (more entries)\n");
                break;
            }
            count++;
            if (child.isDirectory()) {
                sb.append(indent).append("├── ").append(child.getName()).append("/\n");
                appendDirectoryTree(child, indent + "│   ", depth + 1, sb);
            } else {
                sb.append(indent).append("├── ").append(child.getName())
                    .append(" (").append(Math.max(1L, child.length() / 1024L)).append(" KB)\n");
            }
        }
    }

    /**
     * Lists safe, readable source/config/log files in the given working directory for explicit user selection.
     */
    @NonNull
    public static List<File> listSafeSelectableProjectFiles(@Nullable File cwd) {
        List<File> result = new ArrayList<>();
        if (cwd == null || !cwd.exists() || !cwd.isDirectory() || !cwd.canRead()) {
            return result;
        }
        File[] listed = cwd.listFiles();
        if (listed == null) return result;
        Arrays.sort(listed, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : listed) {
            if (f.isFile() && f.canRead() && !isSensitiveOrExcludedFile(f) && !f.getName().startsWith(".")) {
                result.add(f);
                if (result.size() >= 16) break;
            }
        }
        return result;
    }

    /**
     * Shows the Analyze → Explain → Suggest → Review → Insert dialog allowing the user to
     * inspect and edit an AI-suggested command before inserting it into the terminal input buffer.
     * Never auto-executes.
     */
    public static void showReviewAndInsertDialog(
        @NonNull Context context,
        @NonNull String initialCode,
        @Nullable Runnable onInsertedCallback
    ) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(context, 18);
        form.setPadding(pad, WorkspaceUiFactory.dp(context, 10), pad, 0);

        TextView note = WorkspaceUiFactory.createSubtitle(
            context,
            "Review or edit the command below. Inserting places it into the terminal prompt without executing it."
        );
        form.addView(note);

        EditText cmdInput = WorkspaceUiFactory.createInput(context, "Command", initialCode.trim(), true);
        form.addView(cmdInput);

        new AlertDialog.Builder(context)
            .setTitle("Review & Edit Before Insert")
            .setView(form)
            .setPositiveButton("Insert into Terminal", (d, w) -> {
                String edited = cmdInput.getText().toString().trim();
                if (edited.isEmpty()) return;
                boolean inserted = TerminalCommandBridge.insertIntoActiveTerminal(edited);
                Toast.makeText(
                    context,
                    inserted ? R.string.ai_code_inserted : R.string.ai_code_queued_for_insert,
                    Toast.LENGTH_SHORT
                ).show();
                if (onInsertedCallback != null) {
                    onInsertedCallback.run();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Allows the user to explicitly save an AI command block into the V2.1 Command Snippet Manager.
     * AI command history remains separate from saved snippets unless saved here.
     */
    public static void showSaveAsSnippetDialog(@NonNull Context context, @NonNull String code) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(context, 18);
        form.setPadding(pad, WorkspaceUiFactory.dp(context, 10), pad, 0);

        form.addView(WorkspaceUiFactory.createSectionHeader(context, "Snippet Title"));
        EditText titleInput = WorkspaceUiFactory.createInput(context, "e.g., AI Suggested Command", "", false);
        form.addView(titleInput);

        form.addView(WorkspaceUiFactory.createSectionHeader(context, "Category"));
        EditText categoryInput = WorkspaceUiFactory.createInput(context, "Linux, Git, Android, ADB, Python, Custom...", "Custom", false);
        form.addView(categoryInput);

        form.addView(WorkspaceUiFactory.createSectionHeader(context, "Command (never auto-executed)"));
        EditText cmdInput = WorkspaceUiFactory.createInput(context, "Command", code.trim(), true);
        form.addView(cmdInput);

        form.addView(WorkspaceUiFactory.createSectionHeader(context, "Description & Tags"));
        EditText descInput = WorkspaceUiFactory.createInput(context, "Saved from Termux AI Assistant", "Saved from Termux AI Assistant", false);
        form.addView(descInput);

        new AlertDialog.Builder(context)
            .setTitle("Save AI Command as Snippet")
            .setView(form)
            .setPositiveButton("Save Snippet", (d, w) -> {
                String title = titleInput.getText().toString().trim();
                String cmd = cmdInput.getText().toString().trim();
                String cat = categoryInput.getText().toString().trim();
                String desc = descInput.getText().toString().trim();
                if (cmd.isEmpty()) {
                    Toast.makeText(context, "Command cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (title.isEmpty()) {
                    title = cmd.length() > 36 ? cmd.substring(0, 36) + "…" : cmd;
                }
                SnippetRepository repo = new SnippetRepository(context);
                repo.saveSnippet(new CommandSnippet(
                    "",
                    title,
                    cmd,
                    cat.isEmpty() ? "Custom" : cat,
                    desc,
                    "ai,assistant",
                    false,
                    System.currentTimeMillis()
                ));
                Toast.makeText(context, "Saved to Command Snippets", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
