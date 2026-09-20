package com.ssafy.thorp.regimelink.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 워커가 돌려준 구간 요약 저장소 (data-model §5).
 *
 * <p><b>결과 도메인 모델을 두지 않는다</b> — 004 결과 저장과 같은 규칙으로, 워커 계약이 정한 모양을 그대로 보관한다. 메시지 계약 record가 들어오면
 * {@link StoredResult}의 {@code Map} 자리를 그 타입으로 좁힌다.
 *
 * <p>수치는 전부 문자열 decimal이다. 수로 바꿔 담으면 자릿수가 조용히 깎인다.
 */
public interface RegimeLinkResultRepository {

    /**
     * 저장된 결과 한 벌.
     *
     * @param inputDigest 메시지에 담긴 입력 사본의 해시. 재실행 결과가 같은 입력에서 나왔음을 확인한다
     */
    record StoredResult(
            Map<String, Object> window63,
            Map<String, Object> window21,
            List<Map<String, Object>> holdings,
            List<Map<String, Object>> trades,
            Map<String, Object> kospi,
            Map<String, Object> basis,
            String inputDigest,
            Instant computedAt) {}

    Optional<StoredResult> findByLinkId(Long linkId);

    void save(Long linkId, StoredResult result);
}
