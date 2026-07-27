package org.sterl.llmpeon.parts.config;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads and stores the {@link QueryToSourceConfig} as a JSON blob in a single preference key,
 * mirroring {@link McpPreferenceInitializer}.
 */
public class QueryToSourcePreferenceInitializer extends AbstractPreferenceInitializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        defaults.put(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, toJson(QueryToSourceConfig.defaults()));
    }

    public static QueryToSourceConfig load() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        String json = prefs.get(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, null);
        if (json == null || json.isBlank()) return QueryToSourceConfig.defaults();
        try {
            var config = MAPPER.readValue(json, QueryToSourceConfig.class).orDefaultsIfEmpty();
            return config;
        } catch (Exception e) {
            var defaults = QueryToSourceConfig.defaults();
            prefs.put(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, toJson(defaults));
            try { prefs.flush(); } catch (Exception ignored) {}
            return defaults;
        }
    }

    public static void save(QueryToSourceConfig config) {
        try {
            var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
            prefs.put(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, toJson(config));
            prefs.flush();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save Query-to-Source config", e);
        }
    }

    private static String toJson(QueryToSourceConfig config) {
        try {
            return MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Query-to-Source config", e);
        }
    }
}
