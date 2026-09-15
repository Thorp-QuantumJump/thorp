package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.command.NewsArticleExportQuery;
import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherPermissionHistoryRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.application.result.NewsArticleExportResult;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 허가 범위 안의 기사 메타데이터를 내보낸다(spec FR-017, SC-008).
 *
 * <p><b>허가 필터를 노출 모드와 무관하게 건다.</b> 묶음 조회는 내부 검증 환경에서 회신 전 매체까지 보여 주지만 내보내기는 그러지 않는다. FR-017이 "회신이
 * 없거나 불허된 매체의 항목과 기사 본문은 <b>어떤 경우에도</b> 반환하지 않는다"고 못박은 것은, 내보낸 자료가 우리 화면을 떠나 다른 곳에 저장되기 때문이다. 화면은
 * 닫으면 사라지지만 내보낸 파일은 남는다.
 *
 * <p>필터는 두 겹이다. 매체의 현재 허가 상태가 {@code ALLOWED}여야 항목이 실리고, 그 위에 최신 회신이 허용한 필드만 남긴다. 상태만 보면 "허가는 했지만
 * 제목은 빼 달라"는 회신을 지키지 못한다.
 *
 * <p>기사 본문은 어떤 회신으로도 열리지 않는다. 애초에 저장하지 않으므로 여기서 뺄 것도 없다(spec FR-002).
 */
@Service
public class NewsArticleExportService {

    private final NewsArticleRepository articles;
    private final NewsPublisherRepository publishers;
    private final NewsPublisherPermissionHistoryRepository permissions;
    private final NewsSectorQueryPolicy sectorPolicy;
    private final int maxHistoricalRangeDays;

    public NewsArticleExportService(
            NewsArticleRepository articles,
            NewsPublisherRepository publishers,
            NewsPublisherPermissionHistoryRepository permissions,
            NewsSectorQueryPolicy sectorPolicy,
            NewsDigestQueryService.NewsQuerySettings settings) {
        this.articles = articles;
        this.publishers = publishers;
        this.permissions = permissions;
        this.sectorPolicy = sectorPolicy;
        this.maxHistoricalRangeDays = settings.maxHistoricalRangeDays();
    }

    /** 요청 값에서 조회를 만들어 실행한다. */
    @Transactional(readOnly = true)
    public NewsArticleExportResult export(
            String sectorId, Instant from, Instant to, String cursor, Integer limit) {
        return export(
                NewsArticleExportQuery.of(
                        sectorId, from, to, cursor, limit, maxHistoricalRangeDays));
    }

    @Transactional(readOnly = true)
    public NewsArticleExportResult export(NewsArticleExportQuery query) {
        if (!sectorPolicy.canQuery(query.sectorId())) {
            // 001의 대응표가 아직 없다. 확인할 섹터가 없으므로 빈 결과다(spec FR-005).
            return new NewsArticleExportResult(List.of(), null);
        }

        Map<Long, NewsPublisher> permitted = permittedPublishers();
        if (permitted.isEmpty()) {
            return new NewsArticleExportResult(List.of(), null);
        }

        // 페이지를 채우는 동안 미허가 매체의 기사가 걸러지므로, 요청한 만큼 받아도 모자랄 수 있다.
        // 채워질 때까지 뒤로 읽어 나간다. 모자란 채로 돌려주면 클라이언트가 끝에 닿았다고 오해한다.
        List<NewsArticleExportResult.Item> items = new ArrayList<>();
        NewsArticleExportQuery.Cursor position = query.cursor();
        NewsArticleExportQuery.Cursor resultPosition = null;
        boolean hasMore = false;

        outer:
        while (true) {
            List<NewsArticle> page =
                    articles.findForExport(
                            query.from(),
                            query.to(),
                            query.hasSector() ? query.sectorId() : null,
                            position,
                            query.limit() + 1);
            if (page.isEmpty()) {
                break;
            }
            boolean pageHadMore = page.size() > query.limit();
            List<NewsArticle> scanned = pageHadMore ? page.subList(0, query.limit()) : page;

            for (NewsArticle article : scanned) {
                position = cursorAt(article);
                NewsPublisher publisher = permitted.get(article.getPublisherId());
                if (publisher == null) {
                    continue;
                }
                if (items.size() == query.limit()) {
                    hasMore = true;
                    break outer;
                }
                items.add(project(article, publisher));
                resultPosition = position;
            }

            if (!pageHadMore) {
                break;
            }
            if (items.size() == query.limit()) {
                // 다음 허가 기사가 실제로 있는지 확인할 때까지 계속 읽는다. raw 기사만 더 있다는
                // 이유로 cursor를 주면 다음 요청이 빈 페이지가 될 수 있다.
                continue;
            }
        }

        return new NewsArticleExportResult(
                items, hasMore && resultPosition != null ? resultPosition.encode() : null);
    }

    /**
     * 공개 노출이 허용된 매체를 식별자로 찾을 수 있게 모은다.
     *
     * <p>매체 수가 등록 상한(20) 안이라 한 번에 읽어 맵으로 둔다. 기사마다 조회하면 페이지 하나에 수백 번 왕복한다.
     */
    private Map<Long, NewsPublisher> permittedPublishers() {
        Map<Long, NewsPublisher> byId = new HashMap<>();
        publishers
                .findPubliclyVisible()
                .forEach(publisher -> byId.put(publisher.getId(), publisher));
        return byId;
    }

    /** 최신 회신이 허용한 필드만 남긴다. 허용하지 않은 필드는 null이고 응답에서 키가 빠진다. */
    private NewsArticleExportResult.Item project(NewsArticle article, NewsPublisher publisher) {
        Optional<NewsPublisherPermissionHistory> response =
                permissions.findLatestResponse(publisher.getId());
        return new NewsArticleExportResult.Item(
                article.getArticleId().value(),
                allows(response, NewsAllowedField.TITLE) ? article.getTitle() : null,
                allows(response, NewsAllowedField.PUBLISHER) ? publisher.getDisplayName() : null,
                allows(response, NewsAllowedField.PUBLISHED_AT) ? article.getPublishedAt() : null,
                article.getPublishedAtStatus(),
                allows(response, NewsAllowedField.ORIGINAL_URL) ? article.getOriginalUrl() : null);
    }

    /**
     * 회신이 그 필드를 허용했는지.
     *
     * <p>회신이 없으면 어떤 필드도 허용되지 않는다. 매체 상태가 {@code ALLOWED}인데 회신 기록이 없는 것은 있을 수 없는 상태지만, 그때 열어 주는 쪽으로
     * 틀리면 허가 없이 자료가 나간다.
     */
    private static boolean allows(
            Optional<NewsPublisherPermissionHistory> response, NewsAllowedField field) {
        return response.map(event -> event.allows(field)).orElse(false);
    }

    /** 정렬과 cursor에 쓰는 기준 시각. 저장소 질의의 {@code COALESCE}와 같은 규칙이어야 자리가 어긋나지 않는다. */
    private static Instant orderingInstant(NewsArticle article) {
        return article.getPublishedAtStatus() == PublicationTimeStatus.VERIFIED
                        && article.getPublishedAt() != null
                ? article.getPublishedAt()
                : article.getCollectedAt();
    }

    private static NewsArticleExportQuery.Cursor cursorAt(NewsArticle article) {
        return new NewsArticleExportQuery.Cursor(
                orderingInstant(article), article.getArticleId().value());
    }
}
