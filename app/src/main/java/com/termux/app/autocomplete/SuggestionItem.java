package com.termux.app.autocomplete;

import androidx.annotation.NonNull;

import java.util.Objects;

public final class SuggestionItem {

    private final String mDisplayText;
    private final String mInsertSuffix;
    private final String mDescription;
    private final SuggestionType mType;
    private final int mScore;
    private final boolean mFullLineReplacement;

    public SuggestionItem(
        @NonNull String displayText,
        @NonNull String insertSuffix,
        @NonNull String description,
        @NonNull SuggestionType type,
        int score,
        boolean fullLineReplacement
    ) {
        mDisplayText = displayText;
        mInsertSuffix = insertSuffix;
        mDescription = description;
        mType = type;
        mScore = score;
        mFullLineReplacement = fullLineReplacement;
    }

    @NonNull
    public String getDisplayText() {
        return mDisplayText;
    }

    /**
     * Exact suffix characters to write into PTY to complete the user's current token or line.
     */
    @NonNull
    public String getInsertSuffix() {
        return mInsertSuffix;
    }

    @NonNull
    public String getDescription() {
        return mDescription;
    }

    @NonNull
    public SuggestionType getType() {
        return mType;
    }

    public int getScore() {
        return mScore;
    }

    public boolean isFullLineReplacement() {
        return mFullLineReplacement;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SuggestionItem)) return false;
        SuggestionItem that = (SuggestionItem) o;
        return Objects.equals(mDisplayText, that.mDisplayText) && mType == that.mType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayText, mType);
    }
}
