package com.ssafy.thorp.learning.presentation.request;

import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /admin/learning/articles} 요청(learning-admin-api §3). 식별자 형식은 data-model §2다. */
public record CreateArticleRequest(
        @NotNull
                @Size(min = ArticleId.MIN_LENGTH, max = ArticleId.MAX_LENGTH)
                @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                String articleId,
        @NotBlank @Size(max = LearningArticle.TITLE_MAX_LENGTH) String title,
        @NotNull @Min(LearningArticle.MIN_READING_ORDER) @Max(LearningArticle.MAX_READING_ORDER)
                Integer readingOrder) {}
