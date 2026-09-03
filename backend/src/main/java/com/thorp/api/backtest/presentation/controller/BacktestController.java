package com.thorp.api.backtest.presentation.controller;

import com.thorp.api.backtest.application.command.CreateBacktestCommand;
import com.thorp.api.backtest.application.result.BacktestJobResult;
import com.thorp.api.backtest.application.service.BacktestService;
import com.thorp.api.backtest.presentation.request.BacktestJobRequest;
import com.thorp.api.backtest.presentation.response.BacktestJobResponse;
import com.thorp.api.common.web.response.ApiResponse;
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
 * 백테스트 Job API.
 *
 * <p>POST /backtests → Job 저장(QUEUED) 후 Worker에 발행, 202와 { backtestId, status: QUEUED } 즉시 반환. GET
 * /backtests/{id} → 상태 조회 (QUEUED → RUNNING → COMPLETED | FAILED).
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
                                request.strategy(), request.startDate(), request.endDate()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(BacktestJobResponse.from(result)));
    }

    @GetMapping("/{backtestId}")
    public ResponseEntity<ApiResponse<BacktestJobResponse>> get(@PathVariable Long backtestId) {
        BacktestJobResult result = backtestService.get(backtestId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestJobResponse.from(result)));
    }
}
