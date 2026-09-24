package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.port.NewsTopicCatalog;
import com.ssafy.thorp.news.application.repository.NewsArticleLinkRepository;
import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 이미 저장된 기사를 지금 등록표의 제목 키워드로 다시 연결한다(spec FR-010, 등록표 v2).
 *
 * <p>등록표 v1에는 제목 규칙이 없었고 섹터 연결도 비어 있었다. 그때 저장된 기사는 어떤 섹터 조회에도 걸리지 않는다. 새 수집만 v2로 연결하면 보관 기한까지 남은
 * 기사는 끝내 섹터에 나오지 않는다.
 *
 * <p><b>더하기만 한다.</b> 제목 키워드로 걸린 주제·섹터 연결을 지금 규칙 버전으로 없는 것만 넣는다. v1 연결은 그 결과의 근거라 건드리지 않고(원칙 VIII),
 * v2로 이미 수집된 기사의 분류·채널 근거도 지우지 않는다. 그래서 여러 번 돌려도 결과가 같다.
 *
 * <p>원문 분류 값은 저장하지 않으므로(spec FR-010) 옛 기사에 다시 적용할 수 있는 규칙은 제목 키워드뿐이다.
 */
@Service
public class NewsTitleRelinkService {

    private final NewsArticleRepository articles;
    private final NewsArticleLinkRepository links;
    private final NewsTopicCatalog topics;
    private final NewsSectorLinkPolicy sectorLinks;

    public NewsTitleRelinkService(
            NewsArticleRepository articles,
            NewsArticleLinkRepository links,
            NewsTopicCatalog topics,
            NewsSectorLinkPolicy sectorLinks) {
        this.articles = articles;
        this.links = links;
        this.topics = topics;
        this.sectorLinks = sectorLinks;
    }

    /**
     * 표시할 수 있는 저장 기사 전부를 훑어 제목 키워드 연결을 더한다.
     *
     * @return 연결을 더한 기사 수. 걸린 키워드가 없는 기사는 세지 않는다.
     */
    public int relinkStoredTitles() {
        String ruleVersion = topics.version();
        int relinked = 0;
        for (NewsArticle article : articles.findDisplayEligibleSince(Instant.EPOCH)) {
            Map<String, String> matches = topics.matchTitle(article.getTitle());
            if (matches.isEmpty()) {
                continue;
            }
            Map<Long, String> topicEvidence = new LinkedHashMap<>();
            Set<String> proposedSectors = new LinkedHashSet<>();
            for (Map.Entry<String, String> match : matches.entrySet()) {
                topics.findTopic(match.getKey())
                        .ifPresent(
                                topic -> {
                                    topicEvidence.put(
                                            links.ensureTopic(
                                                    topic.topicKey(),
                                                    topic.displayName(),
                                                    topic.domesticLink(),
                                                    ruleVersion),
                                            NewsRelevanceClassifier.TITLE_EVIDENCE_PREFIX
                                                    + match.getValue());
                                    proposedSectors.addAll(topic.sectorIds());
                                });
            }
            links.addLinks(
                    article.getId(),
                    sectorLinks.resolve(proposedSectors),
                    topicEvidence,
                    ruleVersion);
            relinked++;
        }
        return relinked;
    }
}
