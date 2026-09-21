package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkUseRepository;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 열람 기록 저장소 구현. */
@Repository
public class RegimeLinkUseRepositoryImpl implements RegimeLinkUseRepository {

    private final RegimeLinkUseJpaRepository jpaRepository;

    public RegimeLinkUseRepositoryImpl(RegimeLinkUseJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<RegimeLinkUse> findByLinkSearchId(Long linkSearchId) {
        return jpaRepository.findByLinkSearchId(linkSearchId).stream()
                .map(RegimeLinkUseMapper::toDomain)
                .toList();
    }

    @Override
    public List<RegimeLinkUse> findByLinkId(Long linkId) {
        return jpaRepository.findByLinkId(linkId).stream()
                .map(RegimeLinkUseMapper::toDomain)
                .toList();
    }

    @Override
    public RegimeLinkUse save(RegimeLinkUse use) {
        return RegimeLinkUseMapper.toDomain(
                jpaRepository.saveAndFlush(RegimeLinkUseMapper.toEntity(use)));
    }
}
