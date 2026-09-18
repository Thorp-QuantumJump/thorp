package com.ssafy.thorp.learning.domain.model;

import java.util.List;

/**
 * 게시 전 검사 결과. 걸린 항목을 <b>모두</b> 담는다. 비어 있으면 통과다(research R7).
 *
 * @param failures 검사 순서({@link PublishCheckFailure.Check} 선언 순서), 같은 검사 안에서는 제목·질문·예시·블록·용어 순서다
 */
public record PublishCheckResult(List<PublishCheckFailure> failures) {

    public PublishCheckResult {
        failures = List.copyOf(failures);
    }

    public boolean passed() {
        return failures.isEmpty();
    }
}
