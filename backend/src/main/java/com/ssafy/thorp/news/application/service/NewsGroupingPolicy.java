package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.domain.model.NewsArticle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 같은 사건의 보도를 한 묶음으로 정리한다(plan 규칙 5, spec FR-011).
 *
 * <p><b>보수적으로 합친다.</b> 48시간 이내, 공통 주제 1개 이상, 제목 문자 2-gram Jaccard 0.30 이상을 <b>모두</b> 만족하고, 아래 고유어
 * 조건에 걸리지 않을 때만 합친다. 서로 다른 사건을 하나로 잘못 합치는 쪽이 같은 사건을 둘로 나누는 쪽보다 나쁘다(spec SC-002).
 *
 * <p><b>왜 어절이 아니라 문자 2-gram인가.</b> 한국어는 조사·어미가 낱말에 붙어 같은 낱말이 어절로는 다른 토큰이 된다. "신협중앙회장"과 "신협중앙회장,"
 * 정도면 부호 제거로 해결되지만 "회장 선출"과 "회장에"는 어절로 영영 만나지 못한다. 실제 수집분 345건으로 재었을 때 어절 Jaccard는 같은 사건 29개 중 1개만
 * 묶었고 2-gram은 같은 조건에서 4개, 아래 고유어 조건과 함께 쓰면 14개를 묶었다.
 *
 * <p><b>고유어 조건은 합치는 조건이 아니라 거부 조건이다.</b> 두 제목에 모두 희귀 어절이 있는데 하나도 겹치지 않으면 합치지 않는다. "링크솔루션, 300억 원 규모
 * 전환사채 발행"과 "아리바이오LAB, 30억 원 규모 전환사채 발행"은 회사만 다르고 뼈대가 같아 어떤 문자 척도로도 높게 나오지만 서로 다른 사건이다. 이 조건이 없으면
 * 문턱을 0.45 아래로 내릴 수 없고, 있으면 0.30까지 내려도 오병합이 생기지 않는다.
 *
 * <p>희귀 어절의 기준은 <b>이번 후보 집합 안에서의 문서 빈도</b>다. 바깥 사전을 두지 않는 이유는 그것이 또 하나의 등록표가 되어 갱신되지 않으면 조용히 낡기
 * 때문이다. 후보 집합이 작으면 대부분의 어절이 희귀로 판정되어 조건이 자주 걸리는데, 그 방향은 덜 합치는 쪽이라 안전하다.
 *
 * <p><b>전이적으로 연쇄 병합하지 않는다.</b> 새 기사는 묶음의 <b>모든</b> 기존 기사와 조건을 만족해야 한다(complete-link). A와 B가 비슷하고 B와
 * C가 비슷해도 A와 C가 미달이면 C는 따로 둔다. 이 규칙이 없으면 비슷한 제목이 사슬처럼 이어져 관계없는 사건까지 한 묶음이 된다.
 *
 * <p>결과는 입력 순서에 영향받지 않는다. 후보를 먼저 안정된 순서로 정렬한 뒤 훑는다.
 */
@Component
public class NewsGroupingPolicy {

    /** 이 정책이 만든 묶음에 남기는 규칙 버전. 저장된 묶음과 함께 재현에 쓴다. */
    public static final String RULE_VERSION = "news-grouping-v2";

    /** 이보다 멀리 떨어진 보도는 같은 사건으로 보지 않는다. */
    public static final Duration MAX_TIME_GAP = Duration.ofHours(48);

    /**
     * 제목 문자 2-gram Jaccard 문턱. 이 값 미만이면 합치지 않는다.
     *
     * <p>실측으로 정한 값이다. 고유어 조건과 함께 쓸 때 오병합 0건을 지키는 가장 낮은 값이 0.30이고, 더 낮추면 정형 제목이 아닌 쌍에서도 오병합이 나온다.
     */
    public static final double MIN_TITLE_SIMILARITY = 0.30;

    /**
     * 이 횟수 이하로 나타난 어절을 고유어 후보로 본다.
     *
     * <p>회사·기관·인물 이름은 한 사건에서만 반복되므로 후보 집합 안에서 드물다. 값을 키우면 흔한 서술어까지 고유어로 취급해 조건이 무력해진다.
     */
    public static final int RARE_TERM_MAX_DOCUMENT_FREQUENCY = 3;

    /** 고유어 후보로 보기에 너무 짧은 어절. 한 글자는 조사·수사인 경우가 대부분이다. */
    private static final int MIN_RARE_TERM_LENGTH = 2;

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

