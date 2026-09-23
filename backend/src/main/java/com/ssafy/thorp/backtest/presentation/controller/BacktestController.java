package com.ssafy.thorp.backtest.presentation.controller;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestCancelResult;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestExceptionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.application.service.BacktestCancelService;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestRetryService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.application.service.CostProfileService;
import com.ssafy.thorp.backtest.application.service.StrategyRulesService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.presentation.request.BacktestJobRequest;
import com.ssafy.thorp.backtest.presentation.request.TradePageQuery;
import com.ssafy.thorp.backtest.presentation.response.BacktestCancelResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestDetailResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestExceptionsResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestJobResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestPositionsResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestRegistryResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestRetryResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestTimeseriesResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestTradePageResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestWeightsResponse;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
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
 * <p>결과 조회는 응답 크기 때문에 여섯 리소스로 나뉜다 — 계약과 예시는 {@code docs/specs/backtest-api/README.md} §2다. POST 응답은
 * 그대로 { backtestId, status }이며 커지지 않는다.
 */
@RestController
@RequestMapping("/backtests")
public class BacktestController {

    private final BacktestService backtestService;
    private final BacktestResultQueryService queryService;
    private final BacktestCancelService cancelService;
    private final BacktestRetryService retryService;
    private final CurrentRequester currentRequester;
    private final StrategyRulesService strategyRulesService;
    private final CostProfileService costProfileService;

    public BacktestController(
            BacktestService backtestService,
            BacktestResultQueryService queryService,
            BacktestCancelService cancelService,
            BacktestRetryService retryService,
            CurrentRequester currentRequester,
            StrategyRulesService strategyRulesService,
            CostProfileService costProfileService) {
        this.backtestService = backtestService;
        this.queryService = queryService;
        this.cancelService = cancelService;
        this.retryService = retryService;
        this.currentRequester = currentRequester;
        this.strategyRulesService = strategyRulesService;
        this.costProfileService = costProfileService;
    }

