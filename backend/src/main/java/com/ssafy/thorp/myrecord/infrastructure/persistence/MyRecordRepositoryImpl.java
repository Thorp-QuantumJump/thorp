package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.application.result.MyRecordPage;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.application.result.SourceVersions;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 저장 시 외부 식별자를 부여한다.
 *
 * <p>{@code save()}는 신규 엔티티에 {@code persist()}를 호출하고, SEQUENCE라 그 시점에 PK가 나온다. 그 PK로 {@code
 * record_id}를 INSERT 전에 채우므로 INSERT는 한 번이고 컬럼을 NOT NULL로 둘 수 있다. 날짜만 여기서 정하고 PK와의 결합은 엔티티의
 * {@code @PrePersist}가 한다. {@code ParamSearchRepositoryImpl}과 같은 규약이다.
 */
@Repository
public class MyRecordRepositoryImpl implements MyRecordRepository {

    private final MyRecordJpaRepository jpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public MyRecordRepositoryImpl(
            MyRecordJpaRepository jpaRepository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public MyRecord save(MyRecord record) {
        if (record.id() != null) {
            throw new IllegalArgumentException("save is for new records only: id=" + record.id());
        }
        MyRecordJpaEntity entity = MyRecordMapper.toEntity(record);
        entity.assignRecordIdOn(LocalDate.now(clock));
        return MyRecordMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<MyRecord> findByRecordId(String recordId) {
        return jpaRepository.findByRecordId(recordId).map(MyRecordMapper::toDomain);
    }

    @Override
    public Optional<MyRecord> findActiveByOwnerAndRun(long ownerId, long runId) {
        return jpaRepository.findActiveByOwnerAndRun(ownerId, runId).map(MyRecordMapper::toDomain);
    }

    @Override
    public Optional<MyRecord> findActiveByOwnerAndParamSearch(long ownerId, long paramSearchId) {
        return jpaRepository
                .findActiveByOwnerAndParamSearch(ownerId, paramSearchId)
                .map(MyRecordMapper::toDomain);
    }

    @Override
    public Optional<MyRecord> findActiveByOwnerAndRegimeLog(long ownerId, long regimeLogId) {
        return jpaRepository
                .findActiveByOwnerAndRegimeLog(ownerId, regimeLogId)
                .map(MyRecordMapper::toDomain);
    }

    @Override
    public long countActiveByOwnerAndKind(long ownerId, RecordKind kind) {
        return jpaRepository.countActiveByOwnerAndKind(ownerId, kind);
    }

    @Override
    public MyRecordPage findPage(long ownerId, MyRecordListQuery query) {
        MyRecordViewQuery q = new MyRecordViewQuery(ownerId).page(query);
        List<MyRecordView> rows =
                jdbcTemplate.query(q.sql(), MyRecordViewQuery::mapRow, q.params());
        if (rows.size() <= query.size()) {
            return new MyRecordPage(rows, null);
        }
        List<MyRecordView> page = rows.subList(0, query.size());
        return new MyRecordPage(List.copyOf(page), page.get(page.size() - 1).cursor());
    }

    @Override
    public Optional<MyRecordView> findView(long ownerId, String recordId) {
        MyRecordViewQuery q = new MyRecordViewQuery(ownerId).recordId(recordId);
        return jdbcTemplate.query(q.sql(), MyRecordViewQuery::mapRow, q.params()).stream()
                .findFirst();
    }

    /**
     * 원본의 JSONB 를 원문으로 읽는다. 004·007 엔티티를 매핑하지 않고 SQL 로 읽는다 — 표의 소유자는 그쪽이고 006이 필요한 것은 두 컬럼이다. 실패한
     * 실행에도 {@code backtest_run.versions}는 있다(접수가 확정한 값). {@code backtest_result.versions}는 워커 보고라
     * 쓰지 않는다.
     */
    @Override
    public Optional<SourceVersions> findSourceVersions(long id, RecordKind kind) {
        String sql =
                switch (kind) {
                    case ANALYSIS ->
                            """
                            SELECT run.versions::text AS versions, run.request_spec::text AS spec
                              FROM my_record r JOIN backtest_run run ON run.id = r.analysis_run_id
                             WHERE r.id = ?
                            """;
                    case SEARCH_BUNDLE ->
                            """
                            SELECT ps.reference_versions::text AS versions,
                                   ps.execution_plan::text AS spec
                              FROM my_record r JOIN param_search ps ON ps.id = r.param_search_id
                             WHERE r.id = ?
                            """;
                    case REGIME_SEARCH -> null;
                };
        if (sql == null) {
            return Optional.empty();
        }
        return jdbcTemplate
                .query(
                        sql,
                        (rs, rowNum) ->
                                new SourceVersions(
                                        MyRecordViewQuery.json(rs.getString("versions")),
                                        MyRecordViewQuery.json(rs.getString("spec"))),
                        id)
                .stream()
                .findFirst();
    }

    /** 이 컬럼만 쓴다. 엔티티를 읽어 통째로 저장하면 같은 행을 고치는 다른 경로(이름 변경·삭제)의 갱신을 되돌릴 수 있다. */
    @Override
    public void markViewed(long id, Instant at) {
        jdbcTemplate.update(
                "UPDATE my_record SET last_viewed_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(at),
                Timestamp.from(at),
                id);
    }
}
