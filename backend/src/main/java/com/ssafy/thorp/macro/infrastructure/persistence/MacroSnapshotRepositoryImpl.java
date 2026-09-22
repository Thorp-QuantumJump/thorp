package com.ssafy.thorp.macro.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code macro_*} 읽기 구현. JPA Entity 를 두지 않는다.
 *
 * <p>이 표들은 Backend 가 쓰지 않으므로 매핑할 쓰기 모델이 없고, 조회는 여덟 표를 가로지르는 투영 하나뿐이다. Entity 를 만들면 {@code
 * ddl-auto=validate} 를 위해 배치가 쓰는 컬럼까지 전부 매핑해야 하는데 그중 대부분은 이 응답에 쓰이지도 않는다.
 *
 * <p><b>decimal 은 JSONB 문자열로 저장돼 있다</b>(V2 규약). 읽을 때 {@link BigDecimal} 로 되돌리며 double 을 거치지 않는다 —
 * 주택가격 지수는 소수 여러 자리라 double 을 한 번 거치면 끝자리가 흔들리고, 같은 자료로 다시 조회해도 값이 달라진다(SC-004).
 */
@Repository
public class MacroSnapshotRepositoryImpl implements MacroSnapshotRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** 008 의 원값 계보: {@code [[대상월, 값], ...]}. 값은 자릿수를 지키려고 문자열이다. */
    private static final TypeReference<List<List<String>>> LINEAGE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MacroSnapshotRepositoryImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CurrentPointer> findCurrentPointer() {
        List<CurrentPointer> rows =
                jdbc.query(
                        """
                        SELECT p.dataset_version_id   AS dataset_id,
                               d.version_key          AS dataset_key,
                               p.registry_version_id  AS registry_id,
                               r.version_key          AS registry_key,
                               p.display_version_id   AS display_id,
                               s.version_key          AS display_key,
                               COALESCE(d.is_sample_data, FALSE) AS sample_data
                        FROM macro_current_publication p
                        JOIN macro_calculation_registry_version r ON r.id = p.registry_version_id
                        JOIN macro_display_version s ON s.id = p.display_version_id
                        LEFT JOIN macro_dataset_version d ON d.id = p.dataset_version_id
                        """,
                        (rs, rowNum) ->
                                new CurrentPointer(
                                        (Long) rs.getObject("dataset_id"),
                                        rs.getString("dataset_key"),
                                        rs.getLong("registry_id"),
                                        rs.getString("registry_key"),
                                        rs.getLong("display_id"),
                                        rs.getString("display_key"),
                                        rs.getBoolean("sample_data")));
        return rows.stream().findFirst();
    }

    @Override
    public List<IndicatorDefinitionRow> findDefinitions(long registryVersionId) {
        return jdbc.query(
                """
                SELECT indicator_id, field_id, attribution, agency, raw_unit,
                       transformed_unit, transform_rule, license_review_status,
                       license_status, license_suspended_reason
                FROM macro_indicator_definition
                WHERE registry_version_id = ?
                ORDER BY indicator_id
                """,
                (rs, rowNum) ->
                        new IndicatorDefinitionRow(
                                rs.getString("indicator_id"),
                                rs.getString("field_id"),
                                rs.getString("attribution"),
                                rs.getString("agency"),
                                rs.getString("raw_unit"),
                                rs.getString("transformed_unit"),
                                rs.getString("transform_rule"),
                                rs.getString("license_review_status"),
                                rs.getString("license_status"),
                                rs.getString("license_suspended_reason")),
                registryVersionId);
    }

    @Override
    public List<DisplayFieldRow> findDisplayFields(long displayVersionId) {
        return jdbc.query(
                """
                SELECT field_id, display_name, display_order, description
                FROM macro_display_field
                WHERE display_version_id = ?
                ORDER BY display_order
                """,
                (rs, rowNum) ->
                        new DisplayFieldRow(
                                rs.getString("field_id"),
                                rs.getString("display_name"),
                                rs.getInt("display_order"),
                                rs.getString("description")),
                displayVersionId);
    }

    @Override
    public List<DisplayIndicatorRow> findDisplayIndicators(long displayVersionId) {
        return jdbc.query(
                """
                SELECT indicator_id, display_name, display_order
                FROM macro_display_indicator
                WHERE display_version_id = ?
                ORDER BY display_order
                """,
                (rs, rowNum) ->
                        new DisplayIndicatorRow(
                                rs.getString("indicator_id"),
                                rs.getString("display_name"),
                                rs.getInt("display_order")),
                displayVersionId);
    }

    @Override
    public List<BaselineRow> findBaselines(long datasetVersionId, long registryVersionId) {
        return jdbc.query(
                """
                SELECT scope_id, reference_month, reference_month_held_back_by
                FROM macro_baseline
                WHERE dataset_version_id = ? AND registry_version_id = ?
                """,
                (rs, rowNum) ->
                        new BaselineRow(
                                rs.getString("scope_id"),
                                toLocalDate(rs.getDate("reference_month")),
                                readStringList(rs.getString("reference_month_held_back_by"))),
                datasetVersionId,
                registryVersionId);
    }

    @Override
    public List<LatestMonthRow> findLatestValidMonths(long datasetVersionId) {
        return jdbc.query(
                """
                SELECT indicator_id, MAX(target_month) AS target_month
                FROM macro_observation
                WHERE dataset_version_id = ? AND transformed_value IS NOT NULL
                GROUP BY indicator_id
                """,
                (rs, rowNum) ->
                        new LatestMonthRow(
                                rs.getString("indicator_id"),
                                toLocalDate(rs.getDate("target_month"))),
                datasetVersionId);
    }

    @Override
    public List<ObservationRow> findObservations(long datasetVersionId, Collection<MonthKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        // (지표, 대상월) 쌍을 그대로 대조한다. 지표 목록과 월 범위를 각각 IN 으로 주면 두 집합의
        // 곱이 되어 필요 없는 행까지 읽는다 — 지표마다 최신월이 다르므로 그 곱은 실제로 크다.
        String placeholders =
                String.join(", ", java.util.Collections.nCopies(keys.size(), "(?, ?)"));
        List<Object> args = new ArrayList<>();
        args.add(datasetVersionId);
        for (MonthKey key : keys) {
            args.add(key.indicatorId());
            args.add(java.sql.Date.valueOf(key.targetMonth()));
        }
        return jdbc.query(
                """
                SELECT indicator_id, target_month, raw_value, transformed_value,
                       released_at, received_at, missing_reason
                FROM macro_observation
                WHERE dataset_version_id = ?
                  AND (indicator_id, target_month) IN (%s)
                """
                        .formatted(placeholders),
                observationMapper(),
                args.toArray());
    }

    @Override
    public Optional<CollectionStateRow> findLatestCollectionState() {
        List<CollectionStateRow> rows =
                jdbc.query(
                        """
                        SELECT a.status                                         AS status,
                               MAX(i.last_success_at)                           AS last_success_at,
                               a.started_at                                     AS last_attempt_at,
                               COALESCE(BOOL_OR(i.quota_exhausted), FALSE)      AS quota_limited
                        FROM macro_collection_attempt a
                        LEFT JOIN macro_collection_item_status i ON i.attempt_id = a.id
                        WHERE a.id = (SELECT MAX(id) FROM macro_collection_attempt)
                        GROUP BY a.id, a.status, a.started_at
                        """,
                        (rs, rowNum) ->
                                new CollectionStateRow(
                                        rs.getString("status"),
                                        toInstant(rs.getTimestamp("last_success_at")),
                                        toInstant(rs.getTimestamp("last_attempt_at")),
                                        List.of(),
                                        rs.getBoolean("quota_limited")));
        return rows.stream().findFirst().map(this::withFailedIndicators);
    }

    /** 실패한 지표 id 는 별도 조회다. 위 집계와 한 문장에 넣으면 상태 행이 지표 수만큼 늘어난다. */
    private CollectionStateRow withFailedIndicators(CollectionStateRow state) {
        List<String> failed =
                jdbc.queryForList(
                        """
                        SELECT indicator_id
                        FROM macro_collection_item_status
                        WHERE attempt_id = (SELECT MAX(id) FROM macro_collection_attempt)
                          AND status NOT IN ('SUCCEEDED', 'SKIPPED')
                        ORDER BY indicator_id
                        """,
                        String.class);
        return new CollectionStateRow(
                state.status(),
                state.lastSuccessfulAt(),
                state.lastAttemptAt(),
                failed,
                state.quotaLimited());
    }

    private RowMapper<ObservationRow> observationMapper() {
        return (rs, rowNum) ->
                new ObservationRow(
                        rs.getString("indicator_id"),
                        toLocalDate(rs.getDate("target_month")),
                        readRawValue(
                                rs.getString("raw_value"), toLocalDate(rs.getDate("target_month"))),
                        readDecimal(rs.getString("transformed_value")),
                        toLocalDate(rs.getTimestamp("released_at")),
                        toInstant(rs.getTimestamp("received_at")),
                        rs.getString("missing_reason"));
    }

    /**
     * 계보에서 <b>대상월의</b> 원값을 고른다 (008 FR-008).
     *
     * <p>008 의 {@code raw_value} 는 값 하나가 아니라 그 변환값을 만든 원본들이다 — 전년동월 대비는 {@code [[t-12, 값], [t, 값]]}
     * 처럼 둘이 오고, 월 평균은 그 달 거래일 전부가 온다. 배열을 통째로 {@link BigDecimal} 에 넘기면 요청이 400 으로 떨어진다.
     *
     * <p><b>마지막 원소를 고르지 않는다.</b> 증가율은 마침 마지막이 대상월이지만, 규칙을 "마지막"으로 두면 순서가 다른 변환에서 조용히 틀린다. 대상월과 같은
     * 달을 찾는 것이 규칙이다.
     *
     * <p>스칼라로 들어온 값도 그대로 받는다 — 옛 자료 버전이 그 모양이다.
     */
    private BigDecimal readRawValue(String json, LocalDate targetMonth) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        String text = json.trim();
        if (!text.startsWith("[")) {
            return readDecimal(text);
        }
        List<List<String>> lineage;
        try {
            lineage = objectMapper.readValue(text, LINEAGE);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("원값 계보를 읽지 못했다: " + text, error);
        }
        String wanted = targetMonth == null ? null : YearMonth.from(targetMonth).toString();
        for (List<String> pair : lineage) {
            if (pair != null && pair.size() == 2 && Objects.equals(pair.get(0), wanted)) {
                return readDecimal(pair.get(1));
            }
        }
        // 대상월이 계보에 없으면 원값이 없는 것이다. 다른 달 값을 대신 내놓으면 화면의 숫자가
        // 제목과 다른 달을 말한다.
        return null;
    }

    /**
     * JSONB 에 담긴 decimal 을 되돌린다. 값이 없으면 null 이다.
     *
     * <p>배치는 자릿수를 지키려고 문자열로 넣는다. 그래서 {@code "101.20"} 처럼 따옴표가 붙어 오고, 여기서 그것을 벗겨 {@link BigDecimal}
     * 로 만든다. 숫자로 들어온 경우도 같은 경로로 받는다.
     */
    private BigDecimal readDecimal(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        String text = json.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }
        return text.isBlank() ? null : new BigDecimal(text);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : List.copyOf(values);
        } catch (Exception error) {
            // 조용히 빈 배열로 넘기지 않는다 — 기준월을 물러나게 한 지표가 사라지면 화면은
            // 왜 T_g 가 뒤로 갔는지 설명할 수 없다.
            throw new IllegalStateException("기준월 지연 지표 목록을 읽지 못했습니다: " + json, error);
        }
    }

    private LocalDate toLocalDate(java.sql.Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private LocalDate toLocalDate(Timestamp value) {
        return value == null
                ? null
                : value.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
