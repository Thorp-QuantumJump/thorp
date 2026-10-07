package com.ssafy.thorp.learning.presentation.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /learning/me/articles/{articleId}/position} 본문(learning-me-api §3).
 *
 * @param percent 본문 길이에 대한 읽은 비율, 0~100 정수. 없거나 범위 밖이면 400 {@code INVALID_INPUT}
 */
public record PositionRequest(@NotNull @Min(0) @Max(100) Integer percent) {}
