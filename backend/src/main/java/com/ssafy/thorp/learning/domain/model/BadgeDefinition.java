package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.Objects;

/**
 * 배지 하나의 정의(data-model §12-4). 기준을 모두 '나의 학습' 화면에 공개한다(FR-024). 이름은 무엇을 익혔는지만 말한다.
 *
 * @param measure 받는 조건을 무엇으로 세는가
 * @param target 기준 수. 레벨 배지는 레벨, 단계 완주·끈기·탐험·용어 전체는 쓰지 않아 0이다
 * @param stageKey 단계 완주 배지의 단계. 그 밖은 null
 * @param stageNumber 단계 완주 배지의 단계 번호. 그 밖은 null
 */
public record BadgeDefinition(
        BadgeKey key,
        Family family,
        String name,
        String criterion,
        Measure measure,
        int target,
        StageKey stageKey,
        Integer stageNumber) {

    public BadgeDefinition {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(criterion, "criterion must not be null");
        Objects.requireNonNull(measure, "measure must not be null");
        if ((measure == Measure.STAGE) != (stageKey != null)
                || (stageKey == null) != (stageNumber == null)) {
            throw new IllegalArgumentException("only a stage badge points to a stage: " + key);
        }
    }

    /** 배지 갈래. 응답 순서가 이 순서다. */
    public enum Family {
        LEVEL,
        READ,
        CORRECT,
        PERFECT,
        STAGE,
        TERMS,
        SPECIAL
    }

    /** 받는 조건이 세는 것. */
    public enum Measure {
        /** 현재 레벨 ≥ {@code target} */
        LEVEL,
        /** 읽은 글 수 ≥ {@code target} */
        READ,
        /** 여섯 단계 공개 뒤 읽은 글 수 ≥ {@code target}({@code read-27}) */
        READ_ALL_STAGES,
        /** 첫 답 정답 수 ≥ {@code target} */
        CORRECT,
        /** 모두 맞힘 글 수 ≥ {@code target} */
        PERFECT,
        /** 그 단계가 비어 있지 않은 공개 단계이고 공개된 글을 모두 완료 */
        STAGE,
        /** 익힌 용어 수 ≥ {@code target} */
        TERMS,
        /** 여섯 단계 공개 뒤 공개된 용어가 하나 이상이고 모두 익힘({@code terms-all}) */
        TERMS_ALL,
        /** 다시 풀기로 익힌 글이 하나 이상({@code persistence}) */
        PERSISTENCE,
        /** 여섯 단계 공개 뒤 비어 있지 않은 공개 단계마다 완료한 글이 하나 이상({@code exploration}) */
        EXPLORATION
    }
}
