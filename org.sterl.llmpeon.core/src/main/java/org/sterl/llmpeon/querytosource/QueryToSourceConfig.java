package org.sterl.llmpeon.querytosource;

import java.util.List;

/**
 * Persisted configuration for the {@link org.sterl.llmpeon.PeonMode#QUERY_TO_SOURCE} wizard.
 *
 * <p>The pipeline is an ordered list of {@link QueryStep} entries. Each step is driven by a
 * command or skill prompt selected in settings. Peon only orchestrates execution; the concrete
 * rules live in those prompts.</p>
 *
 * @param steps ordered pipeline steps (label, kind, prompt name, required fields)
 * @param showStepNumbers whether to display step numbers in the wizard bar buttons
 */
public record QueryToSourceConfig(List<QueryStep> steps, boolean showStepNumbers) {

    /**
     * Input field with required/optional flag.
     *
     * @param label field name shown in UI and used in field-value block
     * @param required whether this field must be filled before step execution
     */
    public record StepField(String label, boolean required) {
        public StepField {
            if (label == null) label = "";
        }
    }

    /**
     * One configurable pipeline step.
     *
     * @param label  button label shown in the wizard bar
     * @param kind   determines post-processing after the AI call
     * @param prompt name of the command/skill executed for this step
     * @param fields list of input fields (required/optional) to be collected before step execution
     * @param instruction additional step-specific guidance (shown in chat as [Additional Instructions])
     * @param hint user-friendly guidance shown above chat input when this step is next
     */
    public record QueryStep(String label, StepKind kind, String prompt, List<StepField> fields,
            String instruction, String hint) {
        public QueryStep {
            if (label == null) label = "";
            if (kind == null) kind = StepKind.TRANSFORM;
            if (prompt == null) prompt = "";
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (instruction == null) instruction = "";
            if (hint == null) hint = "";
        }

        public QueryStep(String label, StepKind kind, String prompt) {
            this(label, kind, prompt, List.of(), "", "");
        }

        public QueryStep(String label, StepKind kind, String prompt, List<StepField> fields) {
            this(label, kind, prompt, fields, "", "");
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
