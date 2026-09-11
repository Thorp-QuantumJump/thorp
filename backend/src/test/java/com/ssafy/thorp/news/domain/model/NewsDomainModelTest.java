package com.ssafy.thorp.news.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.NewsArticleId;
import com.ssafy.thorp.news.domain.vo.NewsCollectionStatus;
import com.ssafy.thorp.news.domain.vo.NewsDigestId;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 뉴스 도메인 모델과 값 객체의 불변식. 근거: specs/012-market-news-digest/spec.md, data-model.md.
 *
 * <p>여기서 지키는 핵심은 넷이다. 본문을 담지 않는다. 발행 시각을 지어내지 않는다. 식별자가 결정론적이다. 허가되지 않은 매체가 공개 투영에 새지 않는다.
 */
class NewsDomainModelTest {

    private static final Instant COLLECTED = Instant.parse("2026-09-10T06:00:00Z");
    private static final String GROUPING_RULE = "news-grouping-v1";
    private static final String IMPORTANCE_RULE = "news-importance-v1";

    /** 요약 근거가 충분하면 결정론적 한 문장을 만든다. 실제 규칙은 application 계층이 갖고 여기서는 대역을 쓴다. */
    private static final NewsDigest.SummaryRule SUMMARY =
            (representative, members) ->
                    Optional.of(
                            members.size() + "개 매체가 " + representative.getTitle() + "를 보도했습니다.");

    private static final NewsDigest.SummaryRule NO_EVIDENCE =
            (representative, members) -> Optional.empty();

    private static NewsArticle article(
            long publisherId, String url, String title, Instant publishedAt) {
        CanonicalNewsUrl canonical = CanonicalNewsUrl.normalize(url);
        PublicationTimeStatus status = NewsArticle.classifyPublicationTime(publishedAt, COLLECTED);
        return NewsArticle.collected(
                NewsSourceType.RSS,
                publisherId,
                null,
                url,
                canonical,
                title,
                "ko",
                status == PublicationTimeStatus.VERIFIED ? publishedAt : null,
                status,
                COLLECTED,
                null,
                canonical.value(),
                "hash",
                "run-1",
                true);
    }

    /** 저장된 뒤의 기사. 식별자가 있어야 묶음 근거를 기사별로 실을 수 있다. */
    private static NewsArticle stored(
            long id, long publisherId, String url, String title, Instant publishedAt) {
        NewsArticle a = article(publisherId, url, title, publishedAt);
        return NewsArticle.restore(
                id,
                a.getArticleId(),
                a.getSourceType(),
                a.getPublisherId(),
                a.getSourceItemId(),
                a.getOriginalUrl(),
                a.getCanonicalUrl(),
                a.getTitle(),
                a.getLanguage(),
                a.getPublishedAt(),
                a.getPublishedAtStatus(),
                a.getCollectedAt(),
                a.getGdeltRecordedAt(),
                a.getDuplicateKey(),
                a.getSourcePayloadHash(),
                a.getIngestRunId(),
                a.isDisplayEligible());
    }

    @Nested
    @DisplayName("본문 없음 (spec FR-002·SC-001)")
    class NoBody {

        @Test
        @DisplayName("기사 모델에 본문·HTML·Tone에 해당하는 필드가 없다")
        void 기사에_본문_필드가_없다() {
            List<String> suspicious =
                    java.util.Arrays.stream(NewsArticle.class.getDeclaredFields())
                            .map(Field::getName)
                            .map(String::toLowerCase)
                            .filter(
                                    name ->
                                            name.contains("body")
                                                    || name.contains("content")
                                                    || name.contains("fulltext")
                                                    || name.contains("html")
                                                    || name.contains("description")
                                                    || name.contains("tone"))
                            .toList();

            assertThat(suspicious).isEmpty();
        }
    }

    @Nested
    @DisplayName("발행 시각 판정 (spec FR-012)")
    class PublicationTime {

        @Test
        @DisplayName("수집 시각보다 5분 이내면 확인된 시각이다")
        void 정상_시각() {
            Instant published = COLLECTED.plus(Duration.ofMinutes(5));

            assertThat(NewsArticle.classifyPublicationTime(published, COLLECTED))
                    .isEqualTo(PublicationTimeStatus.VERIFIED);
        }

