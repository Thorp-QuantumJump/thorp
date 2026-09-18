package com.ssafy.thorp.result.application.result;

import java.time.LocalDate;
import java.util.List;

/**
 * 공개 결과와 같은 시작 금액으로 환산한 시장 지수 계열.
 *
 * <p>현재 001에는 기간 KOSPI 계열 조회 포트가 없다. 그동안은 빈 계열을 성공처럼 돌려주지 않고 {@code available=false}와 이유를 함께 보낸다.
 * 포트가 생기면 {@link Point}에 서버가 환산한 값을 채운다.
 */
public record PublicResultIndexComparison(boolean available, String reason, List<Point> series) {

    public static PublicResultIndexComparison unavailable(String reason) {
        return new PublicResultIndexComparison(false, reason, List.of());
    }

    public record Point(LocalDate date, String value) {}
}
