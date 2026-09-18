package com.ssafy.thorp.regime.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.regime.application.result.RegimeSearchResult;
import com.ssafy.thorp.regime.domain.model.RankedCandidate;
import java.math.BigDecimal;
import java.util.List;

/**
 * 전체 유효 후보의 순위·거리 목록 (계약 §4).
 *
 * <p><b>필터도 ±6개월 건너뛰기도 적용하지 않은 원본 순위다</b>(spec FR-012·FR-030). 건너뛰기는 순차 규칙이라 011이 가용성 필터를 건
 * <b>뒤에</b> 적용해야 같은 결과가 나온다. 010이 요청마다 필터를 받아 순위를 다시 매기면 같은 기준월에 순위가 두 벌 생긴다.
 *
 * <p>이 응답은 화면 목록용이 아니다 — 사용자 화면은 §1의 {@code cases}(최대 10개)를 쓴다.
 */
public record RegimeCandidatesResponse(String searchId, List<CandidateView> candidates) {

    public static RegimeCandidatesResponse from(RegimeSearchResult result) {
        return new RegimeCandidatesResponse(
                result.snapshot().key().toString(),
                result.snapshot().rankedCandidates().stream().map(CandidateView::of).toList());
    }

    public record CandidateView(
            String endMonth,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal distance,
            int rank) {
        static CandidateView of(RankedCandidate candidate) {
            return new CandidateView(
                    RegimeFormat.month(candidate.endMonth()),
                    candidate.distance(),
                    candidate.rank());
        }
    }
}
