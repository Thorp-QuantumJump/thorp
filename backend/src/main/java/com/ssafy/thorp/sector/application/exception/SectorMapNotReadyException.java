package com.ssafy.thorp.sector.application.exception;

/**
 * 섹터-ETF 대응표가 아직 발행되지 않았다 (contracts/sector-api.md: 409 {@code SECTOR_MAP_NOT_READY}).
 *
 * <p><b>빈 배열과 구분한다.</b> "대응표가 아직 없다"와 "대응표는 있는데 섹터가 0개다"는 다른 사실이고, 원칙 VI가 없는 결과를 만들어 내지 않기를 요구한다. 빈
 * 배열로 뭉개면 화면이 "잠시 후 다시" 안내할지 "섹터가 없습니다"를 띄울지 고를 수 없다. 결과 조회가 {@code RESULT_NOT_READY}에 409를 쓰는 것과
 * 같은 판단이다(backend/CLAUDE.md).
 *
 * <p>{@code common.exception.BusinessException}을 상속하지 않는다. 상속하려면 공용 {@code ErrorCode} enum에 값을 더해야
 * 하는데, {@code common}은 어느 도메인도 참조하지 않는 공용 계층이라 도메인 사정으로 늘리지 않는다. 대신 {@code SectorExceptionHandler}가
 * 이 도메인 안에서 상태·코드로 옮긴다.
 */
public class SectorMapNotReadyException extends RuntimeException {

    public SectorMapNotReadyException() {
        super("섹터-ETF 대응표가 아직 발행되지 않았습니다. 잠시 후 다시 조회해 주세요.");
    }
}
