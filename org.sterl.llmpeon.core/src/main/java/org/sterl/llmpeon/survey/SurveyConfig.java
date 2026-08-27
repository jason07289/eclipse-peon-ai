package org.sterl.llmpeon.survey;

import org.sterl.llmpeon.shared.StringUtil;

/**
 * Admin-managed settings for the satisfaction survey shown after a slash command run.
 *
 * @param enabled         master switch, off by default
 * @param url             POST endpoint receiving the score
 * @param auth            basic auth credentials as {@code publicKey:secretKey}
 * @param cooldownMinutes how long the same command stays quiet after a survey was shown
 */
public record SurveyConfig(
    boolean enabled,
    String url,
    String auth,
    int cooldownMinutes
) {
    public static final int DEFAULT_COOLDOWN_MINUTES = 30;

    /** A survey can only be shown when it could also be delivered somewhere. */
    public boolean isUsable() {
        return enabled && StringUtil.hasValue(url) && StringUtil.hasValue(auth);
    }

    /** Never returns a value that would make the survey re-appear on every single run. */
    public int effectiveCooldownMinutes() {
        return cooldownMinutes > 0 ? cooldownMinutes : DEFAULT_COOLDOWN_MINUTES;
    }
}
