package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.model.NewsRuleVersion;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.NewsArticleId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 도메인 모델과 JPA Entity의 변환. 정적 메서드만 둔다.
 *
 * <p>도메인은 프레임워크를 모르고 Entity는 불변식을 모른다. 변환을 한곳에 모아 두 규칙이 섞이지 않게 한다.
 *
 * <p>본문에 해당하는 값은 어느 방향으로도 옮기지 않는다. 양쪽 모두 그런 필드를 갖고 있지 않다(spec FR-002).
 */
public final class NewsPersistenceMapper {

    private NewsPersistenceMapper() {}

    public static NewsPublisherJpaEntity toEntity(NewsPublisher publisher) {
        return new NewsPublisherJpaEntity(
                publisher.getId(),
                publisher.getPublisherKey(),
                publisher.getDisplayName(),
                publisher.getLanguage(),
                publisher.getRegisteredDomain(),
                publisher.getRssUrl(),
                publisher.getPermissionStatus(),
                publisher.getPermissionUpdatedAt(),
                publisher.isEnabled(),
                publisher.getEtag(),
                publisher.getLastModified());
    }

    public static NewsPublisher toDomain(NewsPublisherJpaEntity entity) {
        return NewsPublisher.restore(
                entity.getId(),
                entity.getPublisherKey(),
                entity.getDisplayName(),
                entity.getLanguage(),
                entity.getRegisteredDomain(),
                entity.getRssUrl(),
                entity.getPermissionStatus(),
                entity.getPermissionUpdatedAt(),
                entity.isEnabled(),
                entity.getEtag(),
                entity.getLastModified());
    }

    public static NewsPublisherPermissionHistoryJpaEntity toEntity(
            NewsPublisherPermissionHistory history) {
        return new NewsPublisherPermissionHistoryJpaEntity(
                history.id(),
                history.publisherId(),
                history.eventType(),
                history.permissionStatus(),
                toStringArray(history.allowedFields()),
                history.occurredAt(),
                history.reference());
    }

    public static NewsPublisherPermissionHistory toDomain(
            NewsPublisherPermissionHistoryJpaEntity entity) {
        return new NewsPublisherPermissionHistory(
                entity.getId(),
                entity.getPublisherId(),
                entity.getEventType(),
                entity.getPermissionStatus(),
                toAllowedFields(entity.getAllowedFields()),
                entity.getOccurredAt(),
                entity.getReference());
    }

    public static NewsArticleJpaEntity toEntity(NewsArticle article) {
        return new NewsArticleJpaEntity(
                article.getId(),
                article.getArticleId().value(),
                article.getSourceType(),
                article.getPublisherId(),
                article.getSourceItemId(),
                article.getOriginalUrl(),
                article.getCanonicalUrl().value(),
                article.getTitle(),
                article.getLanguage(),
                article.getPublishedAt(),
                article.getPublishedAtStatus(),
                article.getCollectedAt(),
                article.getGdeltRecordedAt(),
                article.getDuplicateKey(),
                article.getSourcePayloadHash(),
                article.getIngestRunId(),
                article.isDisplayEligible());
    }

    public static NewsArticle toDomain(NewsArticleJpaEntity entity) {
        return NewsArticle.restore(
                entity.getId(),
                NewsArticleId.from(entity.getArticleId()),
                entity.getSourceType(),
                entity.getPublisherId(),
                entity.getSourceItemId(),
                entity.getOriginalUrl(),
                new CanonicalNewsUrl(entity.getCanonicalUrl()),
                entity.getTitle(),
                entity.getLanguage(),
                entity.getPublishedAt(),
                entity.getPublishedAtStatus(),
                entity.getCollectedAt(),
                entity.getGdeltRecordedAt(),
                entity.getDuplicateKey(),
                entity.getSourcePayloadHash(),
                entity.getIngestRunId(),
                entity.isDisplayEligible());
    }

    public static NewsCollectionStateJpaEntity toEntity(NewsCollectionState state) {
        return new NewsCollectionStateJpaEntity(
                state.getSourceKey(),
                state.getStatus(),
                state.getCursor(),
                state.getLastAttemptAt(),
                state.getLastSuccessAt(),
                state.getNextAttemptAt(),
                state.getRetryCount(),
                state.getErrorCategory(),
                state.getLeaseOwner(),
                state.getLeaseUntil());
    }

    public static NewsCollectionState toDomain(NewsCollectionStateJpaEntity entity) {
        return NewsCollectionState.restore(
                entity.getSourceKey(),
                entity.getStatus(),
                entity.getCursor(),
                entity.getLastAttemptAt(),
                entity.getLastSuccessAt(),
                entity.getNextAttemptAt(),
                entity.getRetryCount(),
                entity.getErrorCategory(),
                entity.getLeaseOwner(),
                entity.getLeaseUntil());
    }

    public static NewsRuleVersionJpaEntity toEntity(NewsRuleVersion ruleVersion) {
        return new NewsRuleVersionJpaEntity(
                ruleVersion.ruleVersion(),
                ruleVersion.ruleType(),
                ruleVersion.definition(),
                ruleVersion.effectiveFrom(),
                ruleVersion.retiredAt());
    }

    public static NewsRuleVersion toDomain(NewsRuleVersionJpaEntity entity) {
        return new NewsRuleVersion(
                entity.getRuleVersion(),
                entity.getRuleType(),
                entity.getDefinition(),
                entity.getEffectiveFrom(),
                entity.getRetiredAt());
    }

    /** 저장된 문자열 배열을 허용 필드로 되돌린다. 모르는 값은 무시해 옛 회신도 읽을 수 있게 한다. */
    private static Set<NewsAllowedField> toAllowedFields(String[] stored) {
        if (stored == null || stored.length == 0) {
            return Set.of();
        }
        Set<NewsAllowedField> fields =
                Arrays.stream(stored)
                        .map(NewsPersistenceMapper::parseAllowedField)
                        .filter(java.util.Objects::nonNull)
                        .collect(
                                Collectors.toCollection(
                                        () -> EnumSet.noneOf(NewsAllowedField.class)));
        return Set.copyOf(fields);
    }

    private static NewsAllowedField parseAllowedField(String value) {
        try {
            return NewsAllowedField.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String[] toStringArray(Set<NewsAllowedField> fields) {
        return fields.stream().map(Enum::name).sorted().toArray(String[]::new);
    }
}
