package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 구간 요약 저장소 구현.
 *
 * <p>워커 계약이 정한 JSON 을 컬럼으로 갈라 담기만 한다 — 수를 다시 만들지 않으므로 문자열 decimal 의 표기가 그대로 남는다.
 */
@Repository
public class RegimeLinkResultRepositoryImpl implements RegimeLinkResultRepository {

    private final RegimeLinkResultJpaRepository jpaRepository;

    public RegimeLinkResultRepositoryImpl(RegimeLinkResultJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<StoredResult> findByLinkId(Long linkId) {
        return jpaRepository
                .findById(linkId)
                .map(
                        entity ->
                                new StoredResult(
                                        entity.getWindow63(),
                                        entity.getWindow21(),
                                        entity.getHoldings(),
                                        entity.getTrades(),
                                        entity.getKospi(),
                                        entity.getBasis(),
                                        entity.getInputDigest(),
                                        entity.getComputedAt()));
    }

    @Override
    public void save(Long linkId, StoredResult result) {
        jpaRepository.saveAndFlush(
                new RegimeLinkResultJpaEntity(
                        linkId,
                        result.window63(),
                        result.window21(),
                        result.holdings(),
                        result.trades(),
                        result.kospi(),
                        result.basis(),
                        result.inputDigest(),
                        result.computedAt()));
    }
}
