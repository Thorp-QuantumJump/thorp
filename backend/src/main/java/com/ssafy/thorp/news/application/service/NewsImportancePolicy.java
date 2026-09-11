package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.domain.model.NewsDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 뉴스 묶음의 주요도 순서를 정한다(spec FR-004, plan "요약·정렬 규칙 v1").
 *
 * <p>정렬 열쇠는 다섯이고 순서가 정해져 있다.
 *
 * <ol>
 *   <li>서로 다른 매체 수 내림차순 — 여러 매체가 다룬 사건을 먼저 보인다
 *   <li>검증된 발행 시각 존재 여부 내림차순 — 시각을 확인한 묶음이 앞선다
 *   <li>최신 검증 발행 시각 내림차순
 *   <li>발행 시각 미확인 묶음끼리는 수집 시각 내림차순
 *   <li>묶음 식별자 오름차순 — 나머지가 모두 같을 때 순서를 고정한다
 * </ol>
 *
 * <p>마지막 열쇠가 있어야 같은 입력이 항상 같은 순서를 낸다. 없으면 동률 묶음의 자리가 조회마다 바뀐다.
 *
 * <p>GDELT Tone과 기사 본문, 전략 결과는 쓰지 않는다(spec Out of Scope).
 *
 * <p>공개 허가 필터는 이 정렬보다 <b>먼저</b> 적용한다. 미허가 매체가 매체 수에 잡히면 순서에 간접적으로 새어 든다(spec FR-016).
 */
@Component
public class NewsImportancePolicy {

    /** 이 정렬에 쓴 규칙 버전. 저장된 묶음과 함께 남겨 재현에 쓴다. */
    public static final String RULE_VERSION = "news-importance-v1";

    /** 표시 순서대로 정렬한다. 입력을 바꾸지 않는다. */
    public List<NewsDigest.Projection> order(List<NewsDigest.Projection> projections) {
        return projections.stream().sorted(comparator()).toList();
    }

    /** 상위 {@code limit}개만 남긴다. 모자라면 있는 만큼만 돌려주고 채우지 않는다(spec FR-001). */
    public List<NewsDigest.Projection> top(List<NewsDigest.Projection> projections, int limit) {
        return order(projections).stream().limit(limit).toList();
    }

    /** 정렬 tuple. 조회 서비스가 같은 기준을 쓰도록 노출한다. */
    public Comparator<NewsDigest.Projection> comparator() {
        return Comparator.comparingInt(NewsDigest.Projection::distinctPublisherCount)
                .reversed()
                .thenComparing(
                        Comparator.comparing(NewsDigest.Projection::hasVerifiedPublishedAt)
                                .reversed())
                .thenComparing(
                        NewsDigest.Projection::latestPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        NewsImportancePolicy::collectedAtOf,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(projection -> projection.digestId().value());
    }

    /** 발행 시각이 미확인인 묶음끼리 비교할 때 쓰는 수집 시각. */
    private static Instant collectedAtOf(NewsDigest.Projection projection) {
        return projection.representative() == null
                ? null
                : projection.representative().getCollectedAt();
    }
}
