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
        String version,
        List<NewsSector> newsSectors,
        List<Topic> topics,
        List<CategoryMapping> categoryMappings) {

    /** 뉴스 섹터를 누가 소유하는가. */
    public enum SectorOwner {
        /** spec 001 대응표의 섹터(투자 대상). 식별자도 001의 것이다. */
        SECTOR_MAP,
        /** 투자 대상과 대응하지 않는 뉴스 전용 섹터. 012가 소유한다. */
        NEWS
    }

    /** 뉴스 전용 섹터 식별자의 머리말. 001 식별자와 겹치지 않게 한다. */
    public static final String NEWS_SECTOR_PREFIX = "NEWS_";

    /**
     * 뉴스를 좁혀 보는 칩 하나(spec FR-005, Session 2026-09-25).
     *
     * @param sectorId 섹터 식별자. {@code SECTOR_MAP}이면 001의 값, {@code NEWS}이면 {@code NEWS_}로 시작한다.
     * @param displayName 화면 이름. {@code SECTOR_MAP} 섹터는 화면에서 001의 이름을 먼저 쓴다.
     * @param owner 소유자.
     */
    public record NewsSector(String sectorId, String displayName, SectorOwner owner) {

        public NewsSector {
            if (sectorId == null || sectorId.isBlank()) {
                throw new IllegalArgumentException("sectorId는 비어 있을 수 없다");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("displayName은 비어 있을 수 없다: " + sectorId);
            }
            if (owner == null) {
                throw new IllegalArgumentException("owner가 없다: " + sectorId);
            }
            if ((owner == SectorOwner.NEWS) != sectorId.startsWith(NEWS_SECTOR_PREFIX)) {
                throw new IllegalArgumentException(
                        "뉴스 전용 섹터만 " + NEWS_SECTOR_PREFIX + "로 시작한다: " + sectorId);
            }
        }
    }

    /** 뉴스 섹터 목록이 없는 등록표(v1과 시험용). */
    public NewsLinkRegistry(
            String version, List<Topic> topics, List<CategoryMapping> categoryMappings) {
        this(version, null, topics, categoryMappings);
    }

    /**
     * 등록된 경제 주제 하나.
     *
     * @param topicKey 주제 키.
     * @param displayName 한국어 표시명.
     * @param domesticLink 국내 섹터와 연결되는 주제인지 여부. 해외 보도는 이 값이 참인 주제가 있어야 수집한다(spec FR-010).
     * @param sectorIds 이 주제가 연결되는 외부 섹터 식별자. spec 001이 소유하는 값이며 여기서 정의하지 않는다.
     * @param titleKeywords 제목에 들어 있으면 이 주제로 연결하는 문자열. 비어 있으면 제목으로는 연결하지 않는다.
     * @param titleExclusions 키워드를 찾기 전에 제목에서 지우는 문자열. '한국은행'이 '은행'으로 걸리는 것처럼 뜻이 다른 말을 막는다.
     */
    public record Topic(
            String topicKey,
            String displayName,
            boolean domesticLink,
            List<String> sectorIds,
            List<String> titleKeywords,
            List<String> titleExclusions) {

        public Topic {
            if (topicKey == null || topicKey.isBlank()) {
                throw new IllegalArgumentException("topicKey는 비어 있을 수 없다");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("displayName은 비어 있을 수 없다");
            }
            sectorIds = sectorIds == null ? List.of() : List.copyOf(sectorIds);
            titleKeywords = normalizedTerms(titleKeywords, "titleKeywords", topicKey);
            titleExclusions = normalizedTerms(titleExclusions, "titleExclusions", topicKey);
            if (!titleKeywords.isEmpty() && sectorIds.isEmpty()) {
                // 제목 키워드는 섹터에 연결하려고 둔다. 섹터 없이 두면 걸려도 아무 조회에 나오지 않는다.
                throw new IllegalArgumentException("제목 키워드가 있는 주제에 sectorIds가 없다: " + topicKey);
            }
        }

        /** 등록표 v1처럼 제목 규칙이 없는 주제. */
        public Topic(
                String topicKey, String displayName, boolean domesticLink, List<String> sectorIds) {
            this(topicKey, displayName, domesticLink, sectorIds, List.of(), List.of());
        }

        /**
         * 제목에서 처음 걸린 키워드를 돌려준다. 없으면 빈 값이다.
         *
         * <p>제외어를 먼저 지운 뒤 찾는다. 영문은 대소문자를 가리지 않는다. 등록표 순서대로 보므로 같은 제목에는 늘 같은 키워드가 근거로 남는다(원칙 VIII).
         */
        public Optional<String> matchTitle(String title) {
            if (titleKeywords.isEmpty() || title == null || title.isBlank()) {
                return Optional.empty();
            }
            String target = title.toLowerCase(Locale.ROOT);
            for (String exclusion : titleExclusions) {
                target = target.replace(exclusion, " ");
            }
            for (String keyword : titleKeywords) {
                if (containsTerm(target, keyword)) {
                    return Optional.of(keyword);
                }
            }
            return Optional.empty();
        }

        /**
         * 제목에 키워드가 들어 있는지.
         *
         * <p>영문·숫자로만 된 키워드(AI·ETF·PB 등)는 앞뒤가 영문·숫자가 아닐 때만 걸린다. 그렇지 않으면 'ai'가 'taiwan'에, 'pb'가 다른 영문
         * 낱말에 걸린다. 한글 키워드는 조사가 바로 붙으므로 그대로 포함 여부만 본다.
         */
        private static boolean containsTerm(String target, String keyword) {
            if (!isLatin(keyword)) {
                return target.contains(keyword);
            }
            int from = 0;
            while (true) {
                int at = target.indexOf(keyword, from);
                if (at < 0) {
                    return false;
                }
                int end = at + keyword.length();
                boolean leftOk = at == 0 || !isLatinChar(target.charAt(at - 1));
                boolean rightOk = end == target.length() || !isLatinChar(target.charAt(end));
                if (leftOk && rightOk) {
                    return true;
                }
                from = at + 1;
            }
        }

        private static boolean isLatin(String term) {
            return term.chars().allMatch(c -> isLatinChar((char) c) || c == '-' || c == ' ');
        }

        private static boolean isLatinChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
        }

        private static List<String> normalizedTerms(
                List<String> terms, String field, String topicKey) {
            if (terms == null) {
                return List.of();
            }
            List<String> normalized = new java.util.ArrayList<>();
            for (String term : terms) {
                if (term == null || term.isBlank()) {
                    throw new IllegalArgumentException(field + "에 빈 값이 있다: " + topicKey);
                }
                normalized.add(term.trim().toLowerCase(Locale.ROOT));
            }
            if (normalized.stream().distinct().count() != normalized.size()) {
                throw new IllegalArgumentException(field + "가 중복됐다: " + topicKey);
            }
            return List.copyOf(normalized);
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
        newsSectors = newsSectors == null ? List.of() : List.copyOf(newsSectors);
        topics = topics == null ? List.of() : List.copyOf(topics);
        Set<String> sectorIdSet =
                newsSectors.stream()
                        .map(NewsSector::sectorId)
                        .collect(java.util.stream.Collectors.toSet());
        if (sectorIdSet.size() != newsSectors.size()) {
            throw new IllegalArgumentException("newsSectors의 sectorId가 중복됐다");
        }
        if (!newsSectors.isEmpty()) {
            for (Topic topic : topics) {
                for (String sectorId : topic.sectorIds()) {
                    if (!sectorIdSet.contains(sectorId)) {
                        // 칩 목록에 없는 섹터로 연결하면 그 기사는 어떤 칩으로도 볼 수 없다.
                        throw new IllegalArgumentException(
                                "newsSectors에 없는 섹터로 연결했다: " + topic.topicKey() + " → " + sectorId);
                    }
                }
            }
        }
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

    /**
     * 제목 키워드로 걸리는 주제와 그 근거 키워드. 등록표 순서를 지킨다.
     *
     * <p>뉴스원 분류 값과 별개의 근거다. 분류 값을 주지 않는 매체의 기사도 제목이 섹터를 말하면 연결한다(spec FR-010).
     */
    public java.util.Map<String, String> matchTitle(String title) {
        java.util.Map<String, String> matched = new java.util.LinkedHashMap<>();
        for (Topic topic : topics) {
            topic.matchTitle(title).ifPresent(keyword -> matched.put(topic.topicKey(), keyword));
        }
        return java.util.Collections.unmodifiableMap(matched);
    }

    /** 012가 소유하는 뉴스 전용 섹터 식별자. 001 대응표와 무관하게 늘 유효하다. */
    public Set<String> newsOwnedSectorIds() {
        return newsSectors.stream()
                .filter(sector -> sector.owner() == SectorOwner.NEWS)
                .map(NewsSector::sectorId)
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