        // 고유어 판정의 기준이 되는 문서 빈도. 이번 후보 집합 안에서만 센다.
        Map<String, Integer> documentFrequency = documentFrequency(ordered);

        List<List<Candidate>> groups = new ArrayList<>();
        for (Candidate candidate : ordered) {
            List<Candidate> target = null;
            for (List<Candidate> group : groups) {
                if (joinsAll(candidate, group, documentFrequency)) {
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

    /** 후보 집합 안에서 각 어절이 몇 건의 제목에 나타나는지. */
    private static Map<String, Integer> documentFrequency(List<Candidate> candidates) {
        Map<String, Integer> frequency = new HashMap<>();
        for (Candidate candidate : candidates) {
            for (String token : titleTokens(candidate.article().getTitle())) {
                frequency.merge(token, 1, Integer::sum);
            }
        }
        return frequency;
    }

    /** 묶음의 모든 기사와 조건을 만족해야 합류한다. 하나라도 미달이면 합류하지 않는다. */
    private boolean joinsAll(
            Candidate candidate, List<Candidate> group, Map<String, Integer> documentFrequency) {
        return group.stream().allMatch(member -> canMerge(candidate, member, documentFrequency));
    }

    /**
     * 두 기사를 같은 사건으로 볼 수 있는지.
     *
     * @param documentFrequency 후보 집합 안의 어절 문서 빈도. 비어 있으면 고유어 조건이 적용되지 않는다.
     */
    public boolean canMerge(
            Candidate left, Candidate right, Map<String, Integer> documentFrequency) {
        return withinTimeWindow(left, right)
                && sharesTopic(left, right)
                && titleSimilarity(left.article().getTitle(), right.article().getTitle())
                        >= MIN_TITLE_SIMILARITY
                && !describesDifferentSubjects(
                        left.article().getTitle(), right.article().getTitle(), documentFrequency);
    }

    /**
     * 두 제목이 서로 다른 대상을 말하고 있는지.
     *
     * <p>양쪽 모두 고유어를 가졌는데 공유하는 것이 하나도 없으면 참이다. 한쪽이라도 고유어가 없으면 판단 근거가 없으므로 거짓이다 — 근거 없이 막으면 제목이 짧은
     * 기사가 영영 묶이지 않는다.
     */
    private static boolean describesDifferentSubjects(
            String left, String right, Map<String, Integer> documentFrequency) {
        Set<String> leftRare = rareTerms(left, documentFrequency);
        Set<String> rightRare = rareTerms(right, documentFrequency);
        if (leftRare.isEmpty() || rightRare.isEmpty()) {
            return false;
        }
        return leftRare.stream().noneMatch(rightRare::contains);
    }

    /** 후보 집합 안에서 드물게 나타난 어절. 회사·기관·인물 이름이 주로 여기 걸린다. */
    private static Set<String> rareTerms(String title, Map<String, Integer> documentFrequency) {
        Set<String> rare = new LinkedHashSet<>();
        for (String token : titleTokens(title)) {
            if (token.length() < MIN_RARE_TERM_LENGTH) {
                continue;
            }
            int frequency = documentFrequency.getOrDefault(token, 1);
            if (frequency <= RARE_TERM_MAX_DOCUMENT_FREQUENCY) {
                rare.add(token);
            }
        }
        return rare;
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

    /**
     * 정규화한 제목의 문자 2-gram Jaccard 유사도. 둘 다 비어 있으면 0이다.
     *
     * <p>공백을 지우고 인접한 두 글자씩 끊는다. 조사·어미가 붙어도 낱말의 앞부분이 겹치므로 어절 단위보다 같은 사건을 잘 알아본다.
     */
    public static double titleSimilarity(String left, String right) {
        Set<String> leftGrams = titleBigrams(left);
        Set<String> rightGrams = titleBigrams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(leftGrams);
        intersection.retainAll(rightGrams);
        Set<String> union = new LinkedHashSet<>(leftGrams);
        union.addAll(rightGrams);
        return (double) intersection.size() / union.size();
    }

    /**
     * 제목을 비교용 문자 2-gram으로 나눈다.
     *
     * <p>한 글자짜리 제목은 2-gram을 만들 수 없어 빈 집합이 되고, 그러면 어떤 것과도 묶이지 않는다. 제목이 한 글자인 기사는 사실상 없고, 있다면 합치지 않는
     * 쪽이 안전하다.
     */
    public static Set<String> titleBigrams(String title) {
        String normalized = NewsUrlNormalizer.normalizeTitle(title).replace(" ", "");
        if (normalized.length() < 2) {
            return Set.of();
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 제목을 어절로 나눈다. 고유어 판정에 쓴다.
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
