package com.ssafy.thorp.regimelink.infrastructure.marketdata;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 현재 트랜잭션 동안만 사는 조회 결과 기억 (spec 011 T110).
 *
 * <p>가용성 판정은 한 요청(= 한 트랜잭션) 안에서 같은 자료 버전·같은 섹터 대응을 수백 번 묻는다. 요청을 넘겨 기억하지 않는다 — 새 자료가 발행되면 다음 요청은 새
 * 값을 봐야 한다. 트랜잭션 동기화가 없으면(트랜잭션 밖 호출) 기억하지 않고 그대로 읽는다.
 */
final class RequestScopedValues {

    private RequestScopedValues() {}

    @SuppressWarnings("unchecked")
    static <T> T memo(Object key, String entry, Supplier<T> loader) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return loader.get();
        }
        Map<String, Object> values =
                (Map<String, Object>) TransactionSynchronizationManager.getResource(key);
        if (values == null) {
            values = new HashMap<>();
            TransactionSynchronizationManager.bindResource(key, values);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(key);
                        }
                    });
        }
        if (values.containsKey(entry)) {
            return (T) values.get(entry);
        }
        T loaded = loader.get();
        values.put(entry, loaded);
        return loaded;
    }
}
