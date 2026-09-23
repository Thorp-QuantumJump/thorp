package com.ssafy.thorp.sector.presentation.response;

import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.domain.model.RepresentativeEtf;
import com.ssafy.thorp.sector.domain.model.Sector;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /sectors} 응답 본문. 계약은 contracts/sector-api.md다.
 *
 * <p>{@code representativeEtf}에 {@code manager}를 싣지 않는다. 목록은 User Story 1이 요구하는 최소 정보이고, 운용사는 User
 * Story 3의 펼쳐 보는 층인 {@code GET /sectors/{code}/instrument-profile}의 필드다.
 */
public record SectorListResponse(String mapVersion, boolean isSample, List<SectorItem> sectors) {

    public static SectorListResponse from(SectorListResult result) {
        return new SectorListResponse(
                result.mapVersion(),
                result.isSample(),
                result.sectors().stream().map(SectorItem::from).toList());
    }

    /** 섹터 한 건. {@code representativeEtf}는 항상 있다 — 없는 항목은 도메인 모델이 만들어지지 않는다(SC-001). */
    public record SectorItem(
            String code,
            String name,
            String classification,
            RepresentativeEtfItem representativeEtf) {

        public static SectorItem from(Sector sector) {
            return new SectorItem(
                    sector.code().value(),
                    sector.name(),
                    sector.classification().name(),
                    RepresentativeEtfItem.from(sector.representativeEtf()));
        }
    }

    /** 대표 ETF. {@code listedOn}은 ISO 날짜 문자열로 나간다. */
    public record RepresentativeEtfItem(
            String instrumentId, String name, LocalDate listedOn, String benchmarkIndex) {

        public static RepresentativeEtfItem from(RepresentativeEtf etf) {
            return new RepresentativeEtfItem(
                    etf.instrumentId(), etf.name(), etf.listedOn(), etf.benchmarkIndex());
        }
    }
}
