package org.sterl.llmpeon.querytosource;

/**
 * Kind of step in the Query-to-Source pipeline. Determines post-processing after the AI call.
 */
public enum StepKind {
    /** Transform query input (SQL or XML); result is reflected back into the editor. */
    TRANSFORM,
    /** Generate source files via edit tools; compile check runs afterward. */
    GENERATE,
    /** Review current query and generated sources; result stays in chat only. */
    REVIEW
}
