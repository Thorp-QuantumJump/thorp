package com.ssafy.thorp.strategy.presentation.request;

import jakarta.validation.constraints.Min;

/**
 * {@code GET /strategies/registry}의 질의 파라미터. 계약: contracts/strategy-registry-api.md §1.
 *
 * <p><b>{@code Integer}인 이유</b>: primitive {@code int}로 받으면 생략과 명시적 {@code universeSize=0}을 구분할 수
 * 없다. 계약은 생략을 "제약을 적용하지 않는다"로, 0 이하를 거절로 규정한다.
 *
 * <p><b>DTO를 두고 {@code @RequestParam @Min}을 쓰지 않는 이유</b>: 그 경로는 구성에 따라 예외 종류가 갈려 {@code
 * ConstraintViolationException}이 500으로 새어 나갈 수 있다. {@code @Valid @ModelAttribute}는 요청 DTO 검증의 기존
 * 경로를 그대로 탄다({@code TradePageQuery}와 같은 이유).
 *
 * @param universeSize 사용자가 002에서 고른 섹터 수. 생략할 수 있고, 주면 1 이상이어야 한다
 */
public record RegistryQuery(@Min(1) Integer universeSize) {}
