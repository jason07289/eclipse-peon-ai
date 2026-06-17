package org.sterl.llmpeon.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.ai.model.AiModel;

class ConfiguredModelTest {

    @Test
    void test_takes_model() throws Exception {
        // GIVEN
        var subject = new ConfiguredChatModel(LlmConfig.newConfig(null, "http://bar"));
        
        // WHEN
        subject.resolveModel(Arrays.asList(AiModel.builder().id("foo").build()));
        
        // THEN
        assertEquals("foo", subject.getConfig().getModel());
    }
    
    @Test
    void test_model_keeps() throws Exception {
        // GIVEN
        var subject = new ConfiguredChatModel(LlmConfig.newConfig("foo", "http://bar"));
        
        // WHEN
        subject.resolveModel(Arrays.asList(AiModel.builder().id("foo").build(),
                AiModel.builder().id("bar").build()));
        
        // THEN
        assertEquals("foo", subject.getConfig().getModel());
    }
    
    @Test
    void test_overwrites_model() throws Exception {
        // GIVEN
        var subject = new ConfiguredChatModel(LlmConfig.newConfig("bar", "http://bar"));
        
        // WHEN
        subject.resolveModel(Arrays.asList(AiModel.builder().id("foo").build()));
        
        // THEN
        assertEquals("foo", subject.getConfig().getModel());
    }

    @Test
    void test_withModel_same_model_no_change() {
        var subject = new ConfiguredChatModel(LlmConfig.newConfig("foo", "http://bar"));
        var aiModel = AiModel.builder().id("foo").maxInputTokens(10000).build();

        boolean changed = subject.withModel(aiModel);

        assertFalse(changed);
    }

    @Test
    void test_withModel_null_max_tokens_does_not_affect_auto_compact() {
        var config = LlmConfig.builder().model("old").url("http://bar").autoCompactAfter(80000).build();
        var subject = new ConfiguredChatModel(config);
        var aiModel = AiModel.builder().id("new").maxInputTokens(null).build();

        boolean changed = subject.withModel(aiModel);

        assertTrue(changed);
        assertEquals("new", subject.getConfig().getModel());
        assertEquals(80000, subject.getConfig().getAutoCompactAfter());
    }

    @Test
    void test_withModel_updates_auto_compact_when_lower() {
        var config = LlmConfig.builder().model("old").url("http://bar").autoCompactAfter(80000).build();
        var subject = new ConfiguredChatModel(config);
        var aiModel = AiModel.builder().id("new").maxInputTokens(10000).build();

        boolean changed = subject.withModel(aiModel);

        assertTrue(changed);
        assertEquals("new", subject.getConfig().getModel());
        assertEquals(9000, subject.getConfig().getAutoCompactAfter());
    }

    @Test
    void test_withModel_null_id_does_not_throw_npe() {
        var config = LlmConfig.builder().model("old").url("http://bar").build();
        var subject = new ConfiguredChatModel(config);
        // Create AiModel with null id — this should not cause NPE
        var aiModel = AiModel.builder().id(null).maxInputTokens(10000).build();

        assertDoesNotThrow(() -> {
            boolean changed = subject.withModel(aiModel);
            // Should return true since models are different (null != "old")
            assertTrue(changed);
        });
    }
}
