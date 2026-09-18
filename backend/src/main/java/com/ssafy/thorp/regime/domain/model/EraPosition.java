package com.ssafy.thorp.regime.domain.model;

import java.time.YearMonth;

/**
 * 지표 하나의 <b>시대별 보조 위치</b> (spec FR-017, data-model §4).
 *
 * <p>사례가 <b>당시 기준으로</b> 어땠는지를 문장 하나로만 말한다. 검색 순위를 정한 공통 척도와는 다른 자이며, 그 사실이 흐려지지 않도록 <b>z 값·분위수·백분율을
 * 담지 않는다</b> — 숫자로 놓이면 유사도처럼 읽힌다(FR-017·FR-022).
 *
 * <p>{@code method}·{@code windowStart}·{@code windowEnd}·{@code months}는 수기 대조용이고 화면에 그대로 내보내지
 * 않는다. 그 구간과 방법이 있으면 독립 계산으로 같은 판정을 다시 만들 수 있다.
 *
 * <p>이 값은 같은 자료·등록표·모델 버전에서 <b>기준월과 무관</b>하다 — 같은 {@code endMonth}면 어느 검색에서 열어도 같은 문장이다(SC-011).
 */
public record EraPosition(
        String indicatorId,
        String sentence,
        String method,
        YearMonth windowStart,
        YearMonth windowEnd,
        int months) {}
