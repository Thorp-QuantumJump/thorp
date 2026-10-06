package com.ssafy.thorp.learning.presentation.request;

import com.ssafy.thorp.learning.domain.model.RichText.RunKind;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.SourceRef;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 글자 조각 필드(data-model §5-2·§5-3). 조각마다의 모양과 필드 전체의 글자 수·조각 수·허용 조각 종류를 검사한다. 위반은 그 필드 자리(예: {@code
 * blocks[2].content}, {@code blocks[0].lines[1]})의 오류로 나간다.
 *
 * <p>조각의 키 구성(키가 하나뿐인지, 모르는 키가 없는지)은 {@link SaveDraftRequest.RunDeserializer}가 본문을 읽을 때 거절한다. 여기서는
 * 값을 본다. 도메인({@code RichText}·블록)도 같은 규칙을 검사하지만, 요청 단계에서 위치와 함께 거절해야 운영자가 고칠 자리를 안다.
 *
 * <p>null은 통과한다. 필수 여부는 {@code @NotNull}이 따로 알린다.
 */
@Documented
@Constraint(validatedBy = RichTextField.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RichTextField {

    /** 글자 수 하한. 글자와 용어 언급의 {@code text}를 더한다. */
    int min() default 1;

    int max();

    /** 조각 수 상한. */
    int maxRuns() default Integer.MAX_VALUE;

    /** 허용하는 조각 종류. */
    RunKind[] kinds() default {RunKind.TEXT, RunKind.TERM, RunKind.SOURCE};

    String message() default "글자 조각이 올바르지 않습니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<RichTextField, List<?>> {

        private static final Pattern TERM_PATTERN = Pattern.compile(ArticleId.REGEX);
        private static final Pattern URL_PATTERN = Pattern.compile(SourceRef.URL_REGEX);

        private int min;
        private int max;
        private int maxRuns;
        private Set<RunKind> kinds;

        @Override
        public void initialize(RichTextField annotation) {
            this.min = annotation.min();
            this.max = annotation.max();
            this.maxRuns = annotation.maxRuns();
            this.kinds = Set.of(annotation.kinds());
        }

        @Override
        public boolean isValid(List<?> runs, ConstraintValidatorContext context) {
            if (runs == null) {
                return true;
            }
            String problem = problem(runs);
            if (problem == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
            return false;
        }

        /** 첫 문제 하나를 문장으로 돌려준다. 문제가 없으면 null이다. */
        private String problem(List<?> runs) {
            if (runs.size() > maxRuns) {
                return "조각은 " + maxRuns + "개 이하여야 합니다";
            }
            int length = 0;
            for (int index = 0; index < runs.size(); index++) {
                if (!(runs.get(index) instanceof SaveDraftRequest.Run run)) {
                    return "비어 있는 조각이 있습니다";
                }
                RunKind kind = run.kind();
                if (!kinds.contains(kind)) {
                    return "이 자리에는 둘 수 없는 조각입니다: " + kindName(kind);
                }
                String problem = runProblem(run, kind, index);
                if (problem != null) {
                    return problem;
                }
                if (run.text() != null) {
                    length += run.text().length();
                }
            }
            if (length < min || length > max) {
                return min + "~" + max + "자여야 합니다. 지금 " + length + "자입니다";
            }
            return null;
        }

        private static String runProblem(SaveDraftRequest.Run run, RunKind kind, int index) {
            return switch (kind) {
                case TEXT -> run.text().isEmpty() ? "글자 조각은 1자 이상이어야 합니다" : null;
                case TERM -> {
                    if (run.term().length() < TermId.MIN_LENGTH
                            || run.term().length() > TermId.MAX_LENGTH
                            || !TERM_PATTERN.matcher(run.term()).matches()) {
                        yield "용어 언급의 term이 소문자·숫자를 하이픈으로 이은 식별자가 아닙니다";
                    }
                    yield run.text().isEmpty()
                                    || run.text().length() > TermMentionRun.TEXT_MAX_LENGTH
                            ? "용어 언급의 글자는 1~" + TermMentionRun.TEXT_MAX_LENGTH + "자여야 합니다"
                            : null;
                }
                case SOURCE -> {
                    if (index == 0) {
                        yield "출처 표시는 첫 조각일 수 없습니다. 인용한 글자 바로 뒤에 둡니다";
                    }
                    if (run.source().label().length() > SourceRef.LABEL_MAX_LENGTH) {
                        yield "출처 이름은 " + SourceRef.LABEL_MAX_LENGTH + "자 이하여야 합니다";
                    }
                    yield run.source().url().length() > SourceRef.URL_MAX_LENGTH
                                    || !URL_PATTERN.matcher(run.source().url()).matches()
                            ? "출처 주소는 http 또는 https 주소여야 합니다"
                            : null;
                }
            };
        }

        private static String kindName(RunKind kind) {
            return switch (kind) {
                case TEXT -> "글자";
                case TERM -> "용어 언급";
                case SOURCE -> "출처 표시";
            };
        }
    }
}
