package com.ssafy.thorp.learning.presentation.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 첫 답·다시 풀기 본문(learning-me-api §5·§6). 값이 비면 400 {@code INVALID_INPUT}이다. 형식이 틀리거나 공개 판에 없는 문제·보기는
 * 운영자가 문제를 바꾼 경우와 가르지 않고 404 {@code LEARNING_QUESTION_NOT_FOUND}다.
 */
public record AnswerRequest(@NotBlank String questionId, @NotBlank String choiceId) {}
