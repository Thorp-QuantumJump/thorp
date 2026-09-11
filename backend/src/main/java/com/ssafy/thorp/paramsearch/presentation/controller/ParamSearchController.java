package com.ssafy.thorp.paramsearch.presentation.controller;

import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchPreviewResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.presentation.request.ParamSearchCreateRequest;
import com.ssafy.thorp.paramsearch.presentation.request.ParamSearchPreviewRequest;
import com.ssafy.thorp.paramsearch.presentation.response.ParamSearchPreviewResponse;
import com.ssafy.thorp.paramsearch.presentation.response.ParamSearchResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설정 조합 자동 비교 API(contracts/param-search-api.md).
 *
 * <p>{@code POST /param-searches/preview}는 저장하지 않고 확인 화면의 네 항목만 계산한다(200). {@code POST
 * /param-searches}는 묶음을 저장하고 발행 창만큼 큐에 넣은 뒤 즉시 202를 돌려준다 — 백엔드는 계산하지 않는다(constitution 아키텍처 원칙 1,
 * 3).
 *
 * <p>{@code GET /param-searches/{searchId}}는 폴링 대상이고 <b>실패해도 200</b>이다. 상태는 {@code status}로 판단한다.
 */
@RestController
@RequestMapping("/param-searches")
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class ParamSearchController {

    private final ParamSearchService paramSearchService;
    private final CurrentRequester currentRequester;

    public ParamSearchController(
            ParamSearchService paramSearchService, CurrentRequester currentRequester) {
        this.paramSearchService = paramSearchService;
        this.currentRequester = currentRequester;
    }

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<ParamSearchPreviewResponse>> preview(
            @Valid @RequestBody ParamSearchPreviewRequest request, HttpServletRequest http) {
        ParamSearchPreviewResult result =
                paramSearchService.preview(request.toCommand(requesterId(http)));
        return ResponseEntity.ok(ApiResponse.ok(ParamSearchPreviewResponse.from(result)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ParamSearchResponse>> create(
            @Valid @RequestBody ParamSearchCreateRequest request, HttpServletRequest http) {
        ParamSearchResult result = paramSearchService.create(request.toCommand(requesterId(http)));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(ParamSearchResponse.accepted(result)));
    }

    @GetMapping("/{searchId}")
    public ResponseEntity<ApiResponse<ParamSearchResponse>> get(@PathVariable String searchId) {
        return ResponseEntity.ok(
                ApiResponse.ok(ParamSearchResponse.from(paramSearchService.get(searchId))));
    }

    /**
     * 요청자 식별. 인증(spec 014) 전에는 개발용 헤더를 켠 환경에서만 값이 있고 기본은 null이다(research R4).
     *
     * <p>{@code requireRequesterId}를 쓰지 않는 이유: 자동 탐색은 아직 소유자 필터가 없어(006에서 붙는다) 헤더가 없다고 접수를 막을 근거가
     * 없다. 소유자는 묶음에 그대로 기록되고, 필터가 붙는 시점에 이 메서드만 바꾼다.
     */
    private Long requesterId(HttpServletRequest http) {
        return currentRequester.findRequesterId(http).orElse(null);
    }
}
