package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkFilterRepository;
import com.ssafy.thorp.regimelink.application.service.FilterKeyCalculator;
import com.ssafy.thorp.regimelink.domain.model.LinkFilter;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 필터 저장소 구현.
 *
 * <p><b>키 충돌을 예외로 만들지 않는다.</b> 두 요청이 같은 묶음을 동시에 처음 조회하면 같은 키를 만들고 INSERT 가 겹친다. 그때 제약 위반을 던지면 진 쪽의
 * 트랜잭션이 rollback-only 로 표시돼 <b>조회 전체가 500 으로 끝난다</b> — 같은 키는 같은 정의라 값이 다를 수 없으니, {@code ON CONFLICT
 * DO NOTHING} 으로 넘기고 이긴 쪽의 행을 그대로 읽는다.
 */
@Repository
public class RegimeLinkFilterRepositoryImpl implements RegimeLinkFilterRepository {

    private static final String INSERT_SQL =
            """
            INSERT INTO regime_link_filter (filter_key, definition, available_range, created_at)
            VALUES (:filterKey, CAST(:definition AS jsonb), CAST(:availableRange AS jsonb), NOW())
            ON CONFLICT (filter_key) DO NOTHING
            """;

    @PersistenceContext private EntityManager entityManager;

    private final RegimeLinkFilterJpaRepository jpaRepository;
    private final FilterKeyCalculator filterKeys;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        entityManager
                .createNativeQuery(INSERT_SQL)
                .setParameter("filterKey", filter.key().value())
                .setParameter("definition", json(filterKeys.asMap(filter.definition())))
                .setParameter("availableRange", json(RegimeLinkFilterMapper.range(filter)))
                .executeUpdate();
        return findByKey(filter.key())
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INTERNAL_ERROR, "가용성 필터를 저장하지 못했습니다."));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "가용성 필터를 저장하지 못했습니다.");
        }
    }
}
