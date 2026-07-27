package org.sterl.llmpeon.querytosource;

/**
 * Detects whether editor input is MyBatis/XML-style markup or plain SQL.
 */
public final class QueryFormat {

    private QueryFormat() {}

    /** Returns {@code true} when trimmed text starts with {@code '<'} (XML / mapper fragment). */
    public static boolean isXml(String text) {
        return text != null && !text.isBlank() && text.strip().startsWith("<");
    }

    /** Fence language tag for code blocks: {@code xml} or {@code sql}. */
    public static String fenceLang(String text) {
        return isXml(text) ? "xml" : "sql";
    }
}
