package com.ssafy.thorp.learning.presentation.request;

import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/learning/articles} 요청(learning-admin-api §3). 식별자 형식은 data-model §2다.
 *
 * <p>새 글은 모두 단계에 속한다. 9월 판 요청({@code readingOrder}만 있고 {@code stageKey}가 없음)은 400이다. 읽는 순서 값은 서버가
 * 단계 번호로 정한다.
 */
public record CreateArticleRequest(
        @NotNull
                @Size(min = ArticleId.MIN_LENGTH, max = ArticleId.MAX_LENGTH)
                @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                String articleId,
        @NotBlank @Size(max = LearningArticle.TITLE_MAX_LENGTH) String title,
        @NotNull
                @Size(min = StageKey.MIN_LENGTH, max = StageKey.MAX_LENGTH)
                @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                String stageKey,
        @NotNull @Min(Placement.MIN_POSITION) @Max(Placement.MAX_POSITION) Integer stagePosition) {}
