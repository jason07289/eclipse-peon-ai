package org.sterl.llmpeon.querytosource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class QueryFormatTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "<select id=\"x\">SELECT 1</select>",
            "  <mapper namespace=\"ns\">",
            "<!-- comment -->\nSELECT 1",
            "<?xml version=\"1.0\"?><mapper/>"
    })
    void detectsXmlInputs(String input) {
        assertThat(QueryFormat.isXml(input)).isTrue();
        assertThat(QueryFormat.fenceLang(input)).isEqualTo("xml");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1 FROM dual",
            "  select * from users",
            "-- comment\nSELECT 1",
            "WITH cte AS (SELECT 1) SELECT * FROM cte"
    })
    void detectsSqlInputs(String input) {
        assertThat(QueryFormat.isXml(input)).isFalse();
        assertThat(QueryFormat.fenceLang(input)).isEqualTo("sql");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\n\t  " })
    void blankInputIsNotXml(String input) {
        assertThat(QueryFormat.isXml(input)).isFalse();
        assertThat(QueryFormat.fenceLang(input)).isEqualTo("sql");
    }
}
