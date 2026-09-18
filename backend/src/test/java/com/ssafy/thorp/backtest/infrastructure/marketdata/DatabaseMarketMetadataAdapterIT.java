package com.ssafy.thorp.backtest.infrastructure.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.MarketCatalogSeed;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적재된 자료를 읽는 {@link DatabaseMarketMetadataAdapter} 의 동작.
 *
 * <p>이 구현이 답을 만드는 자리는 <b>SQL 과 실제 표 구조가 맞는가</b>에 달려 있다. 그래서 가짜 대역이 아니라 운영과 같은 PostgreSQL 에 붙여 건다.
 * 예전 픽스처 구현은 자바 코드에서 바로 답을 줘 이 부분이 검사되지 않았다.
 *
 * <p>여기서 고정하는 성질은 넷이다. <b>판 번호를 어떻게 고르는가</b>, <b>부분 갱신본을 고르지 않는가</b>, <b>자료가 없으면 지어내지 않고 멈추는가</b>,
 * <b>달력을 거래일로 세는가</b>.
 */
@DisplayName("적재된 자료를 읽는 시장 메타데이터 구현")
class DatabaseMarketMetadataAdapterIT extends IntegrationTestSupport {

    @Autowired private DatabaseMarketMetadataAdapter adapter;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 씨앗의 자료 버전을 <b>정확히</b> 읽는지 본다.
     *
     * <p>자료 버전을 더하는 아래 두 검사가 트랜잭션 롤백으로 격리되므로, 이 검사가 돌 때 공용 DB 에는 씨앗의 자료 버전만 남아 있어야 한다. 접두사가 아니라 정확한
     * 일치로 거는 것이 그 격리를 지키는 장치다 — 격리가 풀려 {@code _R2} 가 남으면 여기서 먼저 걸리고, 같은 자료 버전을 기대하는 다른 스펙의 검사(005
     * {@code ResultApiIT} 의 계약 예시 비교)까지 번지지 않는다.
     */
    @Test
    @DisplayName("씨앗이 심은 자료 버전과 대응표를 읽는다")
    void readsSeededCatalog() {
        assertThat(adapter.currentDataVersion()).isEqualTo(MarketCatalogSeed.DATA_VERSION);
        assertThat(adapter.currentMappingVersion()).isEqualTo(MarketCatalogSeed.MAP_VERSION);
    }

    @Test
    @DisplayName("종목의 섹터와 상장일을 대응표에서 읽는다")
    void readsListingFromMap() {
        assertThat(adapter.sectorOf(MarketCatalogSeed.MAP_VERSION, "091160.XKRX"))
                .contains("SEMICONDUCTOR");
        assertThat(
                        adapter.isMappedTo(
                                MarketCatalogSeed.MAP_VERSION, "SEMICONDUCTOR", "091160.XKRX"))
                .isTrue();
        assertThat(adapter.listedOn("091160.XKRX")).contains(LocalDate.of(2015, 1, 2));
    }

    @Test
    @DisplayName("대응표에 없는 짝은 연결로 보지 않는다")
    void doesNotInventMapping() {
        // 요청이 들고 온 (섹터, 종목) 쌍을 그대로 믿으면 대응표가 바뀐 뒤 옛 화면이 보낸 요청이
        // 조용히 다른 종목으로 실행된다.
        assertThat(adapter.isMappedTo(MarketCatalogSeed.MAP_VERSION, "BATTERY", "091160.XKRX"))
                .isFalse();
        assertThat(adapter.sectorOf(MarketCatalogSeed.MAP_VERSION, "999999.XKRX")).isEmpty();
        assertThat(adapter.listedOn("999999.XKRX")).isEmpty();
        assertThat(adapter.sectorOf("SEM-NO-SUCH-VERSION", "091160.XKRX")).isEmpty();
    }

