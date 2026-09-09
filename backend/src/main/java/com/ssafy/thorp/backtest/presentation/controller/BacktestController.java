package com.ssafy.thorp.backtest.presentation.controller;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.presentation.request.BacktestJobRequest;
import com.ssafy.thorp.backtest.presentation.response.BacktestDetailResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestJobResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestTimeseriesResponse;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public BacktestController(
            BacktestService backtestService, BacktestResultQueryService queryService) {
        this.backtestService = backtestService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BacktestJobResponse>> create(
            @Valid @RequestBody BacktestJobRequest request) {
        BacktestJobResult result = backtestService.create(toCommand(request));
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
        BacktestDetailResult result = queryService.getDetail(backtestId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestDetailResponse.from(result)));
    }

    /** 차트용 거래일별 시계열. 결과가 없으면 409다(§2.5) — 빈 배열을 만들어 내지 않는다. */
    @GetMapping("/{backtestId}/timeseries")
    public ResponseEntity<ApiResponse<BacktestTimeseriesResponse>> getTimeseries(
            @PathVariable String backtestId) {
        BacktestTimeseriesResult result = queryService.getTimeseries(backtestId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestTimeseriesResponse.from(result)));
    }

    /** positionSizing이 생략되면 여기서만 기본값을 채운다. 서비스와 도메인은 기본값을 모른다. */
    private static CreateBacktestCommand toCommand(BacktestJobRequest request) {
        BacktestJobRequest.PositionSizingRequest sizing = request.portfolio().positionSizing();
        PositionSizingMode sizingType =
                sizing == null ? PositionSizingMode.STRATEGY_DEFINED : sizing.type();
        BigDecimal maxPositionPct = sizing == null ? null : sizing.maxPositionPct();
        return new CreateBacktestCommand(
                request.strategy().strategyId(),
                request.strategy().parameters(),
                request.universe().instrumentIds(),
                request.run().start(),
                request.run().end(),
                request.portfolio().initialCash(),
                sizingType,
                maxPositionPct,
                request.benchmark() == null ? null : request.benchmark().instrumentId());
    }
}
