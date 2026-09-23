package com.ssafy.thorp.news.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.news.application.result.NewsArticleExportResult;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /market-news/articles/export}의 응답 본문(contracts/openapi.yaml).
 *
 * <p><b>기사 본문 필드를 두지 않는다.</b> 스키마에 자리가 없어야 실수로 채우는 일도 없다(spec FR-002).
 *
 * @param items 이 페이지의 항목.
 * @param nextCursor 다음 페이지의 자리. 더 없으면 null.
 */
public record NewsArticleExportResponse(List<Item> items, String nextCursor) {

    public static NewsArticleExportResponse from(NewsArticleExportResult result) {
        return new NewsArticleExportResponse(
                result.items().stream().map(Item::from).toList(), result.nextCursor());
    }

    /**
     * 기사 하나.
     *
     * <p><b>허용되지 않은 필드는 키째로 빠진다.</b> 여기에만 {@code NON_NULL}을 건다 — 계약이 "허용된 경우에만 포함한다"이고, {@code
     * null}로 실어 보내면 "매체가 값을 주지 않았다"와 "우리가 내보낼 권한이 없다"가 같은 모양이 된다. 받는 쪽이 그 둘을 구별할 수 없으면 빈 값을 표시하거나
     * 저장하게 된다.
     *
     * <p>전역에 걸지 않는다. 다른 도메인의 응답은 명시적 {@code null}에 뜻을 담고 있어 키가 사라지면 그 계약이 깨진다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String articleId,
            String title,
            String publisher,
            Instant publishedAt,
            String publicationTimeStatus,
            String originalUrl) {

        static Item from(NewsArticleExportResult.Item item) {
            return new Item(
                    item.articleId(),
                    item.title(),
                    item.publisher(),
                    item.publishedAt(),
                    item.publicationTimeStatus().name(),
                    item.originalUrl());
        }
    }
}
