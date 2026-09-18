package com.ssafy.thorp.regime.domain.vo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 사전 계산 항목의 식별 키 (spec FR-029, data-model §2).
 *
 * <pre>{mode}:{fieldId|ALL}:{baselineMonth}:{dataVersion}:{registryVersion}:{modelVersion}</pre>
 *
 * <p>예: {@code FIELD:LABOR:2026-07-01:MACRO-20260915:REG-3:REGIME-V1}
 *
 * <p><b>검색 경로와 기준월의 근거는 들어가지 않는다.</b> 경로(시장 흐름/전략 결과)가 달라도 같은 계산이고, 같은 월을 T_g로 얻었든 사용자가 지정했든 결과가
 * 같다. 넣으면 같은 결과가 여러 벌 저장되어 SC-002의 "같은 식별이면 같은 결과"가 흐려지고, 요청을 모르는 사전 계산 배치는 근거를 정할 수도 없다. 둘은 조회한 쪽이
 * 판단해 화면에 표시하고 {@code regime_search_log}에 남긴다.
 *
 * <p>이 클래스는 <b>키를 읽는 쪽</b>이다 — 키를 만드는 것은 배치이고 Backend는 저장된 값을 그대로 돌려준다. 그래도 조각을 꺼낼 수 있어야 {@code
 * searchId}로 들어온 요청을 (모드·분야·기준월)로 되짚을 수 있다(계약 §2·§3).
 */
public record SnapshotKey(
        SearchMode mode,
        String scopeId,
        LocalDate baselineMonth,
        String dataVersion,
        String registryVersion,
        String modelVersion) {

    private static final String DELIMITER = ":";
    private static final int PARTS = 6;

    public SnapshotKey {
        require(scopeId, "범위 id");
        require(dataVersion, "자료 버전");
        require(registryVersion, "등록표 버전");
        require(modelVersion, "모델 버전");
        if (mode == null) {
            throw new IllegalArgumentException("검색 모드가 없습니다.");
        }
        if (baselineMonth == null) {
            throw new IllegalArgumentException("기준월이 없습니다.");
        }
        if (baselineMonth.getDayOfMonth() != 1) {
            // 그 달의 어느 날이 뜻을 갖는 것처럼 보이지 않게 한다. 저장 스키마도 달의 1일이다.
            throw new IllegalArgumentException("기준월은 그 달 1일이어야 합니다: " + baselineMonth);
        }
        if (mode == SearchMode.COMPOSITE && !SearchMode.COMPOSITE_SCOPE.equals(scopeId)) {
            throw new IllegalArgumentException("종합 검색의 범위 id는 ALL 이어야 합니다: " + scopeId);
        }
    }

    /** 저장된 문자열을 조각으로 되돌린다. 모양이 다르면 400이다. */
    public static SnapshotKey parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("searchId 가 비어 있습니다.");
        }
        // 양수 상한을 쓴다 — 끝의 빈 조각을 남겨야 "A:B:C:D:E:"가 여섯 조각으로 읽혀
        // "모델 버전이 비었다"라고 정확히 거절된다.
        String[] parts = value.split(DELIMITER, PARTS + 1);
        if (parts.length != PARTS) {
            throw new IllegalArgumentException(
                    "searchId 는 여섯 조각이어야 합니다(모드:범위:기준월:자료:등록표:모델): " + value);
        }
        try {
            return new SnapshotKey(
                    SearchMode.valueOf(parts[0]),
                    parts[1],
                    LocalDate.parse(parts[2]),
                    parts[3],
                    parts[4],
                    parts[5]);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("searchId 를 읽을 수 없습니다: " + value, e);
        }
    }

    /** 분야 id. 종합이면 {@code null}이다 — 저장 행의 {@code field_id}와 같은 뜻이다. */
    public String fieldId() {
        return mode == SearchMode.COMPOSITE ? null : scopeId;
    }

    @Override
    public String toString() {
        return String.join(
                DELIMITER,
                mode.name(),
                scopeId,
                baselineMonth.toString(),
                dataVersion,
                registryVersion,
                modelVersion);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "이(가) 비어 있습니다.");
        }
    }
}
