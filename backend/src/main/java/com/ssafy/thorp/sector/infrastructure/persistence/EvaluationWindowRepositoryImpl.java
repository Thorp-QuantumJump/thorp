package com.ssafy.thorp.sector.infrastructure.persistence;

import com.ssafy.thorp.sector.application.repository.EvaluationWindowRepository;
import com.ssafy.thorp.sector.application.result.EvaluationWindowInputs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link EvaluationWindowRepository}의 구현. 읽기만 한다.
 *
 * <h2>조회를 SQL로 쓰는 까닭</h2>
 *
 * 읽는 표 넷 가운데 {@code daily_index}와 {@code data_gap}은 엔티티 매핑이 없고, {@code representative_etf}의 {@code
 * delisted_on}은 매핑에서 일부러 빠져 있다(이번 범위의 대응표가 현재 상장 상품만 다루기 때문이다). 여기서 필요한 것은 날짜와 문자열 몇 개뿐이라 그 셋을 위해
 * 엔티티를 만들 이유가 없다. 같은 판단을 {@code backtest.infrastructure.marketdata.DatabaseMarketMetadataAdapter}도
 * 한다.
 */
@Repository
public class EvaluationWindowRepositoryImpl implements EvaluationWindowRepository {

    /**
     * 거래일 달력의 출처. ETF 일별매매정보는 휴장일에도 행을 주므로 거래일을 가릴 수 없고, 달력을 소유한 것은 지수 자료뿐이다(001 {@code
     * contracts/market-dataset.md} ※1).
     *
     * <p><b>{@code DatabaseMarketMetadataAdapter.CALENDAR_INDEX_CODE}·{@code
     * data_pipeline.index.CALENDAR_INDEX_CODE}와 같은 값이어야 한다.</b> 세 곳이 갈라지면 화면이 안내한 시작일을 접수가 거절한다 —
     * 같은 날을 두 달력으로 세기 때문이다.
     */
    private static final String CALENDAR_INDEX_CODE = "KOSPI";

