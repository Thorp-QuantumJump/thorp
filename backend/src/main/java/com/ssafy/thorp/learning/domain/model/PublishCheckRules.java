package com.ssafy.thorp.learning.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 게시 전 검사 규칙 파일의 내용(data-model §6). 파일에서 읽어 만든다. 규칙을 코드 상수로 두지 않는 이유는 목록만 검토할 수 있게 하고, 어느 판이 어떤
 * 목록으로 검사됐는지 남기기 위해서다(research R7).
 *
 * @param version 게시 판의 {@code checkVersion}으로 남는다
 * @param forbiddenGroups 금지 표현 무리. 표현은 공백을 하나로 줄인 값이다
 * @param resultNumberPatterns {@code hypothetical} 밖에서 찾는 결과성 숫자 패턴
 * @param allowedExceptions 걸린 문자열이 이 값과 정확히 같으면 걸리지 않는다
 */
public record PublishCheckRules(
        String version,
        List<ForbiddenGroup> forbiddenGroups,
        List<Pattern> resultNumberPatterns,
        Set<String> allowedExceptions) {

    public PublishCheckRules {
        requireText(version, "version");
        forbiddenGroups = List.copyOf(forbiddenGroups);
        resultNumberPatterns = List.copyOf(resultNumberPatterns);
        allowedExceptions = Set.copyOf(allowedExceptions);
        if (forbiddenGroups.isEmpty()) {
            throw new IllegalArgumentException("forbiddenGroups must not be empty");
        }
        if (resultNumberPatterns.isEmpty()) {
            throw new IllegalArgumentException("resultNumberPatterns must not be empty");
        }
    }

    /**
     * 금지 표현 한 무리.
     *
     * @param message 걸렸을 때 거절 항목에 싣는 문장. 화면이 그대로 보인다
     */
    public record ForbiddenGroup(String name, String message, List<String> expressions) {

        public ForbiddenGroup {
            requireText(name, "forbidden group name");
            requireText(message, "forbidden group message of " + name);
            expressions = List.copyOf(expressions);
            if (expressions.isEmpty()) {
                throw new IllegalArgumentException(
                        "forbidden group must have expressions: " + name);
            }
            for (String expression : expressions) {
                requireText(expression, "forbidden expression of " + name);
                if (!expression.equals(PublishCheck.collapseWhitespace(expression))) {
                    throw new IllegalArgumentException(
                            "forbidden expression must use single spaces: " + expression);
                }
            }
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
