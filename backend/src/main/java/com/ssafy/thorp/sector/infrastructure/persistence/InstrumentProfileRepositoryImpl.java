package com.ssafy.thorp.sector.infrastructure.persistence;

import com.ssafy.thorp.sector.application.repository.InstrumentProfileRepository;
import com.ssafy.thorp.sector.application.result.InstrumentProfileResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link InstrumentProfileRepository}의 JPA 구현. 읽기만 한다. */
@Repository
public class InstrumentProfileRepositoryImpl implements InstrumentProfileRepository {

    /** 가장 최근 한 건만 쓴다. 나머지 자료 버전의 행은 재현을 위해 남아 있을 뿐 화면에 나가지 않는다. */
    private static final PageRequest LATEST = PageRequest.of(0, 1);

    private final InstrumentProfileJpaRepository jpaRepository;

    public InstrumentProfileRepositoryImpl(InstrumentProfileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InstrumentProfileResult> findCurrentProfile(
            String mapVersion, String instrumentId) {
        List<InstrumentProfileJpaEntity> found =
                jpaRepository.findCurrent(mapVersion, instrumentId, LATEST);
        return found.stream().findFirst().map(InstrumentProfileMapper::toResult);
    }
}
