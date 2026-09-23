package com.ssafy.thorp.news.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 정규화·연결·묶음·정렬 규칙의 버전(원칙 VIII).
 *
 * <p>규칙이 바뀌면 기존 결과를 덮어쓰지 않고 새 버전을 만든다. 저장된 기사와 묶음이 자기가 쓴 버전을 참조하므로, 같은 버전으로 다시 실행하면 같은 결과가 나온다.
 *
 * <p>{@code definition}은 임계값과 정렬 순서처럼 재현에 필요한 값이다. 기사 본문이나 외부 응답을 담지 않는다.
 */
public record NewsRuleVersion(
        String ruleVersion,
        RuleType ruleType,
        Map<String, Object> definition,
        Instant effectiveFrom,
        Instant retiredAt) {

    /** 규칙 종류. 각 종류는 같은 시점에 하나만 유효하다. */
    public enum RuleType {
        URL_NORMALIZATION,
        LINK,
        GROUPING,
        IMPORTANCE
    }

    public NewsRuleVersion {
        Objects.requireNonNull(ruleVersion, "ruleVersion must not be null");
        if (ruleVersion.isBlank()) {
            throw new IllegalArgumentException("ruleVersion은 비어 있을 수 없다");
        }
        Objects.requireNonNull(ruleType, "ruleType must not be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        definition = Map.copyOf(Objects.requireNonNull(definition, "definition must not be null"));
        if (retiredAt != null && retiredAt.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("retiredAt은 effectiveFrom보다 앞설 수 없다");
        }
    }

    /** 이 시점에 유효한 규칙인지 여부. */
    public boolean isEffectiveAt(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(effectiveFrom)) {
            return false;
        }
        return retiredAt == null || at.isBefore(retiredAt);
    }
}
