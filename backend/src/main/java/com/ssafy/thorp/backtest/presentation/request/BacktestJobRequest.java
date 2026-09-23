package com.ssafy.thorp.backtest.presentation.request;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 분석 요청 접수 본문(spec 004 contracts/rest-api.md, schema v2).
 *
 * <p>v1과 모양이 다르다. 종목 목록 대신 <b>섹터 배분</b>({@code sectorAllocation})을 받고, 전략에 규칙 버전이 붙고, 기간·금액·시나리오가
 * {@code conditions}로 모인다. 003 화면이 만드는 요청 그대로다.
 *
 * <p>여기서는 <b>모양</b>만 본다 — 비중 합, 규칙 등록 여부, 워밍업 가용성처럼 등록표·자료를 봐야 아는 것은 서비스가 판단한다. 이 구분을 지키지 않으면 검증이 두
 * 군데로 갈라져 한쪽만 고치는 일이 생긴다.
 */
public record BacktestJobRequest(
        @NotNull ExecutionType executionType,
        @NotNull @Valid SectorAllocationRequest sectorAllocation,
        @NotNull @Valid StrategyRequest strategy,
        @NotNull @Valid ConditionsRequest conditions) {

    /**
     * @param mappingVersion 섹터-ETF 대응표 버전. 서버가 이 버전으로 (섹터, 종목) 쌍을 검증한다
     */
    public record SectorAllocationRequest(
            @NotBlank @Size(max = 64) String mappingVersion,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid AllocationItemRequest> items) {}

    /**
     * @param targetWeight FIXED_WEIGHT 에서만 필수이고 다른 전략에서는 금지다(서비스가 판단). 합은 1이어야 한다
     */
    public record AllocationItemRequest(
            @NotBlank @Size(max = 64) String sectorId,
            @NotBlank @Pattern(regexp = InstrumentId.REGEX) String instrumentId,
            @Digits(integer = 1, fraction = 4) BigDecimal targetWeight) {}

    /**
     * @param registryVersion 003 파라미터 등록표 버전. <b>이 값이 실린 요청만</b> 접수 시점에 등록표로 설정을 다시 판정한다(003
     *     FR-006, {@code StrategySettingsValidator}) — 버전이 다르면 409, 미등록·비활성 전략은 422, 축이나 값이 등록표와
     *     어긋나면 400, 등록표 규칙 위반은 422다. <b>[미완료]</b> 값을 빼고 보내면 종전처럼 검증 없이 통과하고 경고 로그만 남는다. 004 워커가 아직
     *     등록표 축 이름을 읽지 못해 관문을 전부에 걸 수 없기 때문이며, 워커 계약 정합 뒤 이 필드를 필수로 바꾼다
     */
    public record StrategyRequest(
            @NotBlank @Size(max = BacktestRun.MAX_STRATEGY_ID_LENGTH) String strategyId,
            @NotBlank @Size(max = 32) String rulesVersion,
            @Size(max = 64) String registryVersion,
            @NotNull Map<String, Object> parameters) {}

    /**
     * @param costProfileVersion null = 서버 기본 비용 조건
     * @param warmupTradingDays null = 선택 전략의 관측 길이를 쓴다. 값이 있으면 비교 묶음이 계산한 공통 워밍업이며 전략 요구값보다 작으면
     *     422다(R-15)
     * @param scenario null = FULL_PERIOD(주 분석)
     * @param scenarioParams TARGET_RISK 면 {@code targetVolatility}, FRESH_CASH_FROM 이면 {@code
     *     startDate}. 그 외 시나리오에서는 금지다
     */
    public record ConditionsRequest(
            @NotNull LocalDate start,
            @NotNull LocalDate end,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal initialCash,
            @Size(max = 32) String costProfileVersion,
            @Size(max = 32) String evaluationSplitVersion,
            @PositiveOrZero Integer warmupTradingDays,
            String scenario,
            Map<String, Object> scenarioParams) {}
}
