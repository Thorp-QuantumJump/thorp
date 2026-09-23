package com.ssafy.thorp.backtest.infrastructure.marketdata;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec 001이 적재한 표를 읽는 구현. {@link MarketMetadataPort} 의 운영 구현이다.
 *
 * <p>포트의 설명이 "001이 들어오면 구현만 바꾸면 되게 하기 위해서다"라고 적어 둔 그 교체다. 부르는 쪽(접수·기간 산출·자동 탐색)은 한 줄도 바뀌지 않는다.
 *
 * <p><b>네 값이 함께 움직여야 뜻이 통한다.</b> 자료 버전만 실제 값으로 바꾸고 종목·대응표·달력을 픽스처로 두면, 결과에 찍힌 딱지는 실제 자료를 가리키는데 접수
 * 검사는 그 자료에 없는 종목을 통과시킨다. 그래서 이 구현이 네 값을 모두 맡는다.
 *
 * <p><b>값이 없으면 지어내지 않는다.</b> 발행된 자료 버전이 없으면 옛 상수로 조용히 돌아가지 않고 접수를 멈춘다. 그 값은 동일 버전 키와 결과 {@code
 * versions.data} 에 그대로 들어가므로, 거짓이 한 번 들어가면 되돌릴 수 없다(FR-017).
 *
 * <h2>조회를 SQL로 쓰는 까닭</h2>
 *
 * 읽는 표 넷 가운데 셋은 {@code sector} 모듈이 엔티티로 갖고 있고 {@code daily_index} 는 매핑이 없다. 여기서 필요한 것은 문자열과 날짜 몇
 * 개뿐이라 엔티티를 다시 만들 이유가 없고, 특히 거래일 달력은 <b>"이 날 이후 N번째 거래일"</b> 하나만 물으면 되는데 그것은 SQL 한 줄이다. 엔티티로 읽으면
 * 484일을 모두 가져와 세게 된다. 같은 방식을 {@code backtest.infrastructure.persistence} 의 다른 조회도 쓴다.
 */
@Component
public class DatabaseMarketMetadataAdapter implements MarketMetadataPort {

    /**
     * 거래일 달력의 출처. ETF 일별매매정보는 휴장일에도 행을 주므로 거래일을 가릴 수 없고, 달력을 소유한 것은 지수 자료뿐이다(001 {@code
     * contracts/market-dataset.md} ※1). 값은 {@code data_pipeline.index.CALENDAR_INDEX_CODE} 와 같다.
     */
    private static final String CALENDAR_INDEX_CODE = "KOSPI";

    /**
     * 지금 쓸 자료 버전. <b>완전한 묶음</b> 가운데 마감 시각이 가장 늦은 것이고, 마감 시각이 같으면 식별자 내림차순으로 가른다.
     *
     * <p>부분 갱신된 묶음({@code complete = false})을 고르지 않는 것은 이용 한도로 중간에 끊긴 자료이기 때문이다(FR-014). 그것으로 낸 결과는
     * 다음 실행과 재현되지 않는다.
     *
     * <p>식별자로 가르는 자리가 <b>판 번호를 받아들이는 자리</b>다. 같은 마감일의 자료를 내용만 바꿔 다시 발행하면 {@code KRX_DAILY_20260915}
     * 와 {@code KRX_DAILY_20260915_R2} 가 함께 있게 되고, 내림차순이라 뒤에 낸 판이 이긴다.
     */
    private static final String CURRENT_DATASET_SQL =
            """
            SELECT dataset_id
              FROM dataset_version
             WHERE complete = TRUE
             ORDER BY data_deadline DESC, dataset_id DESC
             LIMIT 1
            """;

    /** 열린 대응표 버전. {@code uk_sector_etf_map_version_open} 이 최대 하나로 강제한다. */
    private static final String OPEN_MAP_VERSION_SQL =
            """
            SELECT version
              FROM sector_etf_map_version
             WHERE effective_to IS NULL
            """;

    /** 그 대응표 버전에서 이 종목이 붙은 섹터 코드. */
    private static final String SECTOR_OF_SQL =
            """
            SELECT s.code
              FROM sector_etf_map_entry e
              JOIN sector_etf_map_version v ON v.id = e.map_version_id
              JOIN sector s ON s.id = e.sector_id
              JOIN representative_etf r ON r.id = e.etf_id
             WHERE v.version = :mapVersion
               AND r.instrument_id = :instrumentId
            """;

    private static final String LISTED_ON_SQL =
            """
            SELECT listed_on
              FROM representative_etf
             WHERE instrument_id = :instrumentId
            """;

