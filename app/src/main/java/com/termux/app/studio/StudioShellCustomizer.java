package com.termux.app.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Generates clean, idempotent, safe shell configuration modules for Terminal Studio:
 * - Prompt themes (Bash, Zsh, Fish)
 * - Syntax highlighting & autosuggestions (respecting Termux AI offline autocomplete precedence)
 * - Extra keys layouts in ~/.termux/termux.properties
 * - Safe escaped startup banners (never evaluates user text)
 * - Optional salted SHA-256 interactive shell prompt lock (disabled by default with security review)
 */
public class StudioShellCustomizer {

    public static final String MODULE_PROMPT_THEME = "PROMPT_THEME";
    public static final String MODULE_BANNER = "BANNER";
    public static final String MODULE_ZSH_SYNTAX = "ZSH_SYNTAX";
    public static final String MODULE_ZSH_AUTOSUGGEST = "ZSH_AUTOSUGGEST";
    public static final String MODULE_FISH_SYNTAX = "FISH_SYNTAX";
    public static final String MODULE_FISH_AUTOSUGGEST = "FISH_AUTOSUGGEST";
    public static final String MODULE_BASH_BLESH = "BASH_BLESH";
    public static final String MODULE_CYBER_LOCK = "CYBER_LOCK";

    public static final class PromptThemePreset {
        @NonNull public final String id;
        @NonNull public final String name;
        @NonNull public final String description;

        public PromptThemePreset(@NonNull String id, @NonNull String name, @NonNull String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    public static final class ExtraKeysPreset {
        @NonNull public final String id;
        @NonNull public final String name;
        @NonNull public final String description;
        @NonNull public final String propertyValue;

        public ExtraKeysPreset(
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull String propertyValue
        ) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.propertyValue = propertyValue;
        }
    }

    public enum BannerStyle {
        NONE("none", "Disabled"),
        MINIMAL_BOX("minimal_box", "Minimal Frame"),
        DOUBLE_RULE("double_rule", "Double Rule Banner"),
        COMPACT_TAG("compact_tag", "Compact Prompt Tag");

        public final String id;
        public final String label;

