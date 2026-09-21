package com.ssafy.thorp.regimelink.presentation.controller;

import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.regimelink.application.command.OpenRegimeLinkCommand;
import com.ssafy.thorp.regimelink.application.result.OpenLinkResult;
import com.ssafy.thorp.regimelink.application.service.RegimeLinkOpenService;
import com.ssafy.thorp.regimelink.application.service.RegimeLinkQueryService;
import com.ssafy.thorp.regimelink.application.service.RegimeLinkRetryService;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import com.ssafy.thorp.regimelink.presentation.request.OpenRegimeLinkRequest;
import com.ssafy.thorp.regimelink.presentation.response.LinkDetailResponse;
import com.ssafy.thorp.regimelink.presentation.response.OpenLinkResponse;
import jakarta.validation.Valid;
import java.util.List;
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
 * 사례 연결 생성·조회·재시도 (contracts §2·§3·§4·§5).
 *
 * <p>쓰기는 로그인을 요구하고({@code 401 AUTHENTICATION_REQUIRED}) <b>익명 요청에서는 행도 메시지도 만들지 않는다</b>. 조회는 공개 토큰을
 * 함께 받는 조건으로 익명이 열 수 있다(contracts §0).
 *
 * <p>가용성 판정 조회(§1·§1a)는 {@link RegimeLinkAvailabilityController}에 있다 — 그쪽은 읽기만 하고 토큰도 요구하지 않아 인증
 * 규칙이 다르다.
 */
@RestController
@RequestMapping("/regime-links")
public class RegimeLinkController {

    private final RegimeLinkOpenService openService;
    private final RegimeLinkQueryService queryService;
    private final RegimeLinkRetryService retryService;
    private final CurrentRequester currentRequester;

    public RegimeLinkController(
            RegimeLinkOpenService openService,
            RegimeLinkQueryService queryService,
            RegimeLinkRetryService retryService,
            CurrentRequester currentRequester) {
        this.openService = openService;
        this.queryService = queryService;
        this.retryService = retryService;
        this.currentRequester = currentRequester;
    }

    /**
     * §2 — 사례의 전략 결과를 연다.
     *
     * <p>접수는 <b>202</b>, 확인 요구는 <b>200</b>이다. 확인 요구는 오류가 아니라 정상 흐름이라 4xx 로 내지 않는다(Clarifications
     * 09-18 Q1).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OpenLinkResponse>> open(
            @Valid @RequestBody OpenRegimeLinkRequest request) {
        long requesterId = currentRequester.requireRequesterId();
        OpenLinkResult result =
                openService.open(
                        new OpenRegimeLinkCommand(
                                requesterId,
                                searchId(request.linkSearchId()),
                                caseRef(request.caseId()),
                                scenario(request.scenario()),
                                request.confirmFullPeriod()));
        HttpStatus status =
                result.outcome() == OpenLinkResult.Outcome.ACCEPTED
                        ? HttpStatus.ACCEPTED
                        : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(OpenLinkResponse.from(result)));
    }

    /** §3 — 링크 하나의 상태와 결과. {@code token}은 필수다(없으면 400). */
    @GetMapping("/{linkId}")
    public ApiResponse<LinkDetailResponse> get(
            @PathVariable String linkId, @RequestParam(required = false) String token) {
        return ApiResponse.ok(LinkDetailResponse.from(queryService.get(linkId(linkId), token)));
    }

    /** §4 — 사례 하나에 붙은 링크 묶음. 기준 → 비교 대상 순서이며 없는 조합은 요소가 없다. */
    @GetMapping
    public ApiResponse<List<LinkDetailResponse>> findByCase(
            @RequestParam String base,
            @RequestParam(required = false) String compare,
            @RequestParam String caseId) {
        return ApiResponse.ok(
                queryService.findByCase(base, compare, caseRef(caseId)).stream()
                        .map(LinkDetailResponse::from)
                        .toList());
    }

    /** §5 — 실패한 링크를 다시 시도한다. */
    @PostMapping("/{linkId}/retry")
    public ResponseEntity<ApiResponse<RetryResponse>> retry(@PathVariable String linkId) {
        long requesterId = currentRequester.requireRequesterId();
        RegimeLinkRetryService.RetriedLink retried =
                retryService.retry(linkId(linkId), requesterId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        ApiResponse.ok(
                                new RetryResponse(
                                        retried.linkId().value(), retried.status().name())));
    }

    public record RetryResponse(String linkId, String status) {}

    private static LinkId linkId(String value) {
        try {
            return LinkId.from(value);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, malformed.getMessage());
        }
    }

    private static LinkSearchId searchId(String value) {
        try {
            return LinkSearchId.from(value);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, malformed.getMessage());
        }
    }

    private static CaseRef caseRef(String value) {
        try {
            return CaseRef.parse(value);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, malformed.getMessage());
        }
    }

    /** 비우면 연속 운용이다. 알 수 없는 값은 400 이며 기본값으로 떨어뜨리지 않는다(contracts §2 오류표). */
    private static Scenario scenario(String value) {
        if (value == null || value.isBlank()) {
            return Scenario.CONTINUOUS;
        }
        try {
            return Scenario.valueOf(value);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 scenario 입니다: " + value);
        }
    }
}
