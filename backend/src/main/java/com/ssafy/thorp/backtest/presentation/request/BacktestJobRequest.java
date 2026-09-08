package com.ssafy.thorp.backtest.presentation.request;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 백테스트 실행 요청. 설계 BacktestRequest 구조를 그대로 따른다.
 *
 * <p>소수 자릿수는 저장 컬럼에 맞춘다. initialCash는 NUMERIC(19,2), maxPositionPct는 NUMERIC(5,4)이라 더 세밀한 값을 받으면 저장
 * 시 반올림되어 도메인 값과 저장값이 달라진다.
 *
 * <p>기간 역전(start가 end보다 늦음)은 도메인 값 객체가 거부하고 전역 핸들러가 400으로 바꾼다. 전략 파라미터의 스키마 검증은 전략 카탈로그 작업의 몫이며
 * 여기서는 strategyId가 비어 있지 않은지만 본다.
 *
 * @param benchmark null = 벤치마크 미지정
 */
public record BacktestJobRequest(
        @NotNull @Valid RunRequest run,
        @NotNull @Valid UniverseRequest universe,
        @NotNull @Valid StrategyRequest strategy,
        @NotNull @Valid PortfolioRequest portfolio,
        @Valid BenchmarkRequest benchmark) {

    public record RunRequest(@NotNull LocalDate start, @NotNull LocalDate end) {}

    public record UniverseRequest(
            @NotEmpty @Size(max = 50)
                    List<@NotBlank @Pattern(regexp = InstrumentId.REGEX) String> instrumentIds) {}

    /**
     * @param parameters 전략별 자유 형식. 값에 null은 쓰지 않는다(계약상 null은 키 생략)
     */
    public record StrategyRequest(
            @NotBlank @Size(max = BacktestJob.MAX_STRATEGY_ID_LENGTH) String strategyId,
            @NotNull Map<String, Object> parameters) {}

    /**
     * @param positionSizing null = 전략이 정하는 대로(제한 없음)
     */
    public record PortfolioRequest(
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal initialCash,
            @Valid PositionSizingRequest positionSizing) {}

    /**
     * @param maxPositionPct null = 제한 없음. 값이 있으면 도메인과 같은 (0, 1] 범위
     */
    public record PositionSizingRequest(
            @NotNull PositionSizingMode type,
            @DecimalMin(value = "0", inclusive = false)
                    @DecimalMax("1")
                    @Digits(integer = 1, fraction = 4)
                    BigDecimal maxPositionPct) {}

    public record BenchmarkRequest(
            @NotBlank @Pattern(regexp = InstrumentId.REGEX) String instrumentId) {}
}
