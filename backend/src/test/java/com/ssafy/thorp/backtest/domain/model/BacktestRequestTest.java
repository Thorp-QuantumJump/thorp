package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 취소한 요청이 보는 완료 범위를 고정한다(spec 004 research R-04).
 *
 * <p>여기가 흔들리면 먼저 취소한 사용자에게 나중 계산 결과가 보인다. 합류 요청은 실행을 공유하므로 실행의 진행 값을 그대로 읽으면 그런 일이 생긴다 — 그래서 취소 시점
 * 스냅샷이 필요하다.
 */
class BacktestRequestTest {

    private static final Instant AT = Instant.parse("2026-09-11T02:00:00Z");
    private static final LocalDate D1 = LocalDate.of(2021, 6, 30);
    private static final LocalDate D2 = LocalDate.of(2023, 12, 29);

    private static BacktestRequest joined() {
        return BacktestRequest.restore(
                1L,
                BacktestId.from("BT-20260911-000001"),
                10L,
                99L,
                ExecutionType.SINGLE,
                ResultSource.JOINED,
                null,
                null,
                null,
                false,
                PublicResultToken.generate(),
                null);
    }

    @Test
    @DisplayName("취소하지 않은 요청은 실행의 진행을 그대로 본다")
    void activeRequestFollowsRun() {
        assertThat(joined().completedRangeOf(D2)).isEqualTo(D2);
        assertThat(joined().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("먼저 취소한 요청은 취소 시점 범위에 고정된다 — 실행이 더 계산해도 늘지 않는다")
    void earlyCancelFreezesRange() {
        BacktestRequest cancelled = joined().cancel(AT, D1, false);

        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(cancelled.isStoppedRun()).isFalse();
        assertThat(cancelled.completedRangeOf(D2)).isEqualTo(D1);
    }

    @Test
    @DisplayName("첫 진행 보고 전에 취소하면 완료 범위가 없다 — 뒤에 실행이 계산한 범위로 대신하지 않는다")
    void cancelBeforeFirstProgressHasNoRange() {
        BacktestRequest cancelled = joined().cancel(AT, null, false);

        assertThat(cancelled.getCancelledLastTradingDate()).isNull();
        assertThat(cancelled.completedRangeOf(D2)).isNull();
    }

    @Test
    @DisplayName("실행을 멈춘 취소는 실행이 확정한 범위를 쓴다 — 워커는 마지막 보고 뒤 몇 거래일 더 간다")
    void cancelThatStoppedRunUsesRunRange() {
        BacktestRequest cancelled = joined().cancel(AT, D1, true);

        assertThat(cancelled.isStoppedRun()).isTrue();
        assertThat(cancelled.getCancelledLastTradingDate()).isNull();
        assertThat(cancelled.completedRangeOf(D2)).isEqualTo(D2);
    }

    @Test
    @DisplayName("이미 취소한 요청은 다시 취소할 수 없다")
    void doubleCancelIsRejected() {
        BacktestRequest cancelled = joined().cancel(AT, D1, false);

        assertThatThrownBy(() -> cancelled.cancel(AT, D2, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("취소하지 않았는데 취소 부산물만 있는 상태는 만들 수 없다")
    void cancelDetailsWithoutCancelledAtAreRejected() {
        assertThatThrownBy(
                        () ->
                                BacktestRequest.restore(
                                        1L,
                                        BacktestId.from("BT-20260911-000001"),
                                        10L,
                                        99L,
                                        ExecutionType.SINGLE,
                                        ResultSource.NEW,
                                        null,
                                        null,
                                        D1,
                                        false,
                                        PublicResultToken.generate(),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 비로그인 요청과 공개 토큰(spec 005 B01) ----------

    private static final Instant EXPIRES = Instant.parse("2026-09-18T02:00:00Z");

    private static BacktestRequest anonymous(Instant expiresAt) {
        return BacktestRequest.create(
                10L,
                null,
                ExecutionType.SINGLE,
                ResultSource.NEW,
                null,
                PublicResultToken.generate(),
                expiresAt);
    }

    @Test
    @DisplayName("비로그인 요청은 만료 시각이 있어야 한다 — 만료 없는 익명 결과는 정리 작업이 영영 지우지 못한다")
    void anonymousRequestRequiresExpiry() {
        assertThatThrownBy(() -> anonymous(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("공개 토큰이 없는 요청은 만들 수 없다")
    void publicTokenIsRequired() {
        assertThatThrownBy(
                        () ->
                                BacktestRequest.create(
                                        10L,
                                        99L,
                                        ExecutionType.SINGLE,
                                        ResultSource.NEW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("만료 시각 직전까지는 열려 있고, 만료 시각과 같은 순간부터 만료다")
    void publicExpiryBoundary() {
        BacktestRequest request = anonymous(EXPIRES);

        assertThat(request.getRequesterId()).isNull();
        assertThat(request.isPublicExpired(EXPIRES.minusNanos(1))).isFalse();
        assertThat(request.isPublicExpired(EXPIRES)).isTrue();
        assertThat(request.isPublicExpired(EXPIRES.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("로그인 요청은 만료가 없다")
    void loggedInRequestNeverExpires() {
        BacktestRequest request = joined();

        assertThat(request.getRequesterId()).isEqualTo(99L);
        assertThat(request.getPublicTokenExpiresAt()).isNull();
        assertThat(request.isPublicExpired(Instant.MAX)).isFalse();
    }

    @Test
    @DisplayName("비로그인 요청은 누구의 것도 아니다 — 요청자가 null 이어도 NPE 없이 false 다")
    void anonymousRequestIsOwnedByNobody() {
        assertThat(anonymous(EXPIRES).isOwnedBy(99L)).isFalse();
        assertThat(joined().isOwnedBy(99L)).isTrue();
        assertThat(joined().isOwnedBy(98L)).isFalse();
    }

    @Test
    @DisplayName("취소해도 공개 토큰과 만료 시각은 그대로다")
    void cancellationKeepsPublicToken() {
        BacktestRequest request = anonymous(EXPIRES);

        BacktestRequest cancelled = request.cancel(AT, D1, false);

        assertThat(cancelled.getPublicToken()).isEqualTo(request.getPublicToken());
        assertThat(cancelled.getPublicTokenExpiresAt()).isEqualTo(EXPIRES);
    }
}
