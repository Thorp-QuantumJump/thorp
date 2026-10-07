package com.ssafy.thorp.learning.presentation.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;

/**
 * {@code PUT /learning/me/articles/{articleId}/position} 본문(learning-me-api §3).
 *
 * @param percent 본문 길이에 대한 읽은 비율, 0~100 정수. 없거나 범위 밖이면 400 {@code INVALID_INPUT}. 소수({@code
 *     62.5})·문자열 ({@code "62"})·참거짓도 400이다. Jackson 기본 동작은 이 값들을 잘라 정수로 받아서, 화면의 계산 실수가 다른 비율로 조용히
 *     기록된다
 */
public record PositionRequest(
        @NotNull @Min(0) @Max(100) @JsonDeserialize(using = WholeNumber.class) Integer percent) {

    /** JSON 정수 토큰만 받는다. 그 밖은 형식 오류로 돌려 전역 처리기가 400 {@code INVALID_INPUT}으로 답한다. */
    public static final class WholeNumber extends JsonDeserializer<Integer> {

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
