package com.ssafy.thorp.learning.domain.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 단계의 글 가운데 검사에 걸린 글이 있다(422 {@code LEARNING_STAGE_CHECK_FAILED}, learning-admin-api §11). 아무것도 쓰지
 * 않았다. 걸린 글마다 항목을 모두 싣는다. 용어 소유 문제도 409가 아니라 이 항목({@code TERM_TRANSFER}·{@code TERM_ID_TAKEN})이다.
 */
public class StageCheckFailedException extends BusinessException {

    /** 걸린 글 하나. */
    public record ArticleFailures(ArticleId articleId, List<PublishCheckFailure> failures) {

        public ArticleFailures {
            failures = List.copyOf(failures);
        }
    }

    /**
     * @param articles 걸린 글만, 단계 안 순서다
     */
    public StageCheckFailedException(String checkVersion, List<ArticleFailures> articles) {
        super(
                ErrorCode.LEARNING_STAGE_CHECK_FAILED,
                ErrorCode.LEARNING_STAGE_CHECK_FAILED.getMessage(),
                Map.of(
                        "checkVersion",
                        checkVersion,
                        "articles",
                        articles.stream().map(StageCheckFailedException::toDetail).toList()));
    }

    private static Map<String, Object> toDetail(ArticleFailures article) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("articleId", article.articleId().value());
        detail.put(
                "failures",
                article.failures().stream().map(PublishCheckFailedException::toDetail).toList());
        return detail;
    }
}
