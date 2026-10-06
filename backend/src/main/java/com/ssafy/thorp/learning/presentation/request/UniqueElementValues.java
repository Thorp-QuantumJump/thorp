package com.ssafy.thorp.learning.presentation.request;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 목록의 값이 서로 겹치지 않는다. 용어의 다른 이름처럼 값 자체가 키인 목록에 쓴다. record 원소의 한 구성 요소로 가르는 목록은 {@link
 * UniqueElementKeys}다.
 *
 * <p>null 원소는 건너뛴다. 그것은 {@code @NotNull}이 따로 알린다.
 */
@Documented
@Constraint(validatedBy = UniqueElementValues.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueElementValues {

    String message() default "같은 값이 두 번 있습니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<UniqueElementValues, List<?>> {

        @Override
        public boolean isValid(List<?> values, ConstraintValidatorContext context) {
            if (values == null) {
                return true;
            }
            Set<Object> seen = new HashSet<>();
            for (Object value : values) {
                if (value != null && !seen.add(value)) {
                    return false;
                }
            }
            return true;
        }
    }
}
