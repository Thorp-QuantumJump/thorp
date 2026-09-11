package com.ssafy.thorp.news.infrastructure.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 버전 관리되는 섹터-주제 연결 등록표(spec FR-010).
 *
 * <p>이 등록표는 섹터 식별자를 선언하지 않는다. 섹터 식별자는 spec 001이 소유하며 012는 값을 참조만 하고 만들지 않는다(spec FR-005). 여기에 담기는
 * 것은 주제 키와 외부 섹터 식별자의 연결, 그리고 뉴스원 분류 값을 주제 키로 옮기는 매핑이다.
 *
 * <p>뉴스원이 준 분류 값은 그대로 신뢰하지 않는다. 이 등록표의 매핑을 통과한 값만 연결 근거가 되고, 매핑되지 않은 값은 연결에 쓰지 않고 미등록 건수로만
 * 집계한다(spec FR-010).
 *
 * @param version 등록표 버전 식별자.
 * @param topics 등록된 경제 주제 목록.
 * @param categoryMappings 뉴스원 분류 값과 주제 키의 매핑.
 */
public record NewsLinkRegistry(
        String version, List<Topic> topics, List<CategoryMapping> categoryMappings) {

    /**
     * 등록된 경제 주제 하나.
     *
     * @param topicKey 주제 키.
     * @param displayName 한국어 표시명.
     * @param domesticLink 국내 섹터와 연결되는 주제인지 여부. 해외 보도는 이 값이 참인 주제가 있어야 수집한다(spec FR-010).
     * @param sectorIds 이 주제가 연결되는 외부 섹터 식별자. spec 001이 소유하는 값이며 여기서 정의하지 않는다.
     */
    public record Topic(
            String topicKey, String displayName, boolean domesticLink, List<String> sectorIds) {

        public Topic {
            if (topicKey == null || topicKey.isBlank()) {
                throw new IllegalArgumentException("topicKey는 비어 있을 수 없다");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("displayName은 비어 있을 수 없다");
            }
            sectorIds = sectorIds == null ? List.of() : List.copyOf(sectorIds);
        }
    }

    /**
     * 뉴스원 분류 값과 주제 키의 매핑.
     *
     * @param sourceCategory 뉴스원이 준 원문 분류 값. 저장하지 않고 매핑에만 쓴다.
     * @param topicKey 매핑되는 주제 키.
     */
    public record CategoryMapping(String sourceCategory, String topicKey) {

        public CategoryMapping {
            if (sourceCategory == null || sourceCategory.isBlank()) {
                throw new IllegalArgumentException("sourceCategory는 비어 있을 수 없다");
            }
            if (topicKey == null || topicKey.isBlank()) {
                throw new IllegalArgumentException("topicKey는 비어 있을 수 없다");
            }
            sourceCategory = sourceCategory.trim().toLowerCase(Locale.ROOT);
        }
    }

    public NewsLinkRegistry {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version은 비어 있을 수 없다");
        }
        topics = topics == null ? List.of() : List.copyOf(topics);
        categoryMappings = categoryMappings == null ? List.of() : List.copyOf(categoryMappings);
        Set<String> topicKeys =
                topics.stream().map(Topic::topicKey).collect(java.util.stream.Collectors.toSet());
        if (topicKeys.size() != topics.size()) {
            throw new IllegalArgumentException("topicKey가 중복됐다");
        }
        for (CategoryMapping mapping : categoryMappings) {
            if (!topicKeys.contains(mapping.topicKey())) {
                throw new IllegalArgumentException("등록되지 않은 topicKey로 매핑했다: " + mapping.topicKey());
            }
        }
        long distinctCategories =
                categoryMappings.stream().map(CategoryMapping::sourceCategory).distinct().count();
        if (distinctCategories != categoryMappings.size()) {
            throw new IllegalArgumentException("sourceCategory가 중복됐다");
        }
    }

    /** 뉴스원 분류 값을 등록된 주제 키로 옮긴다. 매핑되지 않으면 비어 있는 값을 돌려주며, 호출부는 이를 연결 근거로 쓰지 않고 미등록으로 집계한다. */
    public Optional<String> resolveTopicKey(String sourceCategory) {
        if (sourceCategory == null || sourceCategory.isBlank()) {
            return Optional.empty();
        }
        String normalized = sourceCategory.trim().toLowerCase(Locale.ROOT);
        return categoryMappings.stream()
                .filter(m -> m.sourceCategory().equals(normalized))
                .map(CategoryMapping::topicKey)
                .findFirst();
    }

    public Optional<Topic> findTopic(String topicKey) {
        return topics.stream().filter(t -> t.topicKey().equals(topicKey)).findFirst();
    }

    /** 등록표가 참조하는 외부 섹터 식별자 전체. 미등록 섹터 요청을 거부하는 데 쓴다(spec FR-005). */
    public Set<String> knownSectorIds() {
        return topics.stream()
                .flatMap(t -> t.sectorIds().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 해외 보도를 수집할 수 있는 국내 연결 주제 키. */
    public Set<String> domesticLinkTopicKeys() {
        return topics.stream()
                .filter(Topic::domesticLink)
                .map(Topic::topicKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
