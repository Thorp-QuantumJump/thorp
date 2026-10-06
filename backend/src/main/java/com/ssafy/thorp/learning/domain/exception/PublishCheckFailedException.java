package com.ssafy.thorp.learning.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.model.CheckLocation;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 게시 전 검사에 걸렸다(422 {@code LEARNING_PUBLISH_CHECK_FAILED}, learning-admin-api §6). 걸린 항목을 모두 싣는다.
 *
 * <p>{@code details}는 응답 JSON 모양 그대로의 맵이다. 도메인이 Jackson에 의존하지 않으므로 키 생략(값이 없는 {@code
 * matched}·{@code index}·{@code termId}·{@code field}·{@code questionId}·{@code choiceId})과 소문자
 * {@code part}를 여기서 맞춘다. v1 검사는 2판 위치 키를 쓰지 않으므로 v1 거절 응답의 모양은 9월 판 그대로다.
 */
public class PublishCheckFailedException extends BusinessException {

    public PublishCheckFailedException(String checkVersion, List<PublishCheckFailure> failures) {
        super(
                ErrorCode.LEARNING_PUBLISH_CHECK_FAILED,
                ErrorCode.LEARNING_PUBLISH_CHECK_FAILED.getMessage(),
                Map.of(
                        "checkVersion",
                        checkVersion,
                        "failures",
                        failures.stream().map(PublishCheckFailedException::toDetail).toList()));
    }

    /** 걸린 항목 하나의 응답 모양. 단계 공개의 글별 항목과 단계 미리 검사의 응답도 같은 모양이다. */
    public static Map<String, Object> toDetail(PublishCheckFailure failure) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("check", failure.check().name());
        detail.put("location", toDetail(failure.location()));
        if (failure.matched() != null) {
            detail.put("matched", failure.matched());
        }
        detail.put("message", failure.message());
        return detail;
    }

    private static Map<String, Object> toDetail(CheckLocation location) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("part", location.part().name().toLowerCase(Locale.ROOT));
        if (location.index() != null) {
            detail.put("index", location.index());
        }
        if (location.termId() != null) {
            detail.put("termId", location.termId().value());
        }
        if (location.field() != null) {
            detail.put("field", location.field());
        }
        if (location.questionId() != null) {
            detail.put("questionId", location.questionId().value());
        }
        if (location.choiceId() != null) {
            detail.put("choiceId", location.choiceId().value());
        }
        return detail;
    }
}
