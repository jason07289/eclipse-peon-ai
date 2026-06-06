package org.sterl.llmpeon.shared;

import java.util.LinkedList;
import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

public class ChatMessageUtil {

    public static UserMessage join(UserMessage m1, UserMessage m2) {
        List<Content> data = new LinkedList<>();
        data.addAll(toContent(m1));
        data.addAll(toContent(m2));
        return UserMessage.from(data);
    }

    private static List<Content> toContent(UserMessage m1) {
        List<Content> data = new LinkedList<Content>();
        if (m1.hasSingleText()) data.add(new TextContent(m1.singleText()));
        else data.addAll(m1.contents());
        return data;
    }
    
    public static String toString(ChatMessage msg) {
        var result = new StringBuilder();
        if (msg instanceof UserMessage m) {
            if (m.hasSingleText()) {
                result.append(m.singleText());
            } else {
                m.contents().stream()
                    .filter(s -> s instanceof TextContent)
                    .map(s -> ((TextContent)s))
                    .forEach(s -> result.append(s.text()).append("\n"));
            }
        } else if (msg instanceof AiMessage m) {
            if (StringUtil.hasValue(m.text())) {
                result.append(m.text()).append("\n");
            }
            if (StringUtil.hasValue(m.thinking())) {
                result.append("AI thinking:\n").append(m.thinking()).append("\n");
            }
            if (m.hasToolExecutionRequests()) {
                for (var tr : m.toolExecutionRequests()) {
                    result.append("\ntool name:  ").append(tr.name())
                          .append("\ntool id:    ").append(tr.id())
                          .append("\narguments:  ").append(tr.arguments());
                }
            }
        } else if (msg instanceof ToolExecutionResultMessage tr && tr.hasSingleText()) {
            result.append("\ntool result for id: ").append(tr.id())
                  .append("\n").append(tr.text()).append("\n");
        }
        return result.toString().strip();
    }
}
