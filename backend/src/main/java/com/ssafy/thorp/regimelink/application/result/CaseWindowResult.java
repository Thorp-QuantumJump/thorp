package com.ssafy.thorp.regimelink.application.result;

import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;

/**
 * 실행 없이 읽는 사례의 사후 구간 (contracts §1a).
 *
 * <p>003이 분석 기간을 고르기 <b>전에</b> 하한을 알아야 해서 있는 조회다. 비교 묶음이 정해지지 않았으므로 판정은 붙지 않는다 — 여기 있는 값은 시장 달력만으로
 * 정해진다.
 */
public record CaseWindowResult(CaseRef caseRef, PostWindow postWindow) {}
