package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.application.port.SectorMapEntries;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 대응표 항목을 버전으로 읽는다. 001의 엔티티를 매핑하지 않고 SQL 로 읽는다 — 표의 소유자는 001이고 006이 필요한 것은 (섹터, ETF) 짝이다.
 *
 * <p>버전 행의 유무를 먼저 본다. 항목 쪽만 조인하면 "버전 없음"과 "항목 0개"가 똑같이 빈 결과라 판정 불가를 말할 수 없다.
 */
@Repository
public class SectorMapEntriesImpl implements SectorMapEntries {

    private static final String ENTRIES =
            """
            SELECT s.code, s.name, e.etf_id
              FROM sector_etf_map_entry e
              JOIN sector s ON s.id = e.sector_id
             WHERE e.map_version_id = ?
             ORDER BY s.code
            """;

    private final JdbcTemplate jdbcTemplate;

    public SectorMapEntriesImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<List<Entry>> entriesOf(String mapVersion) {
        return jdbcTemplate
                .query(
                        "SELECT id FROM sector_etf_map_version WHERE version = ?",
                        (rs, rowNum) -> rs.getLong("id"),
                        mapVersion)
                .stream()
                .findFirst()
                .map(this::entries);
    }

    @Override
    public Optional<List<Entry>> currentEntries() {
        return jdbcTemplate
                .query(
                        "SELECT id FROM sector_etf_map_version WHERE effective_to IS NULL",
                        (rs, rowNum) -> rs.getLong("id"))
                .stream()
                .findFirst()
                .map(this::entries);
    }

    private List<Entry> entries(long mapVersionId) {
        return jdbcTemplate.query(
                ENTRIES,
                (rs, rowNum) ->
                        new Entry(rs.getString("code"), rs.getString("name"), rs.getLong("etf_id")),
                mapVersionId);
    }
}
