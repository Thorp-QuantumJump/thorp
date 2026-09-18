package com.ssafy.thorp.regime.infrastructure.persistence;

import com.ssafy.thorp.regime.application.exception.RegimeLookupFailedException;
import com.ssafy.thorp.regime.application.repository.RegimeBaselineMonthRepository;
import com.ssafy.thorp.regime.domain.model.RegimeBaselineMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 배치가 기록한 기준월 조회 구현 (data-model §3). */
@Repository
public class RegimeBaselineMonthRepositoryImpl implements RegimeBaselineMonthRepository {

    private final RegimeBaselineMonthJpaRepository repository;

    public RegimeBaselineMonthRepositoryImpl(RegimeBaselineMonthJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegimeBaselineMonth> findAll() {
        try {
            return repository.findAll().stream().map(RegimeBaselineMonthMapper::toDomain).toList();
        } catch (DataAccessException e) {
            throw new RegimeLookupFailedException("기준월을 읽지 못했습니다.", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegimeBaselineMonth> findByScope(String scopeId) {
        try {
            return repository.findById(scopeId).map(RegimeBaselineMonthMapper::toDomain);
        } catch (DataAccessException e) {
            throw new RegimeLookupFailedException("기준월을 읽지 못했습니다: " + scopeId, e);
        }
    }
}
