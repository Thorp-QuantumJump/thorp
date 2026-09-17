package com.ssafy.thorp.backtest.domain.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 결과를 로그인 없이 여는 공개 토큰(spec 005 FR-019, data-model §1). 결과 주소 {@code /results/{token}}의 열쇠다.
 *
 * <p>{@code SecureRandom} 16바이트(128비트)를 소문자 16진 32자로 쓴다. 순번이나 시각을 섞지 않는다 — 추측할 수 있는 조각이 하나라도 있으면
 * 주소를 바꿔 남의 결과를 열 수 있다.
 *
 * <p><b>위치가 {@code backtest}인 이유</b>: 발급 시점이 004의 접수({@code BacktestService.create})다. 005의 공개 열람
 * 경계 {@code result}에 두면 {@code backtest → result} 의존이 생겨 {@code result → backtest}와 순환한다(spec 005
 * tasks "착수 전에 알아둘 것" ⑥).
 */
public final class PublicResultToken {

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{32}$");
    private static final int BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final String value;

    private PublicResultToken(String value) {
        this.value = value;
    }

    /** 새 토큰을 발급한다. */
    public static PublicResultToken generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return new PublicResultToken(HEX.formatHex(bytes));
    }

    /**
     * 저장된 값이나 주소에서 받은 값을 형식 검증과 함께 되살린다.
     *
     * @throws IllegalArgumentException 소문자 16진 32자가 아니면. 대문자를 소문자로 고쳐 받지 않는다 — 같은 결과에 주소가 둘 생긴다
     */
    public static PublicResultToken of(String value) {
        Objects.requireNonNull(value, "publicToken must not be null");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid publicToken format");
        }
        return new PublicResultToken(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PublicResultToken token && value.equals(token.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
