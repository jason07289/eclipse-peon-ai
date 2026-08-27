package org.sterl.llmpeon.parts.widget;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.osgi.framework.FrameworkUtil;
import org.sterl.llmpeon.parts.shared.EclipseUtil;
import org.sterl.llmpeon.shared.OnPartialAiResponse;
import org.sterl.llmpeon.shared.OnPartialAiResponse.Type;
import org.sterl.llmpeon.tool.model.SimpleMessage;
import org.sterl.llmpeon.tool.model.ToSimpleMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.ChatMessage;

public class ChatMarkdownWidget extends Composite {

    /** Navigation prefixes the embedded page uses to call back into Eclipse. */
    private static final String OPEN_IN_EDITOR_PREFIX = "open-in-editor:";
    private static final String SURVEY_VOTE_PREFIX = "peon-survey-vote:";
    private static final String SURVEY_DISMISS_PREFIX = "peon-survey-dismiss:";

    private final Browser browser;
    private volatile BiConsumer<String, Integer> surveyVoteHandler;
    private volatile Consumer<String> surveyDismissHandler;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private String chatHtml = null;
    
    private final AtomicInteger streamingTokenCount = new AtomicInteger(0);
    private final Composite parent;

    public ChatMarkdownWidget(Composite parent, int style) {
        super(parent, style);
        this.parent = parent;
        setLayout(new FillLayout());

        browser = new Browser(this, SWT.NONE);

        browser.addLocationListener(new LocationListener() {
            @Override
            public void changing(LocationEvent event) {
                if (event.location == null) return;

                if (event.location.startsWith(OPEN_IN_EDITOR_PREFIX)) {
                    event.doit = false;
                    EclipseUtil.openWorkspacePathInEditor(URLDecoder.decode(
                            event.location.substring(OPEN_IN_EDITOR_PREFIX.length()), StandardCharsets.UTF_8));
                    return;
                }

                if (event.location.startsWith(SURVEY_VOTE_PREFIX)) {
                    event.doit = false;
                    onSurveyVote(event.location.substring(SURVEY_VOTE_PREFIX.length()));
                    return;
                }

                if (event.location.startsWith(SURVEY_DISMISS_PREFIX)) {
                    event.doit = false;
                    onSurveyDismiss(event.location.substring(SURVEY_DISMISS_PREFIX.length()));
                }
            }

            @Override
            public void changed(LocationEvent event) {
                // no-op
            }
        });

        clear();
    }
    
    private String loadChatHtml() {
        if (chatHtml != null) return chatHtml;
        try (InputStream is = getClass().getResourceAsStream("/resources/chat/chat.html")) {
            if (is == null) {
                throw new RuntimeException("chat.html not found on classpath");
            }
            var loaded = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            chatHtml = resolveResourcePaths(loaded);
            return chatHtml;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load chat.html", e);
        }
    }

    /**
     * Replaces all relative {@code ./} paths in the HTML with absolute file:// URLs
     * so the embedded browser can load CSS, JS, and language files.
     */
    private String resolveResourcePaths(String html) throws IOException {
        URL chatDir = FileLocator.find(
                FrameworkUtil.getBundle(getClass()),
                new Path("resources/chat/"),
                null
        );
        if (chatDir == null) {
            throw new IOException("resources/chat/ directory not found in bundle");
        }
        String basePath = FileLocator.toFileURL(chatDir).toString();
        // all resources use ./ relative paths, so a single replace resolves everything
        return html.replace("./", basePath);
    }

