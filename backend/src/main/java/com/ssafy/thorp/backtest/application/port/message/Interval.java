package com.ssafy.thorp.backtest.application.port.message;

/**
 * 데이터 주기. JSON 값 {@code "1D"}는 Java 식별자가 될 수 없어 {@link #toString()}으로 매핑한다. 메시지 ObjectMapper가 enum을
 * toString 기준으로 읽고 쓴다.
 */
public enum Interval {
    DAY_1("1D");

    private final String json;

    Interval(String json) {
        this.json = json;
    }

    @Override
    public String toString() {
        return json;
    }
}
