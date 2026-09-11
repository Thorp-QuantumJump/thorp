package com.ssafy.thorp.sector.application.service;

import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import com.ssafy.thorp.sector.application.repository.SectorRepository;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import org.springframework.stereotype.Service;

/**
 * 섹터 목록 조회 유스케이스 (User Story 1).
 *
 * <p>대응표를 고치는 메서드가 없다. 저장소가 읽기 전용이고 원본은 파이프라인이 소유한다(research.md 결정 7).
 */
@Service
public class SectorService {

    private final SectorRepository sectorRepository;

    public SectorService(SectorRepository sectorRepository) {
        this.sectorRepository = sectorRepository;
    }

    /**
     * 현재 열린 대응표의 섹터 전체.
     *
     * @throws SectorMapNotReadyException 대응표가 아직 발행되지 않았을 때. 빈 목록으로 돌려주지 않는다
     */
    public SectorListResult getSectors() {
        return sectorRepository.findOpenSectorMap().orElseThrow(SectorMapNotReadyException::new);
    }
}
