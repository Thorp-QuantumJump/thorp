package com.ssafy.thorp.regimelink.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 비교 묶음을 공개 토큰에서 풀어 004 실행으로 읽는다 (research R5, T026).
 *
 * <p>토큰을 푸는 규칙은 005 {@code PublicResultService.resolve}가 소유한다 — 형식(400) → 존재(404) → 만료(410)를 그대로 쓰고
 * 011이 다시 정의하지 않는다. 실행의 조건·기간·상태는 004의 요청 기반 조회로 읽는다.
 *
 * <p><b>저장 결과에서 증명되지 않은 값은 만들어 내지 않는다.</b> 워밍업·평가 시작일처럼 실행에 고정된 값만 읽고, 없으면 없는 대로 판정에 반영한다.
 */
public interface BacktestRunReadPort {

    /**
     * 기준·비교 대상 토큰을 순서대로 풀어 묶음을 만든다.
     *
     * @param baseToken 필수. 비교 기준
     * @param compareToken 없으면 null. 비교 대상은 최대 하나다(Clarifications 09-17 Q1)
     */
    List<BundleRunView> resolveBundle(String baseToken, String compareToken);

    /**
     * 저장된 검색 기록의 (실행, 요청) 쌍으로 묶음을 되살린다 (contracts §2).
     *
     * <p>사례를 여는 본문에는 토큰이 없고 {@code linkSearchId}만 있다. 그 기록이 들고 있는 요청에서 공개 토큰을 되찾아야 응답의 {@code
     * publicToken}을 채울 수 있다 — 화면은 그 값으로 '전체 기간 결과 보기'를 연다.
     *
     * <p>여기서는 만료를 보지 않는다. 만료 판정은 토큰을 <b>제시받은</b> 경로(§1·§3·§4)의 몫이고, 이미 소유가 확인된 기록에서 되찾는 값까지 막으면 자기
     * 검색을 열지 못한다.
     */
    List<BundleRunView> resolveBundleByRequest(
            Long baseRunId, Long baseRequestId, Long compareRunId, Long compareRequestId);

    /**
     * 실행 하나를 공개 토큰 없이 읽는다. 링크가 들고 있는 것은 실행 PK 라 조회·감시 경로가 이것을 쓴다.
     *
     * <p>{@code publicToken}·{@code role} 자리는 채울 근거가 없으므로 어댑터가 비워 둔다 — 응답에 실리는 토큰은 언제나 <b>요청자가
     * 제시한</b> 것이다(research R14).
     */
    Optional<BundleRunView> findRun(Long runId);

    /**
     * 사후 구간만큼 잘라낸 입력 (계약 "구간 입력의 출처와 제약").
     *
     * <p>결과가 없는 실행(끝나지 않았거나 저장 행이 없는 실행)은 비어 있다. 범위가 모자란 것을 채우지 않는다 — 없는 행을 만들어 내면 워커가 {@code
     * INPUT_INCOMPLETE}로 걸러 낼 기회를 잃는다.
     */
    Optional<RunSlice> readSlice(
            Long runId, LocalDate baselineDate, LocalDate d0, LocalDate lastAvailable);

    /** 004 진행 상태 그대로. {@code PENDING_RUN} 링크의 응답과 감시(T033)가 읽는다. */
    Optional<RunProgressView> readProgress(Long runId);
}
