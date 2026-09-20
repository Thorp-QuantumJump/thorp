package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.model.Retention;
import com.ssafy.thorp.regimelink.domain.model.ShownCase;
import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code regime_link_search} 행과 도메인 사이의 변환 (data-model §3). */
final class RegimeLinkSearchMapper {

    private RegimeLinkSearchMapper() {}

    static RegimeLinkSearchJpaEntity toEntity(RegimeLinkSearch search, LocalDate today) {
        return new RegimeLinkSearchJpaEntity.Builder()
                .owner(search.ownerUserId())
                .entryPath(search.entryPath().name())
                .snapshot(search.snapshotId(), search.snapshotKey())
                .search(search.mode(), search.fieldId(), search.baselineMonth().atDay(1))
                .runs(search.baseRunId(), search.compareRunId())
                .requests(search.baseRequestId(), search.compareRequestId())
                .filter(search.filterId())
                .cases(toRows(search.shownCases()), search.excludedCount())
                .fullSearchShown(search.fullSearchShown())
                .previous(search.previousSearchId(), retention(search.retention()))
                .basis(search.basis())
                .clientRequestId(search.clientRequestId())
                .searchIdDate(today)
                .build();
    }

    static RegimeLinkSearch toDomain(RegimeLinkSearchJpaEntity entity) {
        return new RegimeLinkSearch(
                entity.getId(),
                entity.getSearchId() == null ? null : LinkSearchId.from(entity.getSearchId()),
                entity.getOwnerUserId(),
                EntryPath.valueOf(entity.getEntryPath()),
                entity.getSnapshotId(),
                entity.getSnapshotKey(),
                entity.getMode(),
                entity.getFieldId(),
                YearMonth.from(entity.getBaselineMonth()),
                entity.getBaseRunId(),
                entity.getCompareRunId(),
                entity.getBaseRequestId(),
                entity.getCompareRequestId(),
                entity.getFilterId(),
                toShownCases(entity.getShownCases()),
                entity.getExcludedCount(),
                entity.isFullSearchShown(),
                entity.getPreviousSearchId(),
                retention(entity.getRetention()),
                entity.getBasis(),
                entity.getClientRequestId(),
                entity.getCreatedAt());
    }

    private static List<Map<String, Object>> toRows(List<ShownCase> cases) {
        List<Map<String, Object>> rows = new ArrayList<>(cases.size());
        for (ShownCase shown : cases) {
            Map<String, Object> row = JsonValues.map();
            JsonValues.put(row, "endMonth", shown.endMonth());
            JsonValues.put(row, "rank", shown.rank());
            JsonValues.put(
                    row, "availability", RegimeLinkMapper.availability(shown.availability()));
            if (!shown.linkIds().isEmpty()) {
                row.put("linkIds", shown.linkIds().stream().map(LinkId::value).toList());
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<ShownCase> toShownCases(List<Map<String, Object>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<ShownCase> cases = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object availability = row.get("availability");
            cases.add(
                    new ShownCase(
                            JsonValues.month(row, "endMonth"),
                            JsonValues.integer(row, "rank"),
                            RegimeLinkMapper.availability(asMap(availability)),
                            linkIds(row)));
        }
        return cases;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<LinkId> linkIds(Map<String, Object> row) {
        Object value = row.get("linkIds");
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).map(LinkId::from).toList();
    }

    private static Map<String, Object> retention(Retention retention) {
        if (retention == null) {
            return null;
        }
        Map<String, Object> map = JsonValues.map();
        JsonValues.put(
                map, "ratio", retention.ratio() == null ? null : retention.ratio().toPlainString());
        map.put("kept", retention.kept().stream().map(YearMonth::toString).toList());
        map.put("added", retention.added().stream().map(YearMonth::toString).toList());
        map.put("removed", retention.removed().stream().map(YearMonth::toString).toList());
        return map;
    }

    private static Retention retention(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        return new Retention(
                JsonValues.decimal(source, "ratio"),
                months(source, "kept"),
                months(source, "added"),
                months(source, "removed"));
    }

    private static List<YearMonth> months(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).map(YearMonth::parse).toList();
    }
}
