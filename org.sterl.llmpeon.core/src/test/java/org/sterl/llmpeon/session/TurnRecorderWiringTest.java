package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sterl.llmpeon.AbstractChatService;
import org.sterl.llmpeon.ai.ConfiguredModel;
import org.sterl.llmpeon.ai.LlmConfig;
import org.sterl.llmpeon.shared.AiMonitor;
import org.sterl.llmpeon.tool.ToolLoopRequest;
import org.sterl.llmpeon.tool.ToolService;
import org.sterl.llmpeon.tool.tools.AbstractTool;
import org.sterl.llmpeon.tool.tools.CompactSessionTool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * Verifies the turn-boundary hooks (chat-session design §3/§4): WAL ordering, commit points for
 * completed/canceled/interrupted turns, and both compaction paths emitting {@code compacted}.
 */
class TurnRecorderWiringTest {

    /** Captures hook invocations in order as simple strings. */
    static class RecordingRecorder implements TurnRecorder {
        final List<String> events = new ArrayList<>();
        @Override public void turnStarted(String turnId, String userText) { events.add("turnStarted:" + userText); }
        @Override public void aiMessage(String turnId, String text) { events.add("aiMessage:" + text); }
        @Override public void think(String turnId, String text) { events.add("think"); }
        @Override public void toolIntent(String turnId, String callId, String name, String args) { events.add("toolIntent:" + name); }
        @Override public void toolResult(String turnId, String callId, String name, String result) { events.add("toolResult:" + name); }
        @Override public void problem(String turnId, String text) { events.add("problem"); }
        @Override public void compacted(String turnId, String summary) { events.add("compacted:" + summary); }
        @Override public void cleared() { events.add("cleared"); }
        @Override public void turnFinished(String turnId, SessionStatus status) { events.add("turnFinished:" + status); }
    }

    static class TestChatService extends AbstractChatService {
        TestChatService(ConfiguredModel model, ToolService toolService) {
            super(model, toolService);
        }
        @Override protected String getSystemPrompt() { return "system"; }
        @Override protected double getTemperature() { return 1.0; }
    }

    private static StreamingChatModel mockModel(Function<ChatRequest, ChatResponse> answers) {
        var cm = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            var response = answers.apply(inv.getArgument(0, ChatRequest.class));
            inv.getArgument(1, StreamingChatResponseHandler.class).onCompleteResponse(response);
            return null;
        }).when(cm).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return cm;
    }

    private static ConfiguredModel configured(StreamingChatModel cm) {
        return new ConfiguredModel(LlmConfig.newOpenAi("test"), cm);
    }

    final RecordingRecorder recorder = new RecordingRecorder();

    @Test
    void test_completedTurn_emitsStartAndFinish_inOrder() {
        var cm = mockModel(req -> ChatResponse.builder().aiMessage(AiMessage.from("the answer")).build());
        var service = new TestChatService(configured(cm), new ToolService());
        service.setTurnRecorder(recorder);

        service.call("hello", null);

        assertThat(recorder.events).containsExactly(
                "turnStarted:hello",
                "aiMessage:the answer",
                "turnFinished:COMPLETED");
    }

    @Test
    void test_toolIntent_isRecordedBeforeTheToolExecutes() {
        var intentsSeenByTool = new AtomicInteger(-1);
        class EchoTool extends AbstractTool {
            @Tool(name = "echoTool", value = "echoes")
            public String echo(String text) {
                intentsSeenByTool.set((int) recorder.events.stream().filter(e -> e.startsWith("toolIntent")).count());
                return "echo: " + text;
            }
        }
        var toolService = new ToolService();
        toolService.addTool(new EchoTool());

        var toolRequest = ToolExecutionRequest.builder().id("c1").name("echoTool")
                .arguments("{\"arg0\":\"hi\"}").build();
        var first = new AtomicInteger(0);
        var cm = mockModel(req -> first.getAndIncrement() == 0
                ? ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build()
                : ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        var memory = MessageWindowChatMemory.withMaxMessages(50);
        memory.add(UserMessage.from("run the tool"));
        toolService.executeLoop(ToolLoopRequest.builder()
                .memory(memory).model(configured(cm))
                .turnRecorder(recorder).turnId("t1")
                .build());

        // the WAL record existed before the tool ran
        assertThat(intentsSeenByTool.get()).isEqualTo(1);
        assertThat(recorder.events).containsExactly(
                "toolIntent:echoTool",
                "toolResult:echoTool",
                "aiMessage:done");
    }

    @Test
    void test_exception_marksTurnInterrupted_andRethrows() {
        var toolService = mock(ToolService.class);
        when(toolService.executeLoop(any())).thenThrow(new IllegalStateException("boom"));
        var service = new TestChatService(configured(mock(StreamingChatModel.class)), toolService);
        service.setTurnRecorder(recorder);

        assertThatThrownBy(() -> service.call("hello", null)).hasMessage("boom");

        assertThat(recorder.events).containsExactly(
                "turnStarted:hello",
                "turnFinished:INTERRUPTED");
    }

    @Test
    void test_cancel_marksTurnCanceled() {
        var monitor = mock(AiMonitor.class);
        when(monitor.isCanceled()).thenReturn(true);
        var cm = mockModel(req -> ChatResponse.builder().aiMessage(AiMessage.from("partial")).build());
        var service = new TestChatService(configured(cm), new ToolService());
        service.setTurnRecorder(recorder);

        service.call("hello", monitor);

        assertThat(recorder.events).contains("turnFinished:CANCELED");
    }

    @Test
    void test_compressContext_emitsCompacted_afterMemoryWasReplaced() {
        var cm = mockModel(req -> ChatResponse.builder().aiMessage(AiMessage.from("a useful summary")).build());
        var service = new TestChatService(configured(cm), new ToolService());
        service.setTurnRecorder(recorder);
        service.addMessage(UserMessage.from("lots of history"));

        service.compressContext(null);

        assertThat(recorder.events).containsExactly("compacted:a useful summary");
        assertThat(service.getMessages()).hasSize(1);
        assertThat(((AiMessage) service.getMessages().get(0)).text()).startsWith("[Context summary]");
    }

    @Test
    void test_clear_emitsCleared() {
        var service = new TestChatService(configured(mock(StreamingChatModel.class)), new ToolService());
        service.setTurnRecorder(recorder);
        service.addMessage(UserMessage.from("hi"));

        service.clear();

        assertThat(recorder.events).containsExactly("cleared");
        assertThat(service.getMessages()).isEmpty();
    }

    @Test
    void test_compactSessionTool_emitsCompacted_too() {
        var cm = mockModel(req -> ChatResponse.builder().aiMessage(AiMessage.from("tool-path summary")).build());
        var memory = MessageWindowChatMemory.withMaxMessages(50);
        memory.add(UserMessage.from("long conversation"));
        var request = ToolLoopRequest.builder()
                .memory(memory).model(configured(cm))
                .turnRecorder(recorder).turnId("t1")
                .build();
        var tool = new CompactSessionTool();
        tool.withToolRequest(request);

        tool.compactSession("keep the plan");

        assertThat(recorder.events).containsExactly("compacted:tool-path summary");
        assertThat(memory.messages()).hasSize(1); // replaced by the resume user message
    }
}
