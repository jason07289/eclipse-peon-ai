package org.sterl.llmpeon.parts.config;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

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
        NO_UPDATE_URL, INVALID_URL, UNREACHABLE, NO_UPDATE_NEEDED, UPDATED
    }

    public static Result checkForUpdate() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        var updateUrl = prefs.get(PeonConstants.PREF_UPDATE_URL, "").trim();

        if (updateUrl.isEmpty()) {
            return Result.NO_UPDATE_URL;
        }

        updateUrl = removeTrailingSlash(updateUrl);

        URI baseUri;
        try {
            baseUri = URI.create(updateUrl);
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid update URL: " + updateUrl, e);
            return Result.INVALID_URL;
        }
        if (baseUri.getScheme() == null || baseUri.getHost() == null) {
            LOG.warn("Invalid update URL (missing scheme or host): " + updateUrl);
            return Result.INVALID_URL;
        }

        try {
            String manifestJson = fetchUrl(updateUrl + "/manifest.json");
            JsonNode manifest = MAPPER.readTree(manifestJson);

            String version = manifest.path("version").asText("");
            boolean prefEnabled = manifest.path("pref").asBoolean(false);

            double remoteVersion = parseVersion(version);
            double localVersion = parseVersion(prefs.get(PeonConstants.PREF_SETTINGS_VERSION, ""));

            if (remoteVersion <= localVersion) {
                return Result.NO_UPDATE_NEEDED;
            }

            if (prefEnabled) {
                try {
                    String prefsFile = fetchUrl(updateUrl + "/org.sterl.llmpeon.prefs");
                    Properties remotePrefs = new Properties();
                    remotePrefs.load(new StringReader(prefsFile));

                    for (String key : remotePrefs.stringPropertyNames()) {
                        if (shouldSkipKey(key)) continue;
                        String value = remotePrefs.getProperty(key);
                        prefs.put(key, value);
                        LOG.info("Applied preference: " + key + " = " + value);
                    }
                } catch (IOException e) {
                    LOG.warn("Prefs file not found at " + updateUrl + "/org.sterl.llmpeon.prefs", e);
                }
            }

            syncFiles(updateUrl, manifest);

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

    private static void syncFiles(String updateUrl, JsonNode manifest) {
        var filesNode = manifest.path("files");
        if (!filesNode.isArray()) return;

        var commandFiles = new HashSet<String>();
        var skillFiles = new HashSet<String>();

        for (var node : filesNode) {
            String path = node.asText("");
            if (path.startsWith("commands/")) {
                commandFiles.add(path.substring("commands/".length()));
            } else if (path.startsWith("skills/")) {
                skillFiles.add(path.substring("skills/".length()));
            } else if (!path.isBlank()) {
                LOG.warn("Skipping unknown manifest file path: " + path);
            }
        }

        var config = LlmPreferenceInitializer.buildWithDefaults();
        syncDirectory(updateUrl, "commands", config.getCommandDirectory(), commandFiles);
        syncDirectory(updateUrl, "skills", config.getSkillDirectory(), skillFiles);
    }

    private static void syncDirectory(String updateUrl, String remotePrefix, String localDir,
            Set<String> expectedRelPaths) {
        if (localDir == null || localDir.isBlank()) return;
        var root = Path.of(localDir);

        // 1) 미러 삭제: localDir 안의 .md 파일 중 expectedRelPaths에 없는 것 삭제
        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.toString().endsWith(".md"))
                      .forEach(p -> {
                          var rel = root.relativize(p).toString().replace('\\', '/');
                          if (!expectedRelPaths.contains(rel)) {
                              try {
                                  Files.delete(p);
                                  LOG.info("Removed file no longer in manifest: " + p);
                              } catch (IOException e) {
                                  LOG.warn("Failed to delete " + p, e);
                              }
                          }
                      });
            } catch (IOException e) {
                LOG.warn("Failed to scan directory " + root, e);
            }
        }

        // 2) 다운로드/덮어쓰기
        for (String rel : expectedRelPaths) {
            try {
                String content = fetchUrl(updateUrl + "/" + remotePrefix + "/" + rel);
                var target = root.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.writeString(target, content);
                LOG.info("Synced " + remotePrefix + "/" + rel + " -> " + target);
            } catch (IOException e) {
                LOG.warn("Failed to download " + remotePrefix + "/" + rel, e);
            }
        }
    }
}
