package com.ssafy.thorp.regimelink.application.port;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 사후 구간을 날짜로 옮기는 데 필요한 거래일 달력과 이력 조회 (research R4).
 *
 * <p>기준 달력은 <b>KOSPI 일별 행</b>이다 — 001 FR-009가 전 기간을 보장하고, ETF 일별매매정보는 휴장일에도 행을 주므로 거래일을 가릴 수 없다.
 * 워커처럼 자료 합집합으로 달력을 만들지 않는다.
 *
 * <p><b>004 {@code MarketMetadataPort}에 있는 값은 그것을 그대로 쓴다.</b> 여기 있는 것은 그 포트에 없는 조회뿐이다 — n번째 거래일, 자료
 * 마지막 거래일, 상장폐지일, 날짜 기준 대응표 버전. 워밍업 산식을 여기서 새로 만들지 않는다.
 *
 * <p><b>구간 안에서 거래일을 세는 규칙은 하나다</b>: {@code d0}을 1번째로 센다. 그래서 {@code d21}은 {@code
 * nthTradingDayFrom(d0, 21)}이고 {@code d0}부터 그날까지의 거래일 수가 21이다.
 */
public interface TradingCalendarPort {

    /** {@code date} <b>다음</b> 첫 거래일. 사례 관측 종료월의 말일을 넣으면 {@code d0}이다. */
    Optional<LocalDate> firstTradingDayAfter(LocalDate date);

    /**
     * {@code from}을 1번째로 세어 {@code n}번째 거래일.
     *
     * @param n 1 이상. 달력에 그만큼의 거래일이 없으면 비어 있고, 호출자는 그것을 미완성으로 다룬다
     */
    Optional<LocalDate> nthTradingDayFrom(LocalDate from, int n);

    /** {@code date} 당일 또는 그 앞의 마지막 거래일. 과거 기준월의 판정 기준일을 구한다(T090). */
    Optional<LocalDate> lastTradingDayOnOrBefore(LocalDate date);

    /** {@code from}부터 {@code to}까지의 거래일 수(<b>양끝 포함</b>). {@code to}가 앞서면 0이다. */
    int tradingDaysBetween(LocalDate from, LocalDate to);

    /**
     * 지금 쓰는 자료의 마감일을 거래일로 맞춘 값. 사후 완성 판정의 기본 기준일이다.
     *
     * <p>필터 정의의 {@code dataAsOf}이며, 사례별 {@code lastAvailable}(그 구간 안에서 자료가 있는 마지막 거래일)과 다른 값이다.
     */
    LocalDate dataAsOf();

    /** 종목의 상장폐지일. 상장이 유지되고 있으면 비어 있다. */
    Optional<LocalDate> delistedOn(String instrumentId);

    /**
     * 그 날짜에 유효했던 섹터-ETF 대응표 버전.
     *
     * <p>사례 관측 종료월의 버전으로 판정해야 "그때 그 섹터가 있었나"를 물을 수 있다. 현재 버전({@code
     * MarketMetadataPort#currentMappingVersion})으로 과거를 판정하면 대응표가 바뀐 뒤 옛 사례가 통째로 사라진다.
     */
    Optional<String> mappingVersionAsOf(LocalDate date);

    /** 그 대응표 버전에 이 섹터가 있는가. 없으면 그 실행만 {@code SECTOR_UNMAPPED}다. */
    boolean sectorMapped(String mappingVersion, String sectorId);
}
