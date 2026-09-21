package com.ssafy.thorp.regimelink.application.port;

import java.util.List;

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
}
