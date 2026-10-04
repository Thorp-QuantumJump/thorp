package com.ssafy.thorp.news.application.command;

import com.ssafy.thorp.news.application.exception.NewsQueryException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 뉴스 묶음 조회 요청(spec FR-001·FR-005·FR-006, contracts/openapi.yaml).
 *
 * <p>요청은 두 갈래다. 기간을 주지 않으면 <b>현재 뉴스</b>, `from`과 `to`를 함께 주면 <b>과거 구간</b>이다. 한쪽만 주는 것은 받지 않는다 — 나머지
 * 한쪽을 서버가 정해 주면 사용자가 요청하지 않은 구간의 결과를 요청한 것처럼 돌려주게 된다.
 *
 * <p>구간 상한 {@code maxRangeDays}는 응답 크기와 질의 비용을 묶기 위한 012의 값이다. <b>그 범위에 자료가 있다는 뜻이 아니다.</b> 실제로
 * 반환되는 범위는 이 상한, GDELT가 제공하는 범위, 실제 수집분의 교집합이다(plan "조회와 상태").
 *
 * <p><b>섹터는 여럿일 수 있다(2026-10-02).</b> 투자 대상 고르기의 '분류별 뉴스'가 분류에 배정된 섹터들의 묶음을 한 번에 모아 주요도 순으로 받기
 * 위해서다(spec FR-001 ③). 여러 섹터는 그중 하나 이상과 연결된 묶음이다. 화면이 섹터마다 따로 받아 합치면 주요도 규칙(FR-004)을 화면이 한 벌 더 갖게
 * 된다. 과거 구간은 사례 화면이 섹터 하나로만 부르므로 여럿을 받지 않는다.
 *
 * <p>{@code limit}은 이 요청에서 받을 묶음 수다. 서버 상한({@code thorp.news.max-digests})보다 크면 상한까지만 준다 — 모자라면 있는
 * 만큼만 주는 것과 같은 규칙이다(FR-001 "채우지 않는다"). <b>주지 않으면 상한이 아니라 {@link #DEFAULT_LIMIT}개다.</b> {@code
 * limit}이 생기기 전의 호출자(사례 화면의 '당시 뉴스')는 받은 묶음을 모두 그리므로, 상한을 올린 날 그 화면의 개수가 함께 바뀌면 안 된다.
 *
 * <p>시각은 받은 순간 UTC로 다룬다. 요청이 어느 시간대 표기로 오든 같은 순간이면 같은 결과가 나와야 한다.
 *
 * @param sectorIds 좁힐 섹터. 비었으면 전체다. 식별자는 spec 001(투자 대상 섹터)과 012(뉴스 전용 섹터)가 소유한다.
 * @param from 과거 구간 시작. 경계를 포함한다.
 * @param to 과거 구간 끝. 경계를 포함한다.
 * @param limit 받을 묶음 수. 없으면 {@link #DEFAULT_LIMIT}이다.
 */
public record NewsDigestQuery(List<String> sectorIds, Instant from, Instant to, Integer limit) {

    /** 한 요청에 담을 수 있는 섹터 수. 분류 하나의 섹터 수보다 넉넉하고, 질의가 끝없이 커지지 않게 막는다. */
    public static final int MAX_SECTORS = 12;

    /** {@code limit}을 주지 않은 요청이 받는 묶음 수. {@code limit}이 생기기 전(2026-10-02)의 응답 개수와 같다. */
    public static final int DEFAULT_LIMIT = 3;

    /** 조회 갈래. 응답의 {@code context}로 그대로 나간다. */
    public enum Context {
        CURRENT,
        HISTORICAL
    }

    public NewsDigestQuery {
        sectorIds = normalize(sectorIds);
        if ((from == null) != (to == null)) {
            throw new NewsQueryException("과거 조회는 from과 to를 함께 지정해야 합니다.");
        }
        if (from != null && from.isAfter(to)) {
            throw new NewsQueryException("조회 시작 시각이 종료 시각보다 늦습니다.");
        }
        if (sectorIds.size() > MAX_SECTORS) {
            throw new NewsQueryException("섹터는 한 번에 최대 " + MAX_SECTORS + "개까지 지정할 수 있습니다.");
        }
        if (from != null && sectorIds.size() > 1) {
            throw new NewsQueryException("과거 구간은 섹터 하나까지만 지정할 수 있습니다.");
        }
        if (limit != null && limit < 1) {
            throw new NewsQueryException("받을 묶음 수는 1 이상이어야 합니다.");
        }
    }

    /** 섹터 하나 또는 전체를 묻는 조회. 섹터를 하나만 쓰는 호출자(사례 화면·내보내기 시험)의 자리다. */
    public NewsDigestQuery(String sectorId, Instant from, Instant to) {
        this(sectorId == null ? List.of() : List.of(sectorId), from, to, null);
    }

    /** 현재 뉴스 조회. */
    public static NewsDigestQuery current(String sectorId) {
        return new NewsDigestQuery(sectorId, null, null);
    }

    /**
     * 구간 상한까지 확인한 조회를 만든다.
     *
     * <p>상한 확인을 생성자에 두지 않는 이유는 그 값이 설정에서 오기 때문이다. 도메인 값 객체가 설정을 읽으면 시험에서 그 값을 바꿔 가며 확인할 수 없다.
     *
     * @param maxRangeDays 요청 구간 상한(일). 경계를 포함하므로 정확히 이 길이는 통과한다.
     */
    public static NewsDigestQuery of(
            List<String> sectorIds, Instant from, Instant to, Integer limit, int maxRangeDays) {
        NewsDigestQuery query = new NewsDigestQuery(sectorIds, from, to, limit);
        if (query.isHistorical()
                && Duration.between(from, to).compareTo(Duration.ofDays(maxRangeDays)) > 0) {
            throw new NewsQueryException("조회 구간은 최대 " + maxRangeDays + "일입니다.");
        }
        return query;
    }

    /** 빈 값과 중복을 지우고 처음 나온 순서를 지킨다. 같은 섹터를 두 번 보내도 한 번 보낸 것과 같다. */
    private static List<String> normalize(List<String> sectorIds) {
        if (sectorIds == null) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        sectorIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .forEach(distinct::add);
        return List.copyOf(distinct);
    }

    public boolean isHistorical() {
        return from != null;
    }

    public Context context() {
        return isHistorical() ? Context.HISTORICAL : Context.CURRENT;
    }

    public boolean hasSector() {
        return !sectorIds.isEmpty();
    }

    /** 섹터를 여럿 골랐는지. 여럿이면 응답의 {@code sectorId}는 null이다. */
    public boolean hasManySectors() {
        return sectorIds.size() > 1;
    }

    /** 섹터를 하나만 골랐을 때의 그 식별자. 없거나 여럿이면 null이다. 응답의 {@code sectorId}로 나간다. */
    public String sectorId() {
        return sectorIds.size() == 1 ? sectorIds.get(0) : null;
    }

    /** 이 요청에서 줄 묶음 수. 요청 값이 없으면 {@link #DEFAULT_LIMIT}이고, 어느 쪽이든 상한을 넘지 않는다. */
    public int limitWithin(int maxDigests) {
        return Math.min(limit == null ? DEFAULT_LIMIT : limit, maxDigests);
    }
}
