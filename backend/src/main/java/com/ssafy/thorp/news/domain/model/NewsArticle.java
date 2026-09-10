package com.ssafy.thorp.news.domain.model;

import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsArticleId;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 기사 메타데이터. 순수 도메인 모델이며 JPA Entity({@code NewsArticleJpaEntity})와 분리한다.
 *
 * <p><b>본문을 담지 않는다.</b> 본문·HTML·이미지·RSS description에 해당하는 필드가 없다. 저장·표시·재배포하지 않으며 원문은 매체 링크로만
 * 연결한다(spec FR-002).
 *
 * <p>발행 시각은 확인된 경우에만 값이 있다. timezone이 포함되고 정상 파싱되며 수집 시각보다 5분 넘게 미래가 아닌 시각만 {@code VERIFIED}이고, 그
 * 밖에는 {@code UNKNOWN}과 null이다. 수집 시각이나 GDELT 기록 시각으로 대체하지 않는다(spec FR-012).
 *
 * <p>{@code id}는 저장 전에는 null이다. 외부 식별자 {@code articleId}는 canonical URL에서 유도하므로 저장 전에도 정해진다(spec
 * FR-025).
 */
public class NewsArticle {

    /** 발행 시각이 수집 시각보다 이만큼 넘게 미래이면 확인된 시각으로 보지 않는다. */
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final Long id;
    private final NewsArticleId articleId;
    private final NewsSourceType sourceType;
    private final Long publisherId;
    private final String sourceItemId;
    private final String originalUrl;
    private final CanonicalNewsUrl canonicalUrl;
    private final String title;
    private final String language;
    private final Instant publishedAt;
    private final PublicationTimeStatus publishedAtStatus;
    private final Instant collectedAt;
    private final Instant gdeltRecordedAt;
    private final String duplicateKey;
    private final String sourcePayloadHash;
    private final String ingestRunId;
    private final boolean displayEligible;

    private NewsArticle(
            Long id,
            NewsArticleId articleId,
            NewsSourceType sourceType,
            Long publisherId,
            String sourceItemId,
            String originalUrl,
            CanonicalNewsUrl canonicalUrl,
            String title,
            String language,
            Instant publishedAt,
            PublicationTimeStatus publishedAtStatus,
            Instant collectedAt,
            Instant gdeltRecordedAt,
            String duplicateKey,
            String sourcePayloadHash,
            String ingestRunId,
            boolean displayEligible) {
        this.id = id;
        this.articleId = Objects.requireNonNull(articleId, "articleId must not be null");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        this.publisherId = Objects.requireNonNull(publisherId, "publisherId must not be null");
        this.sourceItemId = sourceItemId;
        this.originalUrl = requireText(originalUrl, "originalUrl");
        this.canonicalUrl = Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        this.title = requireText(title, "title");
        this.language = requireText(language, "language");
        this.publishedAtStatus =
                Objects.requireNonNull(publishedAtStatus, "publishedAtStatus must not be null");
        this.collectedAt = Objects.requireNonNull(collectedAt, "collectedAt must not be null");
        this.publishedAt = requireConsistentPublicationTime(publishedAt, publishedAtStatus);
        this.gdeltRecordedAt = gdeltRecordedAt;
        this.duplicateKey = requireText(duplicateKey, "duplicateKey");
        this.sourcePayloadHash = requireText(sourcePayloadHash, "sourcePayloadHash");
        this.ingestRunId = requireText(ingestRunId, "ingestRunId");
        this.displayEligible = displayEligible;
    }

    /**
     * 수집한 기사를 만든다. 발행 시각 확인 여부는 {@link #classifyPublicationTime(Instant, Instant)}가 판정한 값을 넘긴다.
     *
     * <p>표시 가능 여부({@code displayEligible})는 요약 근거가 충분한지에 달려 있고 그 판단은 application 계층의 요약 규칙이 한다.
     */
    public static NewsArticle collected(
            NewsSourceType sourceType,
            Long publisherId,
            String sourceItemId,
            String originalUrl,
            CanonicalNewsUrl canonicalUrl,
            String title,
            String language,
            Instant publishedAt,
            PublicationTimeStatus publishedAtStatus,
            Instant collectedAt,
            Instant gdeltRecordedAt,
            String duplicateKey,
            String sourcePayloadHash,
            String ingestRunId,
            boolean displayEligible) {
        return new NewsArticle(
                null,
                NewsArticleId.of(canonicalUrl),
                sourceType,
                publisherId,
                sourceItemId,
                originalUrl,
                canonicalUrl,
                title,
                language,
                publishedAt,
                publishedAtStatus,
                collectedAt,
                gdeltRecordedAt,
                duplicateKey,
                sourcePayloadHash,
                ingestRunId,
                displayEligible);
    }

