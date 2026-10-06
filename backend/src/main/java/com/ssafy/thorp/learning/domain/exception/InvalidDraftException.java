package com.ssafy.thorp.learning.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 저장할 수 없는 초안이다(400 {@code INVALID_INPUT}, learning-admin-api §5). 형식 모양 위반, 지어 낸 문제·보기 식별자처럼 요청
 * DTO 검증만으로는 가를 수 없는 것을 위치와 함께 싣는다.
 *
 * <p>{@code details.fields}는 요청 DTO 검증 실패({@code GlobalExceptionHandler})와 같은 모양({@code
 * field}·{@code message})이다. 화면은 두 경로를 가르지 않고 같은 자리에 보인다.
 */
public class InvalidDraftException extends BusinessException {

    /**
     * @param field 요청 본문 안 위치. 예: {@code quiz[1].questionId}, {@code blocks[3].content}
     */
    public record FieldViolation(String field, String message) {
        public FieldViolation {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    private final List<FieldViolation> violations;

    public InvalidDraftException(List<FieldViolation> violations) {
        super(
                ErrorCode.INVALID_INPUT,
                first(violations),
                Map.of(
                        "fields",
                        violations.stream().map(InvalidDraftException::toDetail).toList()));
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    private static String first(List<FieldViolation> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        FieldViolation first = violations.getFirst();
        return first.field() + ": " + first.message();
    }

    private static Map<String, Object> toDetail(FieldViolation violation) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", violation.field());
        detail.put("message", violation.message());
        return detail;
    }
}
