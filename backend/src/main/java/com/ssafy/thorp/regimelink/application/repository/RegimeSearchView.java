package com.ssafy.thorp.regimelink.application.repository;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * 010 검색 하나를 011이 읽은 모양 (research R8).
 *
 * <p>010 도메인 타입을 그대로 들고 다니지 않고 여기서 011 모델로 옮긴다 — 011이 010의 {@code RegimeSnapshot} 전부(척도·지표값·설명)를 알
 * 필요가 없고, 필요 없는 값까지 참조하면 010이 그 모양을 바꿀 때 011이 따라 깨진다.
 *
 * @param snapshotId 010 행의 PK. 검색 기록의 FK로만 쓰고 외부에 내보내지 않는다
 * @param mode {@code FIELD} \| {@code COMPOSITE}. 저장 표기 그대로이며 화면 표기로 바꾸는 것은 표현 계층이다
 * @param status 010 검색 상태. {@code NOT_COMPUTABLE}이면 후보가 없고 그대로 200으로 나간다
 * @param cases 표시 후보(≤10)를 010 순위 순서로
 */
public record RegimeSearchView(
        long snapshotId,
        String snapshotKey,
        String mode,
        String fieldId,
        YearMonth baselineMonth,
        String status,
        List<CaseCandidate> cases) {

    /** 계산할 수 없는 검색의 상태 값. 오류가 아니라 정상 흐름이다(010 계약 §1). */
    public static final String NOT_COMPUTABLE = "NOT_COMPUTABLE";

    public RegimeSearchView {
        Objects.requireNonNull(snapshotKey, "snapshotKey must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(baselineMonth, "baselineMonth must not be null");
        Objects.requireNonNull(status, "status must not be null");
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public boolean computable() {
        return !NOT_COMPUTABLE.equals(status);
    }
}
