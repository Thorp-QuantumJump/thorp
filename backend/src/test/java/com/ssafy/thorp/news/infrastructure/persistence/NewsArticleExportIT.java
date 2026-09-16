package com.ssafy.thorp.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.news.application.command.NewsArticleExportQuery;
import com.ssafy.thorp.news.application.result.NewsArticleExportResult;
import com.ssafy.thorp.news.application.service.NewsArticleExportService;
import com.ssafy.thorp.news.application.service.NewsDigestQueryService;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.NewsExposureMode;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 기사 메타데이터 내보내기를 실제 DB에서 검증한다(spec FR-017·SC-008, T046).
 *
 * <p>서비스의 판단(허용 필드 투영, 미허가 매체 제외)은 {@code NewsArticleExportServiceTest}가 대역으로 본다. 여기서 보는 것은 <b>대역이
 * 흉내 낼 수 없는 것</b>이다 — 저장소 질의의 정렬 기준과 cursor 비교가 실제로 같은 순서를 만드는지.
 *
 * <p>정렬 기준은 {@code COALESCE(published_at, collected_at)}이고 cursor도 같은 값으로 자리를 잡는다. 둘이 어긋나면 페이지를 넘길
 * 때 어떤 기사는 두 번 나오고 어떤 기사는 영영 나오지 않는다. 한 페이지만 보는 시험으로는 드러나지 않아, 여기서 끝까지 넘겨 본다.
 *
 * <p>내보낸 자료는 우리 화면을 떠나 다른 곳에 저장된다. 화면은 닫으면 사라지지만 내보낸 파일은 남으므로, 허가 밖의 행이 한 줄이라도 섞이면 되돌릴 수 없다.
 *
 * <p>Docker가 없으면 {@link com.ssafy.thorp.support.PostgresTestSupport}가 통째로 skip한다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    NewsPublisherRepositoryImpl.class,
    NewsPublisherPermissionHistoryRepositoryImpl.class,
    NewsArticleRepositoryImpl.class,
    NewsArticleExportService.class,
    com.ssafy.thorp.news.application.service.NewsSectorQueryPolicy.class,
    NewsArticleExportIT.TestBeans.class
})
class NewsArticleExportIT extends com.ssafy.thorp.support.PostgresTestSupport {

    private static final Instant NOW = Instant.parse("2026-09-10T06:00:00Z");
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T00:00:00Z");

    @Autowired private TestEntityManager entityManager;
    @Autowired private NewsPublisherRepositoryImpl publishers;
    @Autowired private NewsPublisherPermissionHistoryRepositoryImpl permissions;
    @Autowired private NewsArticleRepositoryImpl articles;
    @Autowired private NewsArticleExportService exportService;

    @Test
    @DisplayName("회신 전·불허 매체의 기사는 내보내지 않는다")
    void 미허가_매체_제외() {
        NewsPublisher allowed = allowedPublisher("ex-allowed", NewsAllowedField.TITLE);
        NewsPublisher pending = savePublisher("ex-pending", PermissionStatus.PENDING);
        NewsPublisher denied = savePublisher("ex-denied", PermissionStatus.DENIED);

        saveArticle(allowed, "allowed-1", "허가 매체 기사", NOW.minus(Duration.ofHours(3)));
        saveArticle(pending, "pending-1", "회신 전 매체 기사", NOW.minus(Duration.ofHours(2)));
        saveArticle(denied, "denied-1", "불허 매체 기사", NOW.minus(Duration.ofHours(1)));

        NewsArticleExportResult result = export(null, 100);

        assertThat(result.items())
                .extracting(NewsArticleExportResult.Item::title)
                .as("내보낸 파일은 화면과 달리 지워지지 않는다. 한 줄도 섞이면 안 된다")
                .containsExactly("허가 매체 기사");
    }

    @Test
    @DisplayName("최신 회신이 허용한 필드만 값이 있다")
    void 허용_필드만_채운다() {
        NewsPublisher publisher =
                allowedPublisher("ex-fields", NewsAllowedField.TITLE, NewsAllowedField.PUBLISHER);
        saveArticle(publisher, "fields-1", "제목만 허용", NOW.minus(Duration.ofHours(1)));

        NewsArticleExportResult.Item item = export(null, 100).items().get(0);

        assertThat(item.title()).isEqualTo("제목만 허용");
        assertThat(item.publisher()).isEqualTo("ex-fields 신문");
        assertThat(item.originalUrl()).as("허용하지 않은 필드는 비어야 한다").isNull();
        assertThat(item.publishedAt()).isNull();
        assertThat(item.articleId()).as("식별자는 매체 정보가 아니라 우리가 만든 값이다").isNotBlank();
    }

