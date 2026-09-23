package com.ssafy.thorp.backtest.infrastructure.stream;

import com.ssafy.thorp.backtest.application.port.BacktestProgressStream;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 실행별 구독자 목록을 이 인스턴스의 메모리에 든다.
 *
 * <p><b>인스턴스 하나를 전제한다.</b> 이벤트 Consumer 는 공유 큐의 competing consumer 라서, 백엔드를 여러 개 띄우면 브라우저가 붙은 인스턴스와
 * 이벤트를 받은 인스턴스가 달라질 수 있다. 그러면 진행이 오지 않고 화면은 폴링이 주는 진행률만 보게 된다 — 결과가 틀리지는 않지만 곡선이 자라지 않는다. 인스턴스를 늘리는
 * 시점에 전파 장치가 필요하다(ADR-0002 "SSE 다중 인스턴스 전파", 후순위).
 *
 * <p><b>구독자 하나가 다른 구독자를 막지 않는다.</b> 전달 중 예외는 그 구독자만 떨어뜨린다. 발행하는 쪽은 브로커 리스너 스레드이고, 거기서 예외가 올라가면 이벤트가
 * 재전달되거나 DLQ 로 간다 — 아무도 보지 않는 화면 때문에 계산 결과를 잃을 수는 없다.
 */
@Component
public class InMemoryBacktestProgressStream implements BacktestProgressStream {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBacktestProgressStream.class);

    private final Map<Long, Set<Listener>> listeners = new ConcurrentHashMap<>();

    @Override
    public void publish(long runId, ProgressPayload payload) {
        forEachListener(runId, listener -> listener.onProgress(payload));
    }

    @Override
    public void complete(long runId, RunStatus status) {
        // 먼저 떼어 낸다. 알림 도중 새로 붙은 구독자에게 같은 종료를 두 번 보내지 않는다.
        Set<Listener> closing = listeners.remove(runId);
        if (closing == null) {
            return;
        }
        for (Listener listener : closing) {
            try {
                listener.onComplete(status);
            } catch (RuntimeException e) {
                log.debug("progress subscriber failed on complete: runId={}", runId, e);
            }
        }
    }

    @Override
    public Subscription subscribe(long runId, Listener listener) {
        listeners.computeIfAbsent(runId, key -> ConcurrentHashMap.newKeySet()).add(listener);
        return () -> remove(runId, listener);
    }

    /** 구독자 수. 테스트가 누수를 확인한다 — 끝난 실행의 목록이 남아 있으면 안 된다. */
    public int subscriberCount(long runId) {
        Set<Listener> current = listeners.get(runId);
        return current == null ? 0 : current.size();
    }

    private void forEachListener(long runId, java.util.function.Consumer<Listener> action) {
        Set<Listener> current = listeners.get(runId);
        if (current == null) {
            return;
        }
        for (Listener listener : current) {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                // 전달이 끊긴 구독자다. 떼어 내고 나머지에게 계속 보낸다.
                log.debug("progress subscriber dropped: runId={}", runId, e);
                remove(runId, listener);
            }
        }
    }

    private void remove(long runId, Listener listener) {
        listeners.computeIfPresent(
                runId,
                (key, current) -> {
                    current.remove(listener);
                    // 빈 집합을 남기면 실행마다 항목이 쌓인다.
                    return current.isEmpty() ? null : current;
                });
    }
}
