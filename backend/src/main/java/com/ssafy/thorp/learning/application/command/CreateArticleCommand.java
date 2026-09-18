package com.ssafy.thorp.learning.application.command;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.Objects;

/** 글 만들기(learning-admin-api §3). 새 글은 본문이 빈 초안이다. */
public record CreateArticleCommand(ArticleId articleId, String title, int readingOrder) {

    public CreateArticleCommand {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
    }
}
