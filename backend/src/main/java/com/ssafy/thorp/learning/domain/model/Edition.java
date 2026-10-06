package com.ssafy.thorp.learning.domain.model;

/** 판의 종류(data-model §3-4, research R16). V1 게시는 {@code LEGACY}, V2 게시는 {@code CURRENT} 판을 만든다. */
public enum Edition {
    /** 이전 판. 2판 전 판은 모두 이것이다. 나의 학습에 세지 않는다. */
    LEGACY,
    /** 새 기준 판. */
    CURRENT
}
