package com.ssafy.thorp.backtest.infrastructure.marketdata;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * spec 001이 자료를 적재하기 전까지 쓰는 픽스처 구현(analyze M1).
 *
 * <p><b>실제 시장 자료가 아니다.</b> 상장일·대응표는 워커 픽스처 {@code worker/examples/three_etf_24m/} 와 같은 세 종목이고, 자료
 * 버전도 같은 {@code FIXTURE-three_etf_24m} 이다. 이 값이 동일 버전 키와 결과 {@code versions.data} 에 그대로 들어가므로 픽스처로
 * 만든 실행은 실제 자료로 만든 실행과 절대 같은 키를 갖지 않는다 — 001이 들어와도 옛 결과를 재사용하지 않는다.
 *
 * <p><b>거래일 달력은 주말만 뺀 평일이다.</b> KRX 공휴일 목록을 지어내지 않는다. 그래서 이 구현이 계산한 {@code effectiveStart} 는 공휴일
 * 수만큼 실제보다 이르다. 001이 달력을 제공하면 이 클래스만 바꾼다.
 */
@Component
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

    public FixtureMarketMetadataAdapter(
            @Value("${thorp.backtest.fixture-calendar-end:2026-12-31}") LocalDate calendarEnd) {
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

    /** 픽스처 달력: 주말만 제외한다. 공휴일은 001이 제공할 때 반영한다. */
    private static boolean isTradingDay(LocalDate day) {
        return !List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(day.getDayOfWeek());
    }
}
