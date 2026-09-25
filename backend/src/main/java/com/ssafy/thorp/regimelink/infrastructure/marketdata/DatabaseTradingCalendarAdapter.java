package com.ssafy.thorp.regimelink.infrastructure.marketdata;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.TradingCalendarPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 001 적재 표를 읽어 거래일 달력과 이력을 내준다 (research R4, T027).
 *
 * <p>자료 버전을 고르는 규칙은 004 {@link MarketMetadataPort#currentDataVersion()} 하나다 — 같은 판정을 두 곳에서 하면 한쪽만
 * 고쳤을 때 백테스트와 사례 연결이 다른 자료를 본다.
 */
@Component
public class DatabaseTradingCalendarAdapter implements TradingCalendarPort {

    /**
     * 거래일 달력의 출처. ETF 일별매매정보는 휴장일에도 행을 주므로 거래일을 가릴 수 없고, 달력을 소유한 것은 지수 자료뿐이다(001 {@code
     * contracts/market-dataset.md} ※1). 004 어댑터와 같은 값이다.
     */
    private static final String CALENDAR_INDEX_CODE = "KOSPI";

    private static final Object DATA_VERSION_KEY =
            DatabaseTradingCalendarAdapter.class.getName() + ".dataVersion";
    private static final Object SECTOR_MAPPED_KEY =
            DatabaseTradingCalendarAdapter.class.getName() + ".sectorMapped";

    /** 거래소 현지. 대응표 유효 구간이 TIMESTAMPTZ 라 날짜를 시각으로 옮길 때 쓴다. */
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Seoul");

    private static final String FIRST_AFTER_SQL =
            """
            SELECT MIN(date)
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
               AND date > :date
            """;

    /** {@code from}을 1번째로 세어 n번째 거래일. OFFSET 이 그 셈을 그대로 표현한다. */
    private static final String NTH_FROM_SQL =
            """
            SELECT date
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
               AND date >= :from
             ORDER BY date
             OFFSET :offset
             LIMIT 1
            """;

    private static final String LAST_ON_OR_BEFORE_SQL =
            """
            SELECT MAX(date)
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
               AND date <= :date
            """;

    private static final String COUNT_BETWEEN_SQL =
            """
            SELECT COUNT(*)
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
               AND date BETWEEN :from AND :to
            """;

    /**
     * 자료의 마지막 거래일.
     *
     * <p>{@code dataset_version.data_deadline}(수집 기준 시각)이 아니라 달력에 실제로 있는 마지막 날을 쓴다 — 사후 완성 판정은 거래일을
     * 세는 일이라 "행이 있는 날"이 기준이어야 하고, 마감 시각만 보고 판정하면 그날 자료가 아직 없을 때 없는 거래일을 센다.
     */
    private static final String DATA_AS_OF_SQL =
            """
            SELECT MAX(date)
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
            """;

    private static final String DELISTED_ON_SQL =
            """
            SELECT delisted_on
              FROM representative_etf
             WHERE instrument_id = :instrumentId
            """;

    /**
     * 그 시각에 열려 있던 대응표 버전.
     *
     * <p>{@code uk_sector_etf_map_version_open} 이 열린 버전을 최대 하나로 강제하고 구간이 겹치지 않으므로 결과는 최대 한 행이다.
     */
    private static final String MAP_VERSION_AS_OF_SQL =
            """
            SELECT version
              FROM sector_etf_map_version
             WHERE effective_from <= :at
               AND (effective_to IS NULL OR effective_to > :at)
             ORDER BY effective_from DESC
             LIMIT 1
            """;

    private static final String SECTOR_MAPPED_SQL =
            """
            SELECT 1
              FROM sector_etf_map_entry e
              JOIN sector_etf_map_version v ON v.id = e.map_version_id
              JOIN sector s ON s.id = e.sector_id
             WHERE v.version = :mapVersion
               AND s.code = :sectorId
             LIMIT 1
            """;

    @PersistenceContext private EntityManager entityManager;

    private final MarketMetadataPort marketMetadata;

    public DatabaseTradingCalendarAdapter(MarketMetadataPort marketMetadata) {
        this.marketMetadata = marketMetadata;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> firstTradingDayAfter(LocalDate date) {
        return date(calendarQuery(FIRST_AFTER_SQL).setParameter("date", date));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> nthTradingDayFrom(LocalDate from, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n 은 1 이상이어야 합니다: " + n);
        }
        return date(
                calendarQuery(NTH_FROM_SQL)
                        .setParameter("from", from)
                        .setParameter("offset", n - 1));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> lastTradingDayOnOrBefore(LocalDate date) {
        return date(calendarQuery(LAST_ON_OR_BEFORE_SQL).setParameter("date", date));
    }

    @Override
    @Transactional(readOnly = true)
    public int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return 0;
        }
        Object count =
                calendarQuery(COUNT_BETWEEN_SQL)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getSingleResult();
        return ((Number) count).intValue();
    }

    @Override
    @Transactional(readOnly = true)
    public LocalDate dataAsOf() {
        return date(calendarQuery(DATA_AS_OF_SQL))
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INSUFFICIENT_HISTORY,
                                        "거래일 달력이 비어 있습니다. 자료를 적재한 뒤 다시 시도해 주세요."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> delistedOn(String instrumentId) {
        return date(
                entityManager
                        .createNativeQuery(DELISTED_ON_SQL)
                        .setParameter("instrumentId", instrumentId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> mappingVersionAsOf(LocalDate date) {
        // 그 날 하루 안에서 버전이 바뀌는 일은 없지만, 하나를 골라야 하므로 그 날의 끝을 기준으로 본다.
        Query query =
                entityManager
                        .createNativeQuery(MAP_VERSION_AS_OF_SQL)
                        .setParameter(
                                "at", date.atTime(LocalTime.MAX).atZone(EXCHANGE_ZONE).toInstant());
        return single(query).map(String::valueOf);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean sectorMapped(String mappingVersion, String sectorId) {
        // 사례 10 × 실행 2 × 섹터 N 만큼 같은 (버전, 섹터)를 묻는다. 한 요청 안에서는 한 번만 읽는다(T110).
        return RequestScopedValues.memo(
                SECTOR_MAPPED_KEY,
                mappingVersion + "\u0000" + sectorId,
                () -> {
                    Query query =
                            entityManager
                                    .createNativeQuery(SECTOR_MAPPED_SQL)
                                    .setParameter("mapVersion", mappingVersion)
                                    .setParameter("sectorId", sectorId);
                    return single(query).isPresent();
                });
    }

    private Query calendarQuery(String sql) {
        return entityManager
                .createNativeQuery(sql)
                .setParameter("datasetId", dataVersion())
                .setParameter("indexCode", CALENDAR_INDEX_CODE);
    }

    /**
     * 달력 질의가 쓸 자료 버전. <b>한 트랜잭션 안에서는 한 번만 읽는다</b>(T110) — 호출마다 다시 읽으면 달력 질의가 두 배가 되고, 요청 도중 새 자료가
     * 발행되면 한 요청의 판정이 두 버전의 달력을 섞어 본다. 고르는 규칙은 여전히 004 {@link
     * MarketMetadataPort#currentDataVersion()} 하나다.
     */
    private String dataVersion() {
        return RequestScopedValues.memo(
                DATA_VERSION_KEY, "current", marketMetadata::currentDataVersion);
    }

    private static Optional<LocalDate> date(Query query) {
        return single(query)
                .map(
                        value ->
                                value instanceof Date sql
                                        ? sql.toLocalDate()
                                        : LocalDate.parse(String.valueOf(value)));
    }

    /**
     * 집계 조회는 행이 없어도 NULL 한 행을 주므로 {@code getResultList} 로 받고 NULL 을 걸러 낸다.
     *
     * <p>{@code setMaxResults} 를 쓰지 않는다 — 몇 질의는 SQL 에 이미 {@code LIMIT} 이 있고, 그 위에 JPA 가 제한을 덧붙이면
     * 구문이 깨진다.
     */
    private static Optional<Object> single(Query query) {
        List<?> rows = query.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
