package com.ssafy.thorp.regimelink.presentation.controller;

import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.regimelink.application.command.CreateLinkSearchCommand;
import com.ssafy.thorp.regimelink.application.result.LinkSearchResult;
import com.ssafy.thorp.regimelink.application.service.RegimeLinkSearchService;
import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import com.ssafy.thorp.regimelink.presentation.request.CreateLinkSearchRequest;
import com.ssafy.thorp.regimelink.presentation.response.LinkSearchResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자가 본 목록을 확정해 기록한다 (contracts §1b).
 *
 * <p>경로가 {@code /regime-links}와 다른 것은 만드는 것이 계산이 아니라 <b>검색 기록</b>이기 때문이다. 이 요청으로는 큐에 아무것도 들어가지 않는다.
 *
 * <p>새 기록을 만들면 <b>201</b>, 같은 멱등 키의 같은 본문 재시도면 <b>200</b>이다 — 화면이 둘을 가려 "또 저장됐다"고 오해하지 않게 한다.
 */
@RestController
@RequestMapping("/regime-link-searches")
public class RegimeLinkSearchController {

    private final RegimeLinkSearchService searchService;
    private final CurrentRequester currentRequester;

    public RegimeLinkSearchController(
            RegimeLinkSearchService searchService, CurrentRequester currentRequester) {
        this.searchService = searchService;
        this.currentRequester = currentRequester;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LinkSearchResponse>> create(
            @Valid @RequestBody CreateLinkSearchRequest request) {
        long ownerUserId = currentRequester.requireRequesterId();
        LinkSearchResult result =
                searchService.create(
                        new CreateLinkSearchCommand(
                                ownerUserId,
                                request.clientRequestId(),
                                request.snapshotKey(),
                                request.base(),
                                request.compare(),
                                entryPath(request.entryPath()),
                                request.fullSearchShown(),
                                searchId(request.previousLinkSearchId())));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(LinkSearchResponse.from(result)));
    }

    private static EntryPath entryPath(String value) {
        try {
            return EntryPath.from(value);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, malformed.getMessage());
        }
    }

    private static LinkSearchId searchId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LinkSearchId.from(value);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, malformed.getMessage());
        }
    }
}
