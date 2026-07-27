package org.sterl.llmpeon.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;
import org.sterl.llmpeon.PeonMode;
import org.sterl.llmpeon.parts.PeonAiService;

/**
 * Integration tests for Query-to-Source wiring in {@link org.sterl.llmpeon.parts.PeonAiService}.
 */
public class PeonAiServiceQueryToSourceTest extends AbstractTest {

  PeonAiService aiService = new PeonAiService(null, null, null);

  @Test
  public void resolvePromptBody_findsCommand() throws IOException {
    assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
    var dir = Files.createTempDirectory("peon-cmd-");
    try {
      Files.writeString(dir.resolve("my-standard.md"), "Apply SQL standard rules.");
      aiService.updateConfig(aiService.getConfig().toBuilder().commandDirectory(dir.toString()).build());

      var body = aiService.resolvePromptBody("my-standard");
      assertNotNull(body);
      assertContains(body, "Apply SQL standard");
    } finally {
      deleteRecursively(dir);
    }
  }

  @Test
  public void resolvePromptBody_findsSkill() throws IOException {
    assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
    assumeTrue("skills directory missing", Files.exists(Path.of("../skills")));
    aiService.updateConfig(aiService.getConfig().toBuilder().skillDirectory("../skills").build());

    var body = aiService.resolvePromptBody("test-skill");
    assertNotNull(body);
  }

  @Test
  public void resolvePromptBody_returnsNullForUnknownName() {
    assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
    assertNull(aiService.resolvePromptBody("nonexistent-prompt-xyz"));
  }

  @Test
  public void resolvePromptBody_returnsNullForBlankName() {
    assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
    assertNull(aiService.resolvePromptBody(""));
    assertNull(aiService.resolvePromptBody("   "));
    assertNull(aiService.resolvePromptBody(null));
  }

  @Test
  public void availablePromptNames_mergesCommandsAndSkills() throws IOException {
    assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
    var cmdDir = Files.createTempDirectory("peon-cmd-merge-");
    try {
      Files.writeString(cmdDir.resolve("alpha-cmd.md"), "alpha body");
      var skillDir = Files.createTempDirectory("peon-skill-merge-");
      try {
        Files.writeString(skillDir.resolve("beta-skill.md"), "beta body");
        aiService.updateConfig(aiService.getConfig().toBuilder()
            .commandDirectory(cmdDir.toString())
            .skillDirectory(skillDir.toString())
            .build());

        var names = aiService.availablePromptNames();
        assertTrue(names.contains("alpha-cmd"));
        assertTrue(names.contains("beta-skill"));
      } finally {
        deleteRecursively(skillDir);
      }
    } finally {
      deleteRecursively(cmdDir);
    }
  }

  @Test
  public void setPeonMode_queryToSource_clearsConversation() {
    assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
    aiService.getQueryToSourceService().addMessage(
        dev.langchain4j.data.message.UserMessage.from("leftover"));
    assertEquals(1, aiService.getQueryToSourceService().getMessages().size());

    aiService.setPeonMode(PeonMode.QUERY_TO_SOURCE);

    assertEquals(PeonMode.QUERY_TO_SOURCE, aiService.getPeonMode());
    assertTrue(aiService.getQueryToSourceService().getMessages().isEmpty());
    assertFalse(aiService.getQueryToSourceMode().getConfig().steps().isEmpty());
  }

  @Test
  public void getActiveService_returnsQueryToSourceServiceInMode() {
    assumeTrue("Eclipse workspace not available", isWorkspaceAvailable());
    aiService.setPeonMode(PeonMode.QUERY_TO_SOURCE);
    assertEquals(aiService.getQueryToSourceService(), aiService.getActiveService());
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    }
  }
}
