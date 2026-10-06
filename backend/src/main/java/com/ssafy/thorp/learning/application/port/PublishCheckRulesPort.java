package com.ssafy.thorp.learning.application.port;

import com.ssafy.thorp.learning.domain.model.PublishCheckRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2;

/**
 * 게시 전 검사 규칙 파일을 읽는 포트(research R7·R19). 구현은 기동 시 두 파일을 한 번 읽어 들고 있다.
 *
 * <p>어느 규칙으로 검사할지는 게시 방식이 정한다(data-model §10-1). V1 이전 판 고치기는 {@link #v1()}, V2 게시는 {@link #v2()}다.
 * 게시 판이 쓴 규칙의 {@code version}을 기록한다.
 */
public interface PublishCheckRulesPort {

    /** V1 초안의 이전 판 고치기에 쓰는 규칙. */
    PublishCheckRules v1();

    /** V2 글의 글마다 게시·단계 공개에 쓰는 규칙. 읽기 시간 계산 값도 여기 있다. */
    PublishCheckRulesV2 v2();
}
