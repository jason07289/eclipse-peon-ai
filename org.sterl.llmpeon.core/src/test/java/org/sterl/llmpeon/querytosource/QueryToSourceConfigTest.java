package org.sterl.llmpeon.querytosource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;

class QueryToSourceConfigTest {

    @Test
    void defaultsProvideExamplePipeline() {
        var config = QueryToSourceConfig.defaults();
        assertThat(config.steps()).hasSize(5);
        assertThat(config.steps()).extracting(QueryStep::label)
                .containsExactly("표준 적용", "DAO 생성", "표준 검토", "Service 생성", "표준 검토");
        assertThat(config.steps().get(0).kind()).isEqualTo(StepKind.TRANSFORM);
        assertThat(config.steps().get(1).kind()).isEqualTo(StepKind.GENERATE);
        assertThat(config.steps().get(2).kind()).isEqualTo(StepKind.REVIEW);
    }

    @Test
    void normalizesNullValues() {
        var config = new QueryToSourceConfig(null);
        assertThat(config.steps()).isEmpty();

        var step = new QueryStep(null, null, null);
        assertThat(step.label()).isEmpty();
        assertThat(step.kind()).isEqualTo(StepKind.TRANSFORM);
        assertThat(step.prompt()).isEmpty();
    }

    @Test
    void stepsAreImmutableCopy() {
        var mutable = Arrays.asList(new QueryStep("DAO", StepKind.GENERATE, "gen-prompt"));
        var config = new QueryToSourceConfig(mutable);
        assertThat(config.steps()).hasSize(1);
        assertThat(config.steps().get(0).prompt()).isEqualTo("gen-prompt");
    }

    @Test
    void orDefaultsIfEmpty_returnsDefaultsWhenNoSteps() {
        assertThat(new QueryToSourceConfig(List.of()).orDefaultsIfEmpty().steps()).hasSize(5);
    }

    @Test
    void orDefaultsIfEmpty_keepsCustomPipeline() {
        var custom = new QueryToSourceConfig(List.of(new QueryStep("Only", StepKind.REVIEW, "r")));
        assertThat(custom.orDefaultsIfEmpty().steps()).hasSize(1);
        assertThat(custom.orDefaultsIfEmpty().steps().get(0).label()).isEqualTo("Only");
    }
}
