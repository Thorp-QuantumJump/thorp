package com.ssafy.thorp.news.domain.model;

import com.ssafy.thorp.news.domain.vo.NewsDigestId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 같은 사건의 표시 묶음. 순수 도메인 모델이다.
 *
 * <p>여기 저장된 대표 기사·대표 제목·사실 요약·매체 수는 <b>전체 멤버 기준</b> 값이다. 공개 응답은 이 값을 그대로 쓰지 않고 허가된 기사만으로 조회 시점에 다시
 * 계산한다({@link #projectFor}). 허가 상태가 바뀔 때 저장된 값을 덮어쓰지 않으므로 허가가 복원되면 원래 구성이 그대로 살아난다(spec FR-016).
 *
 * <p>요약은 대표 제목과 메타데이터로 만든 한 문장이며 본문을 읽어 만들지 않는다. 요약 근거가 부족한 기사는 애초에 멤버가 되지 않는다(spec FR-003).
 */
public class NewsDigest {

    private final Long id;
    private final NewsDigestId digestId;
    private final List<NewsArticle> articles;
    private final NewsArticle representative;
    private final String representativeTitle;
    private final String factualSummary;
    private final String groupingRuleVersion;
    private final String importanceRuleVersion;

    /** 기사별로 연결된 주제 키. 묶음 근거를 남길 때만 쓰고, 저장된 행을 복원할 때는 비어 있다. */
    private final java.util.Map<Long, Set<String>> topicKeysByArticleId;

    private NewsDigest(
            Long id,
            NewsDigestId digestId,
            List<NewsArticle> articles,
            NewsArticle representative,
            String representativeTitle,
            String factualSummary,
            String groupingRuleVersion,
            String importanceRuleVersion,
            java.util.Map<Long, Set<String>> topicKeysByArticleId) {
        this.id = id;
        this.digestId = Objects.requireNonNull(digestId, "digestId must not be null");
        this.articles = List.copyOf(Objects.requireNonNull(articles, "articles must not be null"));
        if (this.articles.isEmpty()) {
            throw new IllegalArgumentException("묶음에는 기사가 하나 이상 있어야 한다");
        }
        this.representative =
                Objects.requireNonNull(representative, "representative must not be null");
        this.representativeTitle = requireText(representativeTitle, "representativeTitle");
        this.factualSummary = requireText(factualSummary, "factualSummary");
        this.groupingRuleVersion = requireText(groupingRuleVersion, "groupingRuleVersion");
        this.importanceRuleVersion = requireText(importanceRuleVersion, "importanceRuleVersion");
        this.topicKeysByArticleId =
                topicKeysByArticleId == null
                        ? java.util.Map.of()
                        : java.util.Map.copyOf(topicKeysByArticleId);
    }

    /**
     * 멤버 기사로 묶음을 만든다. 대표 기사는 {@link #selectRepresentative}가 정하고 요약은 호출부의 요약 규칙이 만든다.
     *
     * <p>묶음 식별자는 대표 기사와 묶음 규칙 버전에서 유도하므로 같은 입력이면 같은 값이 나온다(spec FR-025).
     */
    public static NewsDigest of(
            List<NewsArticle> articles,
            String factualSummary,
            String groupingRuleVersion,
            String importanceRuleVersion) {
        return of(
                articles,
                factualSummary,
                groupingRuleVersion,
                importanceRuleVersion,
                java.util.Map.of());
    }

    /**
     * 주제 근거와 함께 새 묶음을 만든다.
     *
     * @param topicKeysByArticleId 기사 식별자별 연결 주제 키. 대표 기사와 무엇을 공유해 묶였는지 남기는 데 쓴다(원칙 VIII).
     */
    public static NewsDigest of(
            List<NewsArticle> articles,
            String factualSummary,
            String groupingRuleVersion,
            String importanceRuleVersion,
            java.util.Map<Long, Set<String>> topicKeysByArticleId) {
        NewsArticle representative =
                selectRepresentative(articles)
                        .orElseThrow(() -> new IllegalArgumentException("대표 기사를 정할 수 없다"));
        return new NewsDigest(
                null,
                NewsDigestId.of(representative.getArticleId(), groupingRuleVersion),
                articles,
                representative,
                representative.getTitle(),
                factualSummary,
                groupingRuleVersion,
                importanceRuleVersion,
                topicKeysByArticleId);
    }

    /** 저장된 행을 복원한다. */
    public static NewsDigest restore(
            Long id,
            NewsDigestId digestId,
            List<NewsArticle> articles,
            NewsArticle representative,
            String representativeTitle,
            String factualSummary,
            String groupingRuleVersion,
            String importanceRuleVersion) {
        return new NewsDigest(
                Objects.requireNonNull(id, "id must not be null"),
                digestId,
                articles,
                representative,
                representativeTitle,
                factualSummary,
                groupingRuleVersion,
                importanceRuleVersion,
                java.util.Map.of());
    }

    /**
     * 이 기사가 대표 기사와 공유하는 주제 키.
     *
     * <p>묶음 판정의 세 조건 중 "공통 주제 1개 이상"이 왜 성립했는지를 남기는 값이다. 여럿이면 사전순 첫 번째를 골라 같은 입력에서 늘 같은 값이 나오게 한다.
     * 대표 기사 자신과 근거를 모르는 경우에는 값을 두지 않는다.
     */
    public String sharedTopicKeyWith(NewsArticle member) {
        if (member.getId() == null || member.getId().equals(representative.getId())) {
            return null;
        }
        Set<String> representativeTopics = topicKeysByArticleId.get(representative.getId());
        Set<String> memberTopics = topicKeysByArticleId.get(member.getId());
        if (representativeTopics == null || memberTopics == null) {
            return null;
        }
        return representativeTopics.stream()
                .filter(memberTopics::contains)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    /**
     * 대표 기사를 정한다. 검증된 발행 시각이 가장 빠른 기사, 동률이면 canonical URL 오름차순이다.
     *
     * <p>발행 시각이 확인되지 않은 기사는 확인된 기사보다 뒤에 둔다. 규칙이 결정론적이라 같은 입력이면 항상 같은 대표가 나온다(spec FR-004).
     */
    public static Optional<NewsArticle> selectRepresentative(List<NewsArticle> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .min(
                        Comparator.comparing(
                                        (NewsArticle a) ->
                                                a.getPublishedAtStatus().isUsableForHistory()
                                                        ? 0
                                                        : 1)
                                .thenComparing(
                                        NewsArticle::orderingInstant,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(a -> a.getCanonicalUrl().value()));
    }

    /**
     * 허가된 기사만으로 이 묶음을 다시 계산한다(spec FR-016, 체크리스트 CHK035·CHK036·CHK052).
     *
     * <p>대표 기사가 미허가면 남은 허가 기사에 같은 대표 선정 규칙을 다시 적용한다. 매체 수·최근성도 허가 기사만으로 센다. 허가된 기사가 하나도 남지 않으면 이
     * 묶음을 표시하지 않는다는 뜻으로 빈 값을 돌려준다.
     *
     * <p>이 메서드는 저장된 값을 바꾸지 않는다. 허가가 다시 허용으로 바뀌면 원래 구성이 그대로 쓰인다.
     *
     * @param isPermitted 기사의 매체가 공개 노출 가능한지 판정한다.
     * @param summarize 남은 허가 기사와 대표 기사로 사실 요약을 다시 만든다. 근거가 부족하면 빈 값을 돌려준다.
     */
    public Optional<Projection> projectFor(
            Predicate<NewsArticle> isPermitted, SummaryRule summarize) {
        Objects.requireNonNull(isPermitted, "isPermitted must not be null");
        Objects.requireNonNull(summarize, "summarize must not be null");

        List<NewsArticle> permitted = articles.stream().filter(isPermitted).toList();
        if (permitted.isEmpty()) {
            return Optional.empty();
        }
        Optional<NewsArticle> newRepresentative = selectRepresentative(permitted);
        if (newRepresentative.isEmpty()) {
            return Optional.empty();
        }
        NewsArticle head = newRepresentative.get();
        Optional<String> summary = summarize.summarize(head, permitted);
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new Projection(
                        digestId,
                        head,
                        head.getTitle(),
                        summary.get(),
                        permitted,
                        distinctPublisherCount(permitted),
                        firstPublishedAt(permitted),
                        latestPublishedAt(permitted),
                        importanceRuleVersion));
    }

    /** 전체 멤버 기준 서로 다른 매체 수. 저장용 값이다. */
    public int distinctPublisherCount() {
        return distinctPublisherCount(articles);
    }

    /** 전체 멤버 기준 최초 검증 발행 시각. 확인된 시각이 없으면 null이다. */
    public Instant firstPublishedAt() {
        return firstPublishedAt(articles);
    }

    /** 전체 멤버 기준 최근 검증 발행 시각. 확인된 시각이 없으면 null이다. */
    public Instant latestPublishedAt() {
        return latestPublishedAt(articles);
    }

    private static int distinctPublisherCount(List<NewsArticle> members) {
        return (int) members.stream().map(NewsArticle::getPublisherId).distinct().count();
    }

    private static Instant firstPublishedAt(List<NewsArticle> members) {
        return verifiedTimes(members).min(Comparator.naturalOrder()).orElse(null);
    }

    private static Instant latestPublishedAt(List<NewsArticle> members) {
        return verifiedTimes(members).max(Comparator.naturalOrder()).orElse(null);
    }

    private static java.util.stream.Stream<Instant> verifiedTimes(List<NewsArticle> members) {
        return members.stream()
                .filter(a -> a.getPublishedAtStatus().isUsableForHistory())
                .map(NewsArticle::getPublishedAt);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없다");
        }
        return value;
    }

    /** 사실 요약을 만드는 규칙. 제목과 메타데이터만 쓰며 근거가 부족하면 빈 값을 돌려준다(spec FR-003). */
    @FunctionalInterface
    public interface SummaryRule {
        Optional<String> summarize(NewsArticle representative, List<NewsArticle> members);
    }

    /**
     * 조회 시점에 계산한 표시용 묶음. 저장 상태가 아니라 응답에 쓰는 값이다.
     *
     * <p>공개 조회에서는 여기 담긴 모든 값이 허가된 기사만으로 계산된 것이다.
     */
    public record Projection(
            NewsDigestId digestId,
            NewsArticle representative,
            String representativeTitle,
            String factualSummary,
            List<NewsArticle> articles,
            int distinctPublisherCount,
            Instant firstPublishedAt,
            Instant latestPublishedAt,
            String importanceRuleVersion) {

        public Projection {
            articles = List.copyOf(articles);
        }

        /** 검증된 발행 시각을 가진 기사가 있는지. 정렬 tuple의 두 번째 열쇠다(spec FR-004). */
        public boolean hasVerifiedPublishedAt() {
            return latestPublishedAt != null;
        }

        /** 이 묶음이 다루는 매체 식별자. */
        public Set<Long> publisherIds() {
            return articles.stream()
                    .map(NewsArticle::getPublisherId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public Long getId() {
        return id;
    }

    public NewsDigestId getDigestId() {
        return digestId;
    }

    public List<NewsArticle> getArticles() {
        return articles;
    }

    public NewsArticle getRepresentative() {
        return representative;
    }

    public String getRepresentativeTitle() {
        return representativeTitle;
    }

    public String getFactualSummary() {
        return factualSummary;
    }

    public String getGroupingRuleVersion() {
        return groupingRuleVersion;
    }

    public String getImportanceRuleVersion() {
        return importanceRuleVersion;
    }
}
