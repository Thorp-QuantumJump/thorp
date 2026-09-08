package com.ssafy.thorp.backtest.presentation.controller;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.presentation.request.BacktestJobRequest;
import com.ssafy.thorp.backtest.presentation.response.BacktestJobResponse;
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
 * <p>POST /backtests → Job 저장(QUEUED) 후 Worker에 발행, 202와 { backtestId, status: QUEUED } 즉시 반환. GET
 * /backtests/{backtestId} → 상태 조회 (QUEUED → RUNNING → COMPLETED | FAILED).
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
        BacktestJobResult result = backtestService.create(toCommand(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(BacktestJobResponse.from(result)));
    }

    @GetMapping("/{backtestId}")
    public ResponseEntity<ApiResponse<BacktestJobResponse>> get(@PathVariable String backtestId) {
        BacktestJobResult result = backtestService.get(backtestId);
        return ResponseEntity.ok(ApiResponse.ok(BacktestJobResponse.from(result)));
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
