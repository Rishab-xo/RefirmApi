package com.app.refirm.arguments.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Anticipated hard bench interrogation question with high-impact courtroom answer and authority pin.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BenchQuestionItem(
        String questionFromJudge,
        String recommendedCourtroomAnswer,
        String authorityPin
) {
    public BenchQuestionItem {
        questionFromJudge = questionFromJudge != null ? questionFromJudge : "";
        recommendedCourtroomAnswer = recommendedCourtroomAnswer != null ? recommendedCourtroomAnswer : "";
        authorityPin = authorityPin != null ? authorityPin : "";
    }

    public BenchQuestionItem withRecommendedCourtroomAnswer(String newAnswer) {
        return new BenchQuestionItem(
                questionFromJudge,
                newAnswer,
                authorityPin
        );
    }
}
