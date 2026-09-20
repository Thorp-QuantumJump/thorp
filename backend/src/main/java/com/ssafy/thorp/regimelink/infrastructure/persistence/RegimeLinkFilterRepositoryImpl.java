package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkFilterRepository;
import com.ssafy.thorp.regimelink.application.service.FilterKeyCalculator;
import com.ssafy.thorp.regimelink.domain.model.LinkFilter;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * 필터 저장소 구현.
 *
 * <p>키 충돌을 실패로 올리지 않는다 — 두 요청이 같은 묶음을 동시에 조회하면 같은 키를 만들고, 그중 하나만 INSERT 에 성공한다. 진 쪽은 이긴 쪽의 행을 그대로
 * 쓰면 된다. 같은 키는 같은 정의라 값이 다를 수 없다.
 */
@Repository
public class RegimeLinkFilterRepositoryImpl implements RegimeLinkFilterRepository {

    private final RegimeLinkFilterJpaRepository jpaRepository;
    private final FilterKeyCalculator filterKeys;

    public RegimeLinkFilterRepositoryImpl(
            RegimeLinkFilterJpaRepository jpaRepository, FilterKeyCalculator filterKeys) {
        this.jpaRepository = jpaRepository;
        this.filterKeys = filterKeys;
    }

    @Override
    public Optional<LinkFilter> findByKey(FilterKey key) {
        return jpaRepository.findByFilterKey(key.value()).map(RegimeLinkFilterMapper::toDomain);
    }

    @Override
    public LinkFilter save(LinkFilter filter) {
        try {
            return RegimeLinkFilterMapper.toDomain(
                    jpaRepository.saveAndFlush(
                            RegimeLinkFilterMapper.toEntity(
                                    filter, filterKeys.asMap(filter.definition()))));
        } catch (DataIntegrityViolationException raced) {
            return findByKey(filter.key()).orElseThrow(() -> raced);
        }
    }
}