    public void appendMessage(SimpleMessage msg) {
        try {
            browser.execute("appendMessage(" + mapper.writeValueAsString(msg) + ");");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
    
    public void hideLiveStatus() {
        browser.execute("hideLiveStatus();");
    }

    /**
     * Shows the satisfaction survey bar below the last message. Purely passive — the chat input
     * stays usable and an ignored bar just scrolls away.
     */
    public void appendSurvey(String token) {
        try {
            browser.execute("appendSurvey(" + mapper.writeValueAsString(token) + ");");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers the handler invoked with the survey token and score value (2 = satisfied,
     * 1 = not) when the user clicks a survey button. Runs on the UI thread, so the handler must
     * not block.
     */
    public void setSurveyVoteHandler(BiConsumer<String, Integer> handler) {
        this.surveyVoteHandler = handler;
    }

    /**
     * Registers the handler invoked when the survey is dismissed. The token identifies which
     * survey instance got closed.
     */
    public void setSurveyDismissHandler(Consumer<String> handler) {
        this.surveyDismissHandler = handler;
    }

    private void onSurveyVote(String rawPayload) {
        var handler = surveyVoteHandler;
        if (handler == null || rawPayload == null) return;
        int separator = rawPayload.lastIndexOf(':');
        if (separator <= 0 || separator == rawPayload.length() - 1) return;

        var rawToken = rawPayload.substring(0, separator);
        var rawValue = rawPayload.substring(separator + 1);
        try {
            var token = URLDecoder.decode(rawToken, StandardCharsets.UTF_8);
            int value = Integer.parseInt(rawValue);
            if (value != 1 && value != 2) return;
            handler.accept(token, value);
        } catch (RuntimeException e) {
            // malformed token/value is ignored quietly
        }
    }

    private void onSurveyDismiss(String rawToken) {
        var handler = surveyDismissHandler;
        if (handler == null || rawToken == null) return;
        try {
            var token = URLDecoder.decode(rawToken, StandardCharsets.UTF_8);
            if (token.isBlank()) return;
            handler.accept(token);
        } catch (RuntimeException e) {
            // malformed token is ignored quietly
        }
    }

    public void onStreamingChunk(OnPartialAiResponse r) {
        int tokens = 0;
        if (r.type() == Type.START || r.type() ==  Type.END) {
            streamingTokenCount.set(0);
        } else {
            tokens = streamingTokenCount.incrementAndGet();
        }
        
        if (r.type() == Type.END) {
            EclipseUtil.runInUiThread(parent, this::hideLiveStatus);
        } else {
            updateRunningChunk(r, tokens);
        }
    }

    private void updateRunningChunk(OnPartialAiResponse r, int tokens) {
        long elapsed = Duration.between(r.startedAt(), Instant.now()).toSeconds();
        String state = switch (r.type()) {
            case START   -> "waiting for AI...";
            case THINK   -> "working since " + elapsed + "s | thinking...";
            case ANSWER  -> "working since " + elapsed + "s | responding...";
            case TOOL    -> "working since " + elapsed + "s | using tools...";
            case END     -> "done.";
        };
        if (r.type() == Type.START) {
            updateLiveResponseInUIThread(state, 0, "");
        } else if (tokens % 20 == 0) {
            double tokPerSec = elapsed > 0 ? tokens / (double) elapsed : 0;
            updateLiveResponseInUIThread(state, tokPerSec, tokens + " tokens generated");
        }
    }
    
    public void updateLiveResponseInUIThread(String state, double tokPerSec, String safeChunk) {
        EclipseUtil.runInUiThread(parent, () -> browser.execute("updateLiveResponse('" + state + "', " + tokPerSec + ", '" + safeChunk + "');"));
    }

    public void showDiff(String unifiedDiff) {
        try {
            browser.execute(
                "appendDiff(" + mapper.writeValueAsString(unifiedDiff) + ");"
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Reload the while view - clean everything away ....
     */
    public void clear() {
        browser.setText(loadChatHtml());
    }

    /**
     * Just removes the messages
     */
    public void clearMessages() {
        hideLiveStatus();
        browser.execute("clearMessages()");
    }

    public void appendMessage(ChatMessage msg) {
        var toAdd = ToSimpleMessage.INSTANCE.convert(msg);
        toAdd.forEach(this::appendMessage);
    }
}
