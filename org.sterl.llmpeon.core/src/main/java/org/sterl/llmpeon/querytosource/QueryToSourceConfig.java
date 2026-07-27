package org.sterl.llmpeon.querytosource;

import java.util.List;

/**
 * Persisted configuration for the {@link org.sterl.llmpeon.PeonMode#QUERY_TO_SOURCE} wizard.
 *
 * <p>The pipeline is an ordered list of {@link QueryStep} entries. Each step is driven by a
 * command or skill prompt selected in settings. Peon only orchestrates execution; the concrete
 * rules live in those prompts.</p>
 *
 * @param steps ordered pipeline steps (label, kind, prompt name)
 * @param showStepNumbers whether to display step numbers in the wizard bar buttons
 */
public record QueryToSourceConfig(List<QueryStep> steps, boolean showStepNumbers) {

    /**
     * One configurable pipeline step.
     *
     * @param label  button label shown in the wizard bar
     * @param kind   determines post-processing after the AI call
     * @param prompt name of the command/skill executed for this step
     */
    public record QueryStep(String label, StepKind kind, String prompt) {
        public QueryStep {
            if (label == null) label = "";
            if (kind == null) kind = StepKind.TRANSFORM;
            if (prompt == null) prompt = "";
        }
    }

    public QueryToSourceConfig {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public QueryToSourceConfig(List<QueryStep> steps) {
        this(steps, false);
    }

    /** Returns {@link #defaults()} when this config has no steps (e.g. legacy JSON). */
    public QueryToSourceConfig orDefaultsIfEmpty() {
        return steps.isEmpty() ? defaults() : this;
    }

    /** Example pipeline matching a typical query → DAO → review → service → review flow. */
    public static QueryToSourceConfig defaults() {
        return new QueryToSourceConfig(List.of(
                new QueryStep("표준 적용", StepKind.TRANSFORM, ""),
                new QueryStep("DAO 생성", StepKind.GENERATE, ""),
                new QueryStep("표준 검토", StepKind.REVIEW, ""),
                new QueryStep("Service 생성", StepKind.GENERATE, ""),
                new QueryStep("표준 검토", StepKind.REVIEW, "")), false);
    }
}
