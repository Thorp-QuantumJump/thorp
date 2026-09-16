package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import java.math.BigDecimal;

/**
 * 실행 유형 하나의 집계({@code v_run_metric_summary} 한 행, spec 004 R-10). PD-06 한도 값과 R-20 워커 비율 결정의 근거다.
 *
 * <p>분위수는 {@code percentile_cont} 라 소수가 나올 수 있고, 표본이 없으면 null 이다 — 0 으로 채우지 않는다(원칙 VI).
 *
 * @param flexServedRate 공용(flex) 워커가 집은 비율. 단건 유형에서 이 값이 "공용 워커가 단건을 도운 비율"이다
 * @param reuseCount 이 유형의 요청이 새 계산 없이 합류·재사용한 수(FR-008)
 * @param limitHitCount 이 유형이 한도에 닿은 수(접수 거절 + 실행 정지)
 * @param dataRowsP50 실행이 읽은 자료 행 수의 중앙값(데이터 원가, FR-008·T019). 표본 없으면 null
 * @param dataBytesP50 읽은 가격 프레임 바이트의 중앙값. 표본 없으면 null
 */
public record ExecutionMetricSummary(
        ExecutionType executionType,
        long count,
        BigDecimal failRate,
        BigDecimal cancelRate,
        BigDecimal autoRetryRate,
        BigDecimal waitP50Ms,
        BigDecimal waitP95Ms,
        BigDecimal waitP99Ms,
        BigDecimal runP50Ms,
        BigDecimal runP95Ms,
        BigDecimal peakRssP95Mb,
        BigDecimal flexServedRate,
        long reuseCount,
        long limitHitCount,
        BigDecimal dataRowsP50,
        BigDecimal dataBytesP50) {}
