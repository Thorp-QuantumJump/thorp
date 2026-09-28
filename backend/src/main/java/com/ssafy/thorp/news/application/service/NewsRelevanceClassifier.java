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
     * @param evidenceByTopicKey 주제마다 무엇이 걸려 연결됐는지. 기사가 준 분류 값이거나 {@link #CHANNEL_EVIDENCE}다.
     */
    public record Relevance(
            boolean accepted,
            Set<String> topicKeys,
            Set<String> sectorIds,
            Set<String> unmappedCategories,
            java.util.Map<String, String> evidenceByTopicKey) {

        public Relevance {
            topicKeys = Set.copyOf(topicKeys);
            sectorIds = Set.copyOf(sectorIds);
            unmappedCategories = Set.copyOf(unmappedCategories);
            evidenceByTopicKey =
                    evidenceByTopicKey == null
                            ? java.util.Map.of()
                            : java.util.Map.copyOf(evidenceByTopicKey);
        }

        static Relevance rejected(Set<String> unmapped) {
            return new Relevance(false, Set.of(), Set.of(), unmapped, java.util.Map.of());
        }
    }

    /**
     * 기사가 아니라 채널이 주제를 말했다는 근거.
     *
     * <p>기사가 준 분류 값과 구별해 남긴다. 나중에 "왜 이 기사가 이 주제인가"를 볼 때, 기사가 그렇게 말한 것과 우리가 채널을 보고 그렇게 판단한 것은 신뢰도가
     * 다르다(원칙 VIII).
     */
    public static final String CHANNEL_EVIDENCE = "channel";

    /**
     * 제목 키워드가 걸렸다는 근거의 머리말. 뒤에 걸린 키워드가 붙는다(예: {@code title:hbm}).
     *
     * <p>기사가 준 분류 값이나 채널 판단과 구별해 남긴다. 제목의 한 단어로 연결했다는 사실은 그 둘과 신뢰도가 다르다(원칙 VIII).
     */
    public static final String TITLE_EVIDENCE_PREFIX = "title:";

    /**
     * 뉴스원 분류 값을 등록된 주제·섹터로 옮긴다.
     *
     * @param sourceCategories 뉴스원이 준 원문 분류 값.
     * @param overseas 해외 보도인지 여부.
     */
    public Relevance classify(List<String> sourceCategories, boolean overseas) {
        return classify(sourceCategories, overseas, List.of());
    }

    /**
     * 채널이 선언한 주제를 보완으로 받는 분류.
     *
     * <p><b>기사가 말한 것을 먼저 믿는다.</b> 기사가 준 분류 값이 등록표를 하나라도 통과하면 그것만 쓰고 채널 주제는 보지 않는다. 채널 주제는 기사가 아무 말도
     * 하지 않았을 때만 대신 말한다.
     *
     * <p>이 경로가 필요한 이유는 국내 경제 매체 대부분이 기사별 분류 값을 주지 않기 때문이다. 조사해 보니 주는 곳은 한 곳뿐이었고 나머지는 피드 이름을 되풀이하거나
     * 분류가 아예 없었다. 그런 매체에서는 "어느 피드를 구독했는가"가 곧 그 기사의 주제다.
     *
     * @param channelTopicKeys 채널이 선언한 주제. 등록표에 있는 값만 쓴다.
     */
    public Relevance classify(
            List<String> sourceCategories, boolean overseas, List<String> channelTopicKeys) {
        return classify(sourceCategories, overseas, channelTopicKeys, null);
    }

    /**
     * 기사 제목의 섹터 키워드까지 보는 분류(spec FR-010, 등록표 v2).
     *
     * <p><b>제목 키워드는 분류 값·채널 주제에 더해진다.</b> 대신하지 않는다. 국내 매체 대부분이 기사별 분류를 주지 않아, 섹터를 말해 주는 것은 제목뿐인 경우가
     * 많다. 제목 주제는 국내 연결 주제가 아니므로 해외 기사를 받아들이는 근거가 되지 않는다.
     *
     * @param title 기사 제목. 없으면 제목 규칙을 보지 않는다.
     */
    public Relevance classify(
            List<String> sourceCategories,
            boolean overseas,
            List<String> channelTopicKeys,
            String title) {
        Set<String> topicKeys = new LinkedHashSet<>();
        Set<String> unmapped = new LinkedHashSet<>();
        java.util.Map<String, String> evidence = new java.util.LinkedHashMap<>();

        for (String category : sourceCategories) {
            java.util.Optional<String> mapped = topics.resolveTopicKey(category);
            if (mapped.isPresent()) {
                topicKeys.add(mapped.get());
                evidence.putIfAbsent(mapped.get(), category);
                continue;
            }
            // 이미 등록된 주제 키면 그대로 받는다. GDELT는 주제로 질의하므로 원문 분류 값이 아니라
            // 우리가 고른 주제가 실려 온다. 이 경로가 없으면 그 기사가 전부 버려진다.
            if (topics.findTopic(category).isPresent()) {
                topicKeys.add(category);
                evidence.putIfAbsent(category, category);
                continue;
            }
            unmapped.add(category);
        }

        if (topicKeys.isEmpty() && channelTopicKeys != null) {
            for (String channelTopic : channelTopicKeys) {
                if (topics.findTopic(channelTopic).isPresent()) {
                    topicKeys.add(channelTopic);
                    evidence.putIfAbsent(channelTopic, CHANNEL_EVIDENCE);
                }
            }
        }

        for (java.util.Map.Entry<String, String> match : topics.matchTitle(title).entrySet()) {
            if (topicKeys.add(match.getKey())) {
                evidence.putIfAbsent(match.getKey(), TITLE_EVIDENCE_PREFIX + match.getValue());
            }
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
        return new Relevance(true, topicKeys, sectorIds, unmapped, evidence);
    }

    /** 연결 규칙 버전. 저장된 연결과 함께 남겨 재현에 쓴다. */
    public String ruleVersion() {
        return topics.version();
    }
}
