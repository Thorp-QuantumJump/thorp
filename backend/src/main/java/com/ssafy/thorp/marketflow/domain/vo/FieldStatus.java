package com.ssafy.thorp.marketflow.domain.vo;

/** 분야 자료 상태. 월간 거시지표를 시간 경과만으로 STALE·DELAYED라 판정하지 않는다. */
public enum FieldStatus {
    READY,
    EXAMPLE,
    EMPTY,
    COLLECTION_FAILED
}
