package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.application.port.RegimeSearchLogWriter;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.regime.infrastructure.persistence.RegimeSearchLogJpaEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link RegimeSearchLogWriter}의 JPA 구현. 010의 엔티티에 행을 만들고 열람 시각을 올린다. */
@Repository
public class RegimeSearchLogWriterImpl implements RegimeSearchLogWriter {

    private final RegimeSearchLogJpaWriteRepository jpaRepository;

    public RegimeSearchLogWriterImpl(RegimeSearchLogJpaWriteRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Long> findActive(long ownerId, String snapshotKey, EntryPath entryPath) {
        return jpaRepository.findIdByOwnerAndKeyAndPath(ownerId, snapshotKey, entryPath.name());
    }

    @Override
    public long append(
            long ownerId,
            String snapshotKey,
            Long snapshotId,
            EntryPath entryPath,
            BaselineOrigin baselineOrigin,
            Instant viewedAt) {
        RegimeSearchLogJpaEntity saved =
                jpaRepository.save(
                        RegimeSearchLogJpaEntity.create(
                                ownerId,
                                snapshotId,
                                snapshotKey,
                                entryPath.name(),
                                baselineOrigin.name(),
                                viewedAt));
        return saved.getId();
    }

    @Override
    public void touch(long logId, Instant viewedAt) {
        jpaRepository.touchViewedAt(logId, viewedAt);
    }
}
