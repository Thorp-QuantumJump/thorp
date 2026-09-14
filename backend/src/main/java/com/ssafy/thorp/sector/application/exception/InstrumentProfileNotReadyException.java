package com.ssafy.thorp.sector.application.exception;

/**
 * 대표 상품 정보가 아직 만들어지지 않았다 (contracts/sector-api.md: 409 {@code INSTRUMENT_PROFILE_NOT_READY}).
 *
 * <p><b>빈 값과 구분한다.</b> "아직 만들지 않았다"와 "만들었는데 비어 있다"는 다른 사실이고, 원칙 VI가 없는 결과를 만들어 내지 않기를 요구한다. 자료
 * 파이프라인이 그 자료 버전의 대표 상품 정보를 아직 적재하지 않았다면 재시도하면 달라질 수 있는 상태라 409다 — {@code SECTOR_MAP_NOT_READY}와 같은
 * 판단이다.
 *
 * <p>섹터 코드가 대응표에 없는 것과도 구분한다. 그쪽은 재시도해도 달라지지 않으므로 404 {@code SECTOR_NOT_FOUND}다({@link
 * SectorNotFoundException}).
 */
public class InstrumentProfileNotReadyException extends RuntimeException {

    public InstrumentProfileNotReadyException(String sectorCode) {
        super("%s 섹터의 대표 상품 정보가 아직 준비되지 않았습니다. 잠시 후 다시 조회해 주세요.".formatted(sectorCode));
    }
}
