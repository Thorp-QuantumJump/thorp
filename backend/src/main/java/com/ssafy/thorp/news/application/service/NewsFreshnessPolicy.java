package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 뉴스 영역의 표시 상태를 정한다(spec FR-014, plan "조회와 상태").
 *
 * <p>판정은 <b>세 단계를 순서대로</b> 본다. 앞 단계가 걸리면 뒤를 보지 않는다.
 *
 * <ol>
 *   <li>공개 노출 가능한 매체가 하나도 없으면 {@code PREPARING} — 아직 매체 회신을 기다리는 중이다
 *   <li>표시할 묶음이 없으면 {@code EMPTY} — 다른 자료로 채우지 않는다
 *   <li>마지막 갱신이 기준보다 오래됐으면 {@code STALE}, 아니면 {@code READY}
 * </ol>
 *
 * <p>순서가 중요하다. 매체 회신 전에는 자료가 있어도 공개할 수 없으므로 {@code EMPTY}가 아니라 {@code PREPARING}이다. 둘을 뒤집으면 화면이
 * "뉴스가 없다"고 말하지만 실제로는 "아직 보여 줄 권한이 없다"이다.
 *
 * <p><b>네 상태 모두 HTTP 200이다.</b> 외부 뉴스원이 전부 죽어도 이것은 시장 흐름 화면의 오류가 아니다(spec FR-024).
 */
@Component
public class NewsFreshnessPolicy {

    /** 최초 수집 전. 마지막 갱신 시각이 없다는 것과 오래됐다는 것은 다르다. */
    public static final String NEVER_COLLECTED_MESSAGE = "아직 수집되지 않았습니다.";

    /**
     * 준비 중 문구.
     *
     * <p>왜 준비 중인지(매체 회신을 기다린다)는 내부 사정이라 말하지 않는다. 사용자가 할 수 있는 일이 없고, 어느 매체와 무엇을 협의 중인지를 화면이 흘릴 이유도
     * 없다. 문구는 contracts/examples/public-preparing.json이 기준이다.
     */
    public static final String PREPARING_MESSAGE = "뉴스 제공을 준비하고 있습니다.";

    public static final String EMPTY_MESSAGE = "표시할 수 있는 뉴스가 없습니다.";

    public static final String HISTORICAL_EMPTY_MESSAGE = "해당 기간에 표시할 수 있는 당시 뉴스가 없습니다.";

    public static final String STALE_MESSAGE = "뉴스 수집이 지연되어 마지막 갱신 자료를 표시합니다.";

    /**
     * 표시 상태를 정한다.
     *
     * @param hasPermittedPublisher 공개 노출 가능한 매체가 있는지.
     * @param hasDigests 표시할 묶음이 있는지.
     * @param lastUpdatedAt 노출 가능한 뉴스원들의 마지막 성공 시각 중 최신값. 수집 이력이 없으면 null.
     * @param now 기준 시각.
     * @param staleThreshold 이보다 오래되면 지연으로 본다.
     */
    public NewsDigestState stateOf(
            boolean hasPermittedPublisher,
            boolean hasDigests,
            Instant lastUpdatedAt,
            Instant now,
            Duration staleThreshold) {
        if (!hasPermittedPublisher) {
            return NewsDigestState.PREPARING;
        }
        if (!hasDigests) {
            return NewsDigestState.EMPTY;
        }
        return isStale(lastUpdatedAt, now, staleThreshold)
                ? NewsDigestState.STALE
                : NewsDigestState.READY;
    }

    /**
     * 마지막 갱신이 오래됐는지.
     *
     * <p>수집 이력이 아예 없으면 지연이 아니다. 한 번도 성공하지 못한 것과 성공했지만 오래된 것은 화면이 달리 말해야 한다 — 전자는 '아직 수집되지 않음'이고 후자는
     * '지연'이다(spec SC-004).
     */
    public boolean isStale(Instant lastUpdatedAt, Instant now, Duration staleThreshold) {
        if (lastUpdatedAt == null) {
            return false;
        }
        return Duration.between(lastUpdatedAt, now).compareTo(staleThreshold) >= 0;
    }

    /**
     * 상태를 사람 말로 옮긴다. {@code READY}는 덧붙일 말이 없어 null이다.
     *
     * @param historical 과거 구간 조회인지. 빈 결과의 뜻이 달라진다.
     */
    public String messageOf(NewsDigestState state, Instant lastUpdatedAt, boolean historical) {
        return switch (state) {
            case PREPARING -> PREPARING_MESSAGE;
            case EMPTY -> {
                if (historical) {
                    yield HISTORICAL_EMPTY_MESSAGE;
                }
                yield lastUpdatedAt == null ? NEVER_COLLECTED_MESSAGE : EMPTY_MESSAGE;
            }
            case STALE -> STALE_MESSAGE;
            case READY -> null;
        };
    }
}
