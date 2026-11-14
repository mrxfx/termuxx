package com.termux.app.ai.context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AIContextSnapshot {

    public static final class AttachedFile {
        @NonNull public final String name;
        @NonNull public final String path;
        @NonNull public final String content;

        public AttachedFile(@NonNull String name, @NonNull String path, @NonNull String content) {
            this.name = name;
            this.path = path;
            this.content = content;
        }
    }

    @Nullable public final String cwd;
    @Nullable public final String shellName;
    @Nullable public final String lastCommand;
    @Nullable public final String recentOutput;
    @NonNull public final List<String> installedTools;
    @NonNull public final List<AttachedFile> attachedFiles;

    public AIContextSnapshot(
        @Nullable String cwd,
        @Nullable String shellName,
        @Nullable String lastCommand,
        @Nullable String recentOutput,
        @Nullable List<String> installedTools,
        @Nullable List<AttachedFile> attachedFiles
    ) {
        this.cwd = cwd;
        this.shellName = shellName;
        this.lastCommand = lastCommand;
        this.recentOutput = recentOutput;
        this.installedTools = installedTools != null
            ? Collections.unmodifiableList(new ArrayList<>(installedTools))
            : Collections.emptyList();
        this.attachedFiles = attachedFiles != null
            ? Collections.unmodifiableList(new ArrayList<>(attachedFiles))
            : Collections.emptyList();
    }
}
