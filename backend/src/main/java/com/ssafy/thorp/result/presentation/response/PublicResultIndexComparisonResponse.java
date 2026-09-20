package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.result.application.result.PublicResultIndexComparison;
import java.time.LocalDate;
import java.util.List;

/** {@code GET /results/{publicToken}/index-comparison} 공개 응답. 내부 실행 식별자를 담지 않는다. */
public record PublicResultIndexComparisonResponse(
        boolean available, String reason, List<Point> series) {

    public static PublicResultIndexComparisonResponse from(PublicResultIndexComparison result) {
        return new PublicResultIndexComparisonResponse(
                result.available(),
                result.reason(),
                result.series().stream().map(Point::from).toList());
    }

    public record Point(LocalDate date, String value) {
        private static Point from(PublicResultIndexComparison.Point point) {
            return new Point(point.date(), point.value());
        }
    }
}
