package org.sterl.llmpeon.survey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SurveyConfigTest {

    private static final String URL = "http://10.10.100.72:30240/api/public/scores";
    private static final String AUTH = "pk-1f-public:sk-1f-secret";

    @Test
    void isUsableOnlyWhenEnabledAndFullyConfigured() {
        assertThat(new SurveyConfig(true, URL, AUTH, 30).isUsable()).isTrue();

        assertThat(new SurveyConfig(false, URL, AUTH, 30).isUsable()).isFalse();
        assertThat(new SurveyConfig(true, "", AUTH, 30).isUsable()).isFalse();
        assertThat(new SurveyConfig(true, "   ", AUTH, 30).isUsable()).isFalse();
        assertThat(new SurveyConfig(true, null, AUTH, 30).isUsable()).isFalse();
        assertThat(new SurveyConfig(true, URL, "", 30).isUsable()).isFalse();
        assertThat(new SurveyConfig(true, URL, null, 30).isUsable()).isFalse();
    }

    @Test
    void fallsBackToDefaultCooldownForNonPositiveValues() {
        assertThat(new SurveyConfig(true, URL, AUTH, 45).effectiveCooldownMinutes()).isEqualTo(45);
        assertThat(new SurveyConfig(true, URL, AUTH, 0).effectiveCooldownMinutes())
                .isEqualTo(SurveyConfig.DEFAULT_COOLDOWN_MINUTES);
        assertThat(new SurveyConfig(true, URL, AUTH, -5).effectiveCooldownMinutes())
                .isEqualTo(SurveyConfig.DEFAULT_COOLDOWN_MINUTES);
    }
}
