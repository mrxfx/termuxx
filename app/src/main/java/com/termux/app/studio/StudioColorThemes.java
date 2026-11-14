package com.termux.app.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Built-in Termux terminal color schemes (colors.properties) inspired by Termux-OS (GPL-3.0).
 * Applied safely via ShellConfigManager with automatic backup and live reload.
 */
public final class StudioColorThemes {

    public static final class ColorThemePreset {
        @NonNull public final String id;
        @NonNull public final String name;
        @NonNull public final String subtitle;
        @NonNull public final int[] swatches;
        @NonNull public final String propertiesContent;

        public ColorThemePreset(
            @NonNull String id,
            @NonNull String name,
            @NonNull String subtitle,
            @NonNull int[] swatches,
            @NonNull String propertiesContent
        ) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
            this.swatches = swatches;
            this.propertiesContent = propertiesContent;
        }
    }

    private static final List<ColorThemePreset> PRESETS = Arrays.asList(
        new ColorThemePreset(
            "default_dark",
            "Pure Dark / Monochrome",
            "Crisp high-contrast #000000 black background with clean ANSI accents",
            new int[]{0xFF000000, 0xFFFFFFFF, 0xFFA3A3A3, 0xFF4CAF50, 0xFFFFB020},
            "# Termux AI - Pure Dark Monochrome\n" +
            "background=#000000\n" +
            "foreground=#FFFFFF\n" +
            "cursor=#FFFFFF\n" +
            "color0=#111111\n" +
            "color1=#FF5252\n" +
            "color2=#4CAF50\n" +
            "color3=#FFB020\n" +
            "color4=#A3A3A3\n" +
            "color5=#D4D4D4\n" +
            "color6=#C0C0C0\n" +
            "color7=#E5E5E5\n" +
            "color8=#555555\n" +
            "color9=#FF7373\n" +
            "color10=#6FCF73\n" +
            "color11=#FFC65C\n" +
            "color12=#BDBDBD\n" +
            "color13=#EAEAEA\n" +
            "color14=#DEDEDE\n" +
            "color15=#FFFFFF\n"
        ),
        new ColorThemePreset(
            "gunmetal_neon",
            "Gunmetal Neon",
            "Dark gunmetal #12151A canvas with crisp cyan, emerald, and electric white highlights",
            new int[]{0xFF12151A, 0xFFE6EDF3, 0xFF00E5FF, 0xFF00E676, 0xFFFFEA00},
            "# Termux-OS Theme: Gunmetal Neon\n" +
            "background=#12151a\n" +
            "foreground=#e6edf3\n" +
            "cursor=#00e5ff\n" +
            "color0=#12151a\n" +
            "color1=#ff5252\n" +
            "color2=#00e676\n" +
            "color3=#ffea00\n" +
            "color4=#00b0ff\n" +
            "color5=#e040fb\n" +
            "color6=#00e5ff\n" +
            "color7=#e6edf3\n" +
            "color8=#484f58\n" +
            "color9=#ff7b72\n" +
            "color10=#56d364\n" +
            "color11=#f2cc60\n" +
            "color12=#58a6ff\n" +
            "color13=#f778ba\n" +
            "color14=#39c5cf\n" +
            "color15=#ffffff\n"
        ),
        new ColorThemePreset(
            "dracula",
            "Dracula",
            "Classic dark slate background with high-legibility syntax contrast",
            new int[]{0xFF282A36, 0xFFF8F8F2, 0xFF50FA7B, 0xFF8BE9FD, 0xFFFF79C6},
            "# Termux-OS Theme: Dracula\n" +
            "background=#282a36\n" +
            "foreground=#f8f8f2\n" +
            "cursor=#f8f8f2\n" +
            "color0=#21222c\n" +
            "color1=#ff5555\n" +
            "color2=#50fa7b\n" +
            "color3=#f1fa8c\n" +
            "color4=#bd93f9\n" +
            "color5=#ff79c6\n" +
            "color6=#8be9fd\n" +
            "color7=#f8f8f2\n" +
            "color8=#6272a4\n" +
            "color9=#ff6e6e\n" +
            "color10=#69ff94\n" +
            "color11=#ffffa5\n" +
            "color12=#d6acff\n" +
            "color13=#ff92df\n" +
            "color14=#a4ffff\n" +
            "color15=#ffffff\n"
        ),
        new ColorThemePreset(
            "nord",
            "Nord",
            "Arctic polar night palette tuned for long coding sessions",
            new int[]{0xFF2E3440, 0xFFD8DEE9, 0xFF88C0D0, 0xFFA3BE8C, 0xFFEBCB8B},
            "# Termux-OS Theme: Nord\n" +
            "background=#2e3440\n" +
            "foreground=#d8dee9\n" +
            "cursor=#d8dee9\n" +
            "color0=#3b4252\n" +
            "color1=#bf616a\n" +
            "color2=#a3be8c\n" +
            "color3=#ebcb8b\n" +
            "color4=#81a1c1\n" +
            "color5=#b48ead\n" +
            "color6=#88c0d0\n" +
            "color7=#e5e9f0\n" +
            "color8=#4c566a\n" +
            "color9=#bf616a\n" +
            "color10=#a3be8c\n" +
            "color11=#ebcb8b\n" +
            "color12=#81a1c1\n" +
            "color13=#b48ead\n" +
            "color14=#8fbcbb\n" +
            "color15=#eceff4\n"
        ),
        new ColorThemePreset(
            "monokai",
            "Monokai",
            "Warm charcoal surface with vivid green, amber, and coral highlights",
            new int[]{0xFF272822, 0xFFF8F8F2, 0xFFA6E22E, 0xFF66D9EF, 0xFFF92672},
            "# Termux-OS Theme: Monokai\n" +
            "background=#272822\n" +
            "foreground=#f8f8f2\n" +
            "cursor=#f8f8f2\n" +
            "color0=#272822\n" +
            "color1=#f92672\n" +
            "color2=#a6e22e\n" +
            "color3=#f4bf75\n" +
            "color4=#66d9ef\n" +
            "color5=#ae81ff\n" +
            "color6=#a1efe4\n" +
            "color7=#f8f8f2\n" +
            "color8=#75715e\n" +
            "color9=#f92672\n" +
            "color10=#a6e22e\n" +
            "color11=#f4bf75\n" +
            "color12=#66d9ef\n" +
            "color13=#ae81ff\n" +
            "color14=#a1efe4\n" +
            "color15=#f9f8f5\n"
        ),
        new ColorThemePreset(
            "solarized_dark",
            "Solarized Dark",
            "Precision low-fatigue teal slate scheme for readable logs and diffs",
            new int[]{0xFF002B36, 0xFF839496, 0xFF268BD2, 0xFF859900, 0xFFB58900},
            "# Termux-OS Theme: Solarized Dark\n" +
            "background=#002b36\n" +
            "foreground=#839496\n" +
            "cursor=#93a1a1\n" +
            "color0=#073642\n" +
            "color1=#dc322f\n" +
            "color2=#859900\n" +
            "color3=#b58900\n" +
            "color4=#268bd2\n" +
            "color5=#d33682\n" +
            "color6=#2aa198\n" +
            "color7=#eee8d5\n" +
            "color8=#002b36\n" +
            "color9=#cb4b16\n" +
            "color10=#586e75\n" +
            "color11=#657b83\n" +
            "color12=#839496\n" +
            "color13=#6c71c4\n" +
            "color14=#93a1a1\n" +
            "color15=#fdf6e3\n"
        )
    );

    private StudioColorThemes() {}

    @NonNull
    public static List<ColorThemePreset> getPresets() {
        return PRESETS;
    }

    @Nullable
    public static ColorThemePreset findById(@NonNull String id) {
        for (ColorThemePreset p : PRESETS) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    @Nullable
    public static ColorThemePreset detectActiveTheme(@NonNull ShellConfigManager manager) {
        String current = manager.readFileSafely(manager.getColorsPropertiesFile());
        if (current.trim().isEmpty()) return PRESETS.get(0);
        for (ColorThemePreset preset : PRESETS) {
            String firstHeader = preset.propertiesContent.split("\n")[0];
            if (current.contains(firstHeader)) {
                return preset;
            }
        }
        return null;
    }
}
