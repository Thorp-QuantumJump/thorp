package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.application.port.SectorMapEntries;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 대응표 항목을 버전으로 읽는다. 001의 엔티티를 매핑하지 않고 SQL 로 읽는다 — 표의 소유자는 001이고 006이 필요한 것은 (섹터, ETF) 짝이다. 001의 스프링
 * 데이터 인터페이스는 그 패키지 안에 갇혀 있어 빌려 쓸 수 없다.
 *
 * <p>질의는 둘이다. 버전 행 하나(저장 버전과 열린 버전을 함께)로 "버전 없음"과 "항목 0개"를 가르고 두 버전이 같은 행이면 거기서 끝낸다 — 상세를 열 때마다
 * 판정하므로 대부분의 호출이 여기서 돌아간다. 다르면 두 버전의 항목을 한 문장으로 읽는다.
 */
@Repository
public class SectorMapEntriesImpl implements SectorMapEntries {

    private static final String VERSIONS =
            """
            SELECT id, version, (effective_to IS NULL) AS open
              FROM sector_etf_map_version
             WHERE version = ? OR effective_to IS NULL
            """;

    private static final String ENTRIES =
            """
            SELECT e.map_version_id, s.code, s.name, e.etf_id
              FROM sector_etf_map_entry e
              JOIN sector s ON s.id = e.sector_id
             WHERE e.map_version_id IN (?, ?)
             ORDER BY s.code
            """;

    private final JdbcTemplate jdbcTemplate;

    public SectorMapEntriesImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Versions> load(String savedMapVersion) {
        record Row(long id, String version, boolean open) {}
        List<Row> rows =
                jdbcTemplate.query(
                        VERSIONS,
                        (rs, n) ->
                                new Row(
                                        rs.getLong("id"),
                                        rs.getString("version"),
                                        rs.getBoolean("open")),
                        savedMapVersion);
        Optional<Long> saved =
                rows.stream()
                        .filter(r -> r.version().equals(savedMapVersion))
                        .map(Row::id)
                        .findFirst();
        Optional<Long> open = rows.stream().filter(Row::open).map(Row::id).findFirst();
        if (saved.isEmpty() || open.isEmpty()) {
            return Optional.empty();
        }
        if (saved.get().equals(open.get())) {
            return Optional.of(new Versions(true, List.of(), List.of()));
        }
        record EntryRow(long mapId, Entry entry) {}
        Map<Long, List<Entry>> byMap =
                jdbcTemplate
                        .query(
                                ENTRIES,
                                (rs, n) ->
                                        new EntryRow(
                                                rs.getLong("map_version_id"),
                                                new Entry(
                                                        rs.getString("code"),
                                                        rs.getString("name"),
                                                        rs.getLong("etf_id"))),
                                saved.get(),
                                open.get())
                        .stream()
                        .collect(
                                Collectors.groupingBy(
                                        EntryRow::mapId,
                                        Collectors.mapping(EntryRow::entry, Collectors.toList())));
        return Optional.of(
                new Versions(
                        false,
                        byMap.getOrDefault(saved.get(), List.of()),
                        byMap.getOrDefault(open.get(), List.of())));
    }
}
