package org.sterl.llmpeon.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.sterl.llmpeon.AiQueryToSourceService;
import org.sterl.llmpeon.ai.ConfiguredModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.parts.querytosource.QueryToSourceModeService;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;
import org.sterl.llmpeon.querytosource.StepKind;
import org.sterl.llmpeon.tool.ToolService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * Functional tests for {@link QueryToSourceModeService}: message builders, pending-step lifecycle,
 * and project requirements per step kind.
 */
public class QueryToSourceModeServiceTest {

    private QueryToSourceModeService modeService;
    private AiQueryToSourceService chatService;

    @Before
    public void setUp() {
        chatService = new AiQueryToSourceService(
                new ConfiguredModel(LlmConfig.newOllama("test")),
                new ToolService());
        modeService = new QueryToSourceModeService(chatService);
    }

    @Test
    public void messageFor_includesUserTextAsIs() {
        var step = new QueryStep("표준 적용", StepKind.TRANSFORM, "std");
        var msg = modeService.messageFor(step, "  SELECT 1  \n");
        assertContains(msg, "[Query-to-Source: 표준 적용]");
        assertContains(msg, "SELECT 1");
        assertFalse(msg.contains("```sql"));
    }

    @Test
    public void messageFor_acceptsFreeFormNotes() {
        var step = new QueryStep("DAO 생성", StepKind.GENERATE, "dao");
        var msg = modeService.messageFor(step, "Use package com.example.dao and table USERS");
        assertContains(msg, "Use package com.example.dao");
        assertContains(msg, "Generate the sources");
    }

    @Test
    public void messageFor_usesFallbackWhenInputBlank() {
        var step = new QueryStep("검토", StepKind.REVIEW, "rev");
        var msg = modeService.messageFor(step, "   ");
        assertContains(msg, QueryToSourceModeService.FALLBACK_INPUT);
    }

    @Test
    public void markPending_overwritesPreviousPendingStep() {
        var first = new QueryStep("A", StepKind.TRANSFORM, "a");
        var second = new QueryStep("B", StepKind.GENERATE, "b");
        modeService.markPending(0, first);
        modeService.markPending(1, second);
        assertEquals(1, modeService.getPendingStepIndex());
        assertEquals(second, modeService.consumePendingStep());
    }

    @Test
    public void messageFor_dispatchesByStepKind() {
        var transform = new QueryStep("T", StepKind.TRANSFORM, "p1");
        var generate = new QueryStep("G", StepKind.GENERATE, "p2");
        var review = new QueryStep("R", StepKind.REVIEW, "p3");

        assertContains(modeService.messageFor(transform, "input"), "Apply the step instructions");
        assertContains(modeService.messageFor(generate, "input"), "Generate the sources");
        assertContains(modeService.messageFor(review, "input"), "Review against");
    }

    @Test
    public void pendingStep_isConsumedOnce() {
        var step = new QueryStep("DAO", StepKind.GENERATE, "dao");
        modeService.markPending(2, step);
        assertEquals(2, modeService.getPendingStepIndex());
        assertEquals(step, modeService.consumePendingStep());
        assertEquals(-1, modeService.getPendingStepIndex());
        assertNull(modeService.consumePendingStep());
    }

    @Test
    public void markStepCompleted_tracksHighestCompletedIndex() {
        assertEquals(-1, modeService.getCompletedStepIndex());
        modeService.markStepCompleted(0);
        assertEquals(0, modeService.getCompletedStepIndex());
        assertTrue(modeService.isStepCompleted(0));
        assertFalse(modeService.isStepCompleted(1));

        modeService.markStepCompleted(2);
        assertEquals(2, modeService.getCompletedStepIndex());
        assertTrue(modeService.isStepCompleted(1));
        assertTrue(modeService.isStepCompleted(2));
    }

    @Test
    public void markStepCompleted_onlyMovesForward() {
        modeService.markStepCompleted(3);
        modeService.markStepCompleted(1);
        assertEquals(3, modeService.getCompletedStepIndex());
    }

    @Test
    public void markStepCompleted_ignoresNegativeIndex() {
        modeService.markStepCompleted(-1);
        assertEquals(-1, modeService.getCompletedStepIndex());
    }

    @Test
    public void reset_clearsCompletedStepIndex() {
        modeService.markStepCompleted(2);
        modeService.reset();
        assertEquals(-1, modeService.getCompletedStepIndex());
    }

    @Test
    public void setConfig_clearsCompletedStepIndex() {
        modeService.markStepCompleted(1);
        modeService.setConfig(QueryToSourceConfig.defaults());
        assertEquals(-1, modeService.getCompletedStepIndex());
    }

    @Test
    public void reset_clearsChatMemoryAndPendingStep() {
        chatService.addMessage(UserMessage.from("q"));
        chatService.addMessage(AiMessage.from("a"));
        modeService.markPending(0, new QueryStep("x", StepKind.TRANSFORM, "p"));

        modeService.reset();

        assertTrue(chatService.getMessages().isEmpty());
        assertNull(modeService.consumePendingStep());
    }

    @Test
    public void requiresProject_onlyForGenerateAndReview() {
        assertFalse(QueryToSourceModeService.requiresProject(
                new QueryStep("표준", StepKind.TRANSFORM, "p")));
        assertTrue(QueryToSourceModeService.requiresProject(
                new QueryStep("DAO", StepKind.GENERATE, "p")));
        assertTrue(QueryToSourceModeService.requiresProject(
                new QueryStep("검토", StepKind.REVIEW, "p")));
        assertFalse(QueryToSourceModeService.requiresProject(null));
    }

    @Test
    public void setConfig_nullFallsBackToDefaults() {
        modeService.setConfig(null);
        assertEquals(5, modeService.getConfig().steps().size());
        assertEquals("표준 적용", modeService.getConfig().steps().get(0).label());
    }

    @Test
    public void setConfig_customPipelineIsRetained() {
        var custom = new QueryToSourceConfig(java.util.List.of(
                new QueryStep("Step A", StepKind.TRANSFORM, "a"),
                new QueryStep("Step B", StepKind.REVIEW, "b")));
        modeService.setConfig(custom);
        assertEquals(2, modeService.getConfig().steps().size());
        assertEquals(StepKind.REVIEW, modeService.getConfig().steps().get(1).kind());
    }

    private static void assertContains(String haystack, String needle) {
        assertNotNull(haystack);
        assertTrue("Expected:\n" + haystack + "\nto contain:\n" + needle,
                haystack.contains(needle));
    }
}
