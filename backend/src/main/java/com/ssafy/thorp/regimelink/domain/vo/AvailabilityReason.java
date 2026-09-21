package com.ssafy.thorp.regimelink.domain.vo;

/**
 * 판정이 {@code NORMAL}이 아닌 이유 (contracts §1).
 *
 * <p>화면은 이 값으로 사용자에게 보일 문장을 고른다. 계산 실패 코드({@code regime_link.failure.code})와 섞지 않는다 — 자료 부족은 실패가
 * 아니고 '다시 시도'가 없다(research R13).
 */
public enum AvailabilityReason {

    /** 사후 구간 시작 시점에 상장되지 않은 종목이 있다. */
    UNLISTED,

    /** 상장은 했지만 그 전략의 워밍업 거래일을 채울 자료가 없다. */
    WARMUP_SHORT,

    /** 사례 관측 종료월의 대응표 버전에 그 실행의 섹터가 없다. */
    SECTOR_UNMAPPED,

    /** 판정 기준일까지 21거래일이 지나지 않은 최근 사례다. */
    RECENT,

    /** 구간 안 상장폐지로 확보 거래일이 21 미만으로 줄었다. */
    DELISTED
}
