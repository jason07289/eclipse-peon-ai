package org.sterl.llmpeon.parts.querytosource;

import java.util.concurrent.atomic.AtomicInteger;

import org.sterl.llmpeon.AiQueryToSourceService;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;
import org.sterl.llmpeon.querytosource.StepKind;

/**
 * Orchestrator for the {@link org.sterl.llmpeon.PeonMode#QUERY_TO_SOURCE} step pipeline.
 *
 * <p>Holds configuration and the pending step in flight. Step messages wrap the user's chat input
 * (free-form text, SQL, XML, selections, etc.). Post-processing after each call is handled in the
 * chat view.</p>
 *
 * <p>Also remembers how far the user progressed in the current session via
 * {@link #markStepCompleted(int)} / {@link #getCompletedStepIndex()}, so already-run steps can be
 * disabled in the wizard bar. Progress is session-scoped: it resets on {@link #reset()} (mode
 * switch or "Clear") and whenever the pipeline configuration changes.</p>
 */
public class QueryToSourceModeService {

    /** Sentinel meaning "no step completed yet" / "no pending step index known". */
    private static final int NONE = -1;

    public static final String FALLBACK_INPUT =
            "(No new input in this message — use the conversation history and workspace context.)";

    /** Prepended to the step message when the step is configured read-only. */
    public static final String READ_ONLY_NOTICE =
            "[Read-only step] File edit and shell tools are disabled for this step. "
            + "Report findings in the chat only.";

    private final AiQueryToSourceService service;

    private volatile QueryToSourceConfig config = QueryToSourceConfig.defaults();
    private volatile QueryStep pendingStep;
    private volatile int pendingStepIndex = NONE;
    private final AtomicInteger completedStepIndex = new AtomicInteger(NONE);

    public QueryToSourceModeService(AiQueryToSourceService service) {
        this.service = service;
    }

    public AiQueryToSourceService getService() {
        return service;
    }

    public QueryToSourceConfig getConfig() {
        return config;
    }

    /** Replacing the pipeline invalidates any prior step indices, so progress is reset too. */
    public void setConfig(QueryToSourceConfig config) {
        this.config = config == null ? QueryToSourceConfig.defaults() : config;
        completedStepIndex.set(NONE);
        pendingStep = null;
        pendingStepIndex = NONE;
    }

    public void reset() {
        service.clear();
        pendingStep = null;
        pendingStepIndex = NONE;
        completedStepIndex.set(NONE);
    }

    /** Marks {@code step} (at its {@code index} in {@link #getConfig()}) as in flight. */
    public void markPending(int index, QueryStep step) {
        this.pendingStep = step;
        this.pendingStepIndex = index;
    }

    /** Index of the currently pending step, or {@code -1} when unknown/none. Does not consume it. */
    public int getPendingStepIndex() {
        return pendingStepIndex;
    }

    public QueryStep consumePendingStep() {
        var s = pendingStep;
        pendingStep = null;
        pendingStepIndex = NONE;
        return s;
    }

    /**
     * Records that the step at {@code index} finished successfully. Progress only moves forward:
     * re-running an earlier step (or an unknown/negative index) never lowers it.
     */
    public void markStepCompleted(int index) {
        if (index < 0) return;
        completedStepIndex.accumulateAndGet(index, Math::max);
    }

    /** Highest pipeline step index completed so far this session, or {@code -1} when none. */
    public int getCompletedStepIndex() {
        return completedStepIndex.get();
    }

    /** Whether the step at {@code index} was already completed this session. */
    public boolean isStepCompleted(int index) {
        return index >= 0 && index <= completedStepIndex.get();
    }

    public String messageFor(QueryStep step, String userText) {
        return messageFor(step, userText, java.util.Map.of());
    }

    public String messageFor(QueryStep step, String userText, java.util.Map<String, String> fieldValues) {
        var input = formatInput(userText);
        var label = step.label().isBlank() ? step.kind().name() : step.label();
        var fieldsBlock = formatFieldsBlock(fieldValues);
        var instructionBlock = step.instruction().isBlank()
                ? "" : "[Additional Instructions]\n" + step.instruction().strip();
        var parts = new java.util.ArrayList<String>();
        // The edit tools are already withheld from the request; say so, otherwise the model keeps
        // reaching for tools it cannot see and reports a permission problem instead of an answer.
        if (step.readOnly()) parts.add(READ_ONLY_NOTICE);
        if (!fieldsBlock.isEmpty()) parts.add(fieldsBlock);
        if (!instructionBlock.isEmpty()) parts.add(instructionBlock);
        parts.add(input);
        var fullInput = String.join("\n\n", parts);
        return switch (step.kind()) {
            case TRANSFORM -> """
                    [Query-to-Source: %s]
                    Apply the step instructions to the following input.

                    %s""".formatted(label, fullInput);
            case GENERATE -> """
                    [Query-to-Source: %s]
                    Generate the sources for this step following the instructions exactly. \
                    Use the input below and any files or context from earlier in this session.

                    %s""".formatted(label, fullInput);
            case REVIEW -> """
                    [Query-to-Source: %s]
                    Review against the step instructions. Report problems and suggested fixes in the \
                    chat; do not modify files unless the instructions explicitly require it.

                    %s""".formatted(label, fullInput);
        };
    }

    private static String formatFieldsBlock(java.util.Map<String, String> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var entry : fieldValues.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    public java.util.Optional<QueryStep> getNextStep() {
        var steps = config.steps();
        int next = getCompletedStepIndex() + 1;
        return (next >= 0 && next < steps.size()) ? java.util.Optional.of(steps.get(next)) : java.util.Optional.empty();
    }

    static String formatInput(String userText) {
        if (userText == null || userText.isBlank()) return FALLBACK_INPUT;
        return userText.strip();
    }

    /** GENERATE and REVIEW need workspace file access. */
    public static boolean requiresProject(QueryStep step) {
        return step != null && step.kind() != StepKind.TRANSFORM;
    }
}
