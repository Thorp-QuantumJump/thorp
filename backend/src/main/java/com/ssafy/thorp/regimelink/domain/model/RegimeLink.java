package com.ssafy.thorp.regimelink.domain.model;

import com.ssafy.thorp.regimelink.domain.vo.AvailabilityState;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.Instant;
import java.util.Objects;

/**
 * (실행 × 사례 × 시나리오) 계산 하나 (data-model §4).
 *
 * <p>상태 전이의 단위이자 잘라내기 작업의 단위이며 <b>여러 사용자·여러 검색이 공유한다</b>. 그래서 사용자 식별자와 요청 문맥을 갖지 않는다(research R14)
 * — 누가 열었는지는 {@link RegimeLinkUse}에 있다.
 *
 * <p>판정({@code availability})은 <b>만들 때의 값을 그대로 보관</b>하고 다시 판정하지 않는다. 자료가 개정돼 판정이 달라져도 이미 낸 결과의 전제는
 * 그때의 판정이기 때문이다.
 */
public record RegimeLink(
        Long id,
        LinkId linkId,
        Long runId,
        CaseRef caseRef,
        Scenario scenario,
        PostWindow postWindow,
        Availability availability,
        LinkStatus status,
        LinkFailure failure,
        String jobMessageId,
        int attempt,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public RegimeLink {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(caseRef, "caseRef must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(postWindow, "postWindow must not be null");
        Objects.requireNonNull(availability, "availability must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (availability.state() == AvailabilityState.OUT_OF_PERIOD) {
            // 기간 밖은 원래 요청과 사례의 관계라 계산의 성질이 아니다. 확인을 받은 뒤에는
            // 전체 기간 실행의 링크가 만들어지므로 이 상태로 저장될 일이 없다(data-model §4).
            throw new IllegalArgumentException("OUT_OF_PERIOD 는 링크에 저장하지 않습니다.");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 가 음수입니다: " + attempt);
        }
    }

    /**
     * 판정만으로 끝나는 링크. 자료가 없어 작업을 만들지 않고, 화면이 사유를 보이도록 행만 남긴다(FR-018).
     *
     * <p>상태를 {@code COMPLETED}로 두는 것은 결과를 기다릴 이유가 없어서다 — 계산 실패와 섞지 않는다.
     */
    public static RegimeLink dataInsufficient(
            Long runId, CaseRef caseRef, Scenario scenario, PostWindow window, Availability why) {
        if (why.state() != AvailabilityState.DATA_INSUFFICIENT) {
            throw new IllegalArgumentException("자료 부족 판정이 아닙니다: " + why.state());
        }
        return new RegimeLink(
                null,
                null,
                runId,
                caseRef,
                scenario,
                window,
                why,
                LinkStatus.COMPLETED,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    /** 계산을 기다리는 링크. 입력 실행이 이미 끝났으면 {@code QUEUED}, 새 실행을 기다리면 {@code PENDING_RUN}이다. */
    public static RegimeLink pending(
            Long runId,
            CaseRef caseRef,
            Scenario scenario,
            PostWindow window,
            Availability availability,
            LinkStatus status) {
        if (status != LinkStatus.QUEUED && status != LinkStatus.PENDING_RUN) {
            throw new IllegalArgumentException("대기 상태가 아닙니다: " + status);
        }
        return new RegimeLink(
                null,
                null,
                runId,
                caseRef,
                scenario,
                window,
                availability,
                status,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null);
    }
}
