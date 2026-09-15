package com.ssafy.thorp.news.presentation.response;

import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /market-news/digests}의 응답 본문(contracts/openapi.yaml {@code NewsDigestResult}).
 *
 * <p><b>기사 본문 필드를 두지 않는다.</b> 스키마에 자리가 없어야 실수로 채우는 일도 없다(spec FR-002, 원칙 X).
 *
 * <p>{@code null}을 생략하지 않는다. `from`·`to`·`lastUpdatedAt`·`statusMessage`는 값이 없을 때 키가 사라지는 것이 아니라
 * 명시적 null이어야 한다 — 화면이 "키가 없다"와 "값이 없다"를 구별하려 들면 상태 판정이 두 곳으로 갈라진다.
 */
public record NewsDigestResponse(
        String context,
        String state,
        String sectorId,
        Instant from,
        Instant to,
        Instant lastUpdatedAt,
        boolean stale,
        String statusMessage,
        List<Digest> digests) {

    public static NewsDigestResponse from(NewsDigestListResult result) {
        return new NewsDigestResponse(
                result.context().name(),
                result.state().name(),
                result.sectorId(),
                result.from(),
                result.to(),
                result.lastUpdatedAt(),
                result.stale(),
                result.statusMessage(),
                result.digests().stream().map(Digest::from).toList());
    }

    /** 묶음 하나. */
    public record Digest(
            String digestId,
            String representativeTitle,
            String factualSummary,
            Instant firstPublishedAt,
            String publicationTimeStatus,
            int publisherCount,
            List<String> sectors,
            List<String> topics,
            String importanceRuleVersion,
            List<Article> articles) {

        static Digest from(NewsDigestListResult.Digest digest) {
            return new Digest(
                    digest.digestId(),
                    digest.representativeTitle(),
                    digest.factualSummary(),
                    digest.firstPublishedAt(),
                    digest.publicationTimeStatus().name(),
                    digest.publisherCount(),
                    digest.sectors(),
                    digest.topics(),
                    digest.importanceRuleVersion(),
                    digest.articles().stream().map(Article::from).toList());
        }
    }

    /** 기사 하나. 원문은 링크로만 연결한다. */
    public record Article(
            String articleId,
            String title,
            String publisher,
            Instant publishedAt,
            String publicationTimeStatus,
            String originalUrl) {

        static Article from(NewsDigestListResult.Article article) {
            return new Article(
                    article.articleId(),
                    article.title(),
                    article.publisher(),
                    article.publishedAt(),
                    article.publicationTimeStatus().name(),
                    article.originalUrl());
        }
    }
}