    @Test
    @DisplayName("워밍업 거래일을 달력에서 센다")
    void countsWarmupOnTradingDays() {
        // 씨앗의 달력은 주말을 뺀 평일이다. 2026-09-14(월)에서 다섯 거래일 뒤는 그다음 주 월요일이다.
        LocalDate monday = LocalDate.of(2026, 9, 14);
        assertThat(adapter.evaluationStart(monday, 0)).contains(monday);
        assertThat(adapter.evaluationStart(monday, 5)).contains(LocalDate.of(2026, 9, 21));
    }

    @Test
    @DisplayName("주말을 요청하면 그 뒤 첫 거래일을 준다")
    void skipsNonTradingDays() {
        assertThat(adapter.evaluationStart(LocalDate.of(2026, 9, 19), 0))
                .contains(LocalDate.of(2026, 9, 21));
    }

    @Test
    @DisplayName("달력이 모자라면 날짜를 지어내지 않고 비워 준다")
    void doesNotInventDaysBeyondTheCalendar() {
        // 호출자는 이것을 422 INSUFFICIENT_HISTORY 로 다룬다. 없는 거래일을 계산으로 이어 붙이면
        // 자료가 담지 못한 구간을 있는 것처럼 내보내게 된다.
        assertThat(adapter.evaluationStart(LocalDate.of(2026, 12, 30), 100)).isEmpty();
    }

    @Test
    @DisplayName("워밍업 거래일 수가 음수면 거절한다")
    void rejectsNegativeWarmup() {
        assertThatThrownBy(() -> adapter.evaluationStart(LocalDate.of(2026, 9, 14), -1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @Transactional
    @DisplayName("같은 마감일을 다시 발행하면 나중 판을 고른다")
    void prefersTheLaterRevision() {
        // 판 번호가 붙은 식별자는 이름 정렬에서 뒤에 온다(research.md 결정 4의 2026-09-17 보완).
        // 씨앗과 같은 마감 시각으로 _R2 를 넣고 그쪽이 뽑히는지 본다.
        insertDatasetVersion(
                MarketCatalogSeed.DATA_VERSION + "_R2", "2026-12-31 15:30:00+09", true);

        assertThat(adapter.currentDataVersion()).isEqualTo(MarketCatalogSeed.DATA_VERSION + "_R2");
    }

    @Test
    @Transactional
    @DisplayName("부분 갱신된 묶음은 고르지 않는다")
    void ignoresIncompleteDatasets() {
        // 이용 한도로 중간에 끊긴 자료다(FR-014). 그것으로 낸 결과는 다음 실행과 재현되지 않는다.
        // 마감 시각을 어느 것보다도 늦게 두어도 뽑히면 안 된다.
        insertDatasetVersion("FIXTURE-incomplete", "2027-01-31 15:30:00+09", false);

        assertThat(adapter.currentDataVersion()).isEqualTo(MarketCatalogSeed.DATA_VERSION);
    }

    /**
     * 검사용 자료 버전을 하나 넣는다. <b>부르는 검사는 {@code @Transactional} 이어야 한다.</b>
     *
     * <p>{@code dataset_version} 에는 불변 트리거가 걸려 있어(FR-017) 한 번 커밋된 행은 DELETE 로 치울 수 없다. 반면 트랜잭션 롤백은
     * 트리거를 타지 않으므로, 검사를 롤백 경계 안에 두는 것이 이 표를 되돌리는 유일한 길이다. 롤백하지 않으면 여기서 넣은 행이 공용 DB 에 그대로 남아 뒤따르는
     * 검사의 현재 자료 버전을 바꿔 놓는다.
     */
    private void insertDatasetVersion(String datasetId, String deadline, boolean complete) {
        jdbcTemplate.update(
                """
                INSERT INTO dataset_version
                    (dataset_id, data_deadline, collected_at, last_success_at,
                     complete, failure_reason, is_sample, source, map_version_id)
                SELECT ?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ),
                       ?, ?, TRUE, 'FIXED_TEST_DATA', v.id
                  FROM sector_etf_map_version v
                 WHERE v.version = ?
                ON CONFLICT (dataset_id) DO NOTHING
                """,
                datasetId,
                deadline,
                deadline,
                deadline,
                complete,
                complete ? null : "검사용 부분 갱신",
                MarketCatalogSeed.MAP_VERSION);
    }
}
