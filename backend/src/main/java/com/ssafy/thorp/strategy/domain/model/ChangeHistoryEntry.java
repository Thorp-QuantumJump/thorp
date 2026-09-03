package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.ChangeType;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 정의 버전 하나에 대한 변경 기록(FR-019a, FR-019b).
 *
 * <p>등급을 버전마다 남기는 이유는 "v2에 저장한 전략이 지금 v5에서도 유효한가"를 답해야 하기 때문이다. 최신 변경의 등급만 들고 있으면 그 사이 v3에서 비호환 변경이
 * 있었는지 알 방법이 없다.
 *
 * <p>등급은 사람에게 이유를 설명하는 데만 쓴다. 값이 실제로 유효한지는 현재 정의에 다시 판정해서 얻는다(FR-019c). 두 용도를 섞으면 등급이 잘못 기록됐을 때 값
 * 판정까지 틀린다.
 *
 * @param definitionVersion 이 변경으로 도달한 버전
 * @param changeType 호환성 등급
 * @param changeSummary 무엇을 바꿨는지 한 줄
 * @param changedAt 변경 날짜
 */
public record ChangeHistoryEntry(
        int definitionVersion, ChangeType changeType, String changeSummary, LocalDate changedAt) {

    public ChangeHistoryEntry {
        Objects.requireNonNull(changeType, "호환성 등급은 null일 수 없다");
        Objects.requireNonNull(changedAt, "변경 날짜는 null일 수 없다");

        if (definitionVersion < 1) {
            throw new IllegalArgumentException("정의 버전은 1 이상이어야 한다: " + definitionVersion);
        }
        if (changeSummary == null || changeSummary.isBlank()) {
            throw new IllegalArgumentException("변경 요약은 비어 있을 수 없다: version=" + definitionVersion);
        }
        if (definitionVersion == 1 && changeType != ChangeType.INITIAL) {
            throw new IllegalArgumentException("버전 1의 등급은 INITIAL이어야 한다: " + changeType);
        }
        if (definitionVersion > 1 && changeType == ChangeType.INITIAL) {
            throw new IllegalArgumentException(
                    "INITIAL 등급은 버전 1만 가질 수 있다: version=" + definitionVersion);
        }
    }

    /** 이 변경으로 기존에 유효했던 값 집합이 무효해질 수 있는지. */
    public boolean breaksExistingValues() {
        return changeType == ChangeType.INCOMPATIBLE;
    }
}
