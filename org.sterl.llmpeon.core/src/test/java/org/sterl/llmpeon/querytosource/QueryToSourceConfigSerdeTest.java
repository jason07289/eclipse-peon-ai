package org.sterl.llmpeon.querytosource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON round-trip tests mirroring {@code QueryToSourcePreferenceInitializer} persistence.
 */
class QueryToSourceConfigSerdeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void roundTripsCustomPipeline() throws Exception {
        var original = new QueryToSourceConfig(List.of(
                new QueryStep("표준 적용", StepKind.TRANSFORM, "std-cmd"),
                new QueryStep("DAO 생성", StepKind.GENERATE, "dao-cmd"),
                new QueryStep("검토", StepKind.REVIEW, "review-cmd")));

        var json = MAPPER.writeValueAsString(original);
        var restored = MAPPER.readValue(json, QueryToSourceConfig.class);

        assertThat(restored.steps()).hasSize(3);
        assertThat(restored.steps()).extracting(QueryStep::label)
                .containsExactly("표준 적용", "DAO 생성", "검토");
        assertThat(restored.steps()).extracting(QueryStep::kind)
                .containsExactly(StepKind.TRANSFORM, StepKind.GENERATE, StepKind.REVIEW);
        assertThat(restored.steps()).extracting(QueryStep::prompt)
                .containsExactly("std-cmd", "dao-cmd", "review-cmd");
    }

    @Test
    void roundTripsReadOnlyFlag() throws Exception {
        var original = new QueryToSourceConfig(List.of(
                new QueryStep("검토", StepKind.REVIEW, "review-cmd", List.of(), "", "", true),
                new QueryStep("생성", StepKind.GENERATE, "gen-cmd")));

        var restored = MAPPER.readValue(MAPPER.writeValueAsString(original), QueryToSourceConfig.class);

        assertThat(restored.steps()).extracting(QueryStep::readOnly)
                .containsExactly(true, false);
    }

    @Test
    void jsonWithoutReadOnlyDefaultsToWritableStep() throws Exception {
        var config = MAPPER.readValue(
                "{\"steps\":[{\"label\":\"X\",\"kind\":\"REVIEW\",\"prompt\":\"p\"}]}",
                QueryToSourceConfig.class);

        assertThat(config.steps().get(0).readOnly()).isFalse();
    }

    @Test
    void preservesStepOrder() throws Exception {
        var steps = List.of(
                new QueryStep("A", StepKind.TRANSFORM, "p1"),
                new QueryStep("B", StepKind.GENERATE, "p2"),
                new QueryStep("C", StepKind.REVIEW, "p3"),
                new QueryStep("D", StepKind.GENERATE, "p4"));
        var json = MAPPER.writeValueAsString(new QueryToSourceConfig(steps));
        var restored = MAPPER.readValue(json, QueryToSourceConfig.class);
        assertThat(restored.steps()).extracting(QueryStep::label)
                .containsExactly("A", "B", "C", "D");
    }

    @Test
    void emptyStepsArrayDeserializesToEmptyPipeline() throws Exception {
        var config = MAPPER.readValue("{\"steps\":[]}", QueryToSourceConfig.class);
        assertThat(config.steps()).isEmpty();
        assertThat(config.orDefaultsIfEmpty().steps()).hasSize(5);
    }

    @Test
    void malformedJsonHandledLikePreferenceInitializerWouldUseDefaults() throws Exception {
        var config = MAPPER.readValue("{\"steps\":[{\"label\":\"X\",\"kind\":\"TRANSFORM\"}]}", QueryToSourceConfig.class);
        assertThat(config.steps()).hasSize(1);
        assertThat(config.steps().get(0).prompt()).isEmpty();
    }

    @Test
    void legacyJsonWithUnknownFieldsDeserializesToEmptySteps() throws Exception {
        var legacy = """
                {
                  "standardPrompt": "old-standard",
                  "generatePrompt": "old-generate",
                  "layers": [{"label": "service", "prompt": "svc"}]
                }""";
        var config = MAPPER.readValue(legacy, QueryToSourceConfig.class);
        assertThat(config.steps()).isEmpty();
    }

    @Test
    void legacyJsonOrDefaultsIfEmpty_yieldsDefaultPipeline() throws Exception {
        var legacy = """
                {"standardPrompt":"old","generatePrompt":"gen","layers":[]}
                """;
        var config = MAPPER.readValue(legacy, QueryToSourceConfig.class).orDefaultsIfEmpty();
        assertThat(config.steps()).hasSize(5);
        assertThat(config.steps().get(0).kind()).isEqualTo(StepKind.TRANSFORM);
    }
}
