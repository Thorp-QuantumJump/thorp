package com.ssafy.thorp.strategy.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.strategy.application.service.StrategyRegistryQueryService;
import com.ssafy.thorp.strategy.presentation.request.RegistryQuery;
import com.ssafy.thorp.strategy.presentation.response.StrategyRegistryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전략·파라미터 등록표 조회 API (contracts/strategy-registry-api.md §1, User Story 1).
 *
 * <p>읽기만 있다. 등록표를 고치는 엔드포인트를 두지 않는다 — 원본은 자원 파일이고 값이 바뀌면 파일의 버전을 올린다(FR-005).
 *
 * <p><b>비로그인 허용(계약 §1).</b> 전략 진단은 로그인 없이 쓸 수 있다(spec 014). {@code SecurityConfig}가 {@code
 * anyRequest().permitAll()}이라 이 경로에 따로 설정할 것이 없다 — 인증을 요구하는 경로만 그곳에 열거된다.
 *
 * <p>등록표를 읽지 못하면 503 {@code REGISTRY_UNAVAILABLE}이다. 서비스가 예외를 전파하고 전역 처리기가 상태 코드를 붙인다. <b>임시 값으로 대신
 * 응답하지 않는다</b>(FR-018).
 */
@RestController
@RequestMapping("/strategies")
public class StrategyRegistryController {

    private final StrategyRegistryQueryService queryService;

    public StrategyRegistryController(StrategyRegistryQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 활성 전략과 설정 항목을 돌려준다.
     *
     * <p>{@code universeSize}를 주면 유효성 제약을 미리 적용해 고를 수 없는 전략과 후보를 <b>표시한 채로</b> 싣는다. 목록에서 빼지 않는 것은
     * 사용자가 왜 고를 수 없는지 알아야 하기 때문이다(계약 §1).
     */
    @GetMapping("/registry")
    public ResponseEntity<ApiResponse<StrategyRegistryResponse>> getRegistry(
            @Valid @ModelAttribute RegistryQuery query) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        StrategyRegistryResponse.from(queryService.query(query.universeSize()))));
    }
}
