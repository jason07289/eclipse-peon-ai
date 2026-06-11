package org.sterl.llmpeon.parts.config;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.sterl.llmpeon.ai.SharedHttpClient;
import org.sterl.llmpeon.parts.PeonConstants;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PeonUpdateService {

    private static final ILog LOG = Platform.getLog(PeonUpdateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    public enum Result {
        NO_UPDATE_URL, UNREACHABLE, NO_UPDATE_NEEDED, UPDATED
    }

    public static Result checkForUpdate() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        var updateUrl = prefs.get(PeonConstants.PREF_UPDATE_URL, "").trim();

        if (updateUrl.isEmpty()) {
            return Result.NO_UPDATE_URL;
        }

        updateUrl = removeTrailingSlash(updateUrl);

        try {
            String manifestJson = fetchUrl(updateUrl + "/manifest.json");
            JsonNode manifest = MAPPER.readTree(manifestJson);

            String version = manifest.get("version").asText("");
            boolean prefEnabled = manifest.get("pref").asBoolean(false);

            double remoteVersion = parseVersion(version);
            double localVersion = parseVersion(prefs.get(PeonConstants.PREF_SETTINGS_VERSION, ""));

            if (remoteVersion <= localVersion) {
                return Result.NO_UPDATE_NEEDED;
            }

            if (!prefEnabled) {
                LOG.info("Peon update available but pref=false. Version: " + version);
                return Result.NO_UPDATE_NEEDED;
            }

            String prefsFile;
            try {
                prefsFile = fetchUrl(updateUrl + "/org.sterl.llmpeon.prefs");
            } catch (IOException e) {
                LOG.warn("Prefs file not found at " + updateUrl + "/org.sterl.llmpeon.prefs", e);
                return Result.NO_UPDATE_NEEDED;
            }

            Properties remotePrefs = new Properties();
            remotePrefs.load(new StringReader(prefsFile));

            for (String key : remotePrefs.stringPropertyNames()) {
                if (shouldSkipKey(key)) continue;
                String value = remotePrefs.getProperty(key);
                prefs.put(key, value);
                LOG.info("Applied preference: " + key + " = " + value);
            }

            prefs.put(PeonConstants.PREF_SETTINGS_VERSION, version);
            prefs.flush();

            LOG.info("Peon settings updated to version " + version);
            return Result.UPDATED;

        } catch (IOException e) {
            LOG.warn("Failed to reach update URL: " + updateUrl, e);
            return Result.UNREACHABLE;
        } catch (Exception e) {
            LOG.warn("Error checking for Peon update", e);
            return Result.NO_UPDATE_NEEDED;
        }
    }

    private static String fetchUrl(String url) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        try {
            var response = SharedHttpClient.getHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    private static double parseVersion(String versionStr) {
        if (versionStr == null || versionStr.trim().isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(versionStr.replace("-", "."));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static boolean shouldSkipKey(String key) {
        return "eclipse.preferences.version".equals(key)
                || PeonConstants.PREF_UPDATE_URL.equals(key)
                || PeonConstants.PREF_SETTINGS_VERSION.equals(key);
    }

    private static String removeTrailingSlash(String url) {
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
