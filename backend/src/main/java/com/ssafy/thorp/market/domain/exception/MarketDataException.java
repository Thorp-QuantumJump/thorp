package com.ssafy.thorp.market.domain.exception;

/** 원천 URL·키·응답 본문·원인 예외를 저장하지 않는 안전한 오류. */
public class MarketDataException extends RuntimeException {
    public enum Reason {
        NOT_CONFIGURED,
        AUTHENTICATION,
        NO_DATA,
        TIMEOUT,
        INVALID_RESPONSE,
        UPSTREAM_ERROR
    }

    private final Reason reason;

    public MarketDataException(Reason reason) {
        super(
                switch (reason) {
                    case NOT_CONFIGURED -> "데이터 제공 설정을 준비 중입니다.";
                    case AUTHENTICATION -> "외부 데이터 서비스 인증 또는 이용 승인을 확인해야 합니다.";
                    case NO_DATA -> "현재 제공 가능한 관측값이 없습니다.";
                    case TIMEOUT -> "외부 데이터 조회 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
                    case INVALID_RESPONSE -> "외부 데이터 응답을 확인할 수 없습니다.";
                    case UPSTREAM_ERROR -> "외부 데이터 서비스에 일시적인 문제가 있습니다.";
                });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
