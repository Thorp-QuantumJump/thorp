package com.ssafy.thorp.sector.infrastructure.persistence;

import com.ssafy.thorp.sector.application.repository.SectorRepository;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.domain.model.Sector;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link SectorRepository}의 JPA 구현. 읽기만 한다(research.md 결정 7). */
@Repository
public class SectorRepositoryImpl implements SectorRepository {

    private final SectorEtfMapJpaRepository jpaRepository;

    public SectorRepositoryImpl(SectorEtfMapJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /**
     * 열린 버전을 찾고 그 버전의 항목을 읽는다.
     *
     * <p>질의가 둘인 이유는 <b>"대응표 미발행"과 "섹터 0개"를 구분해야 하기 때문</b>이다. 항목 쪽만 조인해서 읽으면 두 경우가 똑같이 빈 결과로 돌아와
     * 409({@code SECTOR_MAP_NOT_READY})와 200(빈 목록)을 가를 수 없다. 버전 행의 유무가 "발행되었는가"의 답이다.
     *
     * <p>두 질의를 {@code @Transactional(readOnly = true)} 하나로 묶는다. 사이에 파이프라인이 버전을 갈아 끼우면 버전 이름과 항목이 서로
     * 다른 판을 가리킬 수 있다. 읽기 전용 트랜잭션이라 쓰기 잠금도 잡지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<SectorListResult> findOpenSectorMap() {
        return jpaRepository
                .findByEffectiveToIsNull()
                .map(
                        map -> {
                            List<Sector> sectors =
                                    jpaRepository.findEntriesByMapId(map.getId()).stream()
                                            .map(SectorMapper::toDomain)
                                            .toList();
                            return new SectorListResult(map.getVersion(), map.isSample(), sectors);
                        });
    }
}
