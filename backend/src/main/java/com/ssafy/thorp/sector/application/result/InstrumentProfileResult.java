package com.ssafy.thorp.sector.application.result;

import com.ssafy.thorp.sector.domain.model.InstrumentProfile;
import com.ssafy.thorp.sector.domain.model.RepresentativeEtf;
import java.time.Instant;

/**
 * {@code GET /sectors/{code}/instrument-profile}의 유스케이스 출력.
 *
 * <p>세 조각이 한 덩어리로만 뜻이 있다. 상품 자체의 값({@code etf}), 자료 버전마다 새로 만드는 설명 값({@code profile}), 그리고 그 값이 어느
 * 시점 자료인지({@code datasetId}·{@code dataDeadline}·{@code isSample})다. 따로 읽어 조립하면 서로 다른 자료 버전을 가리킬 수
 * 있으므로 저장소가 한 트랜잭션에서 이 덩어리를 만들어 돌려준다({@code SectorListResult}와 같은 판단이다).
 *
 * @param isSample 이 대표 상품 정보를 만든 <b>자료 버전</b>이 예시 자료인지다(FR-002, research.md 결정 5). 대응표의 {@code
 *     isSample}과 값이 같을 수도 다를 수도 있어 각자 자기 근거를 쓴다
 */
public record InstrumentProfileResult(
        RepresentativeEtf etf,
        InstrumentProfile profile,
        String datasetId,
        Instant dataDeadline,
        boolean isSample) {}