        BannerStyle(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private final ShellConfigManager mConfigManager;
    private final StudioPackageHelper mPackageHelper;

    public StudioShellCustomizer(@NonNull ShellConfigManager configManager, @NonNull StudioPackageHelper packageHelper) {
        mConfigManager = configManager;
        mPackageHelper = packageHelper;
    }

    @NonNull
    public static List<PromptThemePreset> getPromptThemes() {
        return Arrays.asList(
            new PromptThemePreset("clean_mono", "Clean Monochrome", "High-contrast two-tone user, path, and crisp prompt chevron"),
            new PromptThemePreset("dev_git", "Developer Git Prompt", "Shows current working directory plus active Git branch cleanly"),
            new PromptThemePreset("minimal_dir", "Minimal Path", "Compact single-line prompt showing current directory only"),
            new PromptThemePreset("two_line_box", "Two-Line Workspace", "Box-drawn header line above a clean input prompt")
        );
    }

    @NonNull
    public static List<ExtraKeysPreset> getExtraKeysPresets() {
        return Arrays.asList(
            new ExtraKeysPreset(
                "default",
                "Termux Standard (2 Rows)",
                "ESC, /, -, HOME, arrows, TAB, CTRL, ALT, PGUP/PGDN",
                "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"
            ),
            new ExtraKeysPreset(
                "developer",
                "Developer CLI (2 Rows)",
                "ESC, TAB, CTRL, ALT, /, |, -, ~ and full arrow navigation",
                "[['ESC','TAB','CTRL','ALT','/','|','-','~'], ['HOME','UP','END','LEFT','DOWN','RIGHT','PGDN']]"
            ),
            new ExtraKeysPreset(
                "vim_nav",
                "Vim & Editor Focus",
                "Quick ESC, colon, slash, % and directional navigation keys",
                "[['ESC','TAB','CTRL','/',':','%','HOME','END'], ['LEFT','DOWN','UP','RIGHT','PGUP','PGDN']]"
            ),
            new ExtraKeysPreset(
                "compact",
                "Compact Single Row",
                "Minimal height single row saving vertical screen space",
                "[['ESC','TAB','CTRL','ALT','-','UP','DOWN','LEFT','RIGHT']]"
            )
        );
    }

    public boolean applyPromptTheme(@NonNull String shellName, @NonNull String themeId) {
        File rc = mConfigManager.getRcFileForShell(shellName);
        String body = buildPromptThemeBlock(shellName, themeId);
        return mConfigManager.upsertModuleInRc(rc, MODULE_PROMPT_THEME, body);
    }

    public boolean clearPromptTheme(@NonNull String shellName) {
        return mConfigManager.removeModuleFromRc(mConfigManager.getRcFileForShell(shellName), MODULE_PROMPT_THEME);
    }

    @NonNull
    public static String buildPromptThemeBlock(@NonNull String shellName, @NonNull String themeId) {
        String sh = shellName.trim().toLowerCase(Locale.ROOT);
        if ("zsh".equals(sh)) {
            switch (themeId) {
                case "dev_git":
                    return "autoload -Uz vcs_info\n" +
                        "precmd() { vcs_info }\n" +
                        "zstyle ':vcs_info:git:*' formats ' (%b)'\n" +
                        "setopt PROMPT_SUBST\n" +
                        "PROMPT='%F{white}%B%~%b%f%F{246}${vcs_info_msg_0_}%f %F{white}>%f '";
                case "minimal_dir":
                    return "PROMPT='%F{white}%1~%f %F{248}❯%f '";
                case "two_line_box":
                    return "PROMPT=$'%F{244}┌─[%f%F{white}%B%~%b%f%F{244}]%f\\n%F{244}└─%f%F{white}$%f '";
                case "clean_mono":
                default:
                    return "PROMPT='%F{248}%n%f %F{white}%B%~%b%f %F{white}❯%f '";
            }
        }
        if ("fish".equals(sh)) {
            switch (themeId) {
                case "dev_git":
                    return "function fish_prompt\n" +
                        "    set_color white --bold\n" +
                        "    echo -n (prompt_pwd)\n" +
                        "    set_color 999999\n" +
                        "    echo -n (fish_git_prompt ' (%s)')\n" +
                        "    set_color white\n" +
                        "    echo -n ' ❯ '\n" +
                        "    set_color normal\n" +
                        "end";
                case "minimal_dir":
                    return "function fish_prompt\n" +
                        "    set_color white\n" +
                        "    echo -n (basename (prompt_pwd))' ❯ '\n" +
                        "    set_color normal\n" +
                        "end";
                case "two_line_box":
                    return "function fish_prompt\n" +
                        "    set_color 777777\n" +
                        "    echo -n '┌─['\n" +
                        "    set_color white\n" +
                        "    echo -n (prompt_pwd)\n" +
                        "    set_color 777777\n" +
                        "    echo ']'\n" +
                        "    echo -n '└─$ '\n" +
                        "    set_color normal\n" +
                        "end";
                case "clean_mono":
                default:
                    return "function fish_prompt\n" +
                        "    set_color white --bold\n" +
                        "    echo -n (prompt_pwd)' ❯ '\n" +
                        "    set_color normal\n" +
                        "end";
            }
        }
        // Bash
        switch (themeId) {
            case "dev_git":
                return "__termux_ai_git() { git rev-parse --abbrev-ref HEAD 2>/dev/null | sed 's/^/ (/;s/$/)/'; }\n" +
                    "PS1='\\[\\e[1;37m\\]\\w\\[\\e[0;37m\\]$(__termux_ai_git)\\[\\e[1;37m\\] \\$\\[\\e[0m\\] '";
            case "minimal_dir":
                return "PS1='\\[\\e[1;37m\\]\\W\\[\\e[0m\\] > '";
            case "two_line_box":
                return "PS1='\\[\\e[0;37m\\]┌─[\\[\\e[1;37m\\]\\w\\[\\e[0;37m\\]]\\n└─\\[\\e[1;37m\\]\\$\\[\\e[0m\\] '";
            case "clean_mono":
            default:
                return "PS1='\\[\\e[0;37m\\]\\u \\[\\e[1;37m\\]\\w\\[\\e[0m\\] \\$ '";
        }
    }

    public boolean setPluginEnabled(@NonNull String moduleId, boolean enable) {
        if (MODULE_ZSH_SYNTAX.equals(moduleId)) {
            File rc = mConfigManager.getZshRcFile();
            if (!enable) return mConfigManager.removeModuleFromRc(rc, moduleId);
            File script = mPackageHelper.findZshSyntaxHighlightingScript();
            if (script == null) return false;
            String body = "if [ -r " + shellSingleQuote(script.getAbsolutePath()) + " ]; then\n" +
                "  source " + shellSingleQuote(script.getAbsolutePath()) + "\n" +
                "fi";
            return mConfigManager.upsertModuleInRc(rc, moduleId, body);
        }
        if (MODULE_ZSH_AUTOSUGGEST.equals(moduleId)) {
            File rc = mConfigManager.getZshRcFile();
            if (!enable) return mConfigManager.removeModuleFromRc(rc, moduleId);
            File script = mPackageHelper.findZshAutosuggestionsScript();
            if (script == null) return false;
            String body = "if [ -r " + shellSingleQuote(script.getAbsolutePath()) + " ]; then\n" +
                "  source " + shellSingleQuote(script.getAbsolutePath()) + "\n" +
                "fi";
            return mConfigManager.upsertModuleInRc(rc, moduleId, body);
        }
        if (MODULE_FISH_SYNTAX.equals(moduleId)) {
            File rc = mConfigManager.getFishConfigFile();
            if (!enable) return mConfigManager.removeModuleFromRc(rc, moduleId);
            String body = "set -g fish_color_command white --bold\n" +
                "set -g fish_color_param d4d4d4\n" +
                "set -g fish_color_error ff5252\n" +
                "set -g fish_color_comment 707070";
            return mConfigManager.upsertModuleInRc(rc, moduleId, body);
        }
        if (MODULE_FISH_AUTOSUGGEST.equals(moduleId)) {
            File rc = mConfigManager.getFishConfigFile();
            if (!enable) return mConfigManager.removeModuleFromRc(rc, moduleId);
            String body = "set -g fish_autosuggestion_enabled 1\n" +
                "set -g fish_color_autosuggestion 707070";
            return mConfigManager.upsertModuleInRc(rc, moduleId, body);
        }
        if (MODULE_BASH_BLESH.equals(moduleId)) {
            File rc = mConfigManager.getBashRcFile();
            if (!enable) return mConfigManager.removeModuleFromRc(rc, moduleId);
            File ble = mPackageHelper.findBleshScript();
            if (ble == null) return false;
            String body = "if [[ $- == *i* ]] && [ -r " + shellSingleQuote(ble.getAbsolutePath()) + " ]; then\n" +
                "  source " + shellSingleQuote(ble.getAbsolutePath()) + "\n" +
                "fi";
            return mConfigManager.upsertModuleInRc(rc, moduleId, body);
        }
        return false;
    }

    /**
     * Generates a clean, non-evaluated ASCII startup banner block safe from shell command injection.
     */
    @NonNull
    public static String buildBannerPreview(@NonNull String rawUsername, @NonNull BannerStyle style) {
        String clean = sanitizeBannerText(rawUsername);
        if (clean.isEmpty() || style == BannerStyle.NONE) return "";
        switch (style) {
            case DOUBLE_RULE:
                return "========================================\n" +
                    "  TERMUX AI  ·  " + clean + "\n" +
                    "========================================";
            case COMPACT_TAG:
                return "[ Termux AI · " + clean + " ]";
            case MINIMAL_BOX:
            default:
                int innerWidth = Math.max(22, clean.length() + 14);
                StringBuilder border = new StringBuilder();
                for (int i = 0; i < innerWidth; i++) border.append('-');
                String line = "  Termux AI | " + clean;
                return "+" + border + "+\n| " + line + "\n+" + border + "+";
        }
    }

    public boolean applyBanner(@NonNull String shellName, @NonNull String rawUsername, @NonNull BannerStyle style) {
        File rc = mConfigManager.getRcFileForShell(shellName);
        if (style == BannerStyle.NONE || sanitizeBannerText(rawUsername).isEmpty()) {
            return mConfigManager.removeModuleFromRc(rc, MODULE_BANNER);
        }
        String preview = buildBannerPreview(rawUsername, style);
        String sh = shellName.trim().toLowerCase(Locale.ROOT);
        String block;
        if ("fish".equals(sh)) {
            // Fish: emit escaped literal lines using printf '%s\n'
            StringBuilder sb = new StringBuilder("if status is-interactive\n");
            for (String line : preview.split("\n")) {
                sb.append("    printf '%s\\n' ").append(shellSingleQuote(line)).append('\n');
            }
            sb.append("end");
            block = sb.toString();
        } else {
            // Single-quoted heredoc prevents ALL $(), backticks, and variable expansion
            block = "case $- in *i*)\n" +
                "cat << 'TERMUX_AI_BANNER_EOF'\n" +
                preview.replace("TERMUX_AI_BANNER_EOF", "TERMUX_AI_BANNER") + "\n" +
                "TERMUX_AI_BANNER_EOF\n" +
                ";; esac";
        }
        return mConfigManager.upsertModuleInRc(rc, MODULE_BANNER, block);
    }

    @NonNull
    public static String sanitizeBannerText(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 32; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    @NonNull
    public static String shellSingleQuote(@NonNull String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    // --- Optional Shell Prompt Lock (Salted SHA-256, disabled by default) ---

    @NonNull
    public File getLockMetadataFile() {
        return new File(mConfigManager.getTermuxDir(), "studio_lock.json");
    }

    public boolean isShellLockEnabled() {
        File meta = getLockMetadataFile();
        if (!meta.exists()) return false;
        String bash = mConfigManager.readFileSafely(mConfigManager.getBashRcFile());
        String zsh = mConfigManager.readFileSafely(mConfigManager.getZshRcFile());
        return ShellConfigManager.hasManagedBlock(bash, MODULE_CYBER_LOCK)
            || ShellConfigManager.hasManagedBlock(zsh, MODULE_CYBER_LOCK);
    }

    public boolean enableShellLock(@NonNull String password) {
        if (password.trim().isEmpty()) return false;
        try {
            byte[] saltBytes = new byte[16];
            new SecureRandom().nextBytes(saltBytes);
            String saltHex = toHex(saltBytes);
            String hashHex = sha256Hex(saltHex + password);

            JSONObject json = new JSONObject();
            json.put("algo", "SHA-256");
            json.put("salt", saltHex);
            json.put("hash", hashHex);
            if (!mConfigManager.writeFileAtomicallyWithBackup(getLockMetadataFile(), json.toString())) {
                return false;
            }

            String block = buildShellLockBlock(saltHex, hashHex);
            boolean okBash = mConfigManager.upsertModuleInRc(mConfigManager.getBashRcFile(), MODULE_CYBER_LOCK, block);
            boolean okZsh = true;
            if (mPackageHelper.isBinaryInstalled("zsh") || mConfigManager.getZshRcFile().exists()) {
                okZsh = mConfigManager.upsertModuleInRc(mConfigManager.getZshRcFile(), MODULE_CYBER_LOCK, block);
            }
            return okBash && okZsh;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean disableShellLock() {
        boolean b = mConfigManager.removeModuleFromRc(mConfigManager.getBashRcFile(), MODULE_CYBER_LOCK);
        boolean z = mConfigManager.removeModuleFromRc(mConfigManager.getZshRcFile(), MODULE_CYBER_LOCK);
        File meta = getLockMetadataFile();
        if (meta.exists()) {
            //noinspection ResultOfMethodCallIgnored
            meta.delete();
        }
        return b && z;
    }

    @NonNull
    public static String buildShellLockBlock(@NonNull String saltHex, @NonNull String hashHex) {
        return "if [ -t 0 ] && [ -z \"$SSH_CONNECTION\" ] && [ -z \"$TERMUX_SKIP_LOCK\" ]; then\n" +
            "  case $- in *i*)\n" +
            "    __attempts=0\n" +
            "    __unlocked=0\n" +
            "    while [ \"$__attempts\" -lt 3 ]; do\n" +
            "      printf 'Shell Passcode: '\n" +
            "      stty -echo 2>/dev/null\n" +
            "      IFS= read -r __input\n" +
            "      stty echo 2>/dev/null\n" +
            "      printf '\\n'\n" +
            "      __digest=$(printf '%s%s' " + shellSingleQuote(saltHex) + " \"$__input\" | sha256sum 2>/dev/null | cut -d' ' -f1)\n" +
            "      unset __input\n" +
            "      if [ \"$__digest\" = " + shellSingleQuote(hashHex) + " ]; then\n" +
            "        __unlocked=1\n" +
            "        break\n" +
            "      fi\n" +
            "      __attempts=$((__attempts + 1))\n" +
            "      printf 'Invalid passcode (%s/3)\\n' \"$__attempts\"\n" +
            "    done\n" +
            "    unset __attempts __digest\n" +
            "    if [ \"$__unlocked\" -ne 1 ]; then\n" +
            "      unset __unlocked\n" +
            "      exit 1\n" +
            "    fi\n" +
            "    unset __unlocked\n" +
            "  ;; esac\n" +
            "fi";
    }

    public static final String MODULE_SHELL_ALIASES = "SHELL_ALIASES";

    public boolean isDeveloperAliasesEnabled(@NonNull String shellName) {
        String content = mConfigManager.readFileSafely(mConfigManager.getRcFileForShell(shellName));
        return ShellConfigManager.hasManagedBlock(content, MODULE_SHELL_ALIASES);
    }

    public boolean applyDeveloperAliases(@NonNull String shellName) {
        File rc = mConfigManager.getRcFileForShell(shellName);
        String block = "alias ll='ls -la'\n"
            + "alias la='ls -A'\n"
            + "alias gs='git status -sb'\n"
            + "alias gd='git diff'\n"
            + "alias gl='git log --oneline -n 10'\n"
            + "alias py='python3'";
        return mConfigManager.upsertModuleInRc(rc, MODULE_SHELL_ALIASES, block);
    }

    public boolean clearDeveloperAliases(@NonNull String shellName) {
        File rc = mConfigManager.getRcFileForShell(shellName);
        return mConfigManager.removeModuleFromRc(rc, MODULE_SHELL_ALIASES);
    }

    @NonNull
    public static String sha256Hex(@NonNull String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
