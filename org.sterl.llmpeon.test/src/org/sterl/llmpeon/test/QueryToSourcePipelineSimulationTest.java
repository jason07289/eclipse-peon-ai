package org.sterl.llmpeon.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.sterl.llmpeon.AiQueryToSourceService;
import org.sterl.llmpeon.ai.ConfiguredModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.parts.querytosource.QueryToSourceModeService;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;
import org.sterl.llmpeon.querytosource.StepKind;
import org.sterl.llmpeon.tool.ToolService;

import dev.langchain4j.data.message.AiMessage;

/**
 * Simulates pipeline steps using chat-style input and conversation memory.
 */
public class QueryToSourcePipelineSimulationTest {

  @Test
  public void simulateMultiStepPipeline_transformThenGenerateThenReview() {
    var chat = new AiQueryToSourceService(
        new ConfiguredModel(LlmConfig.newOllama("test")),
        new ToolService());
    var mode = new QueryToSourceModeService(chat);

    var transform = new QueryStep("표준 적용", StepKind.TRANSFORM, "std");
    var generate = new QueryStep("DAO 생성", StepKind.GENERATE, "dao");
    var review = new QueryStep("표준 검토", StepKind.REVIEW, "rev");

    // Step 1: TRANSFORM with SQL in chat input
    mode.markPending(0, transform);
    var transformMsg = mode.messageFor(transform, "SELECT 1");
    chat.addMessage(dev.langchain4j.data.message.UserMessage.from(transformMsg));
    chat.addMessage(AiMessage.from("Standardized:\n```sql\nSELECT user_id FROM users\n```"));
    assertEquals(transform, mode.consumePendingStep());
    mode.markStepCompleted(0);
    assertTrue(mode.isStepCompleted(0));

    // Step 2: GENERATE — can use empty input and rely on conversation
    var genMsg = mode.messageFor(generate, "");
    assertContains(genMsg, QueryToSourceModeService.FALLBACK_INPUT);
    assertContains(genMsg, "DAO 생성");
    assertTrue(QueryToSourceModeService.requiresProject(generate));

    // Step 3: REVIEW with free-form note
    var revMsg = mode.messageFor(review, "Check naming against team guide");
    assertContains(revMsg, "표준 검토");
    assertContains(revMsg, "Check naming");
    assertFalse(QueryToSourceModeService.requiresProject(transform));
  }

  @Test
  public void simulateSqlInChatInput() {
    var chat = new AiQueryToSourceService(
        new ConfiguredModel(LlmConfig.newOllama("test")),
        new ToolService());
    var mode = new QueryToSourceModeService(chat);

    var step = new QueryStep("표준 적용", StepKind.TRANSFORM, "std");
    var query = "SELECT * FROM users";
    var userMessage = mode.messageFor(step, query);
    assertContains(userMessage, query);
    assertContains(userMessage, "표준 적용");

    mode.markPending(0, step);
    chat.addMessage(dev.langchain4j.data.message.UserMessage.from(userMessage));
    chat.addMessage(AiMessage.from("""
        ```sql
        SELECT user_id, email
          FROM users
        ```
        """));

    var pending = mode.consumePendingStep();
    assertNotNull(pending);
    assertEquals(StepKind.TRANSFORM, pending.kind());
    assertContains(chat.lastAiText(), "user_id");
  }

  @Test
  public void simulateXmlInChatInput() {
    var mode = new QueryToSourceModeService(new AiQueryToSourceService(
        new ConfiguredModel(LlmConfig.newOllama("test")),
        new ToolService()));

    var xml = "<select id=\"q\">SELECT 1</select>";
    var step = new QueryStep("표준 적용", StepKind.TRANSFORM, "std");
    var userMessage = mode.messageFor(step, xml);
    assertContains(userMessage, xml);
  }

  @Test
  public void simulateGenerateStepMessageShape() {
    assumeTrue(AbstractTest.isWorkspaceAvailable());
    var mode = new QueryToSourceModeService(new AiQueryToSourceService(
        new ConfiguredModel(LlmConfig.newOllama("test")),
        new ToolService()));

    var step = new QueryStep("DAO 생성", StepKind.GENERATE, "dao-gen");
    var msg = mode.messageFor(step, "SELECT 1");
    assertContains(msg, "DAO 생성");
    assertContains(msg, "Generate the sources");
    assertTrue(QueryToSourceModeService.requiresProject(step));
  }

  private static void assertContains(String haystack, String needle) {
    assertNotNull(haystack);
    assertTrue(haystack.contains(needle));
  }
}
