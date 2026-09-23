package com.ssafy.thorp.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 검사용 시장 자료를 심는다. 접수 경로를 타는 검사가 공통으로 쓴다.
 *
 * <h2>왜 필요한가</h2>
 *
 * {@code MarketMetadataPort} 의 운영 구현이 픽스처 상수에서 DB 조회로 바뀌었다. 전에는 자바 코드에 박힌 값이 답했으므로 검사가 아무것도 심지 않아도
 * 됐지만, 이제는 표에 행이 없으면 접수가 "대응표가 없다"로 막힌다.
 *
 * <h2>값을 바꾸지 않는 것이 요점이다</h2>
 *
 * 검사 열여섯 곳이 이 종목·대응표 이름으로 요청을 보내고 결과를 견준다. 값이 같아야 그 검사들이 무엇을 확인하는지 달라지지 않는다. 바뀌는 것은 <b>값이 어디서
 * 오는가</b> 하나뿐이다.
 *
 * <h2>Flyway 가 아니라 여기서 심는 까닭</h2>
 *
 * 마이그레이션으로 심으면 <b>지워지지 않는 전역 자료</b>가 된다. 이 저장소의 검사는 자기 자료를 직접 넣고 지우는 방식이라 그것과 맞지 않는다. 특히 {@code
 * SectorApiIT} 는 섹터와 대표 ETF 를 지웠다 다시 넣는데, 전역 씨앗이 그 행을 참조하고 있으면 외래키에 걸려 지우지 못한다. 마이그레이션 목록이 운영과 달라져
 * {@code FlywayMigrationTest} 도 깨진다.
 *
 * <p>그래서 <b>검사마다 다시 심는다.</b> 앞선 검사가 치워도 다음 검사 전에 되살아나므로 순서에 기대지 않는다.
 *
 * <h2>실제 시장 자료가 아니다</h2>
 *
 * 자료 버전 이름이 {@code FIXTURE-} 로 시작하는 것이 안전장치다. 이 값은 동일 버전 키와 결과 {@code versions.data} 에 그대로 들어가므로,
 * 검사로 만든 실행은 실제 자료로 만든 실행과 절대 같은 키를 갖지 않는다. {@code is_sample = TRUE} 도 같은 뜻을 표에 남긴다.
 */
public final class MarketCatalogSeed {

    public static final String DATA_VERSION = "FIXTURE-three_etf_24m";
    public static final String MAP_VERSION = "SECTOR-ETF-2026-09";

    /** 거래일 달력의 시작. 가장 이른 상장일보다 앞에서 시작해야 그 무렵을 요청하는 검사가 워밍업을 채운다. */
    private static final String CALENDAR_FROM = "2014-01-01";

    /** 거래일 달력의 끝. 픽스처 구현이 쓰던 상한과 같다. */
    private static final String CALENDAR_TO = "2026-12-31";

    private MarketCatalogSeed() {}

    /**
     * 시장 자료를 심는다. <b>여러 번 불러도 같은 결과다.</b>
     *
     * <p>이미 있는 행은 건드리지 않는다({@code ON CONFLICT DO NOTHING}). 앞선 검사가 일부만 치웠을 수도 있으므로 표마다 따로 판단한다.
     *
     * <p>대응표는 <b>열린 것이 하나뿐</b>이어야 한다({@code uk_sector_etf_map_version_open}). 다른 검사가 자기 대응표를 열어 둔 채
     * 끝났을 수 있으므로 먼저 닫고 이 씨앗을 연다.
     */
    public static void seed(JdbcTemplate jdbc) {
        seedSectors(jdbc);
        seedInstruments(jdbc);
        openOnlyThisMapVersion(jdbc);
        seedMapEntries(jdbc);
        seedDatasetVersion(jdbc);
        seedTradingCalendar(jdbc);
    }

