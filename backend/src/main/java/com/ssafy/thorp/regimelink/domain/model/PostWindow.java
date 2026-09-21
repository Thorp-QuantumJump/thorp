package com.ssafy.thorp.regimelink.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 사례의 사후 구간을 날짜로 환산한 값 (data-model §4, PD-08).
 *
 * <p><b>구간 정의는 010이 소유하고 011은 날짜로 옮길 뿐이다.</b> {@code d0}는 관측 종료월 다음 첫 거래일, {@code d21}·{@code d63}은
 * 거기서 센 21·63번째 거래일이다. 달력은 KOSPI 일별 행이다(research R4).
 *
 * <p>여기 담긴 값은 <b>시장 달력만으로</b> 정해진다. 실행별로 실제 확보한 거래일 수는 상장폐지에 따라 더 짧을 수 있고 그것은 판정({@link
 * Availability})이 들고 있다.
 *
 * @param d21 21거래일째. 판정 기준일을 넘어가면 {@code null}이다
 * @param d63 63거래일째. 판정 기준일을 넘어가면 {@code null}이고 {@code completedTradingDays}가 진짜 길이다
 * @param lastAvailable 이 구간 안에서 자료가 있는 마지막 거래일. 필터의 {@code dataAsOf}(자료 전체의 마감일)와 다른 값이다
 * @param completedTradingDays d0부터 판정 기준일까지 센 거래일 수(최대 63)
 */
public record PostWindow(
        LocalDate d0,
        LocalDate d21,
        LocalDate d63,
        LocalDate lastAvailable,
        int completedTradingDays,
        boolean complete21,
        boolean complete63) {

    /** 사후 구간의 길이. 010이 정한 값이라 011이 바꾸지 않는다. */
    public static final int FULL_TRADING_DAYS = 63;

    /** 결과를 제공하는 최소 거래일 수. 미만이면 요약 수치를 내지 않는다(Clarifications 09-18 Q2). */
    public static final int MIN_TRADING_DAYS = 21;

    public PostWindow {
        Objects.requireNonNull(d0, "d0 must not be null");
        if (completedTradingDays < 0 || completedTradingDays > FULL_TRADING_DAYS) {
            throw new IllegalArgumentException(
                    "completedTradingDays 는 0..63 이어야 합니다: " + completedTradingDays);
        }
        if (complete21 != (d21 != null)) {
            throw new IllegalArgumentException("complete21 과 d21 이 어긋납니다: " + d21);
        }
        if (complete63 != (d63 != null)) {
            throw new IllegalArgumentException("complete63 과 d63 이 어긋납니다: " + d63);
        }
    }

    /** 시장 달력만 보면 21거래일을 채웠는가. 실행별 확보 거래일은 이보다 짧을 수 있다. */
    public boolean reachedMinimum() {
        return completedTradingDays >= MIN_TRADING_DAYS;
    }
}
