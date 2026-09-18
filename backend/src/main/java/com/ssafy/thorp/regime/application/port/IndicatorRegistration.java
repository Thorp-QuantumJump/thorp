package com.ssafy.thorp.regime.application.port;

/**
 * 지표 하나의 등록 정보 — 이름과 단위 (008 FR-002).
 *
 * <p>화면이 저장된 지표 id에 이름을 붙일 때 쓴다. <b>값은 담지 않는다</b> — 지표 시계열은 {@code data/} 배치만 읽으며 여기로 오지 않는다(계약
 * §0.1).
 */
public record IndicatorRegistration(String id, String name, String unit) {

    public IndicatorRegistration {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("지표 id는 비어 있을 수 없습니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("지표 이름은 비어 있을 수 없습니다.");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("지표 단위는 비어 있을 수 없습니다.");
        }
    }
}
