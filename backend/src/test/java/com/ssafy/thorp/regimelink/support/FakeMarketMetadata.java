package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 판정 시험용 시장 메타데이터. 평일 달력 위에서 상장일과 워밍업 소진을 흉내 낸다.
 *
 * <p>{@link #evaluationStart}의 셈은 실제 어댑터와 같다 — 상장일을 0번째로 보고 워밍업 거래일만큼 건너뛴 날이다. 이 셈이 어긋나면 워밍업 부족 판정이
 * 실제와 다른 날짜를 본다.
 */
public class FakeMarketMetadata implements MarketMetadataPort {

    private final Map<String, LocalDate> listings = new HashMap<>();
    private String dataVersion = "DATA-1";
    private String mappingVersion = "MAP-1";

    public FakeMarketMetadata listed(String instrumentId, LocalDate on) {
        listings.put(instrumentId, on);
        return this;
    }

    public FakeMarketMetadata versions(String dataVersion, String mappingVersion) {
        this.dataVersion = dataVersion;
        this.mappingVersion = mappingVersion;
        return this;
    }

    @Override
    public String currentDataVersion() {
        return dataVersion;
    }

    @Override
    public boolean isMappedTo(String mappingVersion, String sectorId, String instrumentId) {
        return true;
    }

    @Override
    public String currentMappingVersion() {
        return mappingVersion;
    }

    @Override
    public Optional<String> sectorOf(String mappingVersion, String instrumentId) {
        return Optional.empty();
    }

    @Override
    public Optional<LocalDate> listedOn(String instrumentId) {
        return Optional.ofNullable(listings.get(instrumentId));
    }

    @Override
    public Optional<LocalDate> evaluationStart(LocalDate notBefore, int warmupTradingDays) {
        LocalDate cursor = notBefore;
        int skipped = 0;
        for (int guard = 0; guard < 4000; guard++) {
            if (tradingDay(cursor)) {
                if (skipped == warmupTradingDays) {
                    return Optional.of(cursor);
                }
                skipped++;
            }
            cursor = cursor.plusDays(1);
        }
        return Optional.empty();
    }

    @Override
    public Optional<LocalDate> firstTradingDayOnOrAfter(LocalDate date) {
        LocalDate cursor = date;
        while (!tradingDay(cursor)) {
            cursor = cursor.plusDays(1);
        }
        return Optional.of(cursor);
    }

    private static boolean tradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