    /** 지금 자료 버전 달력의 마지막 거래일. 곧 이 자료로 평가할 수 있는 마지막 날이다. */
    private static final String LAST_TRADING_DAY_SQL =
            """
            SELECT max(date)
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
            """;

    /**
     * 달력에서 {@code notBefore} 이후 {@code offset} 번째 거래일(0부터 센다).
     *
     * <p>{@code OFFSET} 이 워밍업 소진을 그대로 표현한다. 달력에 그만큼의 거래일이 없으면 행이 없고, 호출자는 그것을 자료 부족으로 다룬다.
     */
    private static final String NTH_TRADING_DAY_SQL =
            """
            SELECT date
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
               AND date >= :notBefore
             ORDER BY date
             OFFSET :offset
             LIMIT 1
            """;

    @PersistenceContext private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public String currentDataVersion() {
        return single(entityManager.createNativeQuery(CURRENT_DATASET_SQL), String.class)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INSUFFICIENT_HISTORY,
                                        "발행된 시장 자료가 없습니다. 자료를 적재한 뒤 다시 시도해 주세요."));
    }

    @Override
    @Transactional(readOnly = true)
    public String currentMappingVersion() {
        return single(entityManager.createNativeQuery(OPEN_MAP_VERSION_SQL), String.class)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INSUFFICIENT_HISTORY,
                                        "열린 섹터-ETF 대응표가 없습니다. 대응표를 발행한 뒤 다시 시도해 주세요."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> sectorOf(String mappingVersion, String instrumentId) {
        Query query =
                entityManager
                        .createNativeQuery(SECTOR_OF_SQL)
                        .setParameter("mapVersion", mappingVersion)
                        .setParameter("instrumentId", instrumentId);
        return single(query, String.class);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMappedTo(String mappingVersion, String sectorId, String instrumentId) {
        return sectorOf(mappingVersion, instrumentId).filter(sectorId::equals).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> listedOn(String instrumentId) {
        Query query =
                entityManager
                        .createNativeQuery(LISTED_ON_SQL)
                        .setParameter("instrumentId", instrumentId);
        return single(query, java.sql.Date.class).map(java.sql.Date::toLocalDate);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> evaluationStart(LocalDate notBefore, int warmupTradingDays) {
        if (warmupTradingDays < 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "워밍업 거래일 수는 음수일 수 없습니다: " + warmupTradingDays);
        }
        return nthTradingDayOnOrAfter(notBefore, warmupTradingDays);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> firstTradingDayOnOrAfter(LocalDate date) {
        // 같은 달력을 쓰되 워밍업을 소진하지 않는다. 뜻이 다른 두 질문이라 포트에서 이름을 갈랐고,
        // 달력 위에서는 그 차이가 건너뛸 거래일 수(0인가 아닌가)로 드러난다.
        return nthTradingDayOnOrAfter(date, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> lastTradingDay() {
        Query query =
                entityManager
                        .createNativeQuery(LAST_TRADING_DAY_SQL)
                        .setParameter("datasetId", currentDataVersion())
                        .setParameter("indexCode", CALENDAR_INDEX_CODE);
        // max() 는 행이 없어도 한 행을 NULL 로 돌려준다. single() 이 그 NULL 을 빈 값으로 옮긴다.
        return single(query, java.sql.Date.class).map(java.sql.Date::toLocalDate);
    }

    /** 달력에서 {@code notBefore} 이후 {@code offset} 번째 거래일(0부터 센다). */
    private Optional<LocalDate> nthTradingDayOnOrAfter(LocalDate notBefore, int offset) {
        Query query =
                entityManager
                        .createNativeQuery(NTH_TRADING_DAY_SQL)
                        .setParameter("datasetId", currentDataVersion())
                        .setParameter("indexCode", CALENDAR_INDEX_CODE)
                        .setParameter("notBefore", notBefore)
                        .setParameter("offset", offset);
        return single(query, java.sql.Date.class).map(java.sql.Date::toLocalDate);
    }

    /**
     * 한 칸짜리 결과를 읽는다. 행이 없으면 비어 있다.
     *
     * <p>{@code getSingleResult()} 는 행이 없을 때 예외를 던지므로 "없음"을 값으로 다루려면 감싸야 한다. 두 행 이상은 위 질의들이 {@code
     * LIMIT} 또는 UNIQUE 제약으로 이미 막는다.
     */
    private <T> Optional<T> single(Query query, Class<T> type) {
        try {
            return Optional.ofNullable(query.getSingleResult()).map(type::cast);
        } catch (NoResultException absent) {
            return Optional.empty();
        }
    }
}
