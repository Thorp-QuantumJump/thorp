package com.ssafy.thorp.paramsearch.presentation.controller;

import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.paramsearch.application.command.FindCandidatesQuery;
import com.ssafy.thorp.paramsearch.application.result.CandidatePageResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchPreviewResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.CandidateQueryService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.presentation.request.ParamSearchCreateRequest;
import com.ssafy.thorp.paramsearch.presentation.request.ParamSearchPreviewRequest;
import com.ssafy.thorp.paramsearch.presentation.response.CandidatePageResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final CandidateQueryService candidateQueryService;
    private final CurrentRequester currentRequester;

    public ParamSearchController(
            ParamSearchService paramSearchService,
            CandidateQueryService candidateQueryService,
            CurrentRequester currentRequester) {
        this.paramSearchService = paramSearchService;
        this.candidateQueryService = candidateQueryService;
        this.currentRequester = currentRequester;
    }

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<ParamSearchPreviewResponse>> preview(
            @Valid @RequestBody ParamSearchPreviewRequest request, HttpServletRequest http) {
        ParamSearchPreviewResult result =
                paramSearchService.preview(request.toCommand(optionalRequesterId(http)));
        return ResponseEntity.ok(ApiResponse.ok(ParamSearchPreviewResponse.from(result)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ParamSearchResponse>> create(
            @Valid @RequestBody ParamSearchCreateRequest request, HttpServletRequest http) {
        ParamSearchResult result =
                paramSearchService.create(
                        request.toCommand(currentRequester.requireRequesterId(http)));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(ParamSearchResponse.accepted(result)));
    }

    @GetMapping("/{searchId}")
    public ResponseEntity<ApiResponse<ParamSearchResponse>> get(@PathVariable String searchId) {
        return ResponseEntity.ok(
                ApiResponse.ok(ParamSearchResponse.from(paramSearchService.get(searchId))));
    }

    /**
     * 후보 목록(계약 §4). 전체 후보를 본다 — 표시 후보만 보려면 {@code selected=true}다.
     *
     * <p>정렬은 허용 목록 안에서만 받는다. 임의의 필드명을 받으면 저장 구조가 그대로 API 계약이 되어 컬럼을 바꿀 수 없게 된다.
     *
     * <p><b>계산되지 않은 후보의 성과는 null이고 0이 아니다</b>(FR-011). 화면은 그 자리를 "0%"가 아니라 사유와 함께 표시한다.
     */
    @GetMapping("/{searchId}/candidates")
    public ResponseEntity<ApiResponse<CandidatePageResponse>> candidates(
            @PathVariable String searchId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean selected,
            @RequestParam(required = false) String sort) {
        CandidatePageResult result =
                candidateQueryService.find(
                        searchId, FindCandidatesQuery.of(page, size, status, selected, sort));
        return ResponseEntity.ok(ApiResponse.ok(CandidatePageResponse.from(result)));
    }

    /**
     * 저장하지 않는 경로의 요청자. 인증(spec 014) 전에는 개발용 헤더를 켠 환경에서만 값이 있고 기본은 null이다(research R4).
     *
     * <p>미리보기만 이걸 쓴다. 아무것도 저장하지 않고 아무 실행도 만들지 않으므로 요청자를 몰라도 성립한다.
     *
     * <p><b>접수는 {@code requireRequesterId}를 쓴다.</b> 접수는 기준 1건과 후보를 004 접수 유스케이스로 넘기는데 {@code
     * backtest_request.requester_id}가 NOT NULL이다(spec 004 R-12). 요청자 없이 진행하면 발행 직전에 처리되지 않은
     * NullPointerException으로 죽어 사용자에게 500이 나간다 — "소유자 필터가 없다"는 것과 "요청자를 몰라도 실행을 만들 수 있다"는 것은 다른
     * 말이다.
     */
    private Long optionalRequesterId(HttpServletRequest http) {
        return currentRequester.findRequesterId(http).orElse(null);
    }
}
