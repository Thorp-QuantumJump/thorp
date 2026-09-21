package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 목록·상세가 읽는 질의 하나(spec 006 T017, data-model §1 "상태는 컬럼이 아니다").
 *
 * <p>{@code my_record}에 종류별 원본을 조인해 상태·사유·결과 토큰을 <b>읽는 순간 파생</b>한다. 상태를 저장하지 않는 이유는 워커 이벤트로 계속 바뀌는
 * 값을 복제하면 갱신 누락이 "실패한 분석이 목록에서는 완료로 보이는" 모습으로 드러나기 때문이다(원칙 VI, research R2).
 *
 * <p><b>성과 수치 컬럼은 select 에 없다</b>(원칙 III). {@code backtest_result}·{@code search_candidate}는 조인하지
 * 않는다.
 *
 * <p>{@code ANALYSIS}의 요청은 저장돼 있지 않으므로 {@code (소유자, 실행)}으로 <b>취소하지 않은 최신 요청</b>을 고른다 — {@code
 * MyRecordRepository.findViewableRequestId}와 같은 규칙이며, 단건({@code SINGLE}) 요청만 본다. 탐색 후보가 같은 실행에 합류해도
 * 단건 기록이 그 상태를 따라가지 않는다.
 *
 * <p>커서는 정렬 키와 식별자의 짝이다({@code (saved_at, id)} 또는 {@code (name, id)}). offset 을 쓰지 않는 이유: 쪽을 넘기는 사이
 * 새 항목이 들어오면 offset 이 가리키는 자리가 밀려 같은 항목을 두 번 받거나 건너뛴다(research R14).
 */
final class MyRecordViewQuery {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /**
     * 파생 컬럼을 포함한 뷰. 상태를 CTE 안에서 만들어야 바깥 WHERE 가 그 값으로 필터할 수 있다.
     *
     * <p>{@code QUEUED}는 계약에 없는 상태라 {@code RUNNING}으로 보인다(사용자에게는 "돌고 있다"). 탐색 묶음의 {@code REJECTED}는
     * 항목이 만들어지지 않지만(등록기), 혹시 있으면 실패로 보인다.
     */
    static final String VIEW =
            """
            WITH v AS (
                SELECT r.id, r.record_id, r.kind, r.name, r.sector_codes, r.strategy_ids,
                       r.period_start, r.period_end, r.saved_at, r.last_viewed_at, r.is_copy,
                       req.public_token AS result_token,
                       ps.search_id,
                       rl.snapshot_key, rl.snapshot_id, rl.entry_path, rl.baseline_origin,
                       CASE r.kind
                         WHEN 'ANALYSIS' THEN CASE
                             WHEN req.cancelled_at IS NOT NULL OR run.status = 'CANCELLED'
                                 THEN 'CANCELLED'
                             WHEN run.status IN ('QUEUED', 'RUNNING') THEN 'RUNNING'
                             ELSE run.status END
                         WHEN 'SEARCH_BUNDLE' THEN CASE
                             WHEN ps.status IN ('QUEUED', 'RUNNING') THEN 'RUNNING'
                             WHEN ps.status = 'REJECTED' THEN 'FAILED'
                             ELSE ps.status END
                         ELSE CASE
                             WHEN rl.snapshot_id IS NULL THEN 'WITHDRAWN'
                             ELSE snap.status END
                       END AS status,
                       CASE r.kind
                         WHEN 'ANALYSIS' THEN run.failure_code
                         WHEN 'SEARCH_BUNDLE' THEN ps.stop_reason
                         ELSE snap.status_reason
                       END AS status_reason
                  FROM my_record r
                  LEFT JOIN backtest_run run ON run.id = r.analysis_run_id
                  LEFT JOIN LATERAL (
                       SELECT q.public_token, q.cancelled_at
                         FROM backtest_request q
                        WHERE q.requester_id = r.owner_id
                          AND q.run_id = r.analysis_run_id
                          AND q.execution_type = 'SINGLE'
                        ORDER BY (q.cancelled_at IS NOT NULL), q.created_at DESC, q.id DESC
                        LIMIT 1) req ON TRUE
                  LEFT JOIN param_search ps ON ps.id = r.param_search_id
                  LEFT JOIN regime_search_log rl ON rl.id = r.regime_log_id
                  LEFT JOIN regime_search_snapshot snap ON snap.id = rl.snapshot_id
                 WHERE r.owner_id = ? AND r.deleted_at IS NULL
            )
            SELECT * FROM v
            """;

    private final StringBuilder sql = new StringBuilder(VIEW);
    private final List<Object> params = new ArrayList<>();

