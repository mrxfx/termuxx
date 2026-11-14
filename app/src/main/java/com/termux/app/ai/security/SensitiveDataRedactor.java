package com.termux.app.ai.security;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts API keys, access tokens, private keys, and sensitive environment variable assignments
 * before terminal context or logs are sent to AI providers or persisted locally.
 */
public final class SensitiveDataRedactor {

    private static final String REDACTED = "[REDACTED]";

    private static final List<Pattern> SECRET_PATTERNS = Arrays.asList(
        // Google / Gemini API keys
        Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}"),
        // OpenRouter / OpenAI / Anthropic style keys
        Pattern.compile("sk-(?:or-v1-|proj-|ant-)?[A-Za-z0-9_\\-]{16,}"),
        // GitHub tokens
        Pattern.compile("(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{20,}"),
        // GitLab / HuggingFace / Slack tokens
        Pattern.compile("(?:glpat-|hf_|xox[baprs]-)[A-Za-z0-9_\\-]{12,}"),
        // AWS access keys
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        // Bearer tokens in curl headers / logs
        Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)([^\\s\"']+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(x-goog-api-key\\s*:\\s*)([^\\s\"']+)", Pattern.CASE_INSENSITIVE),
        // PEM private key blocks
        Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
        // URL embedded credentials https://user:pass@host
        Pattern.compile("(https?://[^/\\s:@]+:)([^@/\\s]+)(@)")
    );

    private static final Pattern ENV_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?im)^(\\s*(?:export\\s+)?[A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|PASSWD|PWD|CREDENTIAL|AUTH)[A-Z0-9_]*\\s*=\\s*)(\"[^\"]*\"|'[^']*'|[^\\s#]+)"
    );

    private SensitiveDataRedactor() {}

    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : "";
        }
        String result = input;
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher m = pattern.matcher(result);
            if (pattern.pattern().contains("(https?://")) {
                result = m.replaceAll("$1" + REDACTED + "$3");
            } else if (m.groupCount() >= 2) {
                result = m.replaceAll("$1" + REDACTED);
            } else {
                result = m.replaceAll(REDACTED);
            }
        }
        Matcher envMatcher = ENV_ASSIGNMENT_PATTERN.matcher(result);
        result = envMatcher.replaceAll("$1" + REDACTED);
        return result;
    }

    public static boolean containsLikelySecret(String input) {
        if (input == null || input.isEmpty()) return false;
        return !input.equals(redact(input));
    }

    public static String maskKeyForDisplay(String rawKey) {
        if (rawKey == null) return "";
        String trimmed = rawKey.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.length() <= 8) {
            return "••••" + trimmed.substring(Math.max(0, trimmed.length() - 2));
        }
        return trimmed.substring(0, 4) + "••••••••" + trimmed.substring(trimmed.length() - 4);
    }
}
