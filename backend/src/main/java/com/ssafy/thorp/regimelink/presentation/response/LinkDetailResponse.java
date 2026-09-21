package com.ssafy.thorp.regimelink.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.regimelink.application.port.RunProgressView;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import com.ssafy.thorp.regimelink.application.result.LinkDetailResult;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 링크 하나의 상태와 구간 요약 (contracts §3).
 *
 * <p>{@code result}는 저장된 워커 산출물을 <b>그대로</b> 내보낸다 — 계약이 정한 표기(소수는 문자열, {@code return} 키, 없는 값은 키
 * 생략)로 저장돼 있어 다시 옮기지 않는다. 옮기면 자릿수가 깎이고 표기가 두 벌이 된다.
 *
 * <p>{@code runProgress}·{@code failure}·{@code result}는 값이 없을 때 <b>명시적 {@code null}</b>이다. 화면이 그
 * 자리를 읽어 상태를 가르므로 키를 지우지 않는다(계약 예시 {@code link-completed.json}와 같은 모양).
 */
public record LinkDetailResponse(
        Basis basis,
        String linkId,
        String publicToken,
        String caseId,
        String scenario,
        String status,
        Window postWindow,
        State availability,
        Progress runProgress,
        Failure failure,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Result result) {

    /** {@code dataVersion}은 결과가 있을 때만 알 수 있다 — 저장된 {@code basis}에서 읽는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Basis(String kind, String dataVersion) {}

    public record Window(
            LocalDate d0,
            LocalDate d21,
            LocalDate d63,
            int completedTradingDays,
            boolean complete21,
            boolean complete63) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(String state, String reason, Boolean partial, LocalDate delistedOn) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(String status, Stage progress, Long queueWaitSeconds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stage(
            String stage,
            LocalDate lastTradingDate,
            Integer completedTradingDays,
            Integer totalTradingDays) {}

    public record Failure(String code, String message, boolean retryable) {}

    /**
     * 저장된 구간 요약. 값은 계약 모양의 JSON 그대로다.
     *
     * <p>{@code window*.lossContribution}은 <b>구간 손익이 0 일 때 키가 없다</b> — 워커가 생략해 보내면 저장에도 응답에도 키가 없고,
     * 화면은 그것으로 표시 여부를 가른다.
     */
    public record Result(
            Map<String, Object> window63,
            Map<String, Object> window21,
            List<Map<String, Object>> holdings,
            List<Map<String, Object>> trades,
            Map<String, Object> kospi) {}

    public static LinkDetailResponse from(LinkDetailResult detail) {
        RegimeLinkResultRepository.StoredResult stored = detail.result();
        return new LinkDetailResponse(
                new Basis("HISTORICAL_REVISION", dataVersion(stored)),
                detail.linkId() == null ? null : detail.linkId().value(),
                detail.publicToken(),
                detail.caseRef().toString(),
                detail.scenario().name(),
                detail.status().name(),
                window(detail.postWindow()),
                state(detail.availability()),
                progress(detail.runProgress()),
                detail.failure() == null
                        ? null
                        : new Failure(
                                detail.failure().code(),
                                detail.failure().message(),
                                detail.failure().retryable()),
                detail.queuedAt(),
                detail.startedAt(),
                detail.finishedAt(),
                stored == null
                        ? null
                        : new Result(
                                stored.window63(),
                                stored.window21(),
                                stored.holdings(),
                                stored.trades(),
                                stored.kospi()));
    }

    private static String dataVersion(RegimeLinkResultRepository.StoredResult stored) {
        if (stored == null || stored.basis() == null) {
            return null;
        }
        Object value = stored.basis().get("dataVersion");
        return value == null ? null : String.valueOf(value);
    }

    private static Window window(PostWindow window) {
        return new Window(
                window.d0(),
                window.d21(),
                window.d63(),
                window.completedTradingDays(),
                window.complete21(),
                window.complete63());
    }

    private static State state(Availability availability) {
        return new State(
                availability.state().name(),
                availability.reason() == null ? null : availability.reason().name(),
                availability.partial() ? Boolean.TRUE : null,
                availability.delistedOn());
    }

    private static Progress progress(RunProgressView view) {
        if (view == null) {
            return null;
        }
        return new Progress(
                view.status(),
                view.progress() == null
                        ? null
                        : new Stage(
                                view.progress().stage(),
                                view.progress().lastTradingDate(),
                                view.progress().completedTradingDays(),
                                view.progress().totalTradingDays()),
                view.queueWaitSeconds());
    }
}
