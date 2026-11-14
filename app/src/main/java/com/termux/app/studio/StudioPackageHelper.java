package com.termux.app.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspects real binaries and Termux packages inside $PREFIX to keep Terminal Studio honest.
 * Never fakes availability and builds explicit user-approved pkg commands for installation.
 */
public class StudioPackageHelper {

    public static final class ShellStatus {
        @NonNull public final String name;
        @NonNull public final String packageName;
        public final boolean installed;
        public final boolean active;

        public ShellStatus(@NonNull String name, @NonNull String packageName, boolean installed, boolean active) {
            this.name = name;
            this.packageName = packageName;
            this.installed = installed;
            this.active = active;
        }
    }

    public static final class PluginStatus {
        @NonNull public final String id;
        @NonNull public final String title;
        @NonNull public final String shell;
        @Nullable public final String sourcePath;
        public final boolean installed;
        public final boolean enabledInRc;
        @Nullable public final String installCommand;

        public PluginStatus(
            @NonNull String id,
            @NonNull String title,
            @NonNull String shell,
            @Nullable String sourcePath,
            boolean installed,
            boolean enabledInRc,
            @Nullable String installCommand
        ) {
            this.id = id;
            this.title = title;
            this.shell = shell;
            this.sourcePath = sourcePath;
            this.installed = installed;
            this.enabledInRc = enabledInRc;
            this.installCommand = installCommand;
        }
    }

    private final ShellConfigManager mConfigManager;

    public StudioPackageHelper(@NonNull ShellConfigManager configManager) {
        mConfigManager = configManager;
    }

    public boolean isBinaryInstalled(@NonNull String binaryName) {
        File bin = new File(new File(mConfigManager.getPrefixDir(), "bin"), binaryName);
        return bin.exists() && bin.canExecute();
    }

    @NonNull
    public List<ShellStatus> inspectShells() {
        String active = mConfigManager.detectActiveDefaultShell();
        List<ShellStatus> list = new ArrayList<>();
        list.add(new ShellStatus("bash", "bash", isBinaryInstalled("bash"), "bash".equals(active)));
        list.add(new ShellStatus("zsh", "zsh", isBinaryInstalled("zsh"), "zsh".equals(active)));
        list.add(new ShellStatus("fish", "fish", isBinaryInstalled("fish"), "fish".equals(active)));
        return list;
    }

    @Nullable
    public File findZshSyntaxHighlightingScript() {
        File prefix = mConfigManager.getPrefixDir();
        File home = mConfigManager.getHomeDir();
        File[] candidates = new File[]{
            new File(prefix, "share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh"),
            new File(home, ".zsh/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh"),
            new File(home, ".oh-my-zsh/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh")
        };
        for (File f : candidates) {
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }

    @Nullable
    public File findZshAutosuggestionsScript() {
        File prefix = mConfigManager.getPrefixDir();
        File home = mConfigManager.getHomeDir();
        File[] candidates = new File[]{
            new File(prefix, "share/zsh-autosuggestions/zsh-autosuggestions.zsh"),
            new File(home, ".zsh/zsh-autosuggestions/zsh-autosuggestions.zsh"),
            new File(home, ".oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh")
        };
        for (File f : candidates) {
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }

    @Nullable
    public File findBleshScript() {
        File prefix = mConfigManager.getPrefixDir();
        File home = mConfigManager.getHomeDir();
        File[] candidates = new File[]{
            new File(prefix, "share/blesh/ble.sh"),
            new File(home, ".local/share/blesh/ble.sh")
        };
        for (File f : candidates) {
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }

    @NonNull
    public List<PluginStatus> inspectSyntaxHighlighting() {
        List<PluginStatus> items = new ArrayList<>();
        String zshRc = mConfigManager.readFileSafely(mConfigManager.getZshRcFile());
        String bashRc = mConfigManager.readFileSafely(mConfigManager.getBashRcFile());
        String fishRc = mConfigManager.readFileSafely(mConfigManager.getFishConfigFile());

        File zshSyntax = findZshSyntaxHighlightingScript();
        items.add(new PluginStatus(
            StudioShellCustomizer.MODULE_ZSH_SYNTAX,
            "Zsh Syntax Highlighting",
            "zsh",
            zshSyntax != null ? zshSyntax.getAbsolutePath() : null,
            zshSyntax != null,
            ShellConfigManager.hasManagedBlock(zshRc, StudioShellCustomizer.MODULE_ZSH_SYNTAX),
            "pkg update -y && pkg install -y zsh zsh-syntax-highlighting"
        ));

        boolean fishInstalled = isBinaryInstalled("fish");
        items.add(new PluginStatus(
            StudioShellCustomizer.MODULE_FISH_SYNTAX,
            "Fish Native Highlighting",
            "fish",
            fishInstalled ? "Built into fish shell" : null,
            fishInstalled,
            ShellConfigManager.hasManagedBlock(fishRc, StudioShellCustomizer.MODULE_FISH_SYNTAX),
            "pkg update -y && pkg install -y fish"
        ));

        File ble = findBleshScript();
        items.add(new PluginStatus(
            StudioShellCustomizer.MODULE_BASH_BLESH,
            "Bash Line Editor (ble.sh)",
            "bash",
            ble != null ? ble.getAbsolutePath() : null,
            ble != null,
            ShellConfigManager.hasManagedBlock(bashRc, StudioShellCustomizer.MODULE_BASH_BLESH),
            "pkg update -y && pkg install -y ble.sh"
        ));
        return items;
    }

    @NonNull
    public List<PluginStatus> inspectAutosuggestions() {
        List<PluginStatus> items = new ArrayList<>();
        String zshRc = mConfigManager.readFileSafely(mConfigManager.getZshRcFile());
        String fishRc = mConfigManager.readFileSafely(mConfigManager.getFishConfigFile());
        String bashRc = mConfigManager.readFileSafely(mConfigManager.getBashRcFile());

        File zshAuto = findZshAutosuggestionsScript();
        items.add(new PluginStatus(
            StudioShellCustomizer.MODULE_ZSH_AUTOSUGGEST,
            "Zsh Autosuggestions",
            "zsh",
            zshAuto != null ? zshAuto.getAbsolutePath() : null,
            zshAuto != null,
            ShellConfigManager.hasManagedBlock(zshRc, StudioShellCustomizer.MODULE_ZSH_AUTOSUGGEST),
            "pkg update -y && pkg install -y zsh zsh-autosuggestions"
        ));

        boolean fishInstalled = isBinaryInstalled("fish");
        items.add(new PluginStatus(
            StudioShellCustomizer.MODULE_FISH_AUTOSUGGEST,
            "Fish Inline Autosuggestions",
            "fish",
            fishInstalled ? "Built into fish shell" : null,
            fishInstalled,
            ShellConfigManager.hasManagedBlock(fishRc, StudioShellCustomizer.MODULE_FISH_AUTOSUGGEST),
            "pkg update -y && pkg install -y fish"
        ));

        File ble = findBleshScript();
        items.add(new PluginStatus(
            StudioShellCustomizer.MODULE_BASH_BLESH,
            "Bash Autosuggestions (ble.sh)",
            "bash",
            ble != null ? ble.getAbsolutePath() : null,
            ble != null,
            ShellConfigManager.hasManagedBlock(bashRc, StudioShellCustomizer.MODULE_BASH_BLESH),
            "pkg update -y && pkg install -y ble.sh"
        ));
        return items;
    }

    @NonNull
    public static String buildInstallCommandForShell(@NonNull String shellName) {
        return "pkg update -y && pkg install -y " + shellName;
    }
}
