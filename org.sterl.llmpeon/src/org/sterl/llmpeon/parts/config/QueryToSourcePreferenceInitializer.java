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
        defaults.put(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, toJson(QueryToSourceConfig.empty()));
        defaults.putBoolean(PeonConstants.PREF_QUERY_TO_SOURCE_SHOW_STEP_NUMBERS, false);
    }

    public static QueryToSourceConfig load() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        String json = prefs.get(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, null);
        // Nothing stored means nobody configured the pipeline, so the mode stays out of the UI.
        if (json == null || json.isBlank()) return QueryToSourceConfig.empty();
        try {
            var node = MAPPER.readTree(json);
            var config = MAPPER.treeToValue(node, QueryToSourceConfig.class);
            // A missing "steps" field means a legacy blob written before the field existed - its
            // owner was using the wizard, so the example pipeline keeps the mode alive for them.
            // An explicit array is the user's own choice and is kept as is, including an empty one,
            // which switches Query-to-Source off.
            return node.has("steps") ? config : config.orExampleIfEmpty();
        } catch (Exception e) {
            // Leave the unreadable blob in place so it can still be repaired by hand. The mode
            // disappearing from the combo is the visible signal that it needs a look.
            return QueryToSourceConfig.empty();
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
