package com.ssafy.thorp.marketflow.domain.vo;

import java.util.Arrays;

/** 008이 고정한 일곱 개 경제환경 화면 분야. 계산 가중이나 평가 등급을 담지 않는다. */
public enum FieldId {
    PRODUCTION("production"),
    CONSUMPTION_INVESTMENT("consumption_investment"),
    EXTERNAL_TRADE("external_trade"),
    LABOR("labor"),
    PRICES_COMMODITIES("prices_commodities"),
    FINANCE_HOUSING("finance_housing"),
    OVERSEAS("overseas");

    private final String value;

    FieldId(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FieldId from(String value) {
        return Arrays.stream(values())
                .filter(field -> field.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 시장 흐름 분야: " + value));
    }
}
