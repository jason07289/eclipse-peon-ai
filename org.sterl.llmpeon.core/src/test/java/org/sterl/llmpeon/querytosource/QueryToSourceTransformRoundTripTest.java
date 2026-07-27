package org.sterl.llmpeon.querytosource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * End-to-end logic for TRANSFORM steps: AI response extraction must match input format expectations.
 */
class QueryToSourceTransformRoundTripTest {

    @Test
    void sqlTransformRoundTrip() {
        var original = "SELECT user_id FROM users WHERE status = 'A'";
        var aiResponse = """
                Applied standard formatting.

                ```sql
                SELECT user_id
                  FROM users
                 WHERE status = 'A'
                ```
                """;
        var extracted = SqlBlockExtractor.extract(aiResponse);
        assertThat(extracted).isNotEqualTo(original);
        assertThat(extracted).contains("SELECT user_id");
        assertThat(QueryFormat.isXml(extracted)).isFalse();
    }

    @Test
    void xmlTransformRoundTrip() {
        var original = "<select id=\"findById\">SELECT user_id FROM users WHERE user_id = #{id}</select>";
        var aiResponse = """
                ```xml
                <select id="findById">
                  SELECT user_id
                    FROM users
                   WHERE user_id = #{id}
                </select>
                ```
                """;
        var extracted = SqlBlockExtractor.extract(aiResponse);
        assertThat(extracted).startsWith("<select");
        assertThat(QueryFormat.isXml(extracted)).isTrue();
    }

    @Test
    void unlabeledFenceStillExtractsForTransform() {
        var aiResponse = "```\nSELECT 1\n```";
        assertThat(SqlBlockExtractor.extract(aiResponse)).isEqualTo("SELECT 1");
    }
}
