package com.ssafy.thorp.backtest.presentation.controller;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.EquityCurveResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.presentation.request.BacktestJobRequest;
import com.ssafy.thorp.backtest.presentation.response.BacktestJobResponse;
import com.ssafy.thorp.backtest.presentation.response.BacktestTimeseriesResponse;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백테스트 Job API. 계약은 {@code specs/003-backtest-result-analysis/contracts/backtests-api.md}.
 *
 * <ul>
 *   <li>POST /backtests → Job 저장(QUEUED) 후 Worker에 발행, 202와 { backtestId, status: QUEUED, config,
 *       summary: null } 즉시 반환. initialCash 생략 시 10,000,000 KRW
 *   <li>GET /backtests/{id} → 상태 조회(QUEUED → RUNNING → COMPLETED | FAILED). COMPLETED이고 결과가 있으면
 *       summary 포함, 그 외 summary null. 없는 id는 404
 *   <li>GET /backtests/{id}/timeseries → 자산 가치 시계열. COMPLETED가 아니거나 결과가 없으면 409 RESULT_NOT_READY,
 *       없는 id는 404
 * </ul>
 */
@RestController
@RequestMapping("/backtests")
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BacktestJobResponse>> create(
            @Valid @RequestBody BacktestJobRequest request) {
        BacktestJobResult result =
                backtestService.create(
                        new CreateBacktestCommand(
                                request.strategy(),
                                request.startDate(),
                                request.endDate(),
                                request.initialCashOrDefault()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(BacktestJobResponse.from(result)));
    }

    @GetMapping("/{backtestId}")
    public ResponseEntity<ApiResponse<BacktestJobResponse>> get(@PathVariable Long backtestId) {
        BacktestJobResult result = backtestService.get(backtestId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestJobResponse.from(result)));
    }

    @GetMapping("/{backtestId}/timeseries")
    public ResponseEntity<ApiResponse<BacktestTimeseriesResponse>> getTimeseries(
            @PathVariable Long backtestId) {
        EquityCurveResult result = backtestService.getTimeseries(backtestId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestTimeseriesResponse.from(result)));
    }
}
