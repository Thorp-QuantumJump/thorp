package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleStatus;

/**
 * 게시 취소 결과(learning-admin-api §7).
 *
 * @param unpublishedRevisionNo 내려간 판 번호. 이미 초안이어서 아무것도 바꾸지 않았으면 null이다
 */
public record UnpublishResult(
        String articleId, ArticleStatus status, Integer unpublishedRevisionNo) {}
