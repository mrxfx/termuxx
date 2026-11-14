package com.termux.app.autocomplete;

import androidx.annotation.NonNull;

public enum SuggestionType {
    HISTORY("HIST"),
    COMMAND("CMD"),
    DIRECTORY("DIR"),
    FILE("FILE"),
    PACKAGE("PKG"),
    CONTEXT_GIT("GIT"),
    CONTEXT_PYTHON("PY"),
    CONTEXT_NODE("NODE"),
    SUBCOMMAND("ARG");

    private final String mBadge;

    SuggestionType(@NonNull String badge) {
        mBadge = badge;
    }

    @NonNull
    public String getBadge() {
        return mBadge;
    }
}
