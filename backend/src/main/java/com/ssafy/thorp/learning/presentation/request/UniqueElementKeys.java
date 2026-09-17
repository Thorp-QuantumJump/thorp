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
import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 목록의 record 원소가 {@link #key()} 값을 서로 겹치지 않게 갖는다. 위반은 목록 필드 자체(예: {@code terms})의 오류로 나가므로 화면이 위치를
 * 그대로 쓸 수 있다.
 *
 * <p>값이 null인 원소와 키는 건너뛴다. 그것은 {@code @NotNull}이 따로 알린다.
 */
@Documented
@Constraint(validatedBy = UniqueElementKeys.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueElementKeys {

    /** 원소 record의 구성 요소 이름. */
    String key();

    String message() default "같은 값이 두 번 있습니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<UniqueElementKeys, List<?>> {

        private String key;

        @Override
        public void initialize(UniqueElementKeys annotation) {
            this.key = annotation.key();
        }

        @Override
        public boolean isValid(List<?> values, ConstraintValidatorContext context) {
            if (values == null) {
                return true;
            }
            Set<Object> seen = new HashSet<>();
            for (Object value : values) {
                Object keyValue = keyOf(value);
                if (keyValue != null && !seen.add(keyValue)) {
                    return false;
                }
            }
            return true;
        }

        private Object keyOf(Object value) {
            if (value == null) {
                return null;
            }
            if (!value.getClass().isRecord()) {
                throw new IllegalStateException(
                        "UniqueElementKeys needs record elements: " + value.getClass());
            }
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                if (component.getName().equals(key)) {
                    try {
                        return component.getAccessor().invoke(value);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("cannot read " + key, e);
                    }
                }
            }
            throw new IllegalStateException(
                    "no record component " + key + " in " + value.getClass());
        }
    }
}