    /** 자료 마감 시각을 날짜로 자를 때 쓰는 기준. 거래소 현지 기준이며 UTC로 자르면 하루 밀린다. */
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 지금 쓸 자료 버전. 지금 열린 대응표를 대상으로 수집됐고, <b>완전한 묶음</b> 가운데 마감 시각이 가장 늦은 것이며, 마감 시각이 같으면 식별자 내림차순으로
     * 가른다. 대표 상품 정보({@code InstrumentProfileJpaRepository#findCurrent})와 같은 세 조건이다 — 다르게 두면 같은 화면의
     * 두 응답이 다른 판을 가리킨다.
     *
     * <p><b>접수 경로와 같은 행을 고르는가.</b> {@code
     * backtest.infrastructure.marketdata.DatabaseMarketMetadataAdapter}는 대응표를 조건에 넣지 않고 나머지 둘로만
     * 고른다. 파이프라인이 자료를 현재 열린 대응표에 붙여 발행하는 한 두 규칙은 같은 행을 고른다. 갈리는 때는 대응표만 새로 열고 자료는 아직 옛 대응표를 가리키는
     * 과도기인데, 그때 이 조회는 "자료 없음"(409)이 되고 접수는 옛 자료로 계속 받는다. <b>막는 쪽으로 갈리는 것이 안전하다</b> — 반대로 갈리면 화면이
     * 안내한 첫날이 접수에서 거절된다.
     */
    private static final String CURRENT_DATASET_SQL =
            """
            SELECT v.dataset_id, v.data_deadline
              FROM dataset_version v
              JOIN sector_etf_map_version m ON m.id = v.map_version_id
             WHERE m.version = :mapVersion
               AND v.complete = TRUE
             ORDER BY v.data_deadline DESC, v.dataset_id DESC
             LIMIT 1
            """;

    /** 후보의 상장·폐지일. 순서는 호출자가 요청 순서로 다시 세우므로 여기서 정하지 않는다. */
    private static final String CANDIDATES_SQL =
            """
            SELECT instrument_id, listed_on, delisted_on
              FROM representative_etf
             WHERE instrument_id IN (:instrumentIds)
            """;

    /**
     * 후보의 자료 부족 구간. 정렬을 고정하는 이유는 산식이 사유가 같고 겹치는 구간을 합치기 전에 정렬을 다시 하더라도, 입력 순서가 흔들리면 같은 요청이 요청마다 다른
     * 로그를 남기기 때문이다.
     */
    private static final String DATA_GAPS_SQL =
            """
            SELECT instrument_id, start_date, end_date, reason
              FROM data_gap
             WHERE dataset_id = :datasetId
               AND instrument_id IN (:instrumentIds)
             ORDER BY instrument_id, start_date, reason
            """;

    /**
     * 거래일 달력. <b>후보의 가장 이른 상장일부터만 읽는다</b> — 산식이 쓰는 것은 상장일 이후 구간뿐이라 그 앞을 읽어도 결과가 같고, 전체를 읽으면 오래된 지수의
     * 날짜가 매 요청마다 딸려 온다.
     */
    private static final String TRADING_CALENDAR_SQL =
            """
            SELECT date
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
               AND date >= :notBefore
             ORDER BY date
            """;

    @PersistenceContext private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Optional<EvaluationWindowInputs> findInputs(
            String mapVersion, List<String> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return Optional.empty();
        }
        Optional<Dataset> dataset = currentDataset(mapVersion);
        if (dataset.isEmpty()) {
            return Optional.empty();
        }
        String datasetId = dataset.get().datasetId();

        Map<String, Listing> listings = listings(instrumentIds);
        if (listings.size() < instrumentIds.size()) {
            // 대응표가 가리키는 대표 ETF 행이 없다는 뜻이다. 그 후보를 빼고 기간을 내면 화면은 요청한
            // 섹터 전부를 담은 값으로 읽는다 — 빠뜨린 채로 답하지 않는다(원칙 VI).
            return Optional.empty();
        }

        LocalDate earliestListing =
                listings.values().stream()
                        .map(Listing::listedOn)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        List<LocalDate> calendar = tradingCalendar(datasetId, earliestListing);
        if (calendar.isEmpty()) {
            return Optional.empty();
        }

        Map<String, List<EvaluationWindowInputs.DataGap>> gaps = dataGaps(datasetId, instrumentIds);
        List<EvaluationWindowInputs.Candidate> candidates = new ArrayList<>(instrumentIds.size());
        for (String instrumentId : instrumentIds) {
            Listing listing = listings.get(instrumentId);
            candidates.add(
                    new EvaluationWindowInputs.Candidate(
                            instrumentId,
                            listing.listedOn(),
                            listing.delistedOn(),
                            gaps.getOrDefault(instrumentId, List.of())));
        }
        return Optional.of(
                new EvaluationWindowInputs(
                        datasetId, dataset.get().dataDeadline(), candidates, calendar));
    }

    private Optional<Dataset> currentDataset(String mapVersion) {
        try {
            Object[] row =
                    (Object[])
                            entityManager
                                    .createNativeQuery(CURRENT_DATASET_SQL)
                                    .setParameter("mapVersion", mapVersion)
                                    .getSingleResult();
            return Optional.of(new Dataset((String) row[0], toLocalDate(row[1])));
        } catch (NoResultException absent) {
            return Optional.empty();
        }
    }

    private Map<String, Listing> listings(List<String> instrumentIds) {
        Query query =
                entityManager
                        .createNativeQuery(CANDIDATES_SQL)
                        .setParameter("instrumentIds", instrumentIds);
        Map<String, Listing> listings = new LinkedHashMap<>();
        for (Object row : query.getResultList()) {
            Object[] columns = (Object[]) row;
            listings.put(
                    (String) columns[0],
                    new Listing(toLocalDate(columns[1]), toLocalDate(columns[2])));
        }
        return listings;
    }

    private Map<String, List<EvaluationWindowInputs.DataGap>> dataGaps(
            String datasetId, List<String> instrumentIds) {
        Query query =
                entityManager
                        .createNativeQuery(DATA_GAPS_SQL)
                        .setParameter("datasetId", datasetId)
                        .setParameter("instrumentIds", instrumentIds);
        Map<String, List<EvaluationWindowInputs.DataGap>> gaps = new LinkedHashMap<>();
        for (Object row : query.getResultList()) {
            Object[] columns = (Object[]) row;
            gaps.computeIfAbsent((String) columns[0], key -> new ArrayList<>())
                    .add(
                            new EvaluationWindowInputs.DataGap(
                                    toLocalDate(columns[1]),
                                    toLocalDate(columns[2]),
                                    (String) columns[3]));
        }
        return gaps;
    }

    private List<LocalDate> tradingCalendar(String datasetId, LocalDate notBefore) {
        Query query =
                entityManager
                        .createNativeQuery(TRADING_CALENDAR_SQL)
                        .setParameter("datasetId", datasetId)
                        .setParameter("indexCode", CALENDAR_INDEX_CODE)
                        .setParameter("notBefore", notBefore);
        return query.getResultList().stream()
                .map(EvaluationWindowRepositoryImpl::toLocalDate)
                .toList();
    }

    /**
     * 질의가 돌려준 날짜/시각 값을 거래소 현지 날짜로 옮긴다.
     *
     * <p><b>타입을 하나로 가정하지 않는다.</b> 같은 {@code date} 열이라도 JDBC 드라이버와 Hibernate 설정에 따라 {@link
     * java.sql.Date}로 오기도 하고 {@link LocalDate}로 오기도 하며, {@code timestamptz}는 {@link Instant}로 온다.
     * 하나로 단정해 캐스팅하면 그 설정이 바뀌는 날 조회가 통째로 500이 된다.
     *
     * <p>시각을 날짜로 자를 때는 거래소 현지 기준을 쓴다. UTC로 자르면 마감 시각이 하루 밀린다.
     */
    private static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate date -> date;
            case java.sql.Date date -> date.toLocalDate();
            case java.sql.Timestamp timestamp ->
                    LocalDate.ofInstant(timestamp.toInstant(), EXCHANGE_ZONE);
            case Instant instant -> LocalDate.ofInstant(instant, EXCHANGE_ZONE);
            case java.time.OffsetDateTime offset ->
                    offset.atZoneSameInstant(EXCHANGE_ZONE).toLocalDate();
            default ->
                    throw new IllegalStateException(
                            "날짜로 옮길 수 없는 값이다: " + value.getClass().getName());
        };
    }

    private record Dataset(String datasetId, LocalDate dataDeadline) {}

    private record Listing(LocalDate listedOn, LocalDate delistedOn) {}
}
