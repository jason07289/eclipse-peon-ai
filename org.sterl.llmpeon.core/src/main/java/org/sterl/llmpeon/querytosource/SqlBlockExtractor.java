package org.sterl.llmpeon.querytosource;

import java.util.regex.Pattern;

/**
 * Extracts fenced code blocks from an AI response for Query-to-Source TRANSFORM steps.
 * Supports {@code sql}, {@code xml}, and unlabeled fences; falls back to the whole text.
 */
public final class SqlBlockExtractor {

    private static final Pattern FENCED = Pattern.compile(
            "```(?:sql|xml)?\\s*\\n(.*?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private SqlBlockExtractor() {}

    /** Returns the first fenced block body, or {@code null} when the input is null/blank. */
    public static String extract(String aiText) {
        if (aiText == null || aiText.isBlank()) return null;
        var matcher = FENCED.matcher(aiText);
        if (matcher.find()) return matcher.group(1).strip();
        return aiText.strip();
    }
}
