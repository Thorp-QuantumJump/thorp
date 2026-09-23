package com.ssafy.thorp.backtest.infrastructure.marketdata;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 검사가 쓰는 결정적인 {@link MarketMetadataPort} 대역이다.
 *
 * <p><b>운영 구현이 아니다.</b> 001이 표를 적재하기 전까지는 이 클래스가 운영 구현이었고, 실제 자료가 들어온 뒤 {@link
 * DatabaseMarketMetadataAdapter} 가 그 자리를 대신했다. 지우지 않고 검사 소스로 옮긴 까닭은 단위 검사가 <b>매번 같은 답</b>을 요구하기
 * 때문이다. DB 를 보게 하면 적재한 자료가 바뀔 때마다 검사 결과가 따라 바뀐다.
 *
 * <p><b>실제 시장 자료가 아니다.</b> 상장일·대응표는 워커 픽스처 {@code worker/examples/three_etf_24m/} 와 같은 세 종목이고, 자료
 * 버전도 같은 {@code FIXTURE-three_etf_24m} 이다. 이름이 {@code FIXTURE-} 로 시작하는 것이 안전장치다 — 이 값이 동일 버전 키와 결과
 * {@code versions.data} 에 그대로 들어가므로, 픽스처로 만든 실행은 실제 자료로 만든 실행과 절대 같은 키를 갖지 않는다.
 *
 * <p><b>거래일 달력은 주말만 뺀 평일이다.</b> KRX 공휴일 목록을 지어내지 않는다. 그래서 이 구현이 세는 거래일은 공휴일 수만큼 실제보다 이르다. 운영 구현은
 * 적재된 지수 자료의 날짜를 그대로 쓰므로 그 어긋남이 없다.
 */
public class FixtureMarketMetadataAdapter implements MarketMetadataPort {

    /** 워커 픽스처와 같은 값. 바꾸면 동일 버전 키가 통째로 바뀐다. */
    private static final String DATA_VERSION = "FIXTURE-three_etf_24m";

    private static final String MAPPING_VERSION = "SECTOR-ETF-2026-09";

    /** 종목 → (섹터, 상장일). 워커 픽스처 {@code instruments.csv}·{@code mapping.csv} 와 같다. */
    private static final Map<String, Listing> LISTINGS =
            Map.of(
                    "091160.XKRX", new Listing("SEMICONDUCTOR", LocalDate.of(2015, 1, 2)),
                    "305720.XKRX", new Listing("BATTERY", LocalDate.of(2018, 9, 12)),
                    "244580.XKRX", new Listing("BIO", LocalDate.of(2023, 7, 3)));

    /** 달력 상한. 이 뒤는 거래일을 셀 수 없으므로 워밍업이 모자라면 422로 거절한다. */
    private final LocalDate calendarEnd;

    public FixtureMarketMetadataAdapter(LocalDate calendarEnd) {
        this.calendarEnd = calendarEnd;
    }

    private record Listing(String sectorId, LocalDate listedOn) {}

    @Override
    public String currentDataVersion() {
        return DATA_VERSION;
    }

    @Override
    public String currentMappingVersion() {
        return MAPPING_VERSION;
    }

    /** 달력 상한 당일 또는 그 앞의 마지막 평일. 이 대역의 달력은 주말만 빼므로 판정이 그것과 같다. */
    @Override
    public Optional<LocalDate> lastTradingDay() {
        LocalDate day = calendarEnd;
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return Optional.of(day);
    }

    @Override
    public Optional<String> sectorOf(String mappingVersion, String instrumentId) {
        if (!MAPPING_VERSION.equals(mappingVersion)) {
            return Optional.empty();
        }
        return Optional.ofNullable(LISTINGS.get(instrumentId)).map(Listing::sectorId);
    }

    @Override
    public boolean isMappedTo(String mappingVersion, String sectorId, String instrumentId) {
        if (!MAPPING_VERSION.equals(mappingVersion)) {
            return false;
        }
        Listing listing = LISTINGS.get(instrumentId);
        return listing != null && listing.sectorId().equals(sectorId);
    }

    @Override
    public Optional<LocalDate> listedOn(String instrumentId) {
        return Optional.ofNullable(LISTINGS.get(instrumentId)).map(Listing::listedOn);
    }

    @Override
    public Optional<LocalDate> evaluationStart(LocalDate notBefore, int warmupTradingDays) {
        if (warmupTradingDays < 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "워밍업 거래일 수는 음수일 수 없습니다: " + warmupTradingDays);
        }
        LocalDate day = notBefore;
        int consumed = 0;
        while (!day.isAfter(calendarEnd)) {
            if (isTradingDay(day)) {
                if (consumed == warmupTradingDays) {
                    return Optional.of(day);
                }
                consumed++;
            }
            day = day.plusDays(1);
        }
        return Optional.empty();
    }

    @Override
    public Optional<LocalDate> firstTradingDayOnOrAfter(LocalDate date) {
        // 같은 달력을 쓰되 워밍업을 소진하지 않는다. 뜻이 다른 두 질문이라 포트에서 이름을 갈랐고,
        // 픽스처 달력에서는 warmup 0 이 곧 "그날 또는 그 뒤 첫 거래일"이다.
        return evaluationStart(date, 0);
    }

    /** 픽스처 달력: 주말만 제외한다. 공휴일은 001이 제공할 때 반영한다. */
    private static boolean isTradingDay(LocalDate day) {
        return !List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(day.getDayOfWeek());
    }
}
