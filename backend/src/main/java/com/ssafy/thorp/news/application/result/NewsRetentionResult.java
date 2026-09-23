package com.ssafy.thorp.news.application.result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 일괄 삭제 회차의 결과(spec FR-019·FR-023).
 *
 * @param outcome 이 회차가 무엇을 했는지.
 * @param deletedRows 표 이름 → 지운 행 수. 실행하지 않았으면 비어 있다.
 */
public record NewsRetentionResult(Outcome outcome, Map<String, Long> deletedRows) {

    public NewsRetentionResult {
        deletedRows = Map.copyOf(deletedRows);
    }

    /** 회차가 한 일. */
    public enum Outcome {

        /** 보관 기한 전이라 아무것도 하지 않았다. */
        NOT_DUE,

        /** 다른 인스턴스가 이미 돌리고 있다(spec FR-022). */
        SKIPPED,

        /** 삭제를 실행했다. 지운 행이 0건이어도 실행이다. */
        EXECUTED
    }

    public static NewsRetentionResult notDue() {
        return new NewsRetentionResult(Outcome.NOT_DUE, new LinkedHashMap<>());
    }

    public static NewsRetentionResult skipped() {
        return new NewsRetentionResult(Outcome.SKIPPED, new LinkedHashMap<>());
    }

    public static NewsRetentionResult executed(Map<String, Long> deletedRows) {
        return new NewsRetentionResult(Outcome.EXECUTED, deletedRows);
    }

    /** 이 회차가 지운 전체 행 수. */
    public long totalDeleted() {
        return deletedRows.values().stream().mapToLong(Long::longValue).sum();
    }
}
