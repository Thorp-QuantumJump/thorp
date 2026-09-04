package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.EnumOption;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.NumericRange;
import com.ssafy.thorp.strategy.domain.vo.ParameterType;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 사용자가 조정할 수 있는 파라미터 하나에 대한 기술(FR-005).
 *
 * <p><b>이 타입이 존재하는 이유</b>: 전략마다 파라미터의 이름과 개수가 다르다. 이동평균 교차는 기간 두 개와 논리값·열거값을 쓰고, RSI 평균 회귀는 기간 하나와
 * 실수 임계값 두 개를 쓴다. 그래도 소비 지점(입력 화면, 값 판정, 실행 요청)이 전략별 분기 없이 동작해야 한다. 그러려면 "무엇이 다를 수 있는가"를 한 규격 안에
 * 담아야 한다. 다를 수 있는 것은 이름·개수·타입·범위이고, 담는 형태는 언제나 이 record다.
 *
 * <p>그래서 타입별로 다른 필드를 갖는 하위 클래스를 두지 않는다. 대신 {@link ParameterType}이 어떤 범위 표현을 쓰는지 정하고, 이 record가 그
 * 대응을 불변식으로 지킨다. 수치형이면 {@code numericRange}가 있고 {@code enumValues}는 없다. 열거형이면 그 반대다. 논리형이면 둘 다 없다.
 * 이 배타성이 지켜지므로 소비 지점은 {@code type} 하나만 보고 나머지를 안전하게 꺼낼 수 있다.
 *
 * <p>선택 파라미터는 기본값을 반드시 갖는다. 확정 값 집합이 전략이 쓰는 모든 파라미터를 빠짐없이 담아야 하므로(FR-014), 기본값 없는 선택 파라미터가 있으면 사용자가
 * 생략했을 때 채울 값을 정의가 내놓지 못한다. 그 순간 값을 정하는 주체가 전략 구현으로 넘어가고 정의가 단일 출처라는 전제가 깨진다.
 *
 * <p>이 record가 지키는 것은 <b>형태</b>다. 기본값이 자기 유효 범위 안에 있는지(D3) 같이 값의 내용을 봐야 하는 정의 형식 규칙은 카탈로그 등재 시점의 형식
 * 검증이 다룬다. 여기서 함께 처리하면 "형태가 잘못된 것"과 "형식 규칙을 어긴 것"의 보고 방식이 섞인다.
 *
 * @param name 프로그램이 쓰는 이름. 정의 안에서 유일해야 한다
 * @param displayName 화면에 보이는 이름
 * @param description 이 값이 무엇을 바꾸는지에 대한 설명
 * @param type 값의 종류
 * @param required 값을 반드시 지정해야 하는지
 * @param defaultValue 생략 시 채워질 값. {@code required}가 {@code false}면 반드시 있어야 한다
 * @param unit 표시용 단위. 판정에 쓰지 않는다. 없으면 {@code null}
 * @param executionScopes 이 파라미터가 의미를 갖는 실행 목적
 * @param numericRange 수치형의 유효 범위. 수치형이 아니면 {@code null}
 * @param enumValues 열거형의 허용 값 목록. 열거형이 아니면 {@code null}
 */
public record ParameterDefinition(
        String name,
        String displayName,
        String description,
        ParameterType type,
        boolean required,
        ParameterValue defaultValue,
        String unit,
        Set<ExecutionScope> executionScopes,
        NumericRange numericRange,
        List<EnumOption> enumValues) {

    /** 계약의 파라미터 이름 규칙. JSON 필드와 같은 camelCase다. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]{0,39}$");

    public ParameterDefinition {
        Objects.requireNonNull(name, "파라미터 이름은 null일 수 없다");
        Objects.requireNonNull(type, "파라미터 타입은 null일 수 없다");
        Objects.requireNonNull(executionScopes, "파라미터의 실행 목적은 null일 수 없다");

        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("파라미터 이름이 규칙에 맞지 않는다(camelCase, 40자 이내): " + name);
        }
        requireText(displayName, "표시 이름", name);
        requireText(description, "설명", name);
        if (executionScopes.isEmpty()) {
            throw new IllegalArgumentException("파라미터의 실행 목적은 하나 이상이어야 한다: " + name);
        }

        // 타입과 범위 표현의 대응. 이 배타성이 공통 규격의 핵심이다.
        if (type.usesNumericRange() && numericRange == null) {
            throw new IllegalArgumentException(
                    "%s 타입 파라미터는 numericRange가 있어야 한다: %s".formatted(type, name));
        }
        if (!type.usesNumericRange() && numericRange != null) {
            throw new IllegalArgumentException(
                    "%s 타입 파라미터는 numericRange를 가질 수 없다: %s".formatted(type, name));
        }
        if (type.usesEnumValues()) {
            if (enumValues == null || enumValues.isEmpty()) {
                throw new IllegalArgumentException("ENUM 타입 파라미터는 허용 값이 하나 이상이어야 한다: " + name);
            }
            Set<String> seen = new HashSet<>();
            for (EnumOption option : enumValues) {
                if (!seen.add(option.value())) {
                    throw new IllegalArgumentException(
                            "ENUM 허용 값이 중복됐다: name=%s, value=%s".formatted(name, option.value()));
                }
            }
        } else if (enumValues != null) {
            throw new IllegalArgumentException(
                    "%s 타입 파라미터는 enumValues를 가질 수 없다: %s".formatted(type, name));
        }

        // 기본값이 있으면 그 종류가 파라미터 타입과 같아야 한다. 값이 범위 안인지는 형식 검증(D3)이 본다.
        if (defaultValue != null && defaultValue.type() != type) {
            throw new IllegalArgumentException(
                    "기본값의 타입이 파라미터 타입과 다르다: name=%s, 파라미터=%s, 기본값=%s"
                            .formatted(name, type, defaultValue.type()));
        }

        // 선택 파라미터는 기본값이 있어야 한다. 확정 값 집합은 전략이 쓰는 모든 파라미터를 빠짐없이 담아야
        // 하는데(FR-014), 기본값 없는 선택 파라미터는 사용자가 생략하면 채울 값이 없다. 그러면 값을 정하는
        // 주체가 전략 구현으로 넘어가고 정의가 단일 출처라는 전제가 깨진다.
        if (!required && defaultValue == null) {
            throw new IllegalArgumentException("선택 파라미터는 기본값이 있어야 한다(필수로 두거나 기본값을 지정한다): " + name);
        }

        executionScopes = Set.copyOf(executionScopes);
        enumValues = enumValues == null ? null : List.copyOf(enumValues);
    }

    private static void requireText(String value, String label, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("파라미터의 %s은 비어 있을 수 없다: %s".formatted(label, name));
        }
    }

    public boolean hasDefault() {
        return defaultValue != null;
    }

    public Optional<ParameterValue> defaultValueIfPresent() {
        return Optional.ofNullable(defaultValue);
    }

    public Optional<String> unitIfPresent() {
        return Optional.ofNullable(unit);
    }

    /** 수치형의 유효 범위. 수치형이 아니면 빈 값. */
    public Optional<NumericRange> range() {
        return Optional.ofNullable(numericRange);
    }

    /** 열거형의 허용 값 목록. 열거형이 아니면 빈 목록. */
    public List<EnumOption> options() {
        return enumValues == null ? List.of() : enumValues;
    }

    /** 주어진 실행 목적에서 이 파라미터가 의미를 갖는지(FR-021). */
    public boolean appliesTo(ExecutionScope scope) {
        return executionScopes.contains(scope);
    }
}
