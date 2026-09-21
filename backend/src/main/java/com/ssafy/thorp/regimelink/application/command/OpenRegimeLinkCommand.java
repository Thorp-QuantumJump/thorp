package com.ssafy.thorp.regimelink.application.command;

import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;

/**
 * 사례 하나의 전략 결과를 여는 입력 (contracts §2).
 *
 * @param confirmFullPeriod 분석 기간이 사례를 덮지 않을 때 전체 기간으로 다시 계산해도 좋다는 확인. 거짓이면 계산도 행도 만들지 않는다
 */
public record OpenRegimeLinkCommand(
        long requesterId,
        LinkSearchId linkSearchId,
        CaseRef caseRef,
        Scenario scenario,
        boolean confirmFullPeriod) {}
