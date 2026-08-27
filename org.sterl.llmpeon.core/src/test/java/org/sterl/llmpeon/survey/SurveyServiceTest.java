package org.sterl.llmpeon.survey;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class SurveyServiceTest {

    private static final String AUTH = "pk-1f-public:sk-1f-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String url;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int responseStatus = 200;

    private final SurveyService subject = new SurveyService();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/scores", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastExchange.set(exchange);
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/public/scores";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private SurveyConfig config() {
        return new SurveyConfig(true, url, AUTH, 30);
    }

    @Test
    void sendsScoreWithExpectedPayload() throws Exception {
        var result = subject.send(config(), "dev-code-04", SurveyService.VALUE_SATISFIED);

        assertThat(result.success()).as(result.message()).isTrue();

        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.get("value").asInt()).isEqualTo(2);
        assertThat(body.get("dataType").asText()).isEqualTo("NUMERIC");
        assertThat(body.get("comment").asText()).isEqualTo("dev-code-04");
        assertThat(body.get("name").asText()).isNotBlank();
        // id and traceId are two independent UUID v4 values, as in the curl this replaced
        assertThat(body.get("id").asText()).isNotEqualTo(body.get("traceId").asText());
        assertThat(java.util.UUID.fromString(body.get("id").asText())).isNotNull();
        assertThat(java.util.UUID.fromString(body.get("traceId").asText())).isNotNull();
    }

    @Test
    void sendsBasicAuthAndJsonContentType() {
        subject.send(config(), "dev-cicd-02", SurveyService.VALUE_UNSATISFIED);

        var headers = lastExchange.get().getRequestHeaders();
        assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(headers.getFirst("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString(AUTH.getBytes(StandardCharsets.UTF_8)));
        assertThat(lastExchange.get().getRequestMethod()).isEqualTo("POST");
    }

    @Test
    void mapsUnsatisfiedToOne() throws Exception {
        subject.send(config(), "dev-cicd-02", SurveyService.VALUE_UNSATISFIED);

        assertThat(MAPPER.readTree(lastBody.get()).get("value").asInt()).isEqualTo(1);
    }

    @Test
    void reportsFailureInsteadOfThrowingOnServerError() {
        responseStatus = 500;

        var result = subject.send(config(), "dev-code-04", SurveyService.VALUE_SATISFIED);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("500");
    }

    @Test
    void reportsFailureInsteadOfThrowingOnUnreachableHost() {
        var unreachable = new SurveyConfig(true, "http://127.0.0.1:1/api/public/scores", AUTH, 30);

        var result = subject.send(unreachable, "dev-code-04", SurveyService.VALUE_SATISFIED);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void doesNotSendWhenNotConfigured() {
        assertThat(subject.send(new SurveyConfig(false, url, AUTH, 30), "s", 2).success()).isFalse();
        assertThat(subject.send(new SurveyConfig(true, "", AUTH, 30), "s", 2).success()).isFalse();
        assertThat(subject.send(new SurveyConfig(true, url, "", 30), "s", 2).success()).isFalse();
        assertThat(subject.send(null, "s", 2).success()).isFalse();
        assertThat(lastBody.get()).isNull();
    }

    @Test
    void rejectsUnexpectedVoteValues() {
        var result = subject.send(config(), "dev-code-04", 99);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("invalid survey value");
        assertThat(lastBody.get()).isNull();
    }

    @Test
    void resolvesALocalIp() {
        assertThat(SurveyService.resolveLocalIp()).isNotBlank();
    }
}
