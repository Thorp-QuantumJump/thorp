package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkSearchRepository;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 검색 기록 저장소 구현. 외부 식별자는 엔티티가 PK 채번 뒤에 짓는다. */
@Repository
public class RegimeLinkSearchRepositoryImpl implements RegimeLinkSearchRepository {

    private final RegimeLinkSearchJpaRepository jpaRepository;
    private final Clock clock;

    public RegimeLinkSearchRepositoryImpl(
            RegimeLinkSearchJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
    }

    @Override
    public Optional<RegimeLinkSearch> findBySearchId(LinkSearchId searchId) {
        return jpaRepository.findBySearchId(searchId.value()).map(RegimeLinkSearchMapper::toDomain);
    }

    @Override
    public Optional<RegimeLinkSearch> findByClientRequest(
            Long ownerUserId, String clientRequestId) {
        return jpaRepository
                .findByOwnerUserIdAndClientRequestId(ownerUserId, clientRequestId)
                .map(RegimeLinkSearchMapper::toDomain);
    }

    @Override
    public RegimeLinkSearch save(RegimeLinkSearch search) {
        return RegimeLinkSearchMapper.toDomain(
                jpaRepository.saveAndFlush(
                        RegimeLinkSearchMapper.toEntity(search, LocalDate.now(clock))));
    }
}
