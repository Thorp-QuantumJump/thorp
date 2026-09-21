package com.ssafy.thorp.regimelink.infrastructure.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.regimelink.application.port.RegimeLinkResultCodec;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkResultPayload;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 결과 record 를 저장 Map 으로 옮긴다. 표기는 {@link RegimeLinkMessageJson} 하나가 정한다.
 *
 * <p>소수가 문자열로, {@code returnRate}가 키 {@code return}으로 저장되므로 <b>저장된 값을 그대로 응답에 실을 수 있다</b> — 조회가 다시
 * 수로 바꾸지 않으니 자릿수가 깎이지 않는다(004 결과 저장과 같은 규칙).
 */
@Component
public class JacksonRegimeLinkResultCodec implements RegimeLinkResultCodec {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> ARRAY = new TypeReference<>() {};

    private final ObjectMapper mapper = RegimeLinkMessageJson.objectMapper();

    @Override
    public RegimeLinkResultRepository.StoredResult toStored(
            RegimeLinkResultPayload payload, Instant computedAt) {
        return new RegimeLinkResultRepository.StoredResult(
                object(payload.window63()),
                object(payload.window21()),
                array(payload.holdings()),
                array(payload.trades()),
                object(payload.kospi()),
                object(payload.basis()),
                payload.inputDigest(),
                computedAt);
    }

    private Map<String, Object> object(Object value) {
        return value == null ? Map.of() : mapper.convertValue(value, OBJECT);
    }

    private List<Map<String, Object>> array(List<?> value) {
        return value == null ? List.of() : mapper.convertValue(value, ARRAY);
    }
}
