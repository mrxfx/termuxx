package com.termux.app.ai.ui;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight deterministic syntax highlighter for shell, Python, and general CLI code blocks.
 */
public final class BashSyntaxHighlighter {

    private static final int COLOR_COMMENT = 0xFF707070;
    private static final int COLOR_STRING = 0xFFD4D4D4;
    private static final int COLOR_KEYWORD = 0xFFFFFFFF;
    private static final int COLOR_VARIABLE = 0xFFFFB020;
    private static final int COLOR_FLAG = 0xFFA3A3A3;

    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?m)#.*$");
    private static final Pattern STRING_PATTERN = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*|\\$\\{[^}]+}");
    private static final Pattern FLAG_PATTERN = Pattern.compile("(?<=\\s)(--?[A-Za-z0-9][A-Za-z0-9\\-]*)");
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
        "\\b(?:if|then|else|elif|fi|for|while|do|done|case|esac|in|function|return|export|local|sudo|pkg|apt|git|python|python3|pip|npm|node|curl|wget|cd|ls|grep|find|chmod|chown|mkdir|rm|cp|mv|cat|echo|def|import|from|class|try|except)\\b"
    );

    private BashSyntaxHighlighter() {}

    @NonNull
    public static CharSequence highlight(@NonNull String code) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(code);
        applyPattern(ssb, KEYWORD_PATTERN, COLOR_KEYWORD);
        applyPattern(ssb, FLAG_PATTERN, COLOR_FLAG);
        applyPattern(ssb, VARIABLE_PATTERN, COLOR_VARIABLE);
        applyPattern(ssb, STRING_PATTERN, COLOR_STRING);
        applyPattern(ssb, COMMENT_PATTERN, COLOR_COMMENT);
        return ssb;
    }

    private static void applyPattern(SpannableStringBuilder ssb, Pattern pattern, int color) {
        Matcher m = pattern.matcher(ssb);
        while (m.find()) {
            ssb.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
