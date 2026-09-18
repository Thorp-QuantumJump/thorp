package com.ssafy.thorp.regime.domain.model;

import java.time.YearMonth;

/**
 * 배치가 기록한 기준월 (data-model §3).
 *
 * <p><b>화면이 고를 수 있는 달</b>이며 008을 실시간으로 읽어 만들지 않는다 — 008은 새 발표분이 들어오는 즉시 T_g를 올리지만 배치는 그 뒤에 돌아서, 그
 * 사이 화면이 008의 최신 T_g로 검색하면 아직 결과가 없는 달이라 404가 난다(계약 §0.2).
 *
 * <p><b>분야 이름은 여기 없다.</b> 이름·단위·순서는 {@code MacroIndicatorPort}가 돌려준다 — 표시용 참조 자료라 008의 최신 값을 그대로
 * 보이는 편이 맞고, 화면 분류가 바뀌어도 계산 가중은 따라가지 않는다(spec FR-011).
 */
public record RegimeBaselineMonth(
        String scopeId,
        YearMonth baselineMonth,
        YearMonth earliestMonth,
        String registryVersion,
        String dataVersion) {}
