package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.port.TradingCalendarPort;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 판정 시험용 거래일 달력. <b>평일이 곧 거래일</b>이고 공휴일은 따로 지정한다.
 *
 * <p>실제 달력(KOSPI 일별 행)을 쓰지 않는 것은 시험이 특정 해의 휴장일에 묶이지 않게 하기 위해서다 — 여기서 확인하려는 것은 판정 규칙이지 달력 자료가 아니다.
 * 달력 조회 자체는 Testcontainers 통합 시험이 본다.
 */
public class FakeTradingCalendar implements TradingCalendarPort {

    private final LocalDate dataAsOf;
    private final Set<LocalDate> holidays = new HashSet<>();
    private final Map<String, LocalDate> delistings = new HashMap<>();
    private final Set<String> mappedSectors = new HashSet<>();
    private String mappingVersion = "MAP-1";

    public FakeTradingCalendar(LocalDate dataAsOf) {
        this.dataAsOf = dataAsOf;
    }

    public FakeTradingCalendar delisted(String instrumentId, LocalDate on) {
        delistings.put(instrumentId, on);
        return this;
    }

    public FakeTradingCalendar mapping(String version, String... sectorIds) {
        this.mappingVersion = version;
        mappedSectors.clear();
        mappedSectors.addAll(Set.of(sectorIds));
        return this;
    }

    @Override
    public Optional<LocalDate> firstTradingDayAfter(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (!tradingDay(next)) {
            next = next.plusDays(1);
        }
        return Optional.of(next);
    }

    @Override
    public Optional<LocalDate> nthTradingDayFrom(LocalDate from, int n) {
        LocalDate cursor = from;
        int seen = 0;
        // 달력에 상한이 없으므로 넉넉한 횟수만 훑고 끝낸다. 시험이 무한히 돌지 않게 하는 안전장치다.
        for (int guard = 0; guard < 4000; guard++) {
            if (tradingDay(cursor)) {
                seen++;
                if (seen == n) {
                    return Optional.of(cursor);
                }
            }
            cursor = cursor.plusDays(1);
        }
        return Optional.empty();
    }

    @Override
    public Optional<LocalDate> lastTradingDayOnOrBefore(LocalDate date) {
        LocalDate cursor = date;
        for (int guard = 0; guard < 30; guard++) {
            if (tradingDay(cursor)) {
                return Optional.of(cursor);
            }
            cursor = cursor.minusDays(1);
        }
        return Optional.empty();
    }

    @Override
    public int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return 0;
        }
        int count = 0;
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            if (tradingDay(cursor)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public LocalDate dataAsOf() {
        return dataAsOf;
    }

    @Override
    public Optional<LocalDate> delistedOn(String instrumentId) {
        return Optional.ofNullable(delistings.get(instrumentId));
    }

    @Override
    public Optional<String> mappingVersionAsOf(LocalDate date) {
        return Optional.of(mappingVersion);
    }

    @Override
    public boolean sectorMapped(String mappingVersion, String sectorId) {
        return mappedSectors.isEmpty() || mappedSectors.contains(sectorId);
    }

    private boolean tradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.contains(date);
    }
}
