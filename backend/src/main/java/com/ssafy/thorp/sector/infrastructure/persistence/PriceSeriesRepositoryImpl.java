package com.ssafy.thorp.sector.infrastructure.persistence;

import com.ssafy.thorp.sector.application.repository.PriceSeriesRepository;
import com.ssafy.thorp.sector.application.result.LatestPricesResult;
import com.ssafy.thorp.sector.application.result.LatestPricesResult.LatestPrice;
import com.ssafy.thorp.sector.application.result.PriceSeriesResult;
import com.ssafy.thorp.sector.application.result.PriceSeriesResult.PricePoint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PriceSeriesRepository}의 구현. 읽기만 한다.
 *
 * <h2>조회를 SQL로 쓰는 까닭</h2>
 *
 * {@code daily_price}는 엔티티 매핑이 없다. 기본키가 (자료 버전, 종목, 날짜) 세 열이라 매핑을 만들려면 복합키 클래스가 따라붙는데, 여기서 필요한 것은
 * 날짜와 수정종가와 표시 두 개뿐이다. 같은 자리에서 {@code
 * backtest.infrastructure.marketdata.DatabaseMarketMetadataAdapter}도 SQL을 쓴다.
 *
 * <p><b>자료 버전을 먼저 고르고 그 버전의 행만 읽는다.</b> 종목만으로 읽으면 여러 자료 버전의 같은 날짜 행이 함께 나와 한 선에 두 판이 섞인다.
 */
@Repository
public class PriceSeriesRepositoryImpl implements PriceSeriesRepository {

    /**
     * 지금 쓸 자료 버전. 그 대응표를 대상으로 수집된 <b>완전한</b> 묶음 가운데 마감 시각이 가장 늦은 것이다.
     *
     * <p>부분 갱신된 묶음({@code complete = false})을 고르지 않는 것은 이용 한도로 중간에 끊긴 자료이기 때문이다(FR-014). 끊긴 자료로 그린
     * 선은 실제로는 있었던 구간을 없었던 것처럼 보이게 한다.
     *
     * <p>마감 시각이 같은 행이 둘일 수 있으므로 식별자로 한 번 더 가른다. 정렬이 흔들리면 같은 요청이 요청마다 다른 값을 돌려준다.
     */
    private static final String CURRENT_DATASET_SQL =
            """
            SELECT v.dataset_id, v.data_deadline, v.is_sample
              FROM dataset_version v
              JOIN sector_etf_map_version m ON m.id = v.map_version_id
             WHERE m.version = :mapVersion
               AND v.complete = TRUE
             ORDER BY v.data_deadline DESC, v.dataset_id DESC
             LIMIT 1
            """;

    /** 그 자료 버전에 담긴 이 종목의 모든 거래일. 날짜 오름차순이라 받는 쪽이 다시 정렬하지 않는다. */
    private static final String SERIES_SQL =
            """
            SELECT p.date, p.adjusted_close, p.trading_suspended, p.price_carried_forward
              FROM daily_price p
             WHERE p.dataset_id = :datasetId
               AND p.instrument_id = :instrumentId
             ORDER BY p.date ASC
            """;

    /**
     * 종목마다 가장 늦은 두 거래일. 순번 1이 마지막 날, 2가 그 직전이다.
     *
     * <p>종목 수만큼 따로 부르지 않고 한 번에 읽는다. 순번은 날짜 내림차순으로 매겨 저장 순서에 기대지 않는다.
     */
    private static final String LATEST_TWO_SQL =
            """
            SELECT t.instrument_id, t.date, t.adjusted_close,
                   t.trading_suspended, t.price_carried_forward, t.rn
              FROM (SELECT p.instrument_id, p.date, p.adjusted_close,
                           p.trading_suspended, p.price_carried_forward,
                           ROW_NUMBER() OVER (PARTITION BY p.instrument_id
                                              ORDER BY p.date DESC) AS rn
                      FROM daily_price p
                     WHERE p.dataset_id = :datasetId
                       AND p.instrument_id IN (:instrumentIds)) t
             WHERE t.rn <= 2
             ORDER BY t.instrument_id, t.rn
            """;

