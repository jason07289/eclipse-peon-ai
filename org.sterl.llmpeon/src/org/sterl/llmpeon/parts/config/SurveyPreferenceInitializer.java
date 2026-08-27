package org.sterl.llmpeon.parts.config;

import java.time.Duration;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.sterl.llmpeon.parts.PeonConstants;
import org.sterl.llmpeon.survey.SurveyConfig;

/**
 * Defaults and access for the satisfaction survey settings, mirroring
 * {@link VoicePreferenceInitializer}.
 *
 * <p>URL and credentials ship empty on purpose: a secret key baked into the source would end up in
 * the git history and in the update-site jar, where it cannot be rotated or revoked. The
 * administrator enters both in the survey settings dialog, and the survey stays off until they do.
 */
public class SurveyPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        defaults.putBoolean(PeonConstants.PREF_SURVEY_ENABLED, false);
        defaults.put(PeonConstants.PREF_SURVEY_URL, "");
        defaults.put(PeonConstants.PREF_SURVEY_AUTH, "");
        defaults.putInt(PeonConstants.PREF_SURVEY_COOLDOWN, SurveyConfig.DEFAULT_COOLDOWN_MINUTES);
    }

    public static SurveyConfig load() {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        return new SurveyConfig(
            prefs.getBoolean(PeonConstants.PREF_SURVEY_ENABLED, false),
            prefs.get(PeonConstants.PREF_SURVEY_URL, ""),
            prefs.get(PeonConstants.PREF_SURVEY_AUTH, ""),
            prefs.getInt(PeonConstants.PREF_SURVEY_COOLDOWN, SurveyConfig.DEFAULT_COOLDOWN_MINUTES)
        );
    }

    public static void save(SurveyConfig config) {
        try {
            var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
            prefs.putBoolean(PeonConstants.PREF_SURVEY_ENABLED, config.enabled());
            prefs.put(PeonConstants.PREF_SURVEY_URL, config.url() == null ? "" : config.url());
            prefs.put(PeonConstants.PREF_SURVEY_AUTH, config.auth() == null ? "" : config.auth());
            prefs.putInt(PeonConstants.PREF_SURVEY_COOLDOWN, config.cooldownMinutes());
            prefs.flush();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save survey settings", e);
        }
    }

    /**
     * Answers whether a survey may be shown for {@code slug} right now and, if so, immediately
     * records the current time. The timestamp is taken when the survey is <em>shown</em>, not when
     * it is answered, so ignoring it still keeps the command quiet for a full cooldown.
     *
     * <p>Purely local state in this Eclipse workspace — nothing is sent to or read from the
     * survey server.
     */
    public static synchronized boolean consumeCooldown(String slug, int cooldownMinutes) {
        var prefs = InstanceScope.INSTANCE.getNode(PeonConstants.PLUGIN_ID);
        var key = PeonConstants.PREF_SURVEY_LAST_PREFIX + slug;

        long now = System.currentTimeMillis();
        long last = prefs.getLong(key, 0L);
        long cooldownMillis = Duration.ofMinutes(cooldownMinutes).toMillis();

        // A clock that jumped backwards must not lock the survey out forever.
        if (last > now) last = 0L;
        if (last > 0L && now - last < cooldownMillis) return false;

        prefs.putLong(key, now);
        try {
            prefs.flush();
        } catch (Exception ignored) {
            // an unflushed timestamp only risks one extra survey after a crash
        }
        return true;
    }
}
