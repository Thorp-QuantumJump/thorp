package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.port.NewsTopicCatalog;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 기사를 등록된 섹터·주제에 연결한다(plan 규칙 2, spec FR-010).
 *
 * <p><b>뉴스원이 준 분류 값을 그대로 믿지 않는다.</b> 매체마다 분류 체계가 달라 원문 값을 쓰면 같은 입력에 다른 결과가 나온다. 등록표 매핑을 통과한 주제 키만
 * 연결 근거가 되고, 매핑되지 않은 값은 연결에 쓰지 않고 미등록 건수로만 센다. 원문 분류 값은 저장하지 않는다.
 *
 * <p>해외 보도는 국내와 연결되는 주제가 있어야 받는다. 국내 섹터와 이어지지 않는 해외 기사는 시장 흐름 화면의 맥락이 되지 못한다(spec FR-005·FR-010).
 */
@Component
public class NewsRelevanceClassifier {

    private final NewsTopicCatalog topics;

    public NewsRelevanceClassifier(NewsTopicCatalog topics) {
        this.topics = topics;
    }

    /**
     * 연결 결과.
     *
     * @param accepted 수집 대상인지 여부. 거짓이면 그 항목을 버린다.
     * @param topicKeys 연결된 주제 키. 등록표를 통과한 값만 들어 있다.
     * @param sectorIds 연결된 섹터 식별자. spec 001이 소유하는 외부 값이며 012가 만들지 않는다.
     * @param unmappedCategories 매핑되지 않은 원문 분류 값. 지표로만 세고 저장하지 않는다.
     */
    public record Relevance(
            boolean accepted,
            Set<String> topicKeys,
            Set<String> sectorIds,
            Set<String> unmappedCategories) {

        public Relevance {
            topicKeys = Set.copyOf(topicKeys);
            sectorIds = Set.copyOf(sectorIds);
            unmappedCategories = Set.copyOf(unmappedCategories);
        }

        static Relevance rejected(Set<String> unmapped) {
            return new Relevance(false, Set.of(), Set.of(), unmapped);
        }
    }

    /**
     * 뉴스원 분류 값을 등록된 주제·섹터로 옮긴다.
     *
     * @param sourceCategories 뉴스원이 준 원문 분류 값.
     * @param overseas 해외 보도인지 여부.
     */
    public Relevance classify(List<String> sourceCategories, boolean overseas) {
        Set<String> topicKeys = new LinkedHashSet<>();
        Set<String> unmapped = new LinkedHashSet<>();

        for (String category : sourceCategories) {
            java.util.Optional<String> mapped = topics.resolveTopicKey(category);
            if (mapped.isPresent()) {
                topicKeys.add(mapped.get());
                continue;
            }
            // 이미 등록된 주제 키면 그대로 받는다. GDELT는 주제로 질의하므로 원문 분류 값이 아니라
            // 우리가 고른 주제가 실려 온다. 이 경로가 없으면 그 기사가 전부 버려진다.
            if (topics.findTopic(category).isPresent()) {
                topicKeys.add(category);
                continue;
            }
            unmapped.add(category);
        }

        if (topicKeys.isEmpty()) {
            return Relevance.rejected(unmapped);
        }
        if (overseas && topicKeys.stream().noneMatch(topics.domesticLinkTopicKeys()::contains)) {
            // 국내와 이어지지 않는 해외 기사는 받지 않는다.
            return Relevance.rejected(unmapped);
        }

        Set<String> sectorIds = new LinkedHashSet<>();
        for (String topicKey : topicKeys) {
            topics.findTopic(topicKey).ifPresent(topic -> sectorIds.addAll(topic.sectorIds()));
        }
        return new Relevance(true, topicKeys, sectorIds, unmapped);
    }

    /** 연결 규칙 버전. 저장된 연결과 함께 남겨 재현에 쓴다. */
    public String ruleVersion() {
        return topics.version();
    }
}
