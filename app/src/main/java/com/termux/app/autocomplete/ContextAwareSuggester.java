package com.termux.app.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides offline context-aware suggestions:
 * - Git subcommands & local branches when inside a Git repository
 * - Python / pip / pytest suggestions when python is available or in a Python project
 * - Node / npm scripts from package.json when node is available
 * - Common subcommands/flags for pkg, apt, systemctl, tmux, docker, grep, ssh
 */
public class ContextAwareSuggester {

    private static final Map<String, String> GIT_SUBCOMMANDS = new LinkedHashMap<>();
    private static final Map<String, String> PKG_SUBCOMMANDS = new LinkedHashMap<>();
    private static final Map<String, String> PIP_SUBCOMMANDS = new LinkedHashMap<>();
    private static final Map<String, String> NPM_SUBCOMMANDS = new LinkedHashMap<>();
    private static final Map<String, String> PYTHON_FLAGS = new LinkedHashMap<>();

    static {
        GIT_SUBCOMMANDS.put("status", "Show working tree status");
        GIT_SUBCOMMANDS.put("checkout", "Switch branches or restore files");
        GIT_SUBCOMMANDS.put("cherry-pick", "Apply changes by existing commits");
        GIT_SUBCOMMANDS.put("commit", "Record changes to repository");
        GIT_SUBCOMMANDS.put("switch", "Switch branches");
        GIT_SUBCOMMANDS.put("branch", "List, create, or delete branches");
        GIT_SUBCOMMANDS.put("add", "Add file contents to index");
        GIT_SUBCOMMANDS.put("diff", "Show changes between commits");
        GIT_SUBCOMMANDS.put("log", "Show commit logs");
        GIT_SUBCOMMANDS.put("pull", "Fetch and integrate remote changes");
        GIT_SUBCOMMANDS.put("push", "Update remote refs");
        GIT_SUBCOMMANDS.put("fetch", "Download objects and refs");
        GIT_SUBCOMMANDS.put("merge", "Join development histories");
        GIT_SUBCOMMANDS.put("rebase", "Reapply commits on top of base");
        GIT_SUBCOMMANDS.put("stash", "Stash dirty working directory");
        GIT_SUBCOMMANDS.put("restore", "Restore working tree files");
        GIT_SUBCOMMANDS.put("clone", "Clone a repository");
        GIT_SUBCOMMANDS.put("init", "Create an empty Git repository");

        PKG_SUBCOMMANDS.put("update", "Update package lists");
        PKG_SUBCOMMANDS.put("upgrade", "Upgrade installed packages");
        PKG_SUBCOMMANDS.put("install", "Install packages");
        PKG_SUBCOMMANDS.put("search", "Search packages by query");
        PKG_SUBCOMMANDS.put("show", "Show package metadata");
        PKG_SUBCOMMANDS.put("list-installed", "List installed packages");
        PKG_SUBCOMMANDS.put("uninstall", "Uninstall packages");
        PKG_SUBCOMMANDS.put("reinstall", "Reinstall packages");
        PKG_SUBCOMMANDS.put("autoclean", "Remove outdated cached archives");

        PIP_SUBCOMMANDS.put("install", "Install Python packages");
        PIP_SUBCOMMANDS.put("uninstall", "Uninstall Python packages");
        PIP_SUBCOMMANDS.put("list", "List installed Python packages");
        PIP_SUBCOMMANDS.put("freeze", "Output installed packages in requirements format");
        PIP_SUBCOMMANDS.put("show", "Show information about installed packages");

        NPM_SUBCOMMANDS.put("install", "Install project dependencies");
        NPM_SUBCOMMANDS.put("run", "Run script from package.json");
        NPM_SUBCOMMANDS.put("test", "Run project tests");
        NPM_SUBCOMMANDS.put("start", "Run start script");
        NPM_SUBCOMMANDS.put("build", "Run build script");
        NPM_SUBCOMMANDS.put("init", "Create package.json");
        NPM_SUBCOMMANDS.put("ci", "Clean install from lockfile");

        PYTHON_FLAGS.put("-m", "Run library module as a script");
        PYTHON_FLAGS.put("-m venv", "Create virtual environment");
        PYTHON_FLAGS.put("-m http.server", "Start simple HTTP server");
        PYTHON_FLAGS.put("-m pip", "Invoke pip module");
        PYTHON_FLAGS.put("-c", "Execute inline Python code");
        PYTHON_FLAGS.put("--version", "Print Python version");
    }

