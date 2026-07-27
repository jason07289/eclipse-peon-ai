package org.sterl.llmpeon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.ai.ConfiguredModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.tool.ToolService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;

class AiQueryToSourceServiceTest {

    private AiQueryToSourceService service;

    @BeforeEach
    void setUp() {
        service = new AiQueryToSourceService(
                new ConfiguredModel(LlmConfig.newOllama("test-model")),
                new ToolService());
    }

    @Test
    void lastAiText_returnsMostRecentNonBlankAiMessage() {
        service.addMessage(UserMessage.from("query"));
        service.addMessage(AiMessage.from("first"));
        service.addMessage(AiMessage.from("second"));

        assertThat(service.lastAiText()).isEqualTo("second");
    }

    @Test
    void lastAiText_skipsBlankAiMessages() {
        service.addMessage(AiMessage.from(""));
        service.addMessage(AiMessage.from("  "));
        service.addMessage(AiMessage.from("valid"));

        assertThat(service.lastAiText()).isEqualTo("valid");
    }

    @Test
    void lastAiText_returnsNullWhenNoAiMessages() {
        service.addMessage(UserMessage.from("only user"));
        assertThat(service.lastAiText()).isNull();
    }

    @Test
    void clear_removesMessagesSoLastAiTextIsNull() {
        service.addMessage(AiMessage.from("gone"));
        service.clear();
        assertThat(service.lastAiText()).isNull();
    }
}
