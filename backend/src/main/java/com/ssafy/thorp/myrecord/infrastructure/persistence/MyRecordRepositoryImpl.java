package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordRef;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
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
    private final Clock clock;

    public MyRecordRepositoryImpl(MyRecordJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
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

    /**
     * 관리 엔티티를 읽어 요청 참조만 바꾼다.
     *
     * <p>도메인 객체를 새 엔티티로 merge 하면 {@code createdAt}(updatable=false)이 null 인 사본이 관리 상태를 덮어 쓴다.
     * {@code ParamSearchRepositoryImpl.update}와 같은 규약이다.
     */
    @Override
    public MyRecord relinkAnalysisRequest(MyRecord record) {
        if (record.id() == null) {
            throw new IllegalArgumentException("relink is for persisted records only: id is null");
        }
        MyRecordJpaEntity entity =
                jpaRepository
                        .findById(record.id())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "my record not found: id=" + record.id()));
        entity.relinkAnalysisRequest(((RecordRef.Analysis) record.ref()).requestId());
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
}
