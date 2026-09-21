package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityReason;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityState;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/** {@code regime_link} 행과 도메인 사이의 변환 (data-model §4). */
final class RegimeLinkMapper {

    private RegimeLinkMapper() {}

    static RegimeLinkJpaEntity toEntity(RegimeLink link, LocalDate today) {
        return new RegimeLinkJpaEntity(
                link.runId(),
                link.caseRef().snapshotKey(),
                link.caseRef().endMonth().atDay(1),
                link.scenario().name(),
                window(link.postWindow()),
                availability(link.availability()),
                link.status().name(),
                (short) link.attempt(),
                today);
    }

    static RegimeLink toDomain(RegimeLinkJpaEntity entity) {
        Map<String, Object> failure = entity.getFailure();
        return new RegimeLink(
                entity.getId(),
                entity.getLinkId() == null ? null : LinkId.from(entity.getLinkId()),
                entity.getRunId(),
                new CaseRef(entity.getSnapshotKey(), YearMonth.from(entity.getEndMonth())),
                Scenario.valueOf(entity.getScenario()),
                window(entity.getPostWindow()),
                availability(entity.getAvailability()),
                LinkStatus.valueOf(entity.getStatus()),
                failure == null
                        ? null
                        : new LinkFailure(
                                JsonValues.text(failure, "code"),
                                JsonValues.text(failure, "message")),
                entity.getJobMessageId(),
                entity.getAttempt(),
                entity.getQueuedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    static Map<String, Object> window(PostWindow window) {
        Map<String, Object> map = JsonValues.map();
        JsonValues.put(map, "d0", window.d0());
        JsonValues.put(map, "d21", window.d21());
        JsonValues.put(map, "d63", window.d63());
        JsonValues.put(map, "lastAvailable", window.lastAvailable());
        JsonValues.put(map, "completedTradingDays", window.completedTradingDays());
        JsonValues.put(map, "complete21", window.complete21());
        JsonValues.put(map, "complete63", window.complete63());
        return map;
    }

    static PostWindow window(Map<String, Object> source) {
        return new PostWindow(
                JsonValues.date(source, "d0"),
                JsonValues.date(source, "d21"),
                JsonValues.date(source, "d63"),
                JsonValues.date(source, "lastAvailable"),
                JsonValues.integer(source, "completedTradingDays"),
                JsonValues.flag(source, "complete21"),
                JsonValues.flag(source, "complete63"));
    }

    static Map<String, Object> availability(Availability availability) {
        Map<String, Object> map = JsonValues.map();
        JsonValues.put(map, "state", availability.state().name());
        JsonValues.put(
                map, "reason", availability.reason() == null ? null : availability.reason().name());
        JsonValues.put(map, "partial", availability.partial() ? Boolean.TRUE : null);
        JsonValues.put(map, "delistedOn", availability.delistedOn());
        JsonValues.put(map, "completedTradingDays", availability.completedTradingDays());
        return map;
    }

    static Availability availability(Map<String, Object> source) {
        String reason = JsonValues.text(source, "reason");
        Integer completed = JsonValues.integer(source, "completedTradingDays");
        return new Availability(
                AvailabilityState.valueOf(JsonValues.text(source, "state")),
                reason == null ? null : AvailabilityReason.valueOf(reason),
                JsonValues.flag(source, "partial"),
                JsonValues.date(source, "delistedOn"),
                completed == null ? 0 : completed);
    }
}