    @NonNull
    public List<SuggestionItem> suggestForParsedInput(
        @NonNull TerminalInputAnalyzer.ParsedInput input,
        @Nullable String cwd,
        @NonNull CommandIndexCache commandIndexCache,
        int limit
    ) {
        List<SuggestionItem> results = new ArrayList<>();
        String cmd = input.getCommandName();
        File gitDir = findGitDir(cwd);
        boolean inGitRepo = gitDir != null;

        if (input.isCommandPosition()) {
            // Boost context-relevant tools when inside git/python/node workspace
            if (inGitRepo && "git".startsWith(input.currentToken) && !"git".equals(input.currentToken)) {
                results.add(new SuggestionItem("git", "git".substring(input.currentToken.length()),
                    "Git repository detected", SuggestionType.CONTEXT_GIT, 88, false));
            }
            if (cwd != null && hasPythonFilesOrManifest(new File(cwd))) {
                for (String py : Arrays.asList("python", "python3", "pip", "pytest")) {
                    if (py.startsWith(input.currentToken) && !py.equals(input.currentToken)
                        && commandIndexCache.isCommandInstalled(py)) {
                        results.add(new SuggestionItem(py, py.substring(input.currentToken.length()),
                            "Python project detected", SuggestionType.CONTEXT_PYTHON, 86, false));
                    }
                }
            }
            if (cwd != null && new File(cwd, "package.json").isFile()) {
                for (String js : Arrays.asList("npm", "node", "npx")) {
                    if (js.startsWith(input.currentToken) && !js.equals(input.currentToken)
                        && commandIndexCache.isCommandInstalled(js)) {
                        results.add(new SuggestionItem(js, js.substring(input.currentToken.length()),
                            "Node.js package.json detected", SuggestionType.CONTEXT_NODE, 86, false));
                    }
                }
            }
            return trim(results, limit);
        }

        // Argument / subcommand completions
        int argIndex = input.completedTokens.size(); // 1 means typing first arg
        String prefix = input.currentToken;

        if ("git".equals(cmd)) {
            if (argIndex == 1) {
                int baseScore = inGitRepo ? 88 : 74;
                collectMapMatches(GIT_SUBCOMMANDS, prefix, SuggestionType.CONTEXT_GIT, baseScore, results);
            } else if (argIndex >= 2 && inGitRepo) {
                String sub = input.completedTokens.get(1);
                if ("checkout".equals(sub) || "switch".equals(sub) || "merge".equals(sub)
                    || "rebase".equals(sub) || "branch".equals(sub)) {
                    for (String branch : readLocalGitBranches(gitDir)) {
                        if (branch.startsWith(prefix) && !branch.equals(prefix)) {
                            results.add(new SuggestionItem(branch, branch.substring(prefix.length()),
                                "Git branch", SuggestionType.CONTEXT_GIT, 85, false));
                        }
                    }
                }
            }
        } else if (("pkg".equals(cmd) || "apt".equals(cmd) || "apt-get".equals(cmd)) && argIndex == 1) {
            collectMapMatches(PKG_SUBCOMMANDS, prefix, SuggestionType.SUBCOMMAND, 78, results);
        } else if (("pip".equals(cmd) || "pip3".equals(cmd)) && argIndex == 1) {
            collectMapMatches(PIP_SUBCOMMANDS, prefix, SuggestionType.CONTEXT_PYTHON, 78, results);
        } else if (("python".equals(cmd) || "python3".equals(cmd)) && argIndex == 1 && prefix.startsWith("-")) {
            collectMapMatches(PYTHON_FLAGS, prefix, SuggestionType.CONTEXT_PYTHON, 77, results);
        } else if ("npm".equals(cmd)) {
            if (argIndex == 1) {
                collectMapMatches(NPM_SUBCOMMANDS, prefix, SuggestionType.CONTEXT_NODE, 78, results);
            } else if (argIndex == 2 && "run".equals(input.completedTokens.get(1)) && cwd != null) {
                for (String script : readNpmScripts(new File(cwd, "package.json"))) {
                    if (script.startsWith(prefix) && !script.equals(prefix)) {
                        results.add(new SuggestionItem(script, script.substring(prefix.length()),
                            "npm script (package.json)", SuggestionType.CONTEXT_NODE, 84, false));
                    }
                }
            }
        }

        return trim(results, limit);
    }

