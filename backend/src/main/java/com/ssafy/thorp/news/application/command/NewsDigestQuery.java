package com.ssafy.thorp.news.application.command;

import com.ssafy.thorp.news.application.exception.NewsQueryException;
import java.time.Duration;
import java.time.Instant;

/**
 * 뉴스 묶음 조회 요청(spec FR-005·FR-006, contracts/openapi.yaml).
 *
 * <p>요청은 두 갈래다. 기간을 주지 않으면 <b>현재 뉴스</b>, `from`과 `to`를 함께 주면 <b>과거 구간</b>이다. 한쪽만 주는 것은 받지 않는다 — 나머지
 * 한쪽을 서버가 정해 주면 사용자가 요청하지 않은 구간의 결과를 요청한 것처럼 돌려주게 된다.
 *
 * <p>구간 상한 {@code maxRangeDays}는 응답 크기와 질의 비용을 묶기 위한 012의 값이다. <b>그 범위에 자료가 있다는 뜻이 아니다.</b> 실제로
 * 반환되는 범위는 이 상한, GDELT가 제공하는 범위, 실제 수집분의 교집합이다(plan "조회와 상태").
 *
 * <p>시각은 받은 순간 UTC로 다룬다. 요청이 어느 시간대 표기로 오든 같은 순간이면 같은 결과가 나와야 한다.
 *
 * @param sectorId 좁힐 섹터. 없으면 전체다. 식별자는 spec 001이 소유한다.
 * @param from 과거 구간 시작. 경계를 포함한다.
 * @param to 과거 구간 끝. 경계를 포함한다.
 */
public record NewsDigestQuery(String sectorId, Instant from, Instant to) {

    /** 조회 갈래. 응답의 {@code context}로 그대로 나간다. */
    public enum Context {
        CURRENT,
        HISTORICAL
    }

    public NewsDigestQuery {
        if ((from == null) != (to == null)) {
            throw new NewsQueryException("과거 조회는 from과 to를 함께 지정해야 합니다.");
        }
        if (from != null && from.isAfter(to)) {
            throw new NewsQueryException("조회 시작 시각이 종료 시각보다 늦습니다.");
        }
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
    public static NewsDigestQuery of(String sectorId, Instant from, Instant to, int maxRangeDays) {
        NewsDigestQuery query = new NewsDigestQuery(sectorId, from, to);
        if (query.isHistorical()
                && Duration.between(from, to).compareTo(Duration.ofDays(maxRangeDays)) > 0) {
            throw new NewsQueryException("조회 구간은 최대 " + maxRangeDays + "일입니다.");
        }
        return query;
    }

    public boolean isHistorical() {
        return from != null;
    }

    public Context context() {
        return isHistorical() ? Context.HISTORICAL : Context.CURRENT;
    }

    public boolean hasSector() {
        return sectorId != null && !sectorId.isBlank();
    }
}
