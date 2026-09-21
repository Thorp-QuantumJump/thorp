package com.ssafy.thorp.myrecord.domain.vo;

import java.util.Objects;

/**
 * 기록 항목의 이름(spec 006 FR-003).
 *
 * <p>저장 시 섹터·전략·저장일로 자동 부여하고 사용자가 바꿀 수 있다. <b>사용자에게 이름 입력을 요구하지 않는다.</b>
 *
 * <p><b>유일성을 강제하지 않는다</b>(research R12). 같은 조건을 다른 날 돌리면 이름이 겹칠 수 있고 그래도 된다 — 목록은 저장일과 상태로 구분되며,
 * 유일성을 강제하면 서버가 {@code (2)} 같은 꼬리를 붙여야 하는데 그 꼬리는 사용자가 붙인 뜻이 없는 식별자다(원칙 II).
 */
public record RecordName(String value) {

    /** 스키마 {@code my_record.name}의 길이와 같다. */
    public static final int MAX_LENGTH = 120;

    public RecordName {
        Objects.requireNonNull(value, "record name must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("record name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "record name must be at most " + MAX_LENGTH + " characters: " + value.length());
        }
    }

    public static RecordName of(String value) {
        return new RecordName(value);
    }
}
