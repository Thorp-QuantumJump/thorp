package com.ssafy.thorp.regimelink.application.port;

import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkResultPayload;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import java.time.Instant;

/**
 * 워커 결과 record 를 저장 모양(JSONB 컬럼별 Map)으로 옮긴다.
 *
 * <p>구현이 infrastructure 에 있는 것은 <b>계약 표기를 아는 곳이 거기</b>이기 때문이다 — {@code returnRate} 컴포넌트를 JSON 키
 * {@code return}에 대응시키는 mixin 이 메시지 ObjectMapper 에 있고, 저장과 발행이 같은 표기를 써야 저장된 결과를 그대로 응답에 실을 수 있다.
 * application 이 자기 매퍼로 다시 옮기면 그 순간 표기가 두 벌이 된다.
 */
public interface RegimeLinkResultCodec {

    RegimeLinkResultRepository.StoredResult toStored(
            RegimeLinkResultPayload payload, Instant computedAt);
}
