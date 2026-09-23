package com.ssafy.thorp.sector.application.repository;

import com.ssafy.thorp.sector.application.result.PriceSeriesResult;
import java.util.Optional;

/**
 * 대표 ETF의 일별 가격 흐름 조회. <b>읽기 전용이다.</b>
 *
 * <p>원본은 {@code data/} 파이프라인이 소유하고 백엔드에는 쓰기 경로가 없다. 대응표·대표 상품 정보와 같은 이유다 — 양방향을 허용하면 같은 값이 두 곳에서
 * 갈라지고, 발행된 자료 버전을 고치지 않는다는 FR-017을 누가 책임지는지 흐려진다.
 *
 * <p><b>백엔드가 시계열을 읽는 첫 자리다.</b> 001 plan은 "시계열을 읽는 것은 워커이고 Backend는 메타데이터만 본다"고 정해 두었는데, 그 전제는 시계열을
 * 쓰는 곳이 계산뿐이었다는 것이다. 화면이 고르기 전에 가격 흐름을 보여 달라는 요구는 조회이고, 워커는 메시지를 받아 도는 쪽이라 화면의 물음에 답할 수 없다. 그래서 이
 * 조회만 예외로 둔다. <b>계산은 여전히 워커가 한다</b> — 여기서 하는 일은 저장된 값을 날짜 순서로 읽어 내보내는 것뿐이다.
 */
public interface PriceSeriesRepository {

    /**
     * 지금 보여 줄 가격 흐름.
     *
     * <p><b>어느 자료 버전을 고르는가</b>는 대표 상품 정보와 같은 규칙이다. 지금 열린 대응표를 대상으로 수집됐고, 완전하며({@code complete =
     * true}), 그중 마감 시각이 가장 늦은 것이다. 마감 시각이 같으면 식별자 내림차순으로 가른다. 규칙이 다르면 같은 화면의 두 값이 다른 판을 가리킨다.
     *
     * @return 그 자료 버전에 이 종목의 행이 하나도 없으면 {@link Optional#empty()}. 호출자는 이것을 빈 선이 아니라 "아직 적재되지 않음"으로
     *     다룬다
     */
    Optional<PriceSeriesResult> findCurrentSeries(String mapVersion, String instrumentId);
}
