package com.ssafy.thorp.sector.application.result;

import com.ssafy.thorp.sector.domain.model.Sector;
import java.util.List;

/**
 * {@code GET /sectors}의 유스케이스 출력. 섹터 목록과 그것이 어느 대응표 버전인지를 함께 담는다.
 *
 * <p>{@code mapVersion}은 FR-003, {@code isSample}은 FR-002가 요구한다. 목록만 돌려주면 화면이 "지금 보고 있는 값이 예시인지"와
 * "어느 버전인지"를 알 수 없다.
 *
 * <p>이 타입이 {@code SectorRepository}의 반환 타입이기도 하다. 대응표는 "열린 버전 + 그 버전의 항목"이 한 덩어리로만 뜻이 있어서, 버전 메타와
 * 항목을 따로 읽어 조립하면 둘이 다른 버전을 가리킬 수 있다. 저장소가 한 트랜잭션에서 이 덩어리를 만들어 돌려준다.
 */
public record SectorListResult(String mapVersion, boolean isSample, List<Sector> sectors) {

    public SectorListResult {
        sectors = List.copyOf(sectors);
    }
}
