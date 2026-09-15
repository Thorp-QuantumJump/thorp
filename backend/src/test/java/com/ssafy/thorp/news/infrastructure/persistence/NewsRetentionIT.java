package com.ssafy.thorp.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.news.application.result.NewsRetentionResult;
import com.ssafy.thorp.news.application.service.NewsRetentionService;
import com.ssafy.thorp.news.support.RecordingNewsMetrics;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 보관 기한 일괄 삭제를 운영과 같은 PostgreSQL에서 검증한다(spec FR-019·SC-007, T049).
 *
 * <p>여기서 확인하는 것은 셋이다 — 기한 전에는 아무것도 지우지 않는다, 기한 뒤 첫 회차가 <b>모든</b> 뉴스 표를 비운다, 이어지는 회차는 지울 것을 찾지 못하고
 * 조용히 끝난다.
 *
 * <p>가장 중요한 단언은 지우는 표 목록이 스키마를 덮는지 보는 것이다. 나머지 시험은 지우기로 한 표가 지워졌는지만 보므로, 뉴스 표를 새로 만들면서 목록에 더하지 않으면
 * 전부 통과한 채로 그 표만 남는다. SC-007은 "예외로 보존한 표가 0개"를 요구한다.
 *
 * <p>Docker가 없으면 {@link PostgresTestSupport}가 통째로 skip한다. 결과의 {@code skipped}를 반드시 확인한다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    NewsRetentionRepositoryImpl.class,
    DatabaseNewsCollectionLease.class,
    NewsRetentionIT.TestBeans.class
})
class NewsRetentionIT extends PostgresTestSupport {

    /** 보관 기한 2026-11-07의 한국 시간 아침. UTC로 자르면 아직 11월 6일이라 하루 늦게 지운다. */
    private static final Instant DUE_DAY = Instant.parse("2026-11-06T23:30:00Z");

    private static final Instant BEFORE_DUE = Instant.parse("2026-11-05T23:30:00Z");

    @Autowired private NewsRetentionRepositoryImpl retentionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.ssafy.thorp.news.application.port.NewsCollectionLease lease;

    /** 시험마다 새로 만든다. 스프링 빈으로 두면 컨텍스트가 시험 사이에 살아 있어 앞 시험이 남긴 기록이 뒤 시험에 그대로 보인다. */
    private final RecordingNewsMetrics metrics = new RecordingNewsMetrics();

    @Test
    @DisplayName("기한 전에는 아무것도 지우지 않는다")
    void 기한_전() {
        seed();

        NewsRetentionResult result = service(BEFORE_DUE).purgeIfDue("instance-1");

        assertThat(result.outcome()).isEqualTo(NewsRetentionResult.Outcome.NOT_DUE);
        assertThat(seededArticles()).isEqualTo(1);
        assertThat(metrics.retentionRuns).isEmpty();
    }

    @Test
    @DisplayName("기한이 지나면 모든 뉴스 표가 비고, 다시 돌려도 조용히 끝난다")
    void 기한_뒤_일괄_삭제() {
        seed();

        NewsRetentionResult first = service(DUE_DAY).purgeIfDue("instance-1");

        assertThat(first.outcome()).isEqualTo(NewsRetentionResult.Outcome.EXECUTED);
        assertThat(first.totalDeleted()).isPositive();
        for (String table : NewsRetentionRepositoryImpl.TABLES) {
            assertThat(count(table)).as(table + "에 남은 행").isZero();
        }

        NewsRetentionResult second = service(DUE_DAY).purgeIfDue("instance-2");

        assertThat(second.outcome())
                .as("이미 비었다고 실패로 보면 날마다 도는 작업이 경보를 울린다")
                .isEqualTo(NewsRetentionResult.Outcome.EXECUTED);
        // 수집 상태 표만 1건이다. 점유를 잡는 행위 자체가 그 표에 행을 만들고, 이어지는 삭제가 그것을
        // 지운다. 남는 것이 없으므로 반복 실행은 여전히 안전하다.
        assertThat(second.deletedRows())
                .allSatisfy(
                        (table, rows) -> {
                            if (!table.equals("news_collection_state")) {
                                assertThat(rows).as(table).isZero();
                            }
                        });
        for (String table : NewsRetentionRepositoryImpl.TABLES) {
            assertThat(count(table)).as(table + "에 남은 행").isZero();
        }
        assertThat(metrics.retentionRuns).as("지운 것이 없는 회차도 지표로 남는다").hasSize(2);
    }

    @Test
    @DisplayName("다른 인스턴스가 점유 중이면 건너뛴다")
    void 점유_중이면_건너뛴다() {
        seed();
        lease.tryAcquire(NewsRetentionService.SOURCE_KEY, "other-instance", DUE_DAY);

        NewsRetentionResult result = service(DUE_DAY).purgeIfDue("instance-1");

        assertThat(result.outcome()).isEqualTo(NewsRetentionResult.Outcome.SKIPPED);
        assertThat(seededArticles()).isEqualTo(1);
    }

    @Test
    @DisplayName("지우는 표 목록이 스키마의 뉴스 표를 전부 덮는다")
    void 지우는_표_목록이_스키마를_덮는다() {
        List<String> inSchema =
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables"
                                + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
                                + " AND table_name LIKE 'news@_%' ESCAPE '@'",
                        String.class);

