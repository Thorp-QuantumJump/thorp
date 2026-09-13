package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.domain.model.NewsArticle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 같은 사건의 보도를 한 묶음으로 정리한다(plan 규칙 5, spec FR-011).
 *
 * <p><b>보수적으로 합친다.</b> 48시간 이내, 공통 주제 1개 이상, 정규화 제목 token Jaccard 0.60 이상을 <b>모두</b> 만족할 때만 합친다.
 * 하나라도 미달이면 별도 묶음으로 둔다. 서로 다른 사건을 하나로 잘못 합치는 쪽이 같은 사건을 둘로 나누는 쪽보다 나쁘기 때문이다(spec SC-002).
 *
 * <p><b>전이적으로 연쇄 병합하지 않는다.</b> 새 기사는 묶음의 <b>모든</b> 기존 기사와 세 조건을 만족해야 한다(complete-link). A와 B가 비슷하고
 * B와 C가 비슷해도 A와 C가 미달이면 C는 따로 둔다. 이 규칙이 없으면 비슷한 제목이 사슬처럼 이어져 관계없는 사건까지 한 묶음이 된다.
 *
 * <p>결과는 입력 순서에 영향받지 않는다. 후보를 먼저 안정된 순서로 정렬한 뒤 훑는다.
 */
@Component
public class NewsGroupingPolicy {

    /** 이 정책이 만든 묶음에 남기는 규칙 버전. 저장된 묶음과 함께 재현에 쓴다. */
    public static final String RULE_VERSION = "news-grouping-v1";

    /** 이보다 멀리 떨어진 보도는 같은 사건으로 보지 않는다. */
    public static final Duration MAX_TIME_GAP = Duration.ofHours(48);

    /** 제목 token Jaccard 문턱. 이 값 미만이면 합치지 않는다. */
    public static final double MIN_TITLE_SIMILARITY = 0.60;

    /**
     * 묶음 후보 하나.
     *
     * @param article 기사.
     * @param topicKeys 그 기사에 연결된 주제 키. 공통 주제 판정에 쓴다.
     */
    public record Candidate(NewsArticle article, Set<String> topicKeys) {

        public Candidate {
            topicKeys = Set.copyOf(topicKeys);
        }
    }

    /**
     * 후보를 묶는다. 같은 입력이면 순서에 관계없이 같은 묶음이 나온다.
     *
     * @param candidates 이번 회차가 다루는 기사.
     * @param groupingRuleVersion 저장할 묶음 규칙 버전.
     */
    public List<List<Candidate>> group(List<Candidate> candidates, String groupingRuleVersion) {
        List<Candidate> ordered =
                candidates.stream()
                        .sorted(
                                Comparator.comparing((Candidate c) -> c.article().orderingInstant())
                                        .thenComparing(c -> c.article().getCanonicalUrl().value()))
                        .toList();

        List<List<Candidate>> groups = new ArrayList<>();
        for (Candidate candidate : ordered) {
            List<Candidate> target = null;
            for (List<Candidate> group : groups) {
                if (joinsAll(candidate, group)) {
                    target = group;
                    break;
                }
            }
            if (target == null) {
                List<Candidate> fresh = new ArrayList<>();
                fresh.add(candidate);
                groups.add(fresh);
            } else {
                target.add(candidate);
            }
        }
        return groups;
    }

    /** 묶음의 모든 기사와 세 조건을 만족해야 합류한다. 하나라도 미달이면 합류하지 않는다. */
    private boolean joinsAll(Candidate candidate, List<Candidate> group) {
        return group.stream().allMatch(member -> canMerge(candidate, member));
    }

    /** 두 기사를 같은 사건으로 볼 수 있는지. 세 조건을 모두 본다. */
    public boolean canMerge(Candidate left, Candidate right) {
        return withinTimeWindow(left, right)
                && sharesTopic(left, right)
                && titleSimilarity(left.article().getTitle(), right.article().getTitle())
                        >= MIN_TITLE_SIMILARITY;
    }

    private static boolean withinTimeWindow(Candidate left, Candidate right) {
        Duration gap =
                Duration.between(
                                left.article().orderingInstant(), right.article().orderingInstant())
                        .abs();
        return gap.compareTo(MAX_TIME_GAP) <= 0;
    }

    private static boolean sharesTopic(Candidate left, Candidate right) {
        return left.topicKeys().stream().anyMatch(right.topicKeys()::contains);
    }

    /** 정규화한 제목 token의 Jaccard 유사도. 둘 다 비어 있으면 0이다. */
    public static double titleSimilarity(String left, String right) {
        Set<String> leftTokens = titleTokens(left);
        Set<String> rightTokens = titleTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    /**
     * 제목을 비교용 token으로 나눈다.
     *
     * <p>Unicode NFKC 정규화, 소문자화, 문장부호·연속 공백 제거를 적용한다. 원제목은 그대로 보존하고 여기서 만든 값은 비교에만 쓴다.
     */
    public static Set<String> titleTokens(String title) {
        String normalized = NewsUrlNormalizer.normalizeTitle(title);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
    }
}
