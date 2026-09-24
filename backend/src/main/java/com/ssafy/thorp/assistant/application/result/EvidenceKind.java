package com.ssafy.thorp.assistant.application.result;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 근거 값의 종류이자 표기 함수(계약 §2.4 표기표).
 *
 * <p>규칙은 프론트 {@code formatResult.ts}와 같아야 근거 카드의 값과 탭의 값이 어긋나지 않는다: 금액은 정수 반올림 + 천 단위 + "원", 비율은
 * ×100 소수 2자리 + "%"({@code -0.00}은 {@code 0.00}), 날짜는 ISO 원문 그대로. 반올림은 HALF_UP이다.
 */
public enum EvidenceKind {
    MONEY,
    RATIO,
    DECIMAL2,
    INTEGER,
    DATE,
    TEXT;

    /** 원문이 숫자가 아니면(빈 값 포함) 원문을 그대로 돌려준다 — 값을 지어내지 않는다. */
    public String format(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip();
        try {
            return switch (this) {
                case MONEY -> group(new BigDecimal(value).setScale(0, RoundingMode.HALF_UP)) + "원";
                case RATIO -> {
                    BigDecimal percent =
                            new BigDecimal(value)
                                    .movePointRight(2)
                                    .setScale(2, RoundingMode.HALF_UP);
                    if (percent.signum() == 0) {
                        percent = percent.abs();
                    }
                    yield percent.toPlainString() + "%";
                }
                case DECIMAL2 ->
                        new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
                case INTEGER -> group(new BigDecimal(value).setScale(0, RoundingMode.HALF_UP));
                case DATE, TEXT -> value;
            };
        } catch (NumberFormatException | ArithmeticException exc) {
            return value;
        }
    }

    private static String group(BigDecimal integral) {
        return String.format(Locale.ROOT, "%,d", integral.toBigIntegerExact());
    }
}
