package com.ssafy.thorp.learning.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 넘겨받기의 {@code transferFrom}이 그 용어의 지금 소유 글이 아니거나, 다른 글이 이미 그 용어를 넘겨받는 중이다(409 {@code
 * LEARNING_TERM_TRANSFER_INVALID}, learning-admin-api §5·§6, research R24).
 *
 * <p>{@code details}에 용어와 지금 소유 글을 싣는다. 소유 글이 없으면(옛 소유 글이 초안에서 빼고 게시 취소함) {@code ownerArticleId}를
 * 싣지 않는다.
 */
public class TermTransferInvalidException extends BusinessException {

    public TermTransferInvalidException(TermId termId, ArticleId ownerArticleId) {
        super(
                ErrorCode.LEARNING_TERM_TRANSFER_INVALID,
                ErrorCode.LEARNING_TERM_TRANSFER_INVALID.getMessage(),
                details(termId, ownerArticleId));
    }

    private static Map<String, Object> details(TermId termId, ArticleId ownerArticleId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("termId", termId.value());
        if (ownerArticleId != null) {
            details.put("ownerArticleId", ownerArticleId.value());
        }
        return details;
    }
}
