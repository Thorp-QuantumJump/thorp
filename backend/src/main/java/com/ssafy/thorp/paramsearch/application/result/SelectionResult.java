package com.ssafy.thorp.paramsearch.application.result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 선정 계산의 결과(contracts/param-search-api.md §3의 {@code selection}).
 *
 * <p><b>순위가 아니라 집합이다</b>(plan 설계 제약 1). {@code displayed}는 정렬 지표를 밝힌 표시 후보의 모음이며, 화면은 여기에 순위 번호나
 * 추천·우승 표현을 붙이지 않는다. 목록이 순서를 갖는 이유는 정렬 지표로 줄 세운 결과이기 때문이지 등수를 매겼기 때문이 아니다.
 *
 * @param displayedCandidateNos 표시 후보의 묶음 내 순번. 문자열 식별자 변환은 응답 계층이 한다
 * @param shortOfDisplayCount 표시 수가 규칙의 {@code displayCount}에 못 미치는가. 문턱을 낮춰 채우지 않은 결과다(원칙 VII)
 * @param note 사용자에게 보일 한 문장. 채우지 못한 이유를 밝힌다
 * @param drawdownFilterApplied 손실 제약을 실제로 걸었는가. 기준 미지정·기준 실행 실패면 false이고 화면이 그 사실을 표시한다(FR-013d)
 * @param selectionReasonByCandidateId 후보 id → 선정·탈락 사유. 탈락도 어느 단계에서 빠졌는지 남긴다(FR-020)
 */
public record SelectionResult(
        List<Integer> displayedCandidateNos,
        boolean shortOfDisplayCount,
        String note,
        boolean drawdownFilterApplied,
        Map<Long, Map<String, Object>> selectionReasonByCandidateId) {

    public SelectionResult {
        displayedCandidateNos =
                List.copyOf(
                        Objects.requireNonNull(
                                displayedCandidateNos, "displayedCandidateNos must not be null"));
        selectionReasonByCandidateId =
                Map.copyOf(
                        Objects.requireNonNull(
                                selectionReasonByCandidateId,
                                "selectionReasonByCandidateId must not be null"));
    }

    /**
     * {@code param_search.selection}에 저장할 {@code Map}. 키 순서는 계약 §3의 예시와 같다.
     *
     * @param candidateIds {@code displayedCandidateNos}와 같은 순서의 외부 식별자
     */
    public Map<String, Object> toMap(List<String> candidateIds) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("displayed", List.copyOf(candidateIds));
        map.put("shortOfDisplayCount", shortOfDisplayCount);
        map.put("note", note);
        map.put("drawdownFilterApplied", drawdownFilterApplied);
        return map;
    }
}
