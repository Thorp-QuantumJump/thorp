package com.ssafy.thorp.regimelink.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkResultPayload;
import java.math.BigDecimal;

/**
 * 사례 연결 메시지 전용 직렬화. 계약 기준: {@code docs/specs/regime-link-messaging/README.md} "표기 규칙".
 *
 * <p>토폴로지 선언과 발행은 {@code RegimeLinkRabbitConfig}(spec 011 T030)가 맡고 이 클래스는 <b>표기만</b> 맡는다. 계약 픽스처
 * round-trip 테스트와 발행·수신 경로가 같은 매퍼를 써야 "테스트가 통과했다 == 나가는 메시지가 픽스처와 같다"가 성립하므로 한 곳에 둔다.
 *
 * <p>백테스트 매퍼({@code BacktestRabbitConfig.messageObjectMapper()})와 설정이 같지만 빌려 쓰지 않는다 — 큐도 스키마 버전도 다른
 * 계약이라, 한쪽 계약이 표기를 바꾼 날 다른 쪽이 조용히 따라가면 안 된다.
 *
 * <ul>
 *   <li>BigDecimal → plain 문자열, 읽기는 숫자·문자열 모두 수용
 *   <li>LocalDate·Instant → ISO 문자열(시각은 UTC "…Z")
 *   <li>null 필드는 키 생략(NON_NULL)
 *   <li>필수 정수(primitive) 누락·null → 실패. enum 자리의 숫자와 정수 자리의 소수 → 실패. 알 수 없는 필드 → 무시
 * </ul>
 */
public final class RegimeLinkMessageJson {

    private RegimeLinkMessageJson() {}

    public static ObjectMapper objectMapper() {
        SimpleModule decimals =
                new SimpleModule().addSerializer(BigDecimal.class, new PlainBigDecimalSerializer());
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(decimals)
                .addMixIn(RegimeLinkResultPayload.WindowSummary.class, ReturnKey.class)
                .addMixIn(RegimeLinkResultPayload.BenchmarkWindow.class, ReturnKey.class)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
                .enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * {@code returnRate} 컴포넌트를 JSON 키 {@code return}에 대응시킨다.
     *
     * <p>계약이 정한 키는 {@code return}인데 Java 예약어라 record 컴포넌트로 쓸 수 없고, 메시지 DTO는 Jackson에 의존하지 않는다는 규칙
     * (ArchUnit 규칙 7)이 있어 애노테이션을 record에 직접 달 수도 없다. mixin은 그 둘을 모두 지키는 자리다.
     */
    private abstract static class ReturnKey {

        @JsonProperty("return")
        abstract BigDecimal returnRate();
    }
}
