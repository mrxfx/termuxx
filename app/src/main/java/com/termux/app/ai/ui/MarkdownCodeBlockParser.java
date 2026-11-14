package com.termux.app.ai.ui;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits Markdown text into prose and fenced code block segments cleanly,
 * including unclosed trailing code fences during live SSE streaming.
 */
public final class MarkdownCodeBlockParser {

    public static final class Segment {
        public enum Kind {
            PROSE,
            CODE_BLOCK
        }

        @NonNull public final Kind kind;
        @NonNull public final String content;
        @NonNull public final String language;

        public Segment(@NonNull Kind kind, @NonNull String content, @NonNull String language) {
            this.kind = kind;
            this.content = content;
            this.language = language;
        }
    }

    private static final Pattern FENCE_OPEN_PATTERN = Pattern.compile("^```([A-Za-z0-9_+\\-.]*)[ \\t]*$");

    private MarkdownCodeBlockParser() {}

    @NonNull
    public static List<Segment> parse(@NonNull String markdown) {
        List<Segment> segments = new ArrayList<>();
        if (markdown.isEmpty()) return segments;

        String[] lines = markdown.split("\n", -1);
        StringBuilder proseBuf = new StringBuilder();
        StringBuilder codeBuf = new StringBuilder();
        boolean inCodeBlock = false;
        String currentLang = "";

        for (String line : lines) {
            String trimmedRight = line.replaceFirst("\\s+$", "");
            if (!inCodeBlock) {
                Matcher open = FENCE_OPEN_PATTERN.matcher(trimmedRight);
                if (open.matches()) {
                    String prose = proseBuf.toString().trim();
                    if (!prose.isEmpty()) {
                        segments.add(new Segment(Segment.Kind.PROSE, prose, ""));
                    }
                    proseBuf.setLength(0);
                    inCodeBlock = true;
                    currentLang = open.group(1) != null ? open.group(1) : "";
                    codeBuf.setLength(0);
                } else {
                    if (proseBuf.length() > 0) proseBuf.append('\n');
                    proseBuf.append(line);
                }
            } else {
                if ("```".equals(trimmedRight)) {
                    String code = trimTrailingNewlines(codeBuf.toString());
                    if (!code.isEmpty()) {
                        segments.add(new Segment(Segment.Kind.CODE_BLOCK, code, currentLang.isEmpty() ? "sh" : currentLang));
                    }
                    codeBuf.setLength(0);
                    currentLang = "";
                    inCodeBlock = false;
                } else {
                    if (codeBuf.length() > 0) codeBuf.append('\n');
                    codeBuf.append(line);
                }
            }
        }

        if (inCodeBlock) {
            String code = trimTrailingNewlines(codeBuf.toString());
            if (!code.isEmpty()) {
                segments.add(new Segment(Segment.Kind.CODE_BLOCK, code, currentLang.isEmpty() ? "sh" : currentLang));
            }
        } else {
            String prose = proseBuf.toString().trim();
            if (!prose.isEmpty()) {
                segments.add(new Segment(Segment.Kind.PROSE, prose, ""));
            }
        }

        return segments;
    }

    private static String trimTrailingNewlines(String s) {
        return s.replaceFirst("\\s+$", "");
    }
}