    MyRecordViewQuery(long ownerId) {
        params.add(ownerId);
    }

    MyRecordViewQuery recordId(String recordId) {
        sql.append(" WHERE record_id = ?");
        params.add(recordId);
        return this;
    }

    /** 필터·정렬·커서를 붙인다. 한 쪽보다 하나 더 읽어 다음 쪽이 있는지 안다 — 개수를 세지 않는다. */
    MyRecordViewQuery page(MyRecordListQuery query) {
        sql.append(" WHERE status IN ").append(inList(query.statuses()));
        if (!query.kinds().isEmpty()) {
            sql.append(" AND kind IN ").append(inList(query.kinds()));
        }
        if (!query.sectorCodes().isEmpty()) {
            sql.append(" AND sector_codes && ").append(textArray(query.sectorCodes()));
        }
        if (!query.strategyIds().isEmpty()) {
            sql.append(" AND strategy_ids && ").append(textArray(query.strategyIds()));
        }
        // 분석 기간이 요청 구간과 겹치는 항목. 기간이 없는 항목(경제환경)은 어느 구간에도 걸리지 않는다.
        if (query.periodFrom() != null) {
            sql.append(" AND period_end >= ?");
            params.add(query.periodFrom());
        }
        if (query.periodTo() != null) {
            sql.append(" AND period_start <= ?");
            params.add(query.periodTo());
        }
        MyRecordListQuery.Cursor cursor = query.cursor();
        switch (query.sort()) {
            case SAVED_AT_DESC -> {
                if (cursor != null) {
                    sql.append(" AND (saved_at, id) < (?, ?)");
                    params.add(Timestamp.from(cursor.savedAt()));
                    params.add(cursor.id());
                }
                sql.append(" ORDER BY saved_at DESC, id DESC");
            }
            case SAVED_AT_ASC -> {
                if (cursor != null) {
                    sql.append(" AND (saved_at, id) > (?, ?)");
                    params.add(Timestamp.from(cursor.savedAt()));
                    params.add(cursor.id());
                }
                sql.append(" ORDER BY saved_at ASC, id ASC");
            }
            case NAME_ASC -> {
                if (cursor != null) {
                    sql.append(" AND (name, id) > (?, ?)");
                    params.add(cursor.name());
                    params.add(cursor.id());
                }
                sql.append(" ORDER BY name ASC, id ASC");
            }
        }
        sql.append(" LIMIT ?");
        params.add(query.size() + 1);
        return this;
    }

    String sql() {
        return sql.toString();
    }

    Object[] params() {
        return params.toArray();
    }

    private String inList(Collection<? extends Enum<?>> values) {
        values.forEach(value -> params.add(value.name()));
        return values.stream().map(v -> "?").collect(Collectors.joining(", ", "(", ")"));
    }

    /** 배열 바인딩 대신 스칼라 자리표시자로 배열을 만든다. GIN 인덱스({@code idx_my_record_sectors})는 그대로 탄다. */
    private String textArray(List<String> values) {
        params.addAll(values);
        return values.stream()
                .map(v -> "?")
                .collect(Collectors.joining(", ", "ARRAY[", "]::text[]"));
    }

    static MyRecordView mapRow(ResultSet rs, int rowNum) throws SQLException {
        RecordKind kind = RecordKind.valueOf(rs.getString("kind"));
        return new MyRecordView(
                rs.getLong("id"),
                rs.getString("record_id"),
                kind,
                rs.getString("name"),
                List.of((String[]) rs.getArray("sector_codes").getArray()),
                List.of((String[]) rs.getArray("strategy_ids").getArray()),
                AnalysisPeriod.ofNullable(
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class)),
                instant(rs, "saved_at"),
                instant(rs, "last_viewed_at"),
                RecordStatus.valueOf(rs.getString("status")),
                rs.getString("status_reason"),
                rs.getString("result_token"),
                rs.getString("search_id"),
                kind == RecordKind.REGIME_SEARCH
                        ? new MyRecordView.Regime(
                                SnapshotKey.parse(rs.getString("snapshot_key")),
                                BaselineOrigin.valueOf(rs.getString("baseline_origin")),
                                EntryPath.valueOf(rs.getString("entry_path")),
                                rs.getObject("snapshot_id") == null)
                        : null,
                rs.getBoolean("is_copy"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** JSONB 컬럼 원문을 맵으로. NULL 이면 빈 맵 — 없는 키는 부른 쪽이 만들지 않는다. */
    static Map<String, Object> json(String raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(raw, MAP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
