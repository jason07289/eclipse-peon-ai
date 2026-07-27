package org.sterl.llmpeon;

import java.util.Arrays;
import java.util.List;

public enum PeonMode {
    /** Read-only analyst: clarifies requirements and produces a User-Story (the WHAT). */
    PLAN("plan", true),
    /** Full-access developer: implements the plan (the HOW). */
    DEV("dev", true),
    /** Guided SQL query → source generation wizard driven by configurable command/skill prompts. */
    QUERY_TO_SOURCE("query-to-source", true),
    /** Orchestrated plan -> dev loop backed by .plan/overview.md. Temporarily hidden. */
    AGENT("Peon-Agent v0.1", false);
    
    private final String label;
    private final boolean visible;

    private PeonMode(String label, boolean visible) {
        this.label = label;
        this.visible = visible;
    }

    public String getLabel() {
        return label;
    }

    /** Whether this mode is offered to the user in the UI. */
    public boolean isVisible() {
        return visible;
    }

    /** Modes currently offered to the user, in declaration order. */
    public static List<PeonMode> visibleValues() {
        return Arrays.stream(values()).filter(PeonMode::isVisible).toList();
    }
}
