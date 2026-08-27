package org.sterl.llmpeon.survey;

/**
 * Outcome of a survey POST. Never an exception: a failed survey must not interrupt the user,
 * so the caller only logs what went wrong.
 */
public record SurveyResult(boolean success, String message) {

    public static SurveyResult ok() {
        return new SurveyResult(true, "sent");
    }

    public static SurveyResult failed(String message) {
        return new SurveyResult(false, message);
    }
}
