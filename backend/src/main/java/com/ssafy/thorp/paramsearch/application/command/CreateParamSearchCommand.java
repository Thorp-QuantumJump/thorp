package com.ssafy.thorp.paramsearch.application.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 탐색 실행 접수 입력(contracts/param-search-api.md §2). 미리보기와 같은 필드에 {@code registryVersion}이 더 붙는다.
 *
 * <p><b>{@code registryVersion}이 필수인 이유</b>: 사용자가 확인한 작업량과 실제 실행되는 후보 집합이 달라지면 재현 기록이 거짓이 된다(원칙
 * VIII). 미리보기 응답의 버전을 되돌려 받아 접수 시점 버전과 비교하면, 그 불일치가 조용히 지나가지 않고 409로 드러난다(FR-028).
 */
public record CreateParamSearchCommand(
        Long requesterId,
        List<String> instrumentIds,
        List<String> strategyIds,
        LocalDate start,
        LocalDate end,
        BigDecimal initialCash,
        Map<String, Map<String, List<Object>>> space,
        String registryVersion) {

    public CreateParamSearchCommand {
        Objects.requireNonNull(instrumentIds, "instrumentIds must not be null");
        Objects.requireNonNull(strategyIds, "strategyIds must not be null");
        if (registryVersion == null || registryVersion.isBlank()) {
            throw new IllegalArgumentException("registryVersion must not be blank");
        }
        instrumentIds = List.copyOf(instrumentIds);
        strategyIds = List.copyOf(strategyIds);
        space = space == null ? Map.of() : Map.copyOf(space);
    }

    /** 같은 선택으로 미리보기를 다시 계산할 때 쓴다 — 409를 만들 때 화면에 돌려줄 요약이 필요하다. */
    public PreviewParamSearchCommand toPreview() {
        return new PreviewParamSearchCommand(
                requesterId, instrumentIds, strategyIds, start, end, initialCash, space);
    }
}
