package org.sterl.llmpeon.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AiProviderReasoningEffortTest {

    @Test
    void qwenModelsUseXhighEffort() {
        assertEquals("xhigh", AiProvider.openAiReasoningEffortFor("qwen3.5-coder"));
        assertEquals("xhigh", AiProvider.openAiReasoningEffortFor("QWEN-3.6"));
    }

    @Test
    void nonQwenModelsKeepHighEffort() {
        assertEquals("high", AiProvider.openAiReasoningEffortFor("minimax-m1"));
        assertEquals("high", AiProvider.openAiReasoningEffortFor("gpt-5"));
        assertEquals("high", AiProvider.openAiReasoningEffortFor(null));
    }
}
