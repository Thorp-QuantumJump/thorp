package com.ssafy.thorp.backtest.domain.vo;

import java.util.List;
import java.util.Objects;

/**
 * 자산 가치 시계열. 비어 있지 않고 날짜가 엄격히 오름차순이어야 한다(FR-007).
 *
 * <p>내부 리스트는 불변 복사본이다.
 */
public record EquityCurve(List<EquityPoint> points) {

    public EquityCurve {
        Objects.requireNonNull(points, "points must not be null");
        if (points.isEmpty()) {
            throw new IllegalArgumentException("equity curve must have at least one point");
        }
        points = List.copyOf(points);
        for (int i = 1; i < points.size(); i++) {
            EquityPoint previous = points.get(i - 1);
            EquityPoint current = points.get(i);
            if (!current.date().isAfter(previous.date())) {
                throw new IllegalArgumentException(
                        "equity curve dates must be strictly ascending: "
                                + previous.date()
                                + " -> "
                                + current.date());
            }
        }
    }

    public EquityPoint first() {
        return points.get(0);
    }

    public EquityPoint last() {
        return points.get(points.size() - 1);
    }

    public int size() {
        return points.size();
    }
}