        @Test
        @DisplayName("5분을 넘는 미래 시각은 확인하지 않는다")
        void 미래_시각() {
            Instant published = COLLECTED.plus(Duration.ofMinutes(5).plusSeconds(1));

            assertThat(NewsArticle.classifyPublicationTime(published, COLLECTED))
                    .isEqualTo(PublicationTimeStatus.UNKNOWN);
        }

        @Test
        @DisplayName("시각이 없으면 수집 시각으로 대체하지 않고 미확인으로 둔다")
        void 시각_없음() {
            assertThat(NewsArticle.classifyPublicationTime(null, COLLECTED))
                    .isEqualTo(PublicationTimeStatus.UNKNOWN);
        }

        @Test
        @DisplayName("미확인 기사는 발행 시각을 비워야 하고 과거 구간에 연결되지 않는다")
        void 미확인_기사() {
            NewsArticle unknown = article(1L, "https://a.example.com/1", "제목", null);

            assertThat(unknown.getPublishedAt()).isNull();
            assertThat(unknown.getPublishedAtStatus()).isEqualTo(PublicationTimeStatus.UNKNOWN);
            assertThat(
                            unknown.isWithinVerifiedRange(
                                    COLLECTED.minus(Duration.ofDays(1)),
                                    COLLECTED.plus(Duration.ofDays(1))))
                    .isFalse();
            assertThat(unknown.orderingInstant()).isEqualTo(COLLECTED);
        }