    private static void seedSectors(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO sector (code, name, classification)
                VALUES ('SEMICONDUCTOR', '반도체', 'INDUSTRY'),
                       ('BATTERY', '2차전지', 'INDUSTRY'),
                       ('BIO', '바이오', 'INDUSTRY')
                ON CONFLICT (code) DO NOTHING
                """);
    }

    /** 상장일은 워커 픽스처 {@code worker/examples/three_etf_24m/instruments.csv} 와 같다. */
    private static void seedInstruments(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO representative_etf
                    (instrument_id, name, listed_on, benchmark_index, manager)
                VALUES ('091160.XKRX', 'KODEX 반도체', DATE '2015-01-02', 'KRX 반도체', '삼성자산운용'),
                       ('305720.XKRX', 'KODEX 2차전지산업', DATE '2018-09-12', 'FnGuide 2차전지 산업',
                        '삼성자산운용'),
                       ('244580.XKRX', 'KODEX 바이오', DATE '2023-07-03', 'KRX 헬스케어', '삼성자산운용')
                ON CONFLICT (instrument_id) DO NOTHING
                """);
    }

    /**
     * 이 씨앗의 대응표만 열어 둔다.
     *
     * <p>먼저 다른 열린 버전을 닫는다. 닫지 않고 넣으면 부분 UNIQUE 인덱스에 걸린다. 자료 버전과 달리 이 표에는 불변 트리거가 없어 UPDATE 할 수 있다.
     */
    private static void openOnlyThisMapVersion(JdbcTemplate jdbc) {
        jdbc.update(
                """
                UPDATE sector_etf_map_version
                   SET effective_to = effective_from + INTERVAL '1 second'
                 WHERE effective_to IS NULL
                   AND version <> ?
                """,
                MAP_VERSION);
        jdbc.update(
                """
                INSERT INTO sector_etf_map_version
                    (version, effective_from, effective_to, is_sample, published_at)
                VALUES (?, TIMESTAMPTZ '2026-09-01 00:00:00+09', NULL, TRUE,
                        TIMESTAMPTZ '2026-09-01 00:00:00+09')
                ON CONFLICT (version) DO UPDATE SET effective_to = NULL
                """,
                MAP_VERSION);
    }

    private static void seedMapEntries(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)
                SELECT v.id, s.id, e.id
                  FROM sector_etf_map_version v
                  JOIN (VALUES ('SEMICONDUCTOR', '091160.XKRX'),
                               ('BATTERY', '305720.XKRX'),
                               ('BIO', '244580.XKRX')) AS pair (sector_code, instrument_id) ON TRUE
                  JOIN sector s ON s.code = pair.sector_code
                  JOIN representative_etf e ON e.instrument_id = pair.instrument_id
                 WHERE v.version = ?
                ON CONFLICT (map_version_id, sector_id) DO NOTHING
                """,
                MAP_VERSION);
    }

    private static void seedDatasetVersion(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO dataset_version
                    (dataset_id, data_deadline, collected_at, last_success_at,
                     complete, is_sample, source, map_version_id)
                SELECT ?, TIMESTAMPTZ '2026-12-31 15:30:00+09',
                       TIMESTAMPTZ '2026-12-31 18:00:00+09',
                       TIMESTAMPTZ '2026-12-31 18:00:00+09',
                       TRUE, TRUE, 'FIXED_TEST_DATA', v.id
                  FROM sector_etf_map_version v
                 WHERE v.version = ?
                ON CONFLICT (dataset_id) DO NOTHING
                """,
                DATA_VERSION,
                MAP_VERSION);
    }

    /**
     * 거래일 달력. 픽스처 구현과 같게 <b>주말만 뺀 평일</b>이며 공휴일은 담지 않는다.
     *
     * <p>공휴일을 지어내면 검사가 보는 값이 실제 달력도 픽스처 달력도 아닌 제3의 것이 된다. OHLC 는 제약(양수, 고가·저가 상하한)만 지키는 상수다. 검사가 보는
     * 것은 날짜뿐이고 지수 값을 쓰는 곳이 없다.
     *
     * <p><b>자료 버전 전부에 같은 달력을 둔다.</b> {@code dataset_version} 에는 불변 트리거가 걸려 있어(FR-017) 검사가 넣은 행을 지울
     * 수 없다. 그래서 자료 버전을 더하는 검사가 하나라도 있으면 그 행이 뒤 검사까지 남고, 달력이 씨앗의 자료 버전에만 있으면 "현재 자료 버전에 달력이 없다"는 상태가
     * 만들어진다. 검사가 순서에 기대지 않게 하려고 달력을 모든 자료 버전에 맞춰 둔다.
     */
    private static void seedTradingCalendar(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO daily_index
                    (dataset_id, index_code, date, open, high, low, close, volume, value)
                SELECT v.dataset_id, 'KOSPI', day::date, 2500, 2500, 2500, 2500, 0, 0
                  FROM dataset_version v
                 CROSS JOIN generate_series(DATE '%s', DATE '%s', INTERVAL '1 day') AS day
                 WHERE EXTRACT(ISODOW FROM day) < 6
                ON CONFLICT (dataset_id, index_code, date) DO NOTHING
                """
                        .formatted(CALENDAR_FROM, CALENDAR_TO));
    }
}
