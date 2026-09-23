package com.ssafy.thorp.backtest.presentation.controller;

import com.ssafy.thorp.backtest.application.service.ExecutionMetricsQueryService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.presentation.response.ExecutionMetricsResponse;
import com.ssafy.thorp.backtest.presentation.support.OperatorGate;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 측정 집계(spec 004 US4, R-10). 사용자 화면에는 노출하지 않는다 — {@link OperatorGate}가 막는다.
 *
 * <p>{@code GET /internal/execution-metrics?type=&from=&to=}. 값은 뷰 {@code v_run_metric_summary}와 같은
 * 문장으로 세고 기간만 더한다. 계약은 {@code docs/specs/backtest-api/README.md} §1.10.
 */
@RestController
@RequestMapping("/internal/execution-metrics")
public class ExecutionMetricsController {

    private final ExecutionMetricsQueryService queryService;
    private final OperatorGate operatorGate;

    public ExecutionMetricsController(
            ExecutionMetricsQueryService queryService, OperatorGate operatorGate) {
        this.queryService = queryService;
        this.operatorGate = operatorGate;
    }

    @GetMapping
    public ApiResponse<ExecutionMetricsResponse> summarize(
            @RequestParam(required = false) ExecutionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            HttpServletRequest request) {
        operatorGate.requireOperator(request);
        return ApiResponse.ok(
                ExecutionMetricsResponse.of(from, to, queryService.summarize(type, from, to)));
    }
}