        assertThat(inSchema).isNotEmpty();
        assertThat(NewsRetentionRepositoryImpl.TABLES)
                .as("뉴스 표를 새로 만들면 삭제 목록에도 더한다. 빠뜨리면 그 표만 기한 뒤에 남는다")
                .containsExactlyInAnyOrderElementsOf(inSchema);
    }

    /** 표마다 한 행씩 심는다. 무엇이 지워지는지 세려면 모든 표에 행이 있어야 한다. */
    private void seed() {
        jdbc.update(
                "INSERT INTO news_publisher (id, publisher_key, display_name, language,"
                        + " registered_domain, permission_status, created_at, updated_at) VALUES"
                        + " (9001, 'retention-it', '매체', 'ko', 'example.com', 'ALLOWED', NOW(),"
                        + " NOW())");
        jdbc.update(
                "INSERT INTO news_publisher_permission_history (id, publisher_id, event_type,"
                        + " permission_status, allowed_fields, occurred_at, created_at, updated_at)"
                        + " VALUES (9001, 9001, 'RESPONDED', 'ALLOWED', ARRAY['TITLE']::TEXT[], NOW(), NOW(),"
                        + " NOW())");
        jdbc.update(
                "INSERT INTO news_article (id, article_id, source_type, publisher_id,"
                        + " original_url, canonical_url, title, language, published_at_status,"
                        + " collected_at, duplicate_key, source_payload_hash, ingest_run_id,"
                        + " created_at, updated_at) VALUES (9001, 'retention-it-a1', 'RSS', 9001, 'https://example.com/retention-it',"
                        + " 'https://example.com/retention-it', '제목', 'ko', 'UNKNOWN', NOW(), 'k1', 'h1', 'r1',"
                        + " NOW(), NOW())");
        jdbc.update(
                "INSERT INTO news_topic (id, topic_key, display_name, rule_version, created_at,"
                        + " updated_at) VALUES (9001, 'retention-it-topic', '시장', 'news-link-v1', NOW(), NOW())");
        jdbc.update(
                "INSERT INTO news_article_topic (article_id, topic_id, rule_version) VALUES (9001,"
                        + " 9001, 'news-link-v1')");
        jdbc.update(
                "INSERT INTO news_article_sector (article_id, sector_id, rule_version) VALUES (9001,"
                        + " 'SEMICONDUCTOR', 'news-link-v1')");
        jdbc.update(
                "INSERT INTO news_digest (id, digest_id, representative_article_id,"
                        + " representative_title, factual_summary, distinct_publisher_count,"
                        + " grouping_rule_version, importance_rule_version, created_at, updated_at)"
                        + " VALUES (9001, 'retention-it-d1', 9001, '제목', '요약', 1, 'news-grouping-v1',"
                        + " 'news-importance-v1', NOW(), NOW())");
        jdbc.update("INSERT INTO news_digest_article (digest_id, article_id) VALUES (9001, 9001)");
        jdbc.update(
                "INSERT INTO news_collection_state (source_key, status, retry_count, created_at,"
                        + " updated_at) VALUES ('rss:retention-it', 'IDLE', 0, NOW(), NOW())");
        // news_rule_version은 마이그레이션 seed가 이미 채워 두었다.
        assertThat(count("news_rule_version")).isPositive();
    }

    /**
     * 이 시험이 심은 기사가 남아 있는지.
     *
     * <p>표 전체를 세지 않는다. 공용 컨테이너를 여러 시험이 함께 쓰고 그중 일부는 커밋한 행을 남긴다 — 전체 건수로 단언하면 다른 시험이 무엇을 남겼느냐에 따라
     * 결과가 흔들린다.
     */
    private long seededArticles() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM news_article WHERE article_id = 'retention-it-a1'",
                Long.class);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private NewsRetentionService service(Instant now) {
        return new NewsRetentionService(
                retentionRepository,
                lease,
                metrics,
                new NewsRetentionService.RetentionSettings(
                        LocalDate.of(2026, 11, 7), ZoneId.of("Asia/Seoul")),
                Clock.fixed(now, ZoneId.of("UTC")));
    }

    @TestConfiguration
    static class TestBeans {

        /** 점유 구현이 만료 시간을 읽는다. 여기서는 그 값만 의미가 있다. */
        @Bean
        com.ssafy.thorp.news.infrastructure.config.NewsProperties newsProperties() {
            return new com.ssafy.thorp.news.infrastructure.config.NewsProperties(
                    com.ssafy.thorp.news.domain.vo.NewsExposureMode.PUBLIC,
                    java.time.Duration.ofMinutes(30),
                    java.time.Duration.ofMinutes(15),
                    java.time.Duration.ofMinutes(15),
                    LocalDate.of(2026, 11, 7),
                    "Asia/Seoul",
                    93,
                    20,
                    3,
                    java.time.Duration.ofHours(48),
                    new com.ssafy.thorp.news.infrastructure.config.NewsProperties.Http(
                            3,
                            java.time.Duration.ofSeconds(5),
                            java.time.Duration.ofSeconds(20),
                            10_485_760L,
                            java.util.Set.of("application/xml"),
                            java.util.Set.of("application/json"),
                            List.of("api.gdeltproject.org")));
        }
    }
}
