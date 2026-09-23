package com.ssafy.thorp.regimelink.domain.model;

import com.ssafy.thorp.regimelink.domain.vo.AvailabilityReason;
import com.ssafy.thorp.regimelink.domain.vo.AvailabilityState;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 사례 하나에 대한 가용성 판정 (research R4 판정 표).
 *
 * <p>실행 하나의 판정이기도 하고, 실행들의 판정을 모은 비교 묶음 전체의 판정이기도 하다. 모을 때는 <b>가장 나쁜 실행</b>이 이긴다({@link #worseOf}).
 *
 * @param partial 21거래일 이상을 확보했지만 구간 안 상장폐지로 63을 채우지 못했다
 * @param delistedOn 구간 안 상장폐지일. {@code partial}이거나 {@code DELISTED}일 때만 있다
 * @param completedTradingDays 이 실행이 실제로 확보한 거래일 수. 달력상의 수({@link PostWindow})와 다를 수 있다
 */
public record Availability(
        AvailabilityState state,
        AvailabilityReason reason,
        boolean partial,
        LocalDate delistedOn,
        int completedTradingDays) {

    public Availability {
        Objects.requireNonNull(state, "state must not be null");
        if (state == AvailabilityState.NORMAL && reason != null) {
            throw new IllegalArgumentException("NORMAL 에는 사유를 붙이지 않습니다: " + reason);
        }
        if (completedTradingDays < 0) {
            throw new IllegalArgumentException(
                    "completedTradingDays 가 음수입니다: " + completedTradingDays);
        }
    }

    public static Availability normal(int completedTradingDays) {
        return new Availability(AvailabilityState.NORMAL, null, false, null, completedTradingDays);
    }

    /** 21거래일 이상을 확보했지만 구간 안에서 폐지된 경우. 결과는 폐지 전날까지다(research R4). */
    public static Availability partial(LocalDate delistedOn, int completedTradingDays) {
        return new Availability(
                AvailabilityState.NORMAL, null, true, delistedOn, completedTradingDays);
    }

    public static Availability insufficient(AvailabilityReason reason) {
        return new Availability(AvailabilityState.DATA_INSUFFICIENT, reason, false, null, 0);
    }

    public static Availability incomplete(
            AvailabilityReason reason, LocalDate delistedOn, int completedTradingDays) {
        return new Availability(
                AvailabilityState.POST_INCOMPLETE, reason, false, delistedOn, completedTradingDays);
    }

    public static Availability outOfPeriod(int completedTradingDays) {
        return new Availability(
                AvailabilityState.OUT_OF_PERIOD, null, false, null, completedTradingDays);
    }

    /**
     * 비교 묶음 전체의 판정을 고른다.
     *
     * <p>상태가 다르면 나쁜 쪽이고, 같으면 확보 거래일이 적은 쪽이다 — 한 실행이 더 일찍 끊겼다면 나란히 놓을 수 있는 구간도 거기까지다.
     */
    public Availability worseOf(Availability other) {
        if (other == null) {
            return this;
        }
        int order = state.compareTo(other.state);
        if (order != 0) {
            return order < 0 ? this : other;
        }
        return completedTradingDays <= other.completedTradingDays ? this : other;
    }
}
