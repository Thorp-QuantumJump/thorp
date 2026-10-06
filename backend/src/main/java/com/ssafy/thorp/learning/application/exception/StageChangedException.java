package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요청의 글 목록이 단계의 공개 집합과 다르거나 초안 번호가 다르다(409 {@code LEARNING_STAGE_CHANGED}, learning-admin-api §11).
 * 운영자가 보지 않은 초안을 공개하지 않는다(research R12·R23).
 *
 * <p>{@code details.articles}에 서버의 공개 집합과 초안 번호를 싣는다. 화면은 이 값으로 미리 검사를 다시 부른다.
 */
public class StageChangedException extends BusinessException {

    /**
     * @param releaseSet 잠근 뒤 다시 읽은 공개 집합, 단계 안 순서
     */
    public StageChangedException(List<LearningArticle> releaseSet) {
        super(
                ErrorCode.LEARNING_STAGE_CHANGED,
                ErrorCode.LEARNING_STAGE_CHANGED.getMessage(),
                Map.of(
                        "articles",
                        releaseSet.stream().map(StageChangedException::toDetail).toList()));
    }

    private static Map<String, Object> toDetail(LearningArticle article) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("articleId", article.articleId().value());
        detail.put("draftVersion", article.draftVersion());
        return detail;
    }
}
