package com.ssafy.thorp.regimelink.application.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkScenario;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 작업 메시지의 {@code inputDigest} (계약 "{@code inputDigest} = 백엔드가 {@code
 * inputs·window·job.scenario·marketData} canonical JSON 에 sha256").
 *
 * <p>워커는 이 값을 검증하지 않고 결과에 그대로 돌려준다. 재현 대조는 백엔드 몫이라 <b>같은 입력이면 같은 값</b>이어야 하고, 그래서 표기를 여기에 고정한다 —
 * 소수는 plain 문자열, 날짜·시각은 ISO, null 은 키 생략. record 컴포넌트는 선언 순서로 직렬화되므로 순서가 결정적이다.
 *
 * <p>메시지 발행용 ObjectMapper 를 빌려 쓰지 않는다 — 그쪽은 infrastructure 에 있어 application 이 참조할 수 없고, 설정을 공유하면 발행
 * 표기를 바꾼 날 옛 결과의 해시가 조용히 전부 어긋난다(004 {@code DedupKeyCalculator}와 같은 이유).
 */
@Component
public class InputDigestCalculator {

    private final ObjectMapper canonical =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .addModule(
                            new SimpleModule()
                                    .addSerializer(
                                            BigDecimal.class, new PlainBigDecimalSerializer()))
                    .serializationInclusion(JsonInclude.Include.NON_NULL)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .build();

    public String calculate(
            RegimeLinkJob.InputsSpec inputs,
            RegimeLinkJob.WindowSpec window,
            RegimeLinkScenario scenario,
            RegimeLinkJob.MarketDataSpec marketData) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("inputs", inputs);
        source.put("window", window);
        source.put("scenario", scenario);
        source.put("marketData", marketData);
        return sha256(json(source));
    }

    private String json(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "입력 해시를 만들지 못했습니다.");
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
        }
    }
}
