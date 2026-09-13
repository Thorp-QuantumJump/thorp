package com.ssafy.thorp.sector.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.sector.application.service.SectorService;
import com.ssafy.thorp.sector.presentation.response.InstrumentProfileResponse;
import com.ssafy.thorp.sector.presentation.response.SectorListResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 섹터 목록·대표 상품 정보 조회 API (contracts/sector-api.md, User Story 1·3).
 *
 * <p>읽기만 있다. 대응표를 만들거나 고치는 엔드포인트는 두지 않는다 — 원본은 {@code data/} 파이프라인이 소유한다(research.md 결정 7).
 *
 * <p>가격 시계열은 이 계약으로 내보내지 않는다.
 */
@RestController
@RequestMapping("/sectors")
public class SectorController {

    private final SectorService sectorService;

    public SectorController(SectorService sectorService) {
        this.sectorService = sectorService;
    }

    /**
     * 섹터 목록. 응답에 {@code mapVersion}(FR-003)과 {@code isSample}(FR-002)이 함께 실린다.
     *
     * <p>대응표가 발행되지 않았으면 빈 배열이 아니라 409 {@code SECTOR_MAP_NOT_READY}다({@link
     * SectorExceptionHandler}).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<SectorListResponse>> getSectors() {
        return ResponseEntity.ok(
                ApiResponse.ok(SectorListResponse.from(sectorService.getSectors())));
    }

    /**
     * 대표 상품 정보. User Story 3이 요구하는 펼쳐 보는 층이다.
     *
     * <p>없는 섹터 코드는 404 {@code SECTOR_NOT_FOUND}, 자료가 아직 만들어지지 않았으면 409 {@code
     * INSTRUMENT_PROFILE_NOT_READY}다({@link SectorExceptionHandler}). <b>빈 값을 만들어 내지 않는다.</b>
     */
    @GetMapping("/{code}/instrument-profile")
    public ResponseEntity<ApiResponse<InstrumentProfileResponse>> getInstrumentProfile(
            @PathVariable String code) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        InstrumentProfileResponse.from(sectorService.getInstrumentProfile(code))));
    }
}
