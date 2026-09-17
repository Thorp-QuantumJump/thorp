package com.ssafy.thorp.learning.application.port;

import com.ssafy.thorp.learning.domain.model.PublishCheckRules;

/** 게시 전 검사 규칙 파일을 읽는 포트(research R7). 구현은 기동 시 한 번 읽어 들고 있다. */
public interface PublishCheckRulesPort {

    /** 지금 게시에 쓰는 규칙. 게시 판이 그 {@code version}을 기록한다. */
    PublishCheckRules current();
}