        @Test
        @DisplayName("확인 여부와 시각이 어긋나면 만들 수 없다")
        void 불일치_거부() {
            CanonicalNewsUrl url = CanonicalNewsUrl.normalize("https://a.example.com/1");

            assertThatThrownBy(
                            () ->
                                    NewsArticle.collected(
                                            NewsSourceType.RSS,
                                            1L,
                                            null,
                                            "https://a.example.com/1",
                                            url,
                                            "제목",
                                            "ko",
                                            null,
                                            PublicationTimeStatus.VERIFIED,
                                            COLLECTED,
                                            null,
                                            url.value(),
                                            "hash",
                                            "run-1",
                                            true))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("정규화된 URL (spec FR-011)")
    class Canonical {

        @Test
        @DisplayName("scheme·host 소문자화, 기본 포트·fragment 제거, 후행 슬래시 정리")
        void 기본_정규화() {
            assertThat(
                            CanonicalNewsUrl.normalize(
                                            "HTTPS://News.Example.COM:443/Economy/1001/#top")
                                    .value())
                    .isEqualTo("https://news.example.com/Economy/1001");
        }

        @Test
        @DisplayName("빈 path는 슬래시로 통일하고 기본이 아닌 포트는 보존한다")
        void 포트와_path() {
            assertThat(CanonicalNewsUrl.normalize("https://news.example.com").value())
                    .isEqualTo("https://news.example.com/");
            assertThat(CanonicalNewsUrl.normalize("https://news.example.com:8443/a").value())
                    .isEqualTo("https://news.example.com:8443/a");
        }

        @Test
        @DisplayName("추적 parameter만 지우고 나머지 query는 정렬해 보존한다")
        void query_정규화() {
            assertThat(
                            CanonicalNewsUrl.normalize(
                                            "https://n.example.com/a?utm_source=x&b=2&a=1&gclid=z")
                                    .value())
                    .isEqualTo("https://n.example.com/a?a=1&b=2");
            assertThat(CanonicalNewsUrl.normalize("https://n.example.com/a?utm_source=x").value())
                    .isEqualTo("https://n.example.com/a");
        }

        @Test
        @DisplayName("같은 원문의 URL 변형은 같은 값으로 합쳐진다")
        void 변형_병합() {
            CanonicalNewsUrl one =
                    CanonicalNewsUrl.normalize("https://news.example.com/a/?utm_campaign=q#x");
            CanonicalNewsUrl two = CanonicalNewsUrl.normalize("HTTPS://NEWS.example.com:443/a");

            assertThat(one).isEqualTo(two);
        }

        @Test
        @DisplayName("정규화할 수 없는 URL은 거부해 수집에서 빠지게 한다")
        void 정규화_불가() {
            assertThatThrownBy(() -> CanonicalNewsUrl.normalize("not a url"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CanonicalNewsUrl.normalize("ftp://example.com/a"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CanonicalNewsUrl.normalize("/relative/path"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("결정론적 외부 식별자 (spec FR-025)")
    class StableIds {

        @Test
        @DisplayName("같은 canonical URL은 언제나 같은 기사 식별자를 만든다")
        void 기사_식별자_재현() {
            CanonicalNewsUrl url =
                    CanonicalNewsUrl.normalize("https://news.example.com/economy/1001");

            assertThat(NewsArticleId.of(url)).isEqualTo(NewsArticleId.of(url));
        }

        @Test
        @DisplayName("기사 식별자는 계약 예시와 같은 값이다")
        void 계약_예시와_일치() {
            CanonicalNewsUrl url =
                    CanonicalNewsUrl.normalize("https://news.example.com/economy/1001");

            assertThat(url.value()).isEqualTo("https://news.example.com/economy/1001");
            assertThat(NewsArticleId.of(url).value())
                    .as("contracts/examples/current-ready.json의 articleId")
                    .isEqualTo("jzxp4sxkitcyjow6mtiywps42u3kr6co");
        }

        @Test
        @DisplayName("묶음 식별자는 대표 기사와 묶음 규칙 버전에서 나온다")
        void 묶음_식별자() {
            NewsArticleId representative =
                    NewsArticleId.of(
                            CanonicalNewsUrl.normalize("https://news.example.com/economy/1001"));

            assertThat(NewsDigestId.of(representative, GROUPING_RULE).value())
                    .as("contracts/examples/current-ready.json의 digestId")
                    .isEqualTo("2356p2tnowwutogqkjlretuvevwid7lg");
            assertThat(NewsDigestId.of(representative, "news-grouping-v2"))
                    .as("규칙 버전이 다르면 다른 묶음으로 식별된다")
                    .isNotEqualTo(NewsDigestId.of(representative, GROUPING_RULE));
        }

        @Test
        @DisplayName("식별자는 내부 순번을 노출하지 않는 고정 형식이다")
        void 형식() {
            assertThatThrownBy(() -> NewsArticleId.from("1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> NewsDigestId.from("ND-20260910-000001"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("허가 이력 (spec FR-015)")
    class PermissionHistory {

        @Test
        @DisplayName("요청은 PENDING, 회신은 ALLOWED 또는 DENIED로만 기록한다")
        void 사건과_상태() {
            assertThat(
                            NewsPublisherPermissionHistory.requested(1L, COLLECTED, "메일함")
                                    .permissionStatus())
                    .isEqualTo(PermissionStatus.PENDING);
            assertThat(
                            NewsPublisherPermissionHistory.denied(1L, COLLECTED, "회신")
                                    .permissionStatus())
                    .isEqualTo(PermissionStatus.DENIED);
        }

        @Test
        @DisplayName("허가 회신에는 허용 필드가 있어야 하고 불허 회신에는 없어야 한다")
        void 허용_필드() {
            NewsPublisherPermissionHistory allowed =
                    NewsPublisherPermissionHistory.allowed(
                            1L,
                            Set.of(NewsAllowedField.TITLE, NewsAllowedField.ORIGINAL_URL),
                            COLLECTED,
                            "회신");

            assertThat(allowed.allows(NewsAllowedField.TITLE)).isTrue();
            assertThat(allowed.allows(NewsAllowedField.PUBLISHED_AT)).isFalse();

            assertThatThrownBy(
                            () ->
                                    NewsPublisherPermissionHistory.allowed(
                                            1L, Set.of(), COLLECTED, "회신"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("허용 필드에 기사 본문이 없다")
        void 본문은_허용_대상이_아니다() {
            assertThat(NewsAllowedField.values())
                    .containsExactlyInAnyOrder(
                            NewsAllowedField.TITLE,
                            NewsAllowedField.PUBLISHER,
                            NewsAllowedField.PUBLISHED_AT,
                            NewsAllowedField.ORIGINAL_URL);
        }
    }

    @Nested
    @DisplayName("공식 채널과 허가 상태 (spec FR-009·FR-016)")
    class Publisher {

        @Test
        @DisplayName("등록 도메인의 HTTPS 채널만 등록할 수 있다")
        void 공식_채널() {
            NewsPublisher publisher =
                    NewsPublisher.register(
                            "example-press",
                            "예시경제",
                            "ko",
                            "example.co.kr",
                            "https://rss.example.co.kr/economy.xml",
                            true);

            assertThat(publisher.getPermissionStatus()).isEqualTo(PermissionStatus.PENDING);
            assertThat(publisher.isPubliclyVisible()).isFalse();
            assertThat(publisher.ownsHost("rss.example.co.kr")).isTrue();
            assertThat(publisher.ownsHost("evil.com")).isFalse();

            assertThatThrownBy(
                            () ->
                                    NewsPublisher.register(
                                            "example-press",
                                            "예시경제",
                                            "ko",
                                            "example.co.kr",
                                            "https://aggregator.example.com/feed.xml",
                                            true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("허가 회신을 반영해도 채널 설정은 그대로 남는다")
        void 허가_반영() {
            NewsPublisher allowed =
                    NewsPublisher.register(
                                    "example-press",
                                    "예시경제",
                                    "ko",
                                    "example.co.kr",
                                    "https://rss.example.co.kr/economy.xml",
                                    true)
                            .withPermission(PermissionStatus.ALLOWED, COLLECTED);

            assertThat(allowed.isPubliclyVisible()).isTrue();
            assertThat(allowed.getPermissionUpdatedAt()).isEqualTo(COLLECTED);
            assertThat(allowed.getRssUrl()).isEqualTo("https://rss.example.co.kr/economy.xml");
        }
    }

    @Nested
    @DisplayName("묶음 대표와 허가 투영 (CHK035·CHK036·CHK052)")
    class DigestProjection {

        private NewsDigest mixedDigest() {
            NewsArticle first =
                    article(
                            1L,
                            "https://a.example.com/1",
                            "가 매체 제목",
                            Instant.parse("2026-09-10T04:00:00Z"));
            NewsArticle second =
                    article(
                            2L,
                            "https://b.example.com/2",
                            "나 매체 제목",
                            Instant.parse("2026-09-10T04:20:00Z"));
            return NewsDigest.of(List.of(first, second), "요약", GROUPING_RULE, IMPORTANCE_RULE);
        }

        @Test
        @DisplayName("멤버의 공통 주제 근거는 대표와 공유하는 주제 중 사전순 첫 번째다")
        void 공통_주제_근거() {
            NewsArticle first =
                    stored(
                            11L,
                            1L,
                            "https://a.example.com/1",
                            "가 매체 제목",
                            Instant.parse("2026-09-10T04:00:00Z"));
            NewsArticle second =
                    stored(
                            12L,
                            2L,
                            "https://b.example.com/2",
                            "나 매체 제목",
                            Instant.parse("2026-09-10T04:20:00Z"));
            NewsDigest digest =
                    NewsDigest.of(
                            List.of(first, second),
                            "요약",
                            GROUPING_RULE,
                            IMPORTANCE_RULE,
                            java.util.Map.of(
                                    first.getId(),
                                    java.util.Set.of("semiconductor", "export"),
                                    second.getId(),
                                    java.util.Set.of("export", "semiconductor", "won")));

            NewsArticle representative = digest.getRepresentative();
            NewsArticle member =
                    digest.getArticles().stream()
                            .filter(a -> !a.getId().equals(representative.getId()))
                            .findFirst()
                            .orElseThrow();

            assertThat(digest.sharedTopicKeyWith(member))
                    .as("근거가 없으면 왜 같은 사건으로 묶였는지 나중에 따질 수 없다")
                    .isEqualTo("export");
            assertThat(digest.sharedTopicKeyWith(representative))
                    .as("대표 기사는 자신과 비교할 대상이 아니다")
                    .isNull();
        }

        @Test
        @DisplayName("주제 근거 없이 만든 묶음은 공통 주제를 비워 둔다")
        void 공통_주제_근거_없음() {
            NewsDigest digest = mixedDigest();
            NewsArticle member =
                    digest.getArticles().stream()
                            .filter(
                                    a ->
                                            !a.getCanonicalUrl()
                                                    .equals(
                                                            digest.getRepresentative()
                                                                    .getCanonicalUrl()))
                            .findFirst()
                            .orElseThrow();

            assertThat(digest.sharedTopicKeyWith(member)).isNull();
        }

        @Test
        @DisplayName("대표는 검증 발행 시각이 가장 빠른 기사다")
        void 대표_선정() {
            NewsDigest digest = mixedDigest();

            assertThat(digest.getRepresentative().getPublisherId()).isEqualTo(1L);
            assertThat(digest.getRepresentativeTitle()).isEqualTo("가 매체 제목");
            assertThat(digest.distinctPublisherCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("대표가 불허되면 남은 허가 기사로 대표와 요약을 다시 만든다")
        void 대표_불허() {
            NewsDigest digest = mixedDigest();

            Optional<NewsDigest.Projection> projected =
                    digest.projectFor(a -> a.getPublisherId() == 2L, SUMMARY);

            assertThat(projected).isPresent();
            NewsDigest.Projection projection = projected.get();
            assertThat(projection.representative().getPublisherId()).isEqualTo(2L);
            assertThat(projection.representativeTitle()).isEqualTo("나 매체 제목");
            assertThat(projection.factualSummary())
                    .as("불허 매체의 제목이 요약에 새지 않는다")
                    .doesNotContain("가 매체 제목")
                    .contains("나 매체 제목");
            assertThat(projection.distinctPublisherCount()).isEqualTo(1);
            assertThat(projection.publisherIds()).containsExactly(2L);
        }

        @Test
        @DisplayName("허가 매체 수만 세고 저장된 값은 그대로 둔다")
        void 저장값_보존() {
            NewsDigest digest = mixedDigest();

            digest.projectFor(a -> a.getPublisherId() == 2L, SUMMARY);

            assertThat(digest.getRepresentative().getPublisherId())
                    .as("허가 변경이 저장된 묶음을 덮어쓰지 않는다")
                    .isEqualTo(1L);
            assertThat(digest.distinctPublisherCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("허가된 기사가 남지 않으면 그 묶음을 표시하지 않는다")
        void 허가_기사_없음() {
            NewsDigest digest = mixedDigest();

            assertThat(digest.projectFor(a -> false, SUMMARY)).isEmpty();
        }

        @Test
        @DisplayName("남은 기사로 요약 조건을 못 맞추면 그 묶음을 표시하지 않는다")
        void 요약_근거_부족() {
            NewsDigest digest = mixedDigest();

            assertThat(digest.projectFor(a -> true, NO_EVIDENCE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("수집 상태와 수집 위치 (spec FR-013·FR-022)")
    class CollectionState {

        private NewsCollectionState running() {
            return NewsCollectionState.restore(
                            "rss:example-press",
                            NewsCollectionStatus.IDLE,
                            "cursor-1",
                            null,
                            Instant.parse("2026-09-10T05:00:00Z"),
                            null,
                            0,
                            null,
                            null,
                            null)
                    .claimed("instance-1", COLLECTED, Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("점유는 만료 시각을 가지며 만료 전에는 다른 인스턴스가 가져가지 못한다")
        void 점유() {
            NewsCollectionState claimed = running();

            assertThat(claimed.getStatus()).isEqualTo(NewsCollectionStatus.RUNNING);
            assertThat(claimed.getLeaseOwner()).isEqualTo("instance-1");
            assertThat(claimed.isDue(COLLECTED.plus(Duration.ofMinutes(1)))).isFalse();
            assertThat(claimed.isDue(COLLECTED.plus(Duration.ofMinutes(16)))).isTrue();
        }

        @Test
        @DisplayName("정리 작업도 수집 뉴스원과 같은 점유 규칙을 쓴다")
        void 정리_작업_점유() {
            NewsCollectionState cleanup =
                    NewsCollectionState.idle(NewsCollectionState.RETENTION_CLEANUP_SOURCE_KEY);

            assertThat(cleanup.isDue(COLLECTED)).isTrue();
            assertThat(
                            cleanup.claimed("instance-1", COLLECTED, Duration.ofMinutes(15))
                                    .isDue(COLLECTED))
                    .isFalse();
        }

        @Test
        @DisplayName("성공하면 수집 위치와 마지막 성공 시각이 함께 전진한다")
        void 성공() {
            NewsCollectionState done =
                    running()
                            .succeeded(
                                    "cursor-2", COLLECTED, COLLECTED.plus(Duration.ofMinutes(15)));

            assertThat(done.getCursor()).isEqualTo("cursor-2");
            assertThat(done.getLastSuccessAt()).isEqualTo(COLLECTED);
            assertThat(done.getRetryCount()).isZero();
            assertThat(done.getLeaseOwner()).isNull();
        }

        @Test
        @DisplayName("304는 성공으로 기록하되 수집 위치를 건드리지 않는다")
        void 변경_없음() {
            NewsCollectionState done =
                    running().notModified(COLLECTED, COLLECTED.plus(Duration.ofMinutes(15)));

            assertThat(done.getStatus()).isEqualTo(NewsCollectionStatus.SUCCEEDED);
            assertThat(done.getCursor()).isEqualTo("cursor-1");
            assertThat(done.getLastSuccessAt()).isEqualTo(COLLECTED);
        }

        @Test
        @DisplayName("결과 상한에 걸리면 수집 위치도 성공 시각도 전진하지 않는다")
        void 부분_수집() {
            NewsCollectionState incomplete =
                    running().incomplete(COLLECTED, COLLECTED.plus(Duration.ofMinutes(15)));

            assertThat(incomplete.getStatus()).isEqualTo(NewsCollectionStatus.INCOMPLETE);
            assertThat(incomplete.getCursor()).as("다음 회차가 같은 구간을 재시도한다").isEqualTo("cursor-1");
            assertThat(incomplete.getLastSuccessAt())
                    .as("부분 수집을 완전 수집으로 기록하지 않는다")
                    .isEqualTo(Instant.parse("2026-09-10T05:00:00Z"));
            assertThat(incomplete.getErrorCategory())
                    .isEqualTo(NewsCollectionState.RESULT_CAP_REACHED);
            assertThat(NewsCollectionStatus.INCOMPLETE.advancesCursor()).isFalse();
            assertThat(NewsCollectionStatus.INCOMPLETE.advancesLastSuccess()).isFalse();
        }

        @Test
        @DisplayName("실패는 재시도 횟수를 올리고 수집 위치를 유지한다")
        void 실패() {
            NewsCollectionState failed =
                    running()
                            .failed(
                                    NewsCollectionStatus.RATE_LIMITED,
                                    "RATE_LIMITED",
                                    COLLECTED,
                                    COLLECTED.plus(Duration.ofMinutes(15)));

            assertThat(failed.getCursor()).isEqualTo("cursor-1");
            assertThat(failed.getRetryCount()).isEqualTo(1);
            assertThat(failed.getLastSuccessAt()).isEqualTo(Instant.parse("2026-09-10T05:00:00Z"));

            assertThatThrownBy(
                            () ->
                                    running()
                                            .failed(
                                                    NewsCollectionStatus.SUCCEEDED,
                                                    "X",
                                                    COLLECTED,
                                                    COLLECTED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("규칙 버전 (원칙 VIII)")
    class RuleVersion {

        @Test
        @DisplayName("유효 구간 안에서만 적용된다")
        void 유효_구간() {
            NewsRuleVersion rule =
                    new NewsRuleVersion(
                            GROUPING_RULE,
                            NewsRuleVersion.RuleType.GROUPING,
                            java.util.Map.of("titleJaccardThreshold", 0.60),
                            Instant.parse("2026-09-10T00:00:00Z"),
                            null);

            assertThat(rule.isEffectiveAt(Instant.parse("2026-09-09T23:59:59Z"))).isFalse();
            assertThat(rule.isEffectiveAt(COLLECTED)).isTrue();
        }
    }
}
