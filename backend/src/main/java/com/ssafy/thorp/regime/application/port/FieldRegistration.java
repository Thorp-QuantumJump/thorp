package com.ssafy.thorp.regime.application.port;

import java.util.Comparator;

/**
 * 분야 하나의 등록 정보 — id·이름·표시 순서 (008 FR-002·FR-003).
 *
 * <p>이름과 순서는 <b>화면 분류</b>다. 계산 가중은 등록표가 따로 정하며 이름이나 배치를 바꿨다는 이유로 따라 바뀌지 않는다(spec 010 FR-011).
 */
public record FieldRegistration(String id, String name, int displayOrder) {

    /** 표시 순서 오름차순, 같으면 id 순. 화면이 분야를 늘어놓는 순서다. */
    public static final Comparator<FieldRegistration> DISPLAY_ORDER =
            Comparator.comparingInt(FieldRegistration::displayOrder)
                    .thenComparing(FieldRegistration::id);

    public FieldRegistration {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("분야 id는 비어 있을 수 없습니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("분야 이름은 비어 있을 수 없습니다.");
        }
    }
}
