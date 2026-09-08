package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 백테스트 생성 유스케이스 입력. HTTP 요청 DTO와 분리한다.
 *
 * <p>요청의 중첩 구조는 컨트롤러가 여기로 펼친다. {@code positionSizingType}은 not null이며 요청에서 생략됐을 때의 기본값도 컨트롤러가 채운다.
 * 서비스와 도메인은 기본값을 모른다.
 *
 * @param maxPositionPct null = 제한 없음
 * @param benchmarkInstrumentId null = 벤치마크 미지정
 */
public record CreateBacktestCommand(
        String strategyId,
        Map<String, Object> parameters,
        List<String> instrumentIds,
        LocalDate start,
        LocalDate end,
        BigDecimal initialCash,
        PositionSizingMode positionSizingType,
        BigDecimal maxPositionPct,
        String benchmarkInstrumentId) {}
