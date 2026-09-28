package com.ssafy.thorp.assistant.application.port;

import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.PageContext;
import com.ssafy.thorp.assistant.application.result.PageKind;

/**
 * 화면 종류별로 초기 근거 팩과 도구를 만든다(FR-006). 결과 화면은 묶음 4의 {@code ResultEvidenceBuilder}가 한다.
 *
 * <p>{@link #build}는 결과 없음(계산 중·실패·시작 전 취소)을 005와 같은 409로 던진다 — 첫 조회 {@code getWeights}가 그 판정을 거친다.
 */
public interface EvidenceBuilder {
    boolean supports(PageKind kind);

    EvidenceBundle build(PageContext context);
}