    @Test
    @DisplayName("끝까지 페이지를 넘겨도 같은 기사를 두 번 받거나 빠뜨리지 않는다")
    void 페이지를_끝까지_넘긴다() {
        NewsPublisher publisher = allowedPublisher("ex-paging", NewsAllowedField.TITLE);
        int total = 10;
        for (int i = 0; i < total; i++) {
            saveArticle(
                    publisher, "paging-" + i, "기사 " + i, NOW.minus(Duration.ofHours(total - i)));
        }

        List<String> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            NewsArticleExportResult page = export(cursor, 3);
            page.items().forEach(item -> collected.add(item.articleId()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < total);

        assertThat(collected).as("중복이 있으면 받는 쪽이 같은 기사를 두 벌 저장한다").doesNotHaveDuplicates();
        assertThat(collected).hasSize(total);
        assertThat(pages).as("한 페이지로 다 준 것이 아니라 정말 넘겨 본 것이다").isGreaterThan(1);
    }

    @Test
    @DisplayName("같은 시각의 기사가 여럿이어도 자리가 흔들리지 않는다")
    void 같은_시각도_안정적이다() {
        NewsPublisher publisher = allowedPublisher("ex-tie", NewsAllowedField.TITLE);
        Instant sameMoment = NOW.minus(Duration.ofHours(2));
        for (int i = 0; i < 5; i++) {
            saveArticle(publisher, "tie-" + i, "같은 시각 기사 " + i, sameMoment);
        }

        List<String> first = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5 && (page == 0 || cursor != null); page++) {
            NewsArticleExportResult result = export(cursor, 2);
            result.items().forEach(item -> first.add(item.articleId()));
            cursor = result.nextCursor();
        }

        assertThat(first)
                .as("기준 시각만으로 자리를 잡으면 같은 시각 기사들이 서로를 가린다")
                .doesNotHaveDuplicates()
                .hasSize(5);
    }

    @Test
    @DisplayName("요청 구간 밖의 기사는 나오지 않는다")
    void 구간_밖은_제외() {
        NewsPublisher publisher = allowedPublisher("ex-range", NewsAllowedField.TITLE);
        saveArticle(publisher, "inside", "구간 안 기사", NOW.minus(Duration.ofHours(1)));
        saveArticle(publisher, "outside", "구간 밖 기사", Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(export(null, 100).items())
                .extracting(NewsArticleExportResult.Item::title)
                .containsExactly("구간 안 기사");
    }

    private NewsArticleExportResult export(String cursor, int limit) {
        return exportService.export(NewsArticleExportQuery.of(null, FROM, TO, cursor, limit, 93));
    }

    /** 허가 상태와 회신 이력을 함께 갖춘 매체. 둘 중 하나만 있으면 내보내기가 아무것도 열지 않는다. */
    private NewsPublisher allowedPublisher(String key, NewsAllowedField... fields) {
        NewsPublisher publisher = savePublisher(key, PermissionStatus.PENDING);
        permissions.appendAndApply(
                NewsPublisherPermissionHistory.allowed(
                        publisher.getId(), Set.of(fields), NOW.minus(Duration.ofDays(1)), "회신"));
        flushAndClear();
        return publishers.findById(publisher.getId()).orElseThrow();
    }

    private NewsPublisher savePublisher(String key, PermissionStatus status) {
        NewsPublisher publisher =
                publishers.save(
                        NewsPublisher.register(
                                key,
                                key + " 신문",
                                "ko",
                                key + ".example.com",
                                "https://rss." + key + ".example.com/economy.xml",
                                true));
        if (status != PermissionStatus.PENDING) {
            publisher = publishers.save(publisher.withPermission(status, NOW));
        }
        flushAndClear();
        return publisher;
    }

    private NewsArticle saveArticle(
            NewsPublisher publisher, String slug, String title, Instant publishedAt) {
        String url = "https://rss." + publisher.getPublisherKey() + ".example.com/" + slug;
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize(url);
        PublicationTimeStatus status = NewsArticle.classifyPublicationTime(publishedAt, NOW);
        NewsArticle saved =
                articles.upsertByCanonicalUrl(
                        NewsArticle.collected(
                                NewsSourceType.RSS,
                                publisher.getId(),
                                null,
                                url,
                                canonical,
                                title,
                                "ko",
                                status == PublicationTimeStatus.VERIFIED ? publishedAt : null,
                                status,
                                NOW,
                                null,
                                canonical.value(),
                                "hash",
                                "run-1",
                                true));
        flushAndClear();
        return saved;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        NewsDigestQueryService.NewsQuerySettings newsQuerySettings() {
            return new NewsDigestQueryService.NewsQuerySettings(
                    NewsExposureMode.PUBLIC, Duration.ofMinutes(30), 3, Duration.ofHours(48), 93);
        }

        /** 섹터를 좁히지 않는 조회만 쓰므로 대응표는 비어 있어도 된다. */
        @Bean
        com.ssafy.thorp.news.application.port.NewsSectorCatalog newsSectorCatalog() {
            return new com.ssafy.thorp.news.support.StubSectorCatalog(Set.of("SEMICONDUCTOR"));
        }
    }
}
