package org.sterl.llmpeon.aimemory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkspaceGuideline(
        @JsonProperty("text")     String text,
        @JsonProperty("createdAt") String createdAt
) {
    @JsonCreator
    public WorkspaceGuideline {}
}