    @PersistenceContext private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceSeriesResult> findCurrentSeries(String mapVersion, String instrumentId) {
        Object[] dataset = findCurrentDataset(mapVersion);
        if (dataset == null) return Optional.empty();

        String datasetId = (String) dataset[0];
        Instant dataDeadline = toInstant(dataset[1]);
        boolean isSample = (Boolean) dataset[2];

        List<PricePoint> points = findPoints(datasetId, instrumentId);
        // 행이 하나도 없으면 빈 선을 돌려주지 않는다. 부르는 쪽이 "아직 적재되지 않음"으로 다룬다.
        if (points.isEmpty()) return Optional.empty();

        return Optional.of(
                new PriceSeriesResult(instrumentId, datasetId, dataDeadline, isSample, points));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LatestPricesResult.Snapshot> findLatestPrices(
            String mapVersion, Collection<String> instrumentIds) {
        Object[] dataset = findCurrentDataset(mapVersion);
        if (dataset == null) return Optional.empty();

        String datasetId = (String) dataset[0];
        Instant dataDeadline = toInstant(dataset[1]);
        boolean isSample = (Boolean) dataset[2];
        // 빈 IN 목록은 SQL 문법 오류다. 물을 종목이 없으면 묻지 않는다.
        Map<String, LatestPrice> prices =
                instrumentIds.isEmpty() ? Map.of() : findLatestTwo(datasetId, instrumentIds);
        return Optional.of(
                new LatestPricesResult.Snapshot(datasetId, dataDeadline, isSample, prices));
    }

    /** 순번 1의 행으로 값을 만들고, 순번 2의 행이 있으면 직전 값으로 채운다. 순번 오름차순으로 읽으므로 1이 늘 먼저 온다. */
    private Map<String, LatestPrice> findLatestTwo(
            String datasetId, Collection<String> instrumentIds) {
        Query query =
                entityManager
                        .createNativeQuery(LATEST_TWO_SQL)
                        .setParameter("datasetId", datasetId)
                        .setParameter("instrumentIds", instrumentIds);

        Map<String, LatestPrice> prices = new HashMap<>();
        for (Object item : query.getResultList()) {
            Object[] row = (Object[]) item;
            String instrumentId = (String) row[0];
            LocalDate date = toLocalDate(row[1]);
            BigDecimal adjustedClose = (BigDecimal) row[2];
            if (((Number) row[5]).intValue() == 1) {
                prices.put(
                        instrumentId,
                        new LatestPrice(
                                date,
                                adjustedClose,
                                (Boolean) row[3],
                                (Boolean) row[4],
                                null,
                                null));
            } else {
                LatestPrice last = prices.get(instrumentId);
                prices.put(
                        instrumentId,
                        new LatestPrice(
                                last.date(),
                                last.adjustedClose(),
                                last.tradingSuspended(),
                                last.priceCarriedForward(),
                                date,
                                adjustedClose));
            }
        }
        return prices;
    }

    private Object[] findCurrentDataset(String mapVersion) {
        Query query =
                entityManager
                        .createNativeQuery(CURRENT_DATASET_SQL)
                        .setParameter("mapVersion", mapVersion);
        List<?> rows = query.getResultList();
        return rows.isEmpty() ? null : (Object[]) rows.get(0);
    }

    private List<PricePoint> findPoints(String datasetId, String instrumentId) {
        Query query =
                entityManager
                        .createNativeQuery(SERIES_SQL)
                        .setParameter("datasetId", datasetId)
                        .setParameter("instrumentId", instrumentId);

        List<?> rows = query.getResultList();
        return rows.stream()
                .map(row -> (Object[]) row)
                .map(
                        row ->
                                new PricePoint(
                                        toLocalDate(row[0]),
                                        (BigDecimal) row[1],
                                        (Boolean) row[2],
                                        (Boolean) row[3]))
                .toList();
    }

    /** 드라이버가 {@link java.sql.Date}로 줄 때와 {@link LocalDate}로 줄 때를 함께 받는다. */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) return date;
        return ((java.sql.Date) value).toLocalDate();
    }

    /** 같은 까닭으로 {@link Timestamp}와 {@link Instant}를 함께 받는다. */
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        return ((Timestamp) value).toInstant();
    }
}
