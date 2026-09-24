package com.ssafy.thorp.sector.application.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 시장 지수의 기간 종가 조회. <b>읽기 전용이다.</b>
 *
 * <p>{@link PriceSeriesRepository}와 같은 예외다 — 저장된 값을 날짜 순서로 읽어 내보낼 뿐 계산하지 않는다. 첫 소비자는 005 결과 화면의
 * KOSPI 보조 비교(T087)다.
 *
 * <p><b>자료 버전을 부르는 쪽이 정한다.</b> 결과와 나란히 그리는 지수는 그 결과가 계산에 쓴 자료 버전({@code versions.data})의 것이어야 한다.
 * 여기서 "지금 판"을 고르면 자료가 다시 적재된 뒤 같은 결과 주소가 다른 지수 선을 보인다(원칙 VIII).
 */
public interface IndexSeriesRepository {

    /**
     * {@code from}~{@code to}(양끝 포함)의 종가. 날짜 오름차순이다. 행이 없는 날은 건너뛰며 채우지 않는다.
     *
     * @return 그 범위에 행이 하나도 없으면 빈 목록
     */
    List<IndexClose> findCloses(String datasetId, String indexCode, LocalDate from, LocalDate to);

    record IndexClose(LocalDate date, BigDecimal close) {}
}
