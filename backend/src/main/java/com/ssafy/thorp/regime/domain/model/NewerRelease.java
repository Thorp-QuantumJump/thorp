package com.ssafy.thorp.regime.domain.model;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 기준월보다 <b>최신인 개별 발표</b> (spec FR-016, US3 시나리오 3).
 *
 * <p>종합 검색에서 T_all이 어느 분야 T_g보다 이를 때 생긴다. 별도 정보로 표시하고 <b>질의값에 섞지 않는다</b> — 섞으면 같은 조건 비교가 깨진다.
 */
public record NewerRelease(String indicatorId, YearMonth month, LocalDate publishedAt) {}
