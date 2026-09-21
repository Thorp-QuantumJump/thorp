package com.ssafy.thorp.regimelink.domain.model;

import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import java.time.Instant;
import java.util.Objects;

/**
 * 어느 검색에서 어느 분석 요청으로 그 계산을 열었나 (data-model §4a).
 *
 * <p>계산({@link RegimeLink})은 여러 사용자가 공유하므로 사용자 문맥을 갖지 않는다. '전체 기간 결과 보기'와 기록 귀속은 이 행이 정한다.
 *
 * @param originRunId 비교 묶음의 원래 실행. 링크의 {@code runId}가 재계산·현금 시작으로 달라도 "어느 전략의 결과인가"는 이 값이다
 * @param createdRequestId 011이 사용자 이름으로 004에 접수해 만든 요청. 없으면 null
 */
public record RegimeLinkUse(
        Long id,
        Long linkSearchId,
        Long linkId,
        LinkRole role,
        Long originRunId,
        Long originRequestId,
        Long createdRequestId,
        Instant createdAt) {

    public RegimeLinkUse {
        Objects.requireNonNull(linkSearchId, "linkSearchId must not be null");
        Objects.requireNonNull(linkId, "linkId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(originRunId, "originRunId must not be null");
    }

    public static RegimeLinkUse create(
            Long linkSearchId,
            Long linkId,
            LinkRole role,
            Long originRunId,
            Long originRequestId,
            Long createdRequestId) {
        return new RegimeLinkUse(
                null,
                linkSearchId,
                linkId,
                role,
                originRunId,
                originRequestId,
                createdRequestId,
                null);
    }
}
