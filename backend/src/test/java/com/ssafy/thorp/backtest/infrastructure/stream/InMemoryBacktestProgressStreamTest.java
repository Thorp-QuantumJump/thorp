package com.ssafy.thorp.backtest.infrastructure.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ssafy.thorp.backtest.application.port.BacktestProgressStream;
import com.ssafy.thorp.backtest.application.port.message.ProcessingStage;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 구독자 관리 규칙을 고정한다. 누수와 상호 간섭이 여기서 드러난다. */
class InMemoryBacktestProgressStreamTest {

    private static final long RUN_ID = 7L;

    private final InMemoryBacktestProgressStream stream = new InMemoryBacktestProgressStream();

    @Test
    @DisplayName("같은 실행을 보는 구독자 전원이 같은 포인트를 받는다")
    void allSubscribersOfTheSameRunReceiveTheSamePoints() {
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        stream.subscribe(RUN_ID, first);
        stream.subscribe(RUN_ID, second);

        ProgressPayload payload = payload(LocalDate.of(2025, 3, 3));
        stream.publish(RUN_ID, payload);

        assertThat(first.received).containsExactly(payload);
        assertThat(second.received).containsExactly(payload);
    }

    @Test
    @DisplayName("구독자가 없는 실행의 발행은 조용히 버려진다 — 나중에 붙은 구독자에게 다시 보내지 않는다")
    void publishingWithoutSubscribersIsDropped() {
        ProgressPayload dropped = payload(LocalDate.of(2025, 3, 3));
        // 발행하는 쪽은 브로커 리스너 스레드다. 여기서 예외가 나면 아무도 보지 않는 화면 때문에 이벤트가 재전달된다.
        assertThatCode(() -> stream.publish(RUN_ID, dropped)).doesNotThrowAnyException();

        RecordingListener late = new RecordingListener();
        stream.subscribe(RUN_ID, late);
        ProgressPayload next = payload(LocalDate.of(2025, 3, 4));
        stream.publish(RUN_ID, next);

        assertThat(late.received).as("구독 전 발행을 쌓아 두지 않는다").containsExactly(next);
    }

    @Test
    @DisplayName("다른 실행의 구독자에게는 흘리지 않는다")
    void pointsDoNotLeakToOtherRuns() {
        RecordingListener other = new RecordingListener();
        stream.subscribe(RUN_ID + 1, other);

        stream.publish(RUN_ID, payload(LocalDate.of(2025, 3, 3)));

        assertThat(other.received).isEmpty();
    }

    @Test
    @DisplayName("전달이 끊긴 구독자만 떨어지고 나머지는 계속 받는다")
    void aBrokenSubscriberDoesNotBlockTheOthers() {
        BacktestProgressStream.Listener broken =
                new BacktestProgressStream.Listener() {
                    @Override
                    public void onProgress(ProgressPayload payload) {
                        throw new IllegalStateException("연결이 끊겼다");
                    }

                    @Override
                    public void onComplete(RunStatus status) {}
                };
        RecordingListener healthy = new RecordingListener();
        stream.subscribe(RUN_ID, broken);
        stream.subscribe(RUN_ID, healthy);

        stream.publish(RUN_ID, payload(LocalDate.of(2025, 3, 3)));
        ProgressPayload second = payload(LocalDate.of(2025, 4, 4));
        stream.publish(RUN_ID, second);

        assertThat(stream.subscriberCount(RUN_ID)).isEqualTo(1);
        assertThat(healthy.received).hasSize(2).last().isEqualTo(second);
    }

    @Test
    @DisplayName("종결은 구독자에게 알린 뒤 목록을 비운다 — 끝난 실행의 항목이 남지 않는다")
    void completeNotifiesThenClears() {
        RecordingListener listener = new RecordingListener();
        stream.subscribe(RUN_ID, listener);

        stream.complete(RUN_ID, RunStatus.COMPLETED);

        assertThat(listener.completedWith).isEqualTo(RunStatus.COMPLETED);
        assertThat(stream.subscriberCount(RUN_ID)).isZero();
    }

    @Test
    @DisplayName("종결 알림 중 예외가 난 구독자가 다른 구독자의 알림을 막지 않는다")
    void aBrokenSubscriberDoesNotBlockCompletion() {
        BacktestProgressStream.Listener broken =
                new BacktestProgressStream.Listener() {
                    @Override
                    public void onProgress(ProgressPayload payload) {}

                    @Override
                    public void onComplete(RunStatus status) {
                        throw new IllegalStateException("연결이 끊겼다");
                    }
                };
        RecordingListener healthy = new RecordingListener();
        stream.subscribe(RUN_ID, broken);
        stream.subscribe(RUN_ID, healthy);

        stream.complete(RUN_ID, RunStatus.FAILED);

        assertThat(healthy.completedWith).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("구독을 닫으면 더 받지 않고, 두 번 닫아도 문제가 없다")
    void closingUnsubscribesAndIsIdempotent() {
        RecordingListener listener = new RecordingListener();
        BacktestProgressStream.Subscription subscription = stream.subscribe(RUN_ID, listener);

        subscription.close();
        subscription.close();
        stream.publish(RUN_ID, payload(LocalDate.of(2025, 3, 3)));

        assertThat(listener.received).isEmpty();
        assertThat(stream.subscriberCount(RUN_ID)).isZero();
    }

    private static ProgressPayload payload(LocalDate lastTradingDate) {
        return new ProgressPayload(
                ProcessingStage.COMPUTING,
                20,
                120,
                lastTradingDate,
                List.of(
                        new ProgressPayload.ProgressPoint(
                                lastTradingDate,
                                new BigDecimal("10120000.00"),
                                new BigDecimal("0.012"),
                                new BigDecimal("-0.004"))));
    }

    private static final class RecordingListener implements BacktestProgressStream.Listener {

        private final List<ProgressPayload> received = new ArrayList<>();
        private RunStatus completedWith;

        @Override
        public void onProgress(ProgressPayload payload) {
            received.add(payload);
        }

        @Override
        public void onComplete(RunStatus status) {
            completedWith = status;
        }
    }
}
