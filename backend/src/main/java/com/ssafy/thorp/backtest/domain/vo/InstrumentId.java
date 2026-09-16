package com.ssafy.thorp.backtest.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 종목 식별자. MVP 대상 시장은 KRX 하나이므로 {@code {6자리 단축코드}.XKRX} 형식만 허용한다.
 *
 * <p>단축코드는 숫자 여섯 자리가 아니라 <b>영문 대문자가 섞일 수 있는</b> 여섯 글자다. 2026-09-11 실측에서 ETF 1,168종 중 304종(26%)이
 * {@code 0111J0}·{@code 0182R0} 같은 코드였고, 숫자만 받으면 그 종목들을 대표 ETF로도 비교 대상으로도 담을 수 없다(PD-01, spec 001
 * T031).
 *
 * <p>다른 거래소를 추가할 때는 이 패턴만 넓힌다. 근거: ADR-0002 D4.
 */
public record InstrumentId(String value) {

    /** 요청 DTO의 검증 패턴과 규칙을 공유하려고 노출한다. */
    public static final String REGEX = "^[0-9A-Z]{6}\\.XKRX$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    public InstrumentId {
        Objects.requireNonNull(value, "instrumentId must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid instrumentId format: " + value);
        }
    }
}
