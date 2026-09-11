package com.ssafy.thorp.paramsearch.presentation.response;

import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import java.time.Instant;
import java.util.Map;

/**
 * 접수 응답(202)과 상태 조회 응답(200)이 같은 모양을 쓴다(contracts/param-search-api.md §2·§3).
 *
 * <p>접수 직후에는 {@code searchId}·{@code status}만 뜻이 있고 나머지는 비어 있거나 초기값이다 — 화면은 그 두 값으로 폴링을 시작한다.
 *
 * <p><b>실패해도 200이다.</b> 상태는 {@code status}로 판단한다({@code GET /backtests/{id}}와 같은 규약).
 */
public record ParamSearchResponse(
        String searchId,
        ParamSearchStatus status,
        String stopReason,
        Counts counts,
        boolean partial,
        String partialNote,
        Config config,
        Map<String, Object> selection,
        Instant startedAt,
        Instant finishedAt) {

    public record Counts(
            long expected,
            long valid,
            long completed,
            long failed,
            long excluded,
            long pending,
            long skipped) {}

    public record Config(
            java.util.List<String> strategyIds,
            java.util.List<String> instrumentIds,
            String registryVersion,
            String searchMethod,
            Integer randomSeed,
            Map<String, Object> space,
            Map<String, Object> selectionRule,
            Map<String, Object> limits,
            Map<String, Object> referenceVersions,
            boolean dataVersionChanged) {}

    /** 접수 직후 응답. 계약이 {@code searchId}와 {@code status}만 요구한다. */
    public static ParamSearchResponse accepted(ParamSearchResult result) {
        return new ParamSearchResponse(
                result.searchId(),
                result.status(),
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    public static ParamSearchResponse from(ParamSearchResult result) {
        return new ParamSearchResponse(
                result.searchId(),
                result.status(),
                result.stopReason() == null ? null : result.stopReason().name(),
                new Counts(
                        result.counts().expected(),
                        result.counts().valid(),
                        result.counts().completed(),
                        result.counts().failed(),
                        result.counts().excluded(),
                        result.counts().pending(),
                        result.counts().skipped()),
                result.partial(),
                result.partialNote(),
                new Config(
                        result.config().strategyIds(),
                        result.config().instrumentIds(),
                        result.config().registryVersion(),
                        result.config().searchMethod(),
                        result.config().randomSeed(),
                        result.config().space(),
                        result.config().selectionRule(),
                        result.config().limits(),
                        result.config().referenceVersions(),
                        result.config().dataVersionChanged()),
                result.selection(),
                result.startedAt(),
                result.finishedAt());
    }
}
