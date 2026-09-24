package com.ssafy.thorp.result.application.result;

import java.time.LocalDate;
import java.util.List;

/**
 * 공개 결과와 같은 시작 금액으로 환산한 시장 지수 계열.
 *
 * <p>계열을 만들 수 없으면(기간·금액·자료 버전이 없거나 그 범위에 지수 관측치가 없으면) 빈 계열을 성공처럼 돌려주지 않고 {@code available=false}와
 * 이유를 함께 보낸다.
 */
public record PublicResultIndexComparison(boolean available, String reason, List<Point> series) {

    public static PublicResultIndexComparison unavailable(String reason) {
        return new PublicResultIndexComparison(false, reason, List.of());
    }

    public record Point(LocalDate date, String value) {}
}