    /** 저장된 행을 복원한다. */
    public static NewsArticle restore(
            Long id,
            NewsArticleId articleId,
            NewsSourceType sourceType,
            Long publisherId,
            String sourceItemId,
            String originalUrl,
            CanonicalNewsUrl canonicalUrl,
            String title,
            String language,
            Instant publishedAt,
            PublicationTimeStatus publishedAtStatus,
            Instant collectedAt,
            Instant gdeltRecordedAt,
            String duplicateKey,
            String sourcePayloadHash,
            String ingestRunId,
            boolean displayEligible) {
        return new NewsArticle(
                Objects.requireNonNull(id, "id must not be null"),
                articleId,
                sourceType,
                publisherId,
                sourceItemId,
                originalUrl,
                canonicalUrl,
                title,
                language,
                publishedAt,
                publishedAtStatus,
                collectedAt,
                gdeltRecordedAt,
                duplicateKey,
                sourcePayloadHash,
                ingestRunId,
                displayEligible);
    }

    /**
     * 뉴스원이 준 발행 시각을 확인 여부로 판정한다(spec FR-012).
     *
     * <p>시각이 없거나(파싱 실패·timezone 누락으로 호출부가 null을 넘긴 경우) 수집 시각보다 5분 넘게 미래이면 {@code UNKNOWN}이다. 이 판정은
     * 값을 만들어내지 않으며 대체 시각을 고르지 않는다.
     */
    public static PublicationTimeStatus classifyPublicationTime(
            Instant parsedPublishedAt, Instant collectedAt) {
        Objects.requireNonNull(collectedAt, "collectedAt must not be null");
        if (parsedPublishedAt == null) {
            return PublicationTimeStatus.UNKNOWN;
        }
        if (parsedPublishedAt.isAfter(collectedAt.plus(MAX_FUTURE_SKEW))) {
            return PublicationTimeStatus.UNKNOWN;
        }
        return PublicationTimeStatus.VERIFIED;
    }

    /** 과거 사례 구간에 연결할 수 있는지 여부. 발행 시각이 확인되고 구간 안이어야 한다(spec FR-006). */
    public boolean isWithinVerifiedRange(Instant from, Instant to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (!publishedAtStatus.isUsableForHistory()) {
            return false;
        }
        return !publishedAt.isBefore(from) && !publishedAt.isAfter(to);
    }

    /** 현재 뉴스 정렬에서 최근성 비교에 쓰는 시각. 확인된 발행 시각이 없으면 수집 시각을 쓴다(spec FR-004). */
    public Instant orderingInstant() {
        return publishedAtStatus.isUsableForHistory() ? publishedAt : collectedAt;
    }

    private static Instant requireConsistentPublicationTime(
            Instant publishedAt, PublicationTimeStatus status) {
        if (status == PublicationTimeStatus.VERIFIED) {
            return Objects.requireNonNull(publishedAt, "VERIFIED이면 publishedAt이 있어야 한다");
        }
        if (publishedAt != null) {
            throw new IllegalArgumentException("UNKNOWN이면 publishedAt은 비어 있어야 한다");
        }
        return null;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없다");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public NewsArticleId getArticleId() {
        return articleId;
    }

    public NewsSourceType getSourceType() {
        return sourceType;
    }

    public Long getPublisherId() {
        return publisherId;
    }

    public String getSourceItemId() {
        return sourceItemId;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public CanonicalNewsUrl getCanonicalUrl() {
        return canonicalUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getLanguage() {
        return language;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public PublicationTimeStatus getPublishedAtStatus() {
        return publishedAtStatus;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getGdeltRecordedAt() {
        return gdeltRecordedAt;
    }

    public String getDuplicateKey() {
        return duplicateKey;
    }

    public String getSourcePayloadHash() {
        return sourcePayloadHash;
    }

    public String getIngestRunId() {
        return ingestRunId;
    }

    public boolean isDisplayEligible() {
        return displayEligible;
    }
}
