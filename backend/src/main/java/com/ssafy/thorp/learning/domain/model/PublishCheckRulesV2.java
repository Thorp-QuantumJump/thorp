package com.ssafy.thorp.learning.domain.model;

import java.util.Objects;

/**
 * 게시 전 검사 v2 규칙 파일의 내용(data-model §8-3, research R19·R20). 금지 표현·숫자 패턴·허용 예외는 v1과 같은 모양이라 {@link
 * PublishCheckRules}로 담고, v2에만 있는 값을 더한다.
 *
 * @param common 버전·금지 표현·결과성 숫자 패턴·허용 예외
 * @param exampleMarker 문단·목록에서 이 표지로 시작해 {@code )}로 닫히는 괄호 안의 숫자는 걸지 않는다
 * @param exampleRequired 예시를 요구하는가. v2 파일은 {@code false}다(FR-003)
 */
public record PublishCheckRulesV2(
        PublishCheckRules common,
        String exampleMarker,
        boolean exampleRequired,
        ReadingRules reading,
        QuizRules quiz) {

    public PublishCheckRulesV2 {
        Objects.requireNonNull(common, "common rules must not be null");
        Objects.requireNonNull(exampleMarker, "exampleMarker must not be null");
        Objects.requireNonNull(reading, "reading rules must not be null");
        Objects.requireNonNull(quiz, "quiz rules must not be null");
        if (exampleMarker.isBlank()) {
            throw new IllegalArgumentException("exampleMarker must not be blank");
        }
    }

    /** 게시 판의 {@code checkVersion}으로 남는다. */
    public String version() {
        return common.version();
    }

    /**
     * 읽기 시간 규칙(R20).
     *
     * @param minMinutes 게시 조건의 보이는 분 하한
     * @param maxMinutes 게시 조건의 보이는 분 상한
     */
    public record ReadingRules(
            int charsPerMinute,
            int minMinutes,
            int maxMinutes,
            int minDwellDivisor,
            int minDwellFloorSeconds) {

        public ReadingRules {
            if (charsPerMinute < 1 || minDwellDivisor < 1 || minDwellFloorSeconds < 0) {
                throw new IllegalArgumentException("invalid reading rules");
            }
            if (minMinutes < 1 || minMinutes > maxMinutes) {
                throw new IllegalArgumentException(
                        "invalid reading minutes range: " + minMinutes + ".." + maxMinutes);
            }
        }

        /** 본문 글자 수로 읽기 값을 계산한다. */
        public ReadingEstimate estimate(int bodyChars) {
            return ReadingEstimate.of(
                    bodyChars, charsPerMinute, minDwellDivisor, minDwellFloorSeconds);
        }
    }

    /** 확인 문제 수와 문제마다 보기 수의 게시 조건(FR-022). */
    public record QuizRules(int minQuestions, int maxQuestions, int minChoices, int maxChoices) {

        public QuizRules {
            if (minQuestions < 0
                    || minQuestions > maxQuestions
                    || maxQuestions > QuizQuestion.MAX_QUESTIONS) {
                throw new IllegalArgumentException(
                        "invalid quiz questions range: " + minQuestions + ".." + maxQuestions);
            }
            if (minChoices < 1
                    || minChoices > maxChoices
                    || maxChoices > QuizQuestion.MAX_CHOICES) {
                throw new IllegalArgumentException(
                        "invalid quiz choices range: " + minChoices + ".." + maxChoices);
            }
        }
    }
}
