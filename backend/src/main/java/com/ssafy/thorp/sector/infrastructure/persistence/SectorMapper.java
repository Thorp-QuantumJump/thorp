package com.ssafy.thorp.sector.infrastructure.persistence;

import com.ssafy.thorp.sector.domain.model.RepresentativeEtf;
import com.ssafy.thorp.sector.domain.model.Sector;
import com.ssafy.thorp.sector.domain.vo.SectorCode;

/**
 * JPA Entity → 도메인 모델 변환 (ADR-0001).
 *
 * <p><b>{@code toEntity}가 없다.</b> 다른 도메인의 {@code *Mapper}는 정적 {@code toEntity}/{@code toDomain} 한
 * 쌍이지만, 대응표는 파이프라인이 소유하는 읽기 전용 사본이라 백엔드에 쓰기 경로가 아예 없다(research.md 결정 7). 부를 일이 없는 {@code toEntity}를
 * 만들어 두면 "여기로 쓸 수 있다"는 잘못된 신호가 된다.
 */
final class SectorMapper {

    private SectorMapper() {}

    /**
     * 대응표 항목 한 줄을 섹터 도메인 모델로 옮긴다.
     *
     * <p>{@code entry.etf}는 NOT NULL FK라 null일 수 없고, {@link Sector}의 생성자가 한 번 더 막는다(SC-001).
     */
    static Sector toDomain(SectorEtfMapEntryJpaEntity entry) {
        SectorJpaEntity sector = entry.getSector();
        return new Sector(
                new SectorCode(sector.getCode()),
                sector.getName(),
                sector.getClassification(),
                toDomain(entry.getEtf()));
    }

    static RepresentativeEtf toDomain(RepresentativeEtfJpaEntity etf) {
        return new RepresentativeEtf(
                etf.getInstrumentId(),
                etf.getName(),
                etf.getListedOn(),
                etf.getBenchmarkIndex(),
                etf.getManager());
    }
}