    @Nullable
    public static File findGitDir(@Nullable String cwd) {
        if (cwd == null || cwd.trim().isEmpty()) return null;
        File dir = new File(cwd);
        int depth = 0;
        while (dir != null && depth < 12) {
            File git = new File(dir, ".git");
            if (git.isDirectory()) return git;
            dir = dir.getParentFile();
            depth++;
        }
        return null;
    }

    @NonNull
    public static List<String> readLocalGitBranches(@NonNull File gitDir) {
        File headsDir = new File(gitDir, "refs/heads");
        if (!headsDir.isDirectory()) return Collections.emptyList();
        List<String> branches = new ArrayList<>();
        collectBranchesRecursive(headsDir, "", branches);
        Collections.sort(branches);
        return branches;
    }

    private static void collectBranchesRecursive(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String rel = prefix.isEmpty() ? f.getName() : (prefix + "/" + f.getName());
            if (f.isDirectory()) {
                collectBranchesRecursive(f, rel, out);
            } else {
                out.add(rel);
            }
        }
    }

    @NonNull
    public static List<String> readNpmScripts(@NonNull File packageJsonFile) {
        if (!packageJsonFile.isFile() || !packageJsonFile.canRead()) return Collections.emptyList();
        List<String> scripts = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(packageJsonFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1 && baos.size() < 65536) {
                baos.write(buf, 0, n);
            }
            JSONObject obj = new JSONObject(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            JSONObject scriptsObj = obj.optJSONObject("scripts");
            if (scriptsObj != null) {
                Iterator<String> keys = scriptsObj.keys();
                while (keys.hasNext()) {
                    scripts.add(keys.next());
                }
            }
        } catch (Exception ignored) {}
        return scripts;
    }

    private static boolean hasPythonFilesOrManifest(@NonNull File dir) {
        if (!dir.isDirectory()) return false;
        if (new File(dir, "requirements.txt").isFile() || new File(dir, "pyproject.toml").isFile()) {
            return true;
        }
        File[] list = dir.listFiles();
        if (list == null) return false;
        int limit = Math.min(list.length, 60);
        for (int i = 0; i < limit; i++) {
            if (list[i].getName().endsWith(".py")) return true;
        }
        return false;
    }

    private static void collectMapMatches(
        Map<String, String> source,
        String prefix,
        SuggestionType type,
        int score,
        List<SuggestionItem> out
    ) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String candidate = entry.getKey();
            if (candidate.startsWith(prefix) && !candidate.equals(prefix)) {
                out.add(new SuggestionItem(
                    candidate,
                    candidate.substring(prefix.length()),
                    entry.getValue(),
                    type,
                    score,
                    false
                ));
            }
        }
    }

    private static List<SuggestionItem> trim(List<SuggestionItem> items, int limit) {
        items.sort((a, b) -> {
            int cmp = Integer.compare(b.getScore(), a.getScore());
            return cmp != 0 ? cmp : a.getDisplayText().compareTo(b.getDisplayText());
        });
        return items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items;
    }
}
