package com.ssafy.thorp.backtest.infrastructure.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.support.MarketCatalogSeed;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 적재된 자료를 읽는 {@link DatabaseMarketMetadataAdapter} 의 동작.
 *
 * <p>이 구현이 답을 만드는 자리는 <b>SQL 과 실제 표 구조가 맞는가</b>에 달려 있다. 그래서 가짜 대역이 아니라 운영과 같은 PostgreSQL 에 붙여 건다.
 * 예전 픽스처 구현은 자바 코드에서 바로 답을 줘 이 부분이 검사되지 않았다.
 *
 * <p>여기서 고정하는 성질은 넷이다. <b>판 번호를 어떻게 고르는가</b>, <b>부분 갱신본을 고르지 않는가</b>, <b>자료가 없으면 지어내지 않고 멈추는가</b>,
 * <b>달력을 거래일로 세는가</b>.
 */
// 설정값을 다른 통합 검사와 똑같이 둔다. Spring 이 애플리케이션 컨텍스트를 설정 단위로 캐시하므로
// 한 글자라도 다르면 컨텍스트를 하나 더 띄워 전체 검사가 느려진다. 메일·브로커 값은 이 검사가 쓰지
// 않지만, 없으면 그 빈들이 만들어지지 못해 컨텍스트 자체가 뜨지 않는다.
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@DisplayName("적재된 자료를 읽는 시장 메타데이터 구현")
class DatabaseMarketMetadataAdapterIT extends PostgresTestSupport {

    @Autowired private DatabaseMarketMetadataAdapter adapter;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 씨앗의 자료 버전으로 <b>시작하는지</b>만 본다.
     *
     * <p>{@code dataset_version} 에는 불변 트리거가 걸려 있어(FR-017) 검사가 넣은 행을 지울 수 없다. 아래 판 번호 검사가 {@code
     * _R2} 를 넣고 나면 그쪽이 현재 판이 되며, 그 상태는 되돌릴 수 없다. 그래서 정확한 일치가 아니라 접두사로 건다 — 검사 순서에 기대지 않기 위함이다.
     */
    @Test
    @DisplayName("씨앗이 심은 자료 버전과 대응표를 읽는다")
    void readsSeededCatalog() {
        assertThat(adapter.currentDataVersion()).startsWith(MarketCatalogSeed.DATA_VERSION);
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
    @DisplayName("같은 마감일을 다시 발행하면 나중 판을 고른다")
    void prefersTheLaterRevision() {
        // 판 번호가 붙은 식별자는 이름 정렬에서 뒤에 온다(research.md 결정 4의 2026-09-17 보완).
        // 씨앗과 같은 마감 시각으로 _R2 를 넣고 그쪽이 뽑히는지 본다.
        insertDatasetVersion(
                MarketCatalogSeed.DATA_VERSION + "_R2", "2026-12-31 15:30:00+09", true);

        assertThat(adapter.currentDataVersion()).isEqualTo(MarketCatalogSeed.DATA_VERSION + "_R2");
    }

    @Test
    @DisplayName("부분 갱신된 묶음은 고르지 않는다")
    void ignoresIncompleteDatasets() {
        // 이용 한도로 중간에 끊긴 자료다(FR-014). 그것으로 낸 결과는 다음 실행과 재현되지 않는다.
        // 마감 시각을 어느 것보다도 늦게 두어도 뽑히면 안 된다.
        insertDatasetVersion("FIXTURE-incomplete", "2027-01-31 15:30:00+09", false);

        assertThat(adapter.currentDataVersion()).isNotEqualTo("FIXTURE-incomplete");
        assertThat(adapter.currentDataVersion()).startsWith(MarketCatalogSeed.DATA_VERSION);
    }

    /** {@code dataset_version} 은 불변 트리거 때문에 지울 수 없으므로 검사마다 다른 식별자를 쓴다. */
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
