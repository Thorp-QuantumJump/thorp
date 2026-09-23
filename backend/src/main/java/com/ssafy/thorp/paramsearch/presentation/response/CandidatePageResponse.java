package com.ssafy.thorp.paramsearch.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.paramsearch.application.result.CandidatePageResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 후보 목록 응답(contracts/param-search-api.md §4).
 *
 * <p><b>성과 필드는 명시적 null이다.</b> 계산되지 않은 후보에서 키를 생략하면 화면이 "값이 없다"와 "필드가 없다"를 구분할 수 없다 — 응답 전역 {@code
 * NON_NULL}을 쓰지 않는 이유이기도 하다(backend CLAUDE.md).
 *
 * <p>decimal은 필드마다 문자열로 직렬화한다. 웹 ObjectMapper에 전역 등록하지 않는다 — 다른 도메인의 계약이 함께 바뀐다.
 */
public record CandidatePageResponse(
        int page, int size, long totalElements, int totalPages, List<Item> content) {

    public record Item(
            String candidateId,
            int candidateNo,
            String strategyId,
            Map<String, Object> parameters,
            String status,
            String backtestId,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal netReturn,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal maxDrawdown,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal tradingCost,
            Map<String, Object> stability,
            int retryCount,
            String validationLabel,
            boolean selected,
            Map<String, Object> selectionReason) {}

    public static CandidatePageResponse from(CandidatePageResult result) {
        return new CandidatePageResponse(
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.content().stream().map(CandidatePageResponse::toItem).toList());
    }

    private static Item toItem(CandidatePageResult.Item item) {
        return new Item(
                item.candidateId(),
                item.candidateNo(),
                item.strategyId(),
                item.parameters(),
                item.status(),
                item.backtestId(),
                item.netReturn(),
                item.maxDrawdown(),
                item.tradingCost(),
                item.stability(),
                item.retryCount(),
                item.validationLabel(),
                item.selected(),
                item.selectionReason());
    }
}
