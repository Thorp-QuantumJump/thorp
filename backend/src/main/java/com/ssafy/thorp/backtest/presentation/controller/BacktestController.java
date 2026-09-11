package com.ssafy.thorp.backtest.presentation.controller;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.presentation.request.BacktestJobRequest;
import com.ssafy.thorp.backtest.presentation.request.TradePageQuery;
import com.ssafy.thorp.backtest.presentation.response.BacktestDetailResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestJobResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestPositionsResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestTimeseriesResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestTradePageResponse;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백테스트 Job API.
 *
 * <p>POST /backtests → Job 저장(QUEUED) 후 Worker에 발행, 202와 { backtestId, status: QUEUED } 즉시 반환.
 *
 * <p>결과 조회는 응답 크기 때문에 네 리소스로 나뉜다 — 계약과 예시는 {@code docs/specs/backtest-api/README.md} §2다. POST 응답은
 * 그대로 { backtestId, status }이며 커지지 않는다.
 */
@RestController
@RequestMapping("/backtests")
public class BacktestController {

    private final BacktestService backtestService;
    private final BacktestResultQueryService queryService;
    private final CurrentRequester currentRequester;

    public BacktestController(
            BacktestService backtestService,
            BacktestResultQueryService queryService,
            CurrentRequester currentRequester) {
        this.backtestService = backtestService;
        this.queryService = queryService;
        this.currentRequester = currentRequester;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BacktestJobResponse>> create(
            @Valid @RequestBody BacktestJobRequest request, HttpServletRequest http) {
        long requesterId = currentRequester.requireRequesterId(http);
        BacktestJobResult result = backtestService.create(toCommand(request, requesterId));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(BacktestJobResponse.from(result)));
    }

    /**
     * 상태 + 설정 + 요약 + 오류. <b>백테스트가 실패해도 200이다</b> — Job 조회 자체는 성공했고 실패는 {@code status}·{@code
     * error}로 전달한다(설계 "HTTP 상태 코드"). 프론트 폴링은 이 리소스로 한다.
     */
    @GetMapping("/{backtestId}")
    public ResponseEntity<ApiResponse<BacktestDetailResponse>> get(
            @PathVariable String backtestId, HttpServletRequest http) {
        long requesterId = currentRequester.requireRequesterId(http);
        BacktestDetailResult result = queryService.getDetail(backtestId, requesterId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestDetailResponse.from(result)));
    }

    /** 차트용 거래일별 시계열. 결과가 없으면 409다(§2.5) — 빈 배열을 만들어 내지 않는다. */
    @GetMapping("/{backtestId}/timeseries")
    public ResponseEntity<ApiResponse<BacktestTimeseriesResponse>> getTimeseries(
            @PathVariable String backtestId) {
        BacktestTimeseriesResult result = queryService.getTimeseries(backtestId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestTimeseriesResponse.from(result)));
    }

    /**
     * 포지션 단위 거래 내역. 정렬은 {@code entryDate} 오름차순 고정이라 정렬 파라미터가 없다.
     *
     * <p>범위 검증은 {@code @Valid}가, 기본값과 {@code instrumentId} 형식 검증은 {@link #toQuery}가 한다.
     */
    @GetMapping("/{backtestId}/trades")
    public ResponseEntity<ApiResponse<BacktestTradePageResponse>> getTrades(
            @PathVariable String backtestId, @Valid @ModelAttribute TradePageQuery query) {
        BacktestTradePageResult result = queryService.getTrades(backtestId, toQuery(query));
        return ResponseEntity.ok(ApiResponse.ok(BacktestTradePageResponse.from(result)));
    }

    /**
     * 특정 시점 보유 구성. {@code date}는 필수이고 스냅샷이 없는 날이면 직전 스냅샷을 돌려준다.
     *
     * <p>{@code date} 누락·형식 오류는 Spring MVC 표준 예외라 전역 처리기가 400 {@code INVALID_INPUT}으로 감싼다.
     */
    @GetMapping("/{backtestId}/positions")
    public ResponseEntity<ApiResponse<BacktestPositionsResponse>> getPositions(
            @PathVariable String backtestId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        BacktestPositionsResult result = queryService.getPositions(backtestId, date);
        return ResponseEntity.ok(ApiResponse.ok(BacktestPositionsResponse.from(result)));
    }

    /**
     * 요청 DTO → 커맨드. 기본값을 채우고 {@code instrumentId} 형식을 검증한다.
     *
     * <p>형식 검증에 기존 {@link InstrumentId} VO를 그대로 쓴다. 없으면 {@code instrumentId=abc}가 조용히 빈 페이지가 되는데,
     * 그건 "그 종목의 거래가 없다"는 거짓이다. VO가 던지는 {@code IllegalArgumentException}은 전역 처리기가 이미 400 {@code
     * INVALID_INPUT}으로 매핑한다.
     */
    private static FindBacktestTradesQuery toQuery(TradePageQuery query) {
        if (query.instrumentId() != null) {
            new InstrumentId(query.instrumentId());
        }
        return new FindBacktestTradesQuery(
                query.pageOrDefault(), query.sizeOrDefault(), query.instrumentId(), query.status());
    }

    /**
     * 요청 DTO를 유스케이스 입력으로 옮긴다. 기본값은 여기서만 채운다 — 서비스와 도메인은 "생략됐다"는 사실을 모른다.
     *
     * <p>{@code scenario} 생략은 주 분석({@code FULL_PERIOD})이다.
     */
    private static CreateBacktestCommand toCommand(BacktestJobRequest request, long requesterId) {
        BacktestJobRequest.ConditionsRequest conditions = request.conditions();
        return new CreateBacktestCommand(
                requesterId,
                request.executionType(),
                request.sectorAllocation().mappingVersion(),
                request.sectorAllocation().items().stream()
                        .map(
                                item ->
                                        new CreateBacktestCommand.AllocationItem(
                                                item.sectorId(),
                                                item.instrumentId(),
                                                item.targetWeight()))
                        .toList(),
                request.strategy().strategyId(),
                request.strategy().rulesVersion(),
                request.strategy().registryVersion(),
                request.strategy().parameters(),
                conditions.start(),
                conditions.end(),
                conditions.initialCash(),
                conditions.costProfileVersion(),
                conditions.evaluationSplitVersion(),
                conditions.warmupTradingDays(),
                toScenario(conditions.scenario()),
                conditions.scenarioParams());
    }

    /** 모르는 시나리오 이름은 400이다. enum 변환 실패를 500으로 흘리지 않는다. */
    private static Scenario toScenario(String value) {
        if (value == null || value.isBlank()) {
            return Scenario.FULL_PERIOD;
        }
        try {
            return Scenario.valueOf(value);
        } catch (IllegalArgumentException exc) {
            throw new IllegalArgumentException("모르는 scenario 입니다: " + value);
        }
    }
}
