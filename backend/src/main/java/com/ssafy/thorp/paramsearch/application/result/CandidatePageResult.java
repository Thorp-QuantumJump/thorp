package com.ssafy.thorp.paramsearch.application.result;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 후보 목록 한 쪽(contracts/param-search-api.md §4).
 *
 * <p><b>성과 필드가 {@code null}인 것과 0인 것은 다르다</b>(FR-011, 원칙 VI). {@code EXCLUDED}·{@code
 * FAILED}·{@code SKIPPED}·{@code PENDING} 후보의 수익·하락 폭·거래비용은 항상 {@code null}이며 0으로 채우지 않는다 — 0은 "손실
 * 없음"·"비용 없음"으로 읽혀 낮은 수익과 구분되지 않는다. 화면은 이 값들을 "0%"가 아니라 사유와 함께 표시한다.
 *
 * @param content 이 쪽의 후보들. 순서는 요청한 정렬 기준을 따른다
 */
public record CandidatePageResult(
        int page, int size, long totalElements, int totalPages, List<Item> content) {

    /**
     * 후보 하나.
     *
     * @param candidateId 외부 식별자. DB PK는 노출하지 않는다(원칙 VIII)
     * @param backtestId 이 후보의 실행을 여는 <b>요청</b> 식별자. 발행 전·제외 후보는 null
     * @param stability 인접 설정 안정성. 계산 전이면 null이고, 계산 불가면 {@code calculable=false}다
     * @param selectionReason 선정·탈락 사유. 선정 계산 전에는 null이다
     */
    public record Item(
            String candidateId,
            int candidateNo,
            String strategyId,
            Map<String, Object> parameters,
            String status,
            String backtestId,
            BigDecimal netReturn,
            BigDecimal maxDrawdown,
            BigDecimal tradingCost,
            Map<String, Object> stability,
            int retryCount,
            String validationLabel,
            boolean selected,
            Map<String, Object> selectionReason) {}
}
