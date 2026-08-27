package org.sterl.llmpeon.survey;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.sterl.llmpeon.ai.SharedHttpClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Posts a single satisfaction score to the configured endpoint.
 *
 * <p>Replaces the old approach where a command prompt made the LLM assemble and run a
 * {@code curl} via {@code runOsCommand()} — that depended on the OS shell and on the model
 * actually following the step.
 */
public class SurveyService {

    public static final int VALUE_SATISFIED = 2;
    public static final int VALUE_UNSATISFIED = 1;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String UNKNOWN_HOST = "unknown";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Sends the score. Returns the outcome instead of throwing, because a survey that fails to
     * reach the server must stay invisible to the user.
     *
     * @param slug  the command's frontmatter slug, sent as {@code comment}
     * @param value {@link #VALUE_SATISFIED} or {@link #VALUE_UNSATISFIED}
     */
    public SurveyResult send(SurveyConfig config, String slug, int value) {
        if (config == null || !config.isUsable()) return SurveyResult.failed("survey not configured");
        if (value != VALUE_SATISFIED && value != VALUE_UNSATISFIED) {
            return SurveyResult.failed("invalid survey value: " + value);
        }

        try {
            var request = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth(config.auth()))
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(slug, value), StandardCharsets.UTF_8))
                    .build();

            var response = SharedHttpClient.getHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 == 2) return SurveyResult.ok();
            return SurveyResult.failed("HTTP " + response.statusCode() + ": " + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SurveyResult.failed("interrupted");
        } catch (Exception e) {
            return SurveyResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Builds the score payload. {@code id} and {@code traceId} are deliberately two independent
     * UUIDs, mirroring the curl command this replaced.
     */
    String buildPayload(String slug, int value) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("traceId", UUID.randomUUID().toString());
        payload.put("name", resolveLocalIp());
        payload.put("value", value);
        payload.put("dataType", "NUMERIC");
        payload.put("comment", slug);
        return mapper.writeValueAsString(payload);
    }

    static String basicAuth(String credentials) {
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Best-effort local IPv4 address, used as the score's {@code name} so scores can be grouped
     * per workstation.
     *
     * <p>{@link InetAddress#getLocalHost()} alone is not enough — on Linux it commonly resolves to
     * {@code 127.0.0.1} — so the network interfaces are scanned first.
     */
    public static String resolveLocalIp() {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                var nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;

                var addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var address = addresses.nextElement();
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to getLocalHost()
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return UNKNOWN_HOST;
        }
    }
}
