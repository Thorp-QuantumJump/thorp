package com.ssafy.thorp.sector.application.exception;

/**
 * 열린 대응표에 그 섹터 코드가 없다 (contracts/sector-api.md: 404 {@code SECTOR_NOT_FOUND}).
 *
 * <p>코드 형식이 틀린 요청도 여기로 온다. 형식이 맞지 않는 코드는 대응표에 있을 수 없으므로 "없는 섹터"와 결과가 같고, 계약이 이 경로에 정의한 오류도 404
 * 하나뿐이다. 형식 검사를 따로 두어 400을 내면 클라이언트가 구분할 수 없는 두 가지 실패를 만들게 된다.
 *
 * <p>이 예외 덕분에 {@code SectorCode}를 경로 변수로 직접 만들지 않는다. 만들면 형식 위반이 {@code IllegalArgumentException}이
 * 되고, {@code SectorExceptionHandler}가 그것을 <b>저장된 자료의 결함</b>으로 보아 500으로 옮긴다 — 클라이언트 입력 때문에 5xx 경보가
 * 울린다.
 */
public class SectorNotFoundException extends RuntimeException {

    public SectorNotFoundException(String sectorCode) {
        super("%s 섹터를 찾을 수 없습니다.".formatted(sectorCode));
    }
}
