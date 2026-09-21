package com.ssafy.thorp.regimelink.domain.vo;

/** 계산 하나의 진행 상태 (data-model §7). */
public enum LinkStatus {

    /** 004 실행을 기다린다. 화면은 그 자리에 004 진행 상태를 보인다. */
    PENDING_RUN,
    QUEUED,
    RUNNING,

    /** 결과가 있거나, 자료 부족이라 계산하지 않기로 한 종결이다 — 둘을 섞지 않는다(FR-018). */
    COMPLETED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
