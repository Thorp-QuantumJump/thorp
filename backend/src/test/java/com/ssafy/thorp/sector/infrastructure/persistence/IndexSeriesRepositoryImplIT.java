package com.ssafy.thorp.sector.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.sector.application.repository.IndexSeriesRepository.IndexClose;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.MarketCatalogSeed;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IndexSeriesRepositoryImpl}의 SQL이 실제 {@code daily_index}와 맞는지 본다.
 *
 * <p>씨앗의 KOSPI 달력은 종가가 모두 같아 순서·범위를 가릴 수 없다. 그래서 검사용 지수 코드에 값이 다른 행을 넣고 롤백한다.
 */
@DisplayName("지수 기간 종가 조회")
@Transactional
class IndexSeriesRepositoryImplIT extends IntegrationTestSupport {

    private static final String CODE = "TEST_IDX";

    @Autowired private IndexSeriesRepositoryImpl repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private void close(String indexCode, String date, int close) {
        close(MarketCatalogSeed.DATA_VERSION, indexCode, date, close);
    }

    private void close(String datasetId, String indexCode, String date, int close) {
        jdbcTemplate.update(
                "INSERT INTO daily_index"
                        + " (dataset_id, index_code, date, open, high, low, close, volume, value)"
                        + " VALUES (?, ?, ?::date, ?, ?, ?, ?, 0, 0)",
                datasetId,
                indexCode,
                date,
                close,
                close,
                close,
                close);
    }

    /** 씨앗과 같은 대응표를 가리키는 다른 자료 버전. 트랜잭션 롤백으로 지워진다. */
    private void otherDataset(String datasetId) {
        jdbcTemplate.update(
                """
                INSERT INTO dataset_version
                    (dataset_id, data_deadline, collected_at, last_success_at,
                     complete, is_sample, source, map_version_id)
                SELECT ?, TIMESTAMPTZ '2027-01-31 15:30:00+09',
                       TIMESTAMPTZ '2027-01-31 18:00:00+09',
                       TIMESTAMPTZ '2027-01-31 18:00:00+09',
                       TRUE, TRUE, 'FIXED_TEST_DATA', v.id
                  FROM sector_etf_map_version v
                 WHERE v.version = ?
                """,
                datasetId,
                MarketCatalogSeed.MAP_VERSION);
    }

    @Test
    @DisplayName("자료 버전·지수 코드·기간(양끝 포함)의 종가를 날짜 오름차순으로 읽고, 빠진 날은 채우지 않는다")
    void readsClosesInRange() {
        close(CODE, "2025-01-07", 2400);
        close(CODE, "2025-01-02", 2500);
        close(CODE, "2025-01-06", 2750);
        close(CODE, "2025-01-08", 9999); // 기간 밖
        close("OTHER_IDX", "2025-01-03", 1111); // 다른 지수

        var closes =
                repository.findCloses(
                        MarketCatalogSeed.DATA_VERSION,
                        CODE,
                        LocalDate.of(2025, 1, 2),
                        LocalDate.of(2025, 1, 7));

        assertThat(closes)
                .extracting(IndexClose::date)
                .containsExactly(
                        LocalDate.of(2025, 1, 2),
                        LocalDate.of(2025, 1, 6),
                        LocalDate.of(2025, 1, 7));
        assertThat(closes)
                .extracting(c -> c.close().intValueExact())
                .containsExactly(2500, 2750, 2400);
    }

    @Test
    @DisplayName("같은 지수·날짜라도 다른 자료 버전의 행은 섞이지 않는다")
    void isolatesDatasetVersions() {
        otherDataset("TEST-DS-R2");
        close(CODE, "2025-01-02", 2500);
        close("TEST-DS-R2", CODE, "2025-01-02", 3000);
        close("TEST-DS-R2", CODE, "2025-01-03", 3100);

        var from = LocalDate.of(2025, 1, 1);
        var to = LocalDate.of(2025, 1, 31);
        assertThat(repository.findCloses(MarketCatalogSeed.DATA_VERSION, CODE, from, to))
                .extracting(c -> c.close().intValueExact())
                .containsExactly(2500);
        assertThat(repository.findCloses("TEST-DS-R2", CODE, from, to))
                .extracting(c -> c.close().intValueExact())
                .containsExactly(3000, 3100);
    }

    @Test
    @DisplayName("행이 없는 기간이면 빈 목록이다")
    void emptyWhenNothingInRange() {
        close(CODE, "2025-01-02", 2500);

        assertThat(
                        repository.findCloses(
                                MarketCatalogSeed.DATA_VERSION,
                                CODE,
                                LocalDate.of(2024, 1, 1),
                                LocalDate.of(2024, 12, 31)))
                .isEmpty();
    }
}
