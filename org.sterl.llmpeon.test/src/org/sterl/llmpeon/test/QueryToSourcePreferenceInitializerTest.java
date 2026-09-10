package org.sterl.llmpeon.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.parts.config.QueryToSourcePreferenceInitializer;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig;
import org.sterl.llmpeon.querytosource.QueryToSourceConfig.QueryStep;
import org.sterl.llmpeon.querytosource.StepKind;

/**
 * Persistence rules of the Query-to-Source config, mainly the distinction between a legacy blob
 * (no steps field -> default pipeline) and a deliberately emptied pipeline (mode switched off).
 */
public class QueryToSourcePreferenceInitializerTest {

    private String previous;

    @Before
    public void setUp() {
        previous = prefs().get(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, null);
    }

    @After
    public void tearDown() throws Exception {
        if (previous == null) {
            prefs().remove(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG);
        } else {
            prefs().put(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, previous);
        }
        prefs().flush();
    }

    @Test
    public void emptyPipelineIsKeptAndDisablesTheMode() {
        QueryToSourcePreferenceInitializer.save(new QueryToSourceConfig(List.of()));

        var loaded = QueryToSourcePreferenceInitializer.load();
        assertTrue(loaded.steps().isEmpty());
        assertFalse(loaded.hasSteps());
    }

    @Test
    public void customPipelineSurvivesTheRoundTrip() {
        QueryToSourcePreferenceInitializer.save(new QueryToSourceConfig(
                List.of(new QueryStep("Only", StepKind.REVIEW, "review-prompt"))));

        var loaded = QueryToSourcePreferenceInitializer.load();
        assertEquals(1, loaded.steps().size());
        assertEquals("Only", loaded.steps().get(0).label());
        assertTrue(loaded.hasSteps());
    }

    @Test
    public void legacyBlobWithoutStepsFallsBackToDefaults() throws Exception {
        prefs().put(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG,
                "{\"standardPrompt\":\"old\",\"generatePrompt\":\"gen\",\"layers\":[]}");
        prefs().flush();

        var loaded = QueryToSourcePreferenceInitializer.load();
        assertEquals(QueryToSourceConfig.examplePipeline().steps().size(), loaded.steps().size());
        assertTrue(loaded.hasSteps());
    }

    @Test
    public void unconfiguredInstallHasNoPipelineSoTheModeStaysHidden() throws Exception {
        prefs().remove(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG);
        prefs().flush();

        assertFalse(QueryToSourcePreferenceInitializer.load().hasSteps());
    }

    @Test
    public void unreadableBlobIsKeptButYieldsNoPipeline() throws Exception {
        prefs().put(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, "{not json");
        prefs().flush();

        assertFalse(QueryToSourcePreferenceInitializer.load().hasSteps());
        assertEquals("{not json", prefs().get(PeonConstants.PREF_QUERY_TO_SOURCE_CONFIG, null));
    }

    private static org.eclipse.core.runtime.preferences.IEclipsePreferences prefs() {
        return InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
    }
}