    /**
     * 화면이 요청에 실을 규칙 버전과 기본 비용 조건(T025, README §1.11). 대체되지 않은 등록표 행을 그대로 내보낸다(T029).
     *
     * <p>로그인을 요구하지 않는다 — 사용자 데이터가 아니라 등록표이고, 003 등록표 조회와 같은 성격이다.
     *
     * <p>{@code /{backtestId}}와 겹치지 않는다: Spring 은 리터럴 경로를 패턴 변수보다 먼저 고른다.
     */
    @GetMapping("/registry")
    public ResponseEntity<ApiResponse<BacktestRegistryResponse>> registry() {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        BacktestRegistryResponse.of(
                                strategyRulesService.findCurrent(),
                                costProfileService.resolve(null))));
    }

    /**
     * 이 요청만 취소한다(R-04). 같은 실행에 합류한 다른 요청은 영향이 없고, 마지막 활성 요청이면 실행을 멈춘다. 종결 상태면 409 {@code
     * NOT_CANCELLABLE}.
     */
    @PostMapping("/{backtestId}/cancel")
    public ResponseEntity<ApiResponse<BacktestCancelResponse>> cancel(
            @PathVariable String backtestId) {
        BacktestCancelResult result =
                cancelService.cancel(backtestId, currentRequester.requireRequesterId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(BacktestCancelResponse.from(result)));
    }

    /** 실패·취소한 분석을 같은 조건으로 다시 접수한다. 재시도도 동일 버전 판정을 받으므로 합류·재사용이 될 수 있다(T010). */
    @PostMapping("/{backtestId}/retry")
    public ResponseEntity<ApiResponse<BacktestRetryResponse>> retry(
            @PathVariable String backtestId) {
        BacktestJobResult result =
                retryService.retry(backtestId, currentRequester.requireRequesterId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(BacktestRetryResponse.from(result)));
    }

    /**
     * 분석을 접수한다. <b>로그인하지 않아도 된다</b>(spec 005 B01) — 비로그인 요청은 요청자 없이 저장되고, 결과는 응답의 {@code
     * publicToken}으로만 연다. 취소·재시도·조회({@code /backtests/{id}/**})는 그대로 소유자 경로다.
     *
     * <p>비로그인 요청은 사용자별 대기 한도를 셀 수 없어 전체 한도만 받는다({@code ExecutionLimitPolicy}).
     *
     * <p><b>비로그인 요청은 단건({@code SINGLE})만 받는다</b>(spec 005 T089, 원칙 IX). 실행 한도는 단건 큐에만 있어서, 본문의
     * {@code executionType}을 그대로 믿으면 비로그인 클라이언트가 {@code SEARCH_BUNDLE}로 보내 한도 없이 대기열을 채울 수 있다.
     * 묶음·경제환경 파생 실행은 로그인한 사용자의 유스케이스(007·010)가 만든다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BacktestJobResponse>> create(
            @Valid @RequestBody BacktestJobRequest request) {
        Long requesterId = currentRequester.findRequesterId().orElse(null);
        if (requesterId == null && request.executionType() != ExecutionType.SINGLE) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "로그인하지 않은 요청은 단건 분석(SINGLE)만 접수합니다.");
        }
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
            @PathVariable String backtestId) {
        long requesterId = currentRequester.requireRequesterId();
        BacktestDetailResult result = queryService.getDetail(backtestId, requesterId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestDetailResponse.from(result)));
    }

    /** 차트용 거래일별 시계열. 결과가 없으면 409다(§2.5) — 빈 배열을 만들어 내지 않는다. */
    @GetMapping("/{backtestId}/timeseries")
    public ResponseEntity<ApiResponse<BacktestTimeseriesResponse>> getTimeseries(
            @PathVariable String backtestId) {
        BacktestTimeseriesResult result =
                queryService.getTimeseries(backtestId, currentRequester.requireRequesterId());
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
        BacktestTradePageResult result =
                queryService.getTrades(
                        backtestId, currentRequester.requireRequesterId(), toQuery(query));
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
        BacktestPositionsResult result =
                queryService.getPositions(backtestId, currentRequester.requireRequesterId(), date);
        return ResponseEntity.ok(ApiResponse.ok(BacktestPositionsResponse.from(result)));
    }

    /** 리밸런싱마다 목표·실제 비중(FR-026). {@code weightsSource}로 사용자 입력 비중과 계산 비중을 구분한다. */
    @GetMapping("/{backtestId}/weights")
    public ResponseEntity<ApiResponse<BacktestWeightsResponse>> getWeights(
            @PathVariable String backtestId) {
        BacktestWeightsResult result =
                queryService.getWeights(backtestId, currentRequester.requireRequesterId());
        return ResponseEntity.ok(ApiResponse.ok(BacktestWeightsResponse.from(result)));
    }

    /** 예외 처리 기록(FR-020·FR-030). 005가 "결과 옆 한 문장"에 쓰는 원천이다. */
    @GetMapping("/{backtestId}/exceptions")
    public ResponseEntity<ApiResponse<BacktestExceptionsResponse>> getExceptions(
            @PathVariable String backtestId) {
        BacktestExceptionsResult result =
                queryService.getExceptions(backtestId, currentRequester.requireRequesterId());
        return ResponseEntity.ok(ApiResponse.ok(BacktestExceptionsResponse.from(result)));
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
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            // 뒤집힌 기간을 빈 페이지로 돌려주면 "그 기간에 거래가 없다"는 거짓이 된다.
            throw new IllegalArgumentException(
                    "from 은 to 보다 늦을 수 없습니다: from=" + query.from() + ", to=" + query.to());
        }
        return new FindBacktestTradesQuery(
                query.pageOrDefault(),
                query.sizeOrDefault(),
                query.instrumentId(),
                query.status(),
                query.from(),
                query.to());
    }

    /**
     * 요청 DTO를 유스케이스 입력으로 옮긴다. 기본값은 여기서만 채운다 — 서비스와 도메인은 "생략됐다"는 사실을 모른다.
     *
     * <p>{@code scenario} 생략은 주 분석({@code FULL_PERIOD})이다.
     */
    private static CreateBacktestCommand toCommand(BacktestJobRequest request, Long requesterId) {
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
