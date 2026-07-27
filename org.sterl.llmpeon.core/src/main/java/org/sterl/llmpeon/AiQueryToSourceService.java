package org.sterl.llmpeon;

import org.sterl.llmpeon.ai.ConfiguredModel;
import org.sterl.llmpeon.shared.StringUtil;
import org.sterl.llmpeon.tool.ToolService;

import dev.langchain4j.data.message.AiMessage;

/**
 * Developer-like chat service backing the {@link PeonMode#QUERY_TO_SOURCE} wizard.
 *
 * <p>It has full access to the edit tools (like {@link AiDeveloperService}) so the individual
 * wizard steps can create/modify files. The concrete "how" of each step (apply SQL standard,
 * generate mapper/DTO/mapper.java, build a service/controller layer) is supplied per step as a
 * one-shot system prompt taken from a user-selected command or skill, so the base prompt only
 * describes the general behaviour.</p>
 */
public class AiQueryToSourceService extends AbstractChatService {

    private static final String BASE_PROMPT = PromptLoader.loadWithDefault("query-to-source.txt");

    public AiQueryToSourceService(ConfiguredModel configuredModel, ToolService toolService) {
        super(configuredModel, toolService);
    }

    @Override
    protected String getSystemPrompt() {
        return BASE_PROMPT;
    }

    @Override
    protected double getTemperature() {
        return configuredModel.getConfig().getDevTemperature();
    }

    /** Returns the text of the most recent AI message, or {@code null} if there is none. */
    public String lastAiText() {
        var messages = getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage ai && StringUtil.hasValue(ai.text())) {
                return ai.text();
            }
        }
        return null;
    }
}
