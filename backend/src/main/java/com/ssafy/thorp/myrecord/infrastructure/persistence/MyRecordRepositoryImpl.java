package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import java.time.Clock;
import java.time.LocalDate;
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

    /**
     * 취소하지 않은 것 중 최신, 없으면 취소한 것 중 최신.
     *
     * <p>{@code backtest_request} 는 004 소유라 엔티티를 매핑하지 않고 SQL 로 읽는다. 006 이 필요한 것은 식별자 하나다.
     */
    @Override
    public Optional<Long> findViewableRequestId(long ownerId, long runId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT id FROM backtest_request
                        WHERE requester_id = ? AND run_id = ?
                        ORDER BY (cancelled_at IS NOT NULL), created_at DESC, id DESC
                        LIMIT 1
                        """,
                        (rs, rowNum) -> rs.getLong("id"),
                        ownerId,
                        runId)
                .stream()
                .findFirst();
    }
}
