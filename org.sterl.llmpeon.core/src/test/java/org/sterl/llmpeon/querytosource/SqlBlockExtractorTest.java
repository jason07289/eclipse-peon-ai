package org.sterl.llmpeon.querytosource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlBlockExtractorTest {

    @Test
    void extractsSqlFencedBlock() {
        var text = "Here is the standardized query:\n\n```sql\nSELECT 1 FROM dual\n```\n\nDone.";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("SELECT 1 FROM dual");
    }

    @Test
    void extractsGenericFencedBlock() {
        var text = "```\nSELECT * FROM foo\n```";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("SELECT * FROM foo");
    }

    @Test
    void extractsFirstBlockWhenMultiplePresent() {
        var text = "```sql\nSELECT a\n```\nnext\n```sql\nSELECT b\n```";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("SELECT a");
    }

    @Test
    void fallsBackToWholeTextWhenNoFence() {
        assertThat(SqlBlockExtractor.extract("  SELECT 1  ")).isEqualTo("SELECT 1");
    }

    @Test
    void extractsXmlFencedBlock() {
        var text = "```xml\n<select id=\"findById\">SELECT 1</select>\n```";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("<select id=\"findById\">SELECT 1</select>");
    }

    @Test
    void prefersSqlFenceWhenBothSqlAndXmlPresent() {
        var text = "```sql\nSELECT 1\n```\n```xml\n<select/>\n```";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("SELECT 1");
    }

    @Test
    void stripsExtractedBlockContent() {
        var text = "```sql\n  SELECT 1  \n```";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("SELECT 1");
    }

    @Test
    void queryFormatDetectsXml() {
        assertThat(QueryFormat.isXml("<select id=\"x\">")).isTrue();
        assertThat(QueryFormat.isXml("  <mapper>")).isTrue();
        assertThat(QueryFormat.isXml("SELECT 1")).isFalse();
        assertThat(QueryFormat.fenceLang("<select/>")).isEqualTo("xml");
        assertThat(QueryFormat.fenceLang("SELECT 1")).isEqualTo("sql");
    }

    @Test
    void extractsXmlFenceCaseInsensitive() {
        var text = "```XML\n<select id=\"x\"/>\n```";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("<select id=\"x\"/>");
    }

    @Test
    void extractsSqlFenceCaseInsensitive() {
        var text = "```SQL\nSELECT 1\n```";
        assertThat(SqlBlockExtractor.extract(text)).isEqualTo("SELECT 1");
    }

    @Test
    void returnsNullForNullOrBlank() {
        assertThat(SqlBlockExtractor.extract(null)).isNull();
        assertThat(SqlBlockExtractor.extract("   ")).isNull();
    }
}
