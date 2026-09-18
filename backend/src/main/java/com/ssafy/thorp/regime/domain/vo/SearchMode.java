package com.ssafy.thorp.regime.domain.vo;

/** 검색 모드 — 분야 하나인가 Thorp 종합 비교인가 (data-model §2). */
public enum SearchMode {

    /** 분야 g 하나. 식별 키의 범위 자리에 분야 id가 들어간다. */
    FIELD,

    /** 등록표의 모든 분야를 묶은 종합. 식별 키의 범위 자리는 {@code ALL}이고 저장 행의 {@code field_id}는 NULL이다. */
    COMPOSITE;

    /** 종합의 범위 id. 저장 행의 NULL 자리를 식별 키와 기준월 표에서 이 값이 채운다. */
    public static final String COMPOSITE_SCOPE = "ALL";

    /** 요청 파라미터의 {@code field} / {@code composite}를 읽는다. 모르는 값은 400이다. */
    public static SearchMode from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mode 는 field 또는 composite 여야 합니다.");
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "field" -> FIELD;
            case "composite" -> COMPOSITE;
            default ->
                    throw new IllegalArgumentException(
                            "mode 는 field 또는 composite 여야 합니다: " + value);
        };
    }

    /** 이 모드에서 그 분야 id가 범위 id로 쓰이는 값. 종합이면 {@code ALL}이다. */
    public String scopeId(String fieldId) {
        return this == COMPOSITE ? COMPOSITE_SCOPE : fieldId;
    }
}
