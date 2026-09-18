package com.ssafy.thorp.regime.infrastructure.persistence;

import com.ssafy.thorp.regime.application.exception.RegimeLookupFailedException;
import com.ssafy.thorp.regime.application.repository.RegimeSearchLogRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 철회 여부만 읽는다. 기록을 만들거나 고치지 않는다 — 그 표의 주인은 006·014다. */
@Repository
public class RegimeSearchLogRepositoryImpl implements RegimeSearchLogRepository {

    private final RegimeSearchLogJpaRepository repository;

    public RegimeSearchLogRepositoryImpl(RegimeSearchLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isWithdrawn(String snapshotKey) {
        try {
            return repository.existsWithdrawn(snapshotKey);
        } catch (DataAccessException e) {
            throw new RegimeLookupFailedException("검색 기록을 읽지 못했습니다: " + snapshotKey, e);
        }
    }
}
