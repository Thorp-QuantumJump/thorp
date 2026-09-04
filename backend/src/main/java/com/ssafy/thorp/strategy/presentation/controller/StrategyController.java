package com.ssafy.thorp.strategy.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.strategy.application.result.StrategyConfigValidationResult;
import com.ssafy.thorp.strategy.application.result.StrategyDefinitionResult;
import com.ssafy.thorp.strategy.application.service.StrategyCatalogService;
import com.ssafy.thorp.strategy.application.service.StrategyConfigValidationService;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.presentation.request.StrategyConfigRequest;
import com.ssafy.thorp.strategy.presentation.response.StrategyConfigValidationResponse;
import com.ssafy.thorp.strategy.presentation.response.StrategyDefinitionResponse;
import com.ssafy.thorp.strategy.presentation.response.StrategySummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전략 카탈로그 API. FE에서는 개발 서버 프록시를 거쳐 {@code /api/strategies}로 보인다.
 *
 * <pre>
 * GET  /strategies?executionScope=BACKTEST  → 전략 목록
 * GET  /strategies/{strategyId}             → 전략 정의 전체 (없으면 404)
 * POST /strategies/config/validate          → 사용자가 고른 값 집합 판정
 * </pre>
 *
 * <p>세 경로 모두 전략 계산을 하지 않는다. 이 컨트롤러가 다루는 것은 "무엇을 고를 수 있고 무슨 값을 넣어야 하는가"까지이며, 실행은 백테스트 API의 몫이다.
 */
@RestController
@RequestMapping("/strategies")
public class StrategyController {

    private final StrategyCatalogService catalogService;
    private final StrategyConfigValidationService validationService;

    public StrategyController(
            StrategyCatalogService catalogService,
            StrategyConfigValidationService validationService) {
        this.catalogService = catalogService;
        this.validationService = validationService;
    }

    /** 전략이 하나도 없으면 빈 배열이며 오류가 아니다. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<StrategySummaryResponse>>> list(
            @RequestParam(defaultValue = "BACKTEST") ExecutionScope executionScope) {
        List<StrategySummaryResponse> body =
                catalogService.findAll(executionScope).stream()
                        .map(StrategySummaryResponse::from)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @GetMapping("/{strategyId}")
    public ResponseEntity<ApiResponse<StrategyDefinitionResponse>> get(
            @PathVariable String strategyId) {
        StrategyDefinitionResult result = catalogService.findById(strategyId);
        return ResponseEntity.ok(ApiResponse.ok(StrategyDefinitionResponse.from(result)));
    }

    /**
     * 값 집합 판정. 판정 실패도 200이며 결과는 본문에 담긴다 — 요청 자체는 성공했고 답이 "쓸 수 없다"일 뿐이다. 400은 요청의 형태가 깨졌을 때만 나온다.
     */
    @PostMapping("/config/validate")
    public ResponseEntity<ApiResponse<StrategyConfigValidationResponse>> validateConfig(
            @Valid @RequestBody StrategyConfigRequest request) {
        StrategyConfigValidationResult result = validationService.validate(request.toCommand());
        return ResponseEntity.ok(ApiResponse.ok(StrategyConfigValidationResponse.from(result)));
    }
}
