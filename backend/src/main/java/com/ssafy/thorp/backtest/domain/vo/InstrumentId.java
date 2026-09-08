package com.ssafy.thorp.backtest.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 종목 식별자. MVP 대상 시장은 KRX 하나이므로 {@code {6자리 종목코드}.XKRX} 형식만 허용한다.
 *
 * <p>다른 거래소를 추가할 때는 이 패턴만 넓힌다. 근거: ADR-0002 D4.
 */
public record InstrumentId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^\\d{6}\\.XKRX$");

    public InstrumentId {
        Objects.requireNonNull(value, "instrumentId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid instrumentId format: " + value);
        }
    }
}
