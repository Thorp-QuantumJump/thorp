package com.ssafy.thorp.common.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * {@code BigDecimal}을 plain 표기 문자열로 쓴다.
 *
 * <p>{@code toString()}은 소수 7자리 이상에서 지수 표기("1E-7")를 내므로 쓰지 않는다. 계약은 메시지·HTTP 양쪽에서 금액·비율을 plain 문자열로
 * 규정하고(브로커 계약 README §5, HTTP 계약 README) 지수 표기를 금지한다. 그래서 Jackson 기본 {@code ToStringSerializer}도 쓸
 * 수 없다.
 *
 * <p>쓰는 곳이 두 군데다.
 *
 * <ul>
 *   <li>브로커 메시지 ObjectMapper — 모듈로 등록해 모든 {@code BigDecimal}에 적용한다
 *   <li>백테스트 HTTP 응답 DTO — 필드마다 {@code @JsonSerialize(using = ...)}로 붙인다. 웹 ObjectMapper에 전역 등록하지
 *       않는다: 다른 도메인의 응답(예 {@code MarketResponse.value})이 JSON 숫자에서 문자열로 바뀌어 그 API 계약이 깨진다
 * </ul>
 */
public final class PlainBigDecimalSerializer extends StdSerializer<BigDecimal> {

    public PlainBigDecimalSerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeString(value.toPlainString());
    }
}
